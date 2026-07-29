(ns dapr.ui.events
  "Side-effecting event handlers for the cljfx UI. Pure state transitions live in
  dapr.state; filesystem work lives in dapr.fs.nio / dapr.sync; library and scan
  persistence in dapr.db.cache. Each handler runs on the JavaFX Application Thread,
  so long-running copies are dispatched to background threads to keep the UI
  responsive.

  No handler scans a device. The table is always painted from the cache
  (dapr.library.catalogs) and all walking is owned by the background refresher
  (dapr.refresh), which a selection change merely re-prioritizes. The only
  foreground device work left is the sync's copies/deletes and the sink's
  free-space query, both taken under the device lock (dapr.device.coordinator) so
  they preempt an in-flight refresh."
  (:require [clojure.java.io :as io]
            [dapr.db.cache :as cache]
            [dapr.device.coordinator :as coord]
            [dapr.device.events :as device-events]
            [dapr.device.file.events]
            [dapr.device.format :as device]
            [dapr.device.fs :as dfs]
            [dapr.device.mtp.events]
            [dapr.device.smb.events]
            [dapr.domain.library :as lib]
            [dapr.domain.plan :as plan]
            [dapr.library.catalogs :as catalogs]
            [dapr.log :as log]
            [dapr.refresh :as refresh]
            [dapr.state :as state]
            [dapr.sync :as sync]
            [dapr.ui.format :as fmt]
            [datascript.core :as d]
            [taoensso.telemere :as t])
  (:import (javafx.animation PauseTransition)
           (javafx.application Platform)
           (javafx.event EventHandler)
           (javafx.scene.control ListView)
           (javafx.scene.input MouseEvent)
           (javafx.stage DirectoryChooser)
           (javafx.util Duration)))

(defn- refresh-libraries!
  "Re-read the library projection in state from the cache DB (the system of
  record) after a mutation."
  [state-atom conn]
  (swap! state-atom state/set-libraries (cache/libraries (d/db conn))))

(defn- error-summary
  "A short one-line description of `t` for the status/error field — its message, or
  its class name when it has none (e.g. a StackOverflowError)."
  [^Throwable t]
  (or (not-empty (.getMessage t)) (.getName (class t))))

(defn- log-error!
  "Emit an error signal carrying `t` (so its stack trace lands in the log file) with
  a one-line `prefix` message, and set the UI error field to its summary."
  [state-atom prefix ^Throwable t]
  (let [summary (error-summary t)]
    (t/log! {:level :error :error t :msg (str prefix summary)})
    (swap! state-atom state/set-error summary)))

(defn- paint-catalogs!
  "Repaint the source/sink catalogs from the cache — no device walk (see
  dapr.library.catalogs/paint!). `preselect?` pre-selects the tracks already on the
  sink, which is what a *fresh* source/sink choice wants; a repaint mid-session
  passes false so the user's ticks survive."
  [state-atom {:keys [conn]} preselect?]
  (try
    (when-let [{:keys [source sink free]} (catalogs/paint! state-atom conn {:preselect? preselect?})]
      (t/log! (format "Source %d · Sink %d tracks · %s free."
                      source sink (fmt/human-bytes free))))
    (catch Throwable t
      (log-error! state-atom "Loading the catalogs failed: " t))))

(defn- select-libraries!
  "React to a new source/sink choice: paint their tracks from the cache instantly,
  then push them to the front of the background refresh queue. Runs off the JFX
  thread (the free-space query can block on the device).

  Works with or without a sink: a sink-less source shows its tracks alone (empty
  sink catalog, 0 free, nothing pre-selected) so the table is browsable before a
  sink is picked — Preview/Sync stay disabled until both are set (see
  fmt/can-preview?)."
  [state-atom cache refresher]
  (paint-catalogs! state-atom cache true)
  (let [s @state-atom]
    (refresh/prioritize! refresher [(:source-id s) (:sink-id s)])))

(defn- run-preview!
  "Compute the selection plan from the catalogs already in state and move to
  :planned. Purely cache-driven — the libraries are never re-walked here; the only
  device touch is the sink's per-root free space, which the placement math needs
  and which is taken under the sink's device lock so it preempts a running
  refresh."
  [state-atom]
  (let [{:keys [source-catalog sink-catalog selected] :as s} @state-atom
        src       (state/library-by-id s (:source-id s))
        snk       (state/library-by-id s (:sink-id s))
        handling  (state/setting s :sink-only-handling :keep)]
    (swap! state-atom state/set-status :scanning)
    (t/log! "Computing plan…")
    (try
      (let [src-roots (when (= handling :add-to-source)
                        (coord/with-device! (coord/library-device src) #(sync/library-roots! src)))
            actions   (plan/selection-plan
                       {:source-catalog     source-catalog
                        :sink-catalog       sink-catalog
                        :selected           selected
                        :sink-roots         (coord/with-device! (coord/library-device snk)
                                              #(sync/library-roots! snk))
                        :sink-only-handling handling
                        :source-roots       src-roots})
            summ      (plan/summary actions)]
        (swap! state-atom (fn [s] (-> s
                                      (state/set-plan actions summ)
                                      (state/set-progress nil))))
        (t/log! (fmt/plan-summary-text summ)))
      (catch Throwable t
        (log-error! state-atom "Plan failed: " t)))))

(defn- run-sync!
  "Execute the planned copies/deletes. The whole execution holds both libraries'
  device locks (dedup'd to one when they share a device), so a background refresh
  check-points and gets out of the way for the duration instead of interleaving
  with the transfer."
  [state-atom {:keys [conn path] :as cache}]
  (let [{:keys [source-catalog sink-catalog source-id sink-id] :as s0} @state-atom
        actions (get-in s0 [:plan :actions])
        src     (state/library-by-id s0 source-id)
        snk     (state/library-by-id s0 sink-id)]
    (swap! state-atom state/set-status :syncing)
    (t/log! "Syncing…")
    (try
      (let [result (coord/with-devices!
                     [(coord/library-device src) (coord/library-device snk)]
                     (fn []
                       (sync/execute-plan!
                        actions
                        {:on-progress (fn [p] (swap! state-atom state/set-progress
                                                     (select-keys p [:done :total])))})))]
        ;; Update the sink's cache entry directly from the executed plan, so a
        ;; sync needs no re-walk; then refresh the catalogs from the cache.
        (sync/apply-plan-to-cache! conn sink-id source-catalog actions)
        ;; Register copied-back sink-only tracks (:add-to-source) on the source.
        (sync/apply-source-adds-to-cache! conn source-id sink-catalog actions)
        (cache/snapshot! conn path)
        (paint-catalogs! state-atom cache true)
        (swap! state-atom (fn [s] (-> s
                                      (state/set-status :done)
                                      (state/set-progress nil))))
        (t/log! (format "Done. Added %d, deleted %d, to source %d."
                        (:add result) (:delete result) (:add-to-source result))))
      (catch Throwable t
        (log-error! state-atom "Sync failed: " t)))))

(def ^:private sync-confirm
  "Confirmation shown when a sync would run against a library whose background
  refresh hasn't finished this session. Its cached catalog is then a *superset* of
  what is really on the device (nothing is retracted until a walk completes), so a
  plan built from it can try to copy a track that has since been deleted, or skip
  one it wrongly believes is already on the sink."
  {:kind         :sync
   :title        "Refresh still in progress"
   :confirm-text "Sync anyway"})

(defn- sync-confirmation
  "The confirmation to show before syncing `state`, or nil when both libraries have
  completed a refresh this session and the plan can be trusted."
  [state]
  (when-let [libs (seq (state/sync-incomplete-libraries state))]
    (assoc sync-confirm
           :message (format "Still refreshing %s, so the track list may not match what is on the device. Sync anyway?"
                            (fmt/name-list (map :name libs))))))

(defn- probe-availability!
  "Probe each library's device reachability off the JFX thread and record an
  id->bool map in state (dfs/available? per root; a library is available when all
  its roots resolve to an existing directory). SMB/MTP probes may block, hence the
  background thread at the call sites."
  [state-atom]
  (let [libs  (:libraries @state-atom)
        avail (into {} (map (fn [l] [(:id l) (boolean (and (seq (:roots l))
                                                           (every? dfs/available? (:roots l))))]))
                    libs)]
    (swap! state-atom state/set-library-availability avail)))

(defn- refresh-availability!
  "Re-probe availability, drop any source/sink selection that has become
  unavailable, repaint the remaining catalogs from the cache, and re-queue a
  background refresh of every library. Used at launch and by the ↻ Refresh button —
  which is therefore also how a user forces an already-completed library to be
  re-walked (see refresh/refresh-all!)."
  [state-atom cache refresher]
  (probe-availability! state-atom)
  (swap! state-atom (fn [s] (state/clear-unavailable-selection s (:library-availability s))))
  (when (:source-id @state-atom)
    (paint-catalogs! state-atom cache false))
  (refresh/refresh-all! refresher))

(defn start!
  "Once the UI is mounted, probe library availability, drop any pre-selected
  default whose device is unreachable, paint the source's tracks from the cache
  (instant, no walk), and kick off the background refresh."
  [state-atom cache refresher]
  (future
    (probe-availability! state-atom)
    (swap! state-atom (fn [s] (state/clear-unavailable-selection s (:library-availability s))))
    (when (:source-id @state-atom)
      (paint-catalogs! state-atom cache true))
    (refresh/refresh-all! refresher)))

(def ^:private mixed-device-msg
  "A library's roots must all live on one device — remove the existing roots first to switch device.")

(defn- library-id-by-name [state-atom nm]
  (:id (first (filter #(= nm (:name %)) (:libraries @state-atom)))))

(defn- choose-log-dir!
  "Open a directory chooser (on the JFX thread); on a pick, persist the :log-dir
  setting and repoint the file log there (a fresh dapr.N.log). No-op on cancel."
  [state-atom {:keys [conn path]}]
  (let [init    (let [d (io/file (log/log-dir (:settings @state-atom)))]
                  (when (.isDirectory d) d))
        chooser (doto (DirectoryChooser.)
                  (.setTitle "Choose log directory")
                  (.setInitialDirectory init))
        dir     (.showDialog chooser nil)]
    (when dir
      (let [dir-path (.getAbsolutePath dir)]
        (swap! state-atom state/set-setting :log-dir dir-path)
        (cache/set-app-setting! conn :log-dir dir-path)
        (cache/snapshot! conn path)
        (log/set-dir! state-atom dir-path)))))

(defonce ^:private state-atom*
  ;; Holds the live state-atom so the log window's raw JavaFX listeners (which cljfx
  ;; can't express declaratively — a ListView items-change tail-follow scroll and a
  ;; scrollbar freeze detector) can read/update state outside the normal event flow.
  (atom nil))

(defn log-state
  "Current app state, for the log window's raw JavaFX listeners (see dapr.ui.views).
  nil before the handler is installed."
  []
  (some-> @state-atom* deref))

(defn on-log-scroll!
  "Feed the log ListView's vertical scrollbar value (0..1) into state so scrolling up
  freezes tail-following (see state/log-scrolled). A no-op before wiring."
  [pos]
  (some-> @state-atom* (swap! state/log-scrolled pos)))

(def ^:private facet-single-click-millis
  "Delay before a single facet click applies its filter — long enough for a second
  click to arrive and be treated as a double-click (which toggles without filtering),
  so the first click of a double never flashes the view narrowed."
  250.0)

(defonce ^:private pending-facet-click*
  ;; The scheduled single-click filter (a PauseTransition), held so a following click
  ;; can cancel it before it fires. One is enough — any new click supersedes it.
  (atom nil))

(defn- cancel-pending-facet-click! []
  (when-let [^PauseTransition p @pending-facet-click*]
    (.stop p)
    (reset! pending-facet-click* nil)))

(defn- facet-click!
  "Handle a click on a column-browser facet list for column `col` (:artist/:album).
  Filtering is driven from the click (not the selection model) so a double-click can
  leave the view unnarrowed. A **single** click applies the clicked facet as the
  filter, but only after a short delay — if a second click lands first it's a
  **double** click, which cancels the pending filter and instead toggles selection of
  every track under the facet (never touching the filter). Matching keys come from the
  union catalog via fmt/filter-catalog — an album is scoped to the active artist filter
  so same-named albums across artists don't collide. The 'All' entry clears the filter
  but toggles nothing."
  [state-atom col ^MouseEvent ev]
  (let [item  (.getSelectedItem (.getSelectionModel ^ListView (.getSource ev)))
        value (when (and (string? item) (not= "All" item)) item)]
    (cancel-pending-facet-click!)
    (if (= 2 (.getClickCount ev))
      (when value
        (let [{:keys [source-catalog sink-catalog filter]} @state-atom
              flt (case col
                    :artist {:artist value :album nil}
                    :album  {:artist (:artist filter) :album value})
              ks  (keys (fmt/filter-catalog (merge sink-catalog source-catalog) flt))]
          (swap! state-atom state/toggle-keys ks)))
      (let [set-filter (case col
                         :artist state/set-filter-artist
                         :album  state/set-filter-album)
            p          (doto (PauseTransition. (Duration/millis facet-single-click-millis))
                         (.setOnFinished
                          (reify EventHandler
                            (handle [_ _]
                              (reset! pending-facet-click* nil)
                              (swap! state-atom set-filter value)))))]
        (reset! pending-facet-click* p)
        (.play p)))))

(defn make-handler
  "Return a cljfx event handler closing over `state-atom`, the `cache` component
  {:conn :path} that owns library/scan persistence, and the background `refresher`
  that owns all device scanning."
  [state-atom {:keys [conn] :as cache} refresher]
  (reset! state-atom* state-atom)
  (fn [event]
    (case (:event/type event)
      ;; settings modal
      ::settings-open  (swap! state-atom state/open-settings)
      ::settings-close (swap! state-atom state/close-settings)

      ;; app settings — generic seam: update the in-memory map and persist to the
      ;; cache DB. Feature settings dispatch ::set-setting with {:key :value}.
      ::set-setting    (do (swap! state-atom state/set-setting (:key event) (:value event))
                           (cache/set-app-setting! conn (:key event) (:value event))
                           (cache/snapshot! conn (:path cache)))

      ;; library manager — the device type is chosen from the New… submenu and
      ;; pins the new library to file://, mtp:// or smb:// (editing derives it from
      ;; the existing roots)
      ::library-new    (swap! state-atom state/set-editor
                              {:id nil :name "" :roots []
                               :device/type (:device/type event)})
      ::library-edit   (when-let [l (state/library-by-id @state-atom (:id event))]
                         (swap! state-atom state/set-editor
                                (assoc l :device/type (device/device-type (first (:roots l))))))
      ::library-delete (do (cache/delete-library! conn (:id event))
                           (cache/snapshot! conn (:path cache))
                           (swap! state-atom (fn [s] (-> s
                                                         (state/delete-library (:id event))
                                                         (state/forget-refresh (:id event)))))
                           (refresh-libraries! state-atom conn)
                           (future (probe-availability! state-atom)))
      ;; Mark/clear a library as the default source or sink (applied at next
      ;; launch, see start!). The current session's selection is left as-is.
      ::library-default (do (cache/set-default! conn (:role event) (:id event))
                            (cache/snapshot! conn (:path cache))
                            (refresh-libraries! state-atom conn))

      ;; editor
      ::editor-name        (swap! state-atom state/editor-name (:fx/event event))
      ::editor-remove-root (swap! state-atom state/editor-remove-root (:uri event))

      ;; folder browser — each device type owns how its browser opens (see
      ;; dapr.device.*.events): file:// navigates folders directly, mtp:// first
      ;; picks a connected device, smb:// first enters a share URL + credentials.
      ;; Once a cwd is established the navigation events below are device-generic.
      ::editor-browse        (device-events/open-browser!
                              (get-in @state-atom [:editor :device/type]) state-atom)
      ::browser-connect-field (swap! state-atom state/browser-field
                                     (:field event) (:fx/event event))
      ::browser-connect      (when (device-events/connect!
                                    (get-in @state-atom [:browser :device/type]) state-atom)
                               (device-events/load-browser-entries! state-atom))
      ::browser-device       (when (device-events/choose-device!
                                    (get-in @state-atom [:browser :device/type]) state-atom (:device event))
                               (device-events/load-browser-entries! state-atom))
      ::browser-enter        (do (swap! state-atom state/browser-enter (:child event))
                                 (device-events/load-browser-entries! state-atom))
      ::browser-crumb        (do (swap! state-atom state/browser-to-crumb (:idx event))
                                 (device-events/load-browser-entries! state-atom))
      ::browser-places       (do (swap! state-atom state/browser-to-places)
                                 (device-events/load-browser-entries! state-atom))
      ::browser-select (when-let [uri (get-in @state-atom [:browser :cwd])]
                         (if (lib/root-addable? (get-in @state-atom [:editor :roots]) uri)
                           (swap! state-atom (fn [s] (-> s
                                                         (state/editor-add-root uri)
                                                         (state/browser-close))))
                           (t/log! mixed-device-msg)))
      ::browser-cancel (swap! state-atom state/browser-close)

      ::editor-save
      (let [library (select-keys (:editor @state-atom) [:id :name :roots])]
        (if (lib/library-valid? library)
          (let [lib-id (cache/upsert-library! conn library)]
            (cache/snapshot! conn (:path cache))
            (refresh-libraries! state-atom conn)
            (swap! state-atom state/cancel-editor)
            ;; A new or re-rooted library needs a walk before its cache means
            ;; anything, so queue one.
            (refresh/enqueue! refresher lib-id)
            (future (probe-availability! state-atom)))
          (t/log! "Library needs a name and at least one file://, mtp:// or smb:// root.")))
      ::editor-cancel (swap! state-atom state/cancel-editor)

      ;; sync workflow — a selection paints from the cache and re-prioritizes the
      ;; background refresh; it never scans (see select-libraries!).
      ::select-source (do (swap! state-atom state/select-source
                                 (library-id-by-name state-atom (:fx/event event)))
                          (future (select-libraries! state-atom cache refresher)))
      ::select-sink   (do (swap! state-atom state/select-sink
                                 (library-id-by-name state-atom (:fx/event event)))
                          (future (select-libraries! state-atom cache refresher)))
      ::toggle-track  (swap! state-atom state/toggle-track (:key event))

      ;; column-browser facets — single click filters (see facet-click!), double
      ;; click toggles every track under the facet without narrowing the view.
      ::facet-click-artist   (facet-click! state-atom :artist (:fx/event event))
      ::facet-click-album    (facet-click! state-atom :album (:fx/event event))
      ::filter-search-artist (swap! state-atom state/set-filter-search :artist (:fx/event event))
      ::filter-search-album  (swap! state-atom state/set-filter-search :album (:fx/event event))
      ::refresh-availability (future (refresh-availability! state-atom cache refresher))
      ::preview       (future (run-preview! state-atom))

      ;; Sync is gated on both libraries having completed a refresh this session:
      ;; until then their cached catalogs are supersets of the devices (see
      ;; sync-confirmation), so the user is asked first.
      ::sync          (if-let [confirm (sync-confirmation @state-atom)]
                        (swap! state-atom state/open-confirm confirm)
                        (future (run-sync! state-atom cache)))
      ;; No re-check on confirm: if the refresh finished while the dialog was open
      ;; the sync is simply safer than advertised, and the user has already said go.
      ::sync-confirm  (do (swap! state-atom state/close-confirm)
                          (future (run-sync! state-atom cache)))
      ::confirm-cancel (swap! state-atom state/close-confirm)

      ;; logging — the live log window + its log-dir picker
      ::view-logs      (swap! state-atom state/open-log)
      ::log-close      (swap! state-atom state/close-log)
      ::log-follow     (swap! state-atom state/follow-log)
      ::choose-log-dir (choose-log-dir! state-atom cache)

      ::quit          (do (Platform/exit) (System/exit 0))
      nil)))
