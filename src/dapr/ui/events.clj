(ns dapr.ui.events
  "Side-effecting event handlers for the cljfx UI. Pure state transitions live in
  dapr.state; filesystem work lives in dapr.fs.nio / dapr.sync; library and scan
  persistence in dapr.cache. Each handler runs on the JavaFX Application Thread,
  so long-running scans/copies are dispatched to background threads to keep the
  UI responsive."
  (:require [clojure.java.io :as io]
            [dapr.cache :as cache]
            [dapr.device.events :as device-events]
            [dapr.device.file.events]
            [dapr.device.format :as device]
            [dapr.device.fs :as dfs]
            [dapr.device.mtp.events]
            [dapr.device.smb.events]
            [dapr.domain.library :as lib]
            [dapr.domain.plan :as plan]
            [dapr.log :as log]
            [dapr.state :as state]
            [dapr.sync :as sync]
            [dapr.ui.format :as fmt]
            [datascript.core :as d]
            [taoensso.telemere :as t])
  (:import (javafx.application Platform)
           (javafx.stage DirectoryChooser)))

(defn- refresh-libraries!
  "Re-read the library projection in state from the cache DB (the system of
  record) after a mutation."
  [state-atom conn]
  (swap! state-atom state/set-libraries (cache/libraries (d/db conn))))

(defn- filter-value
  "Normalize a column-browser selection to a filter value: the list's 'All' entry
  and a cleared selection (nil) both mean no constraint."
  [v]
  (when (and v (not= "All" v)) v))

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

(defn- scan-logger
  "Scan-event callback that logs progress, tagged with the library being scanned.
  Each directory entered is logged at :info (the last such line before a freeze
  pinpoints the directory whose listing hung); per-file lines are :debug (filtered
  from the file/UI by default, see dapr.log) since the progress bar already tracks
  file-level progress."
  [label]
  (let [scanned (atom 0)]
    (fn [{:keys [type rel track]}]
      (case type
        :dir  (t/log! (format "  [%s] scanning %s/" label (if (= "" rel) "" rel)))
        :file (let [n (swap! scanned inc)]
                (t/log! :debug (format "  [%s] #%d %s" label n (or (:rel track) (:name track)))))
        nil))))

(defn- begin-scan!
  "Start a new scan generation, superseding any in-flight one. Returns a
  `superseded?` predicate that becomes true once a later scan begins."
  [state-atom]
  (let [gen (:scan-gen (swap! state-atom update :scan-gen inc))]
    (fn [] (not= gen (:scan-gen @state-atom)))))

(defn- superseded-ex?
  "True when `t` (or any of its causes) is the marker thrown to abort a scan that
  a newer one has superseded."
  [t]
  (boolean (some #(:dapr/abort (ex-data %))
                 (take-while some? (iterate #(some-> ^Throwable % .getCause) t)))))

(def ^:private progress-update-stride
  "Publish scan progress to the UI only every Nth visited entry (plus on every
  directory listing), so a large scan advances the progress bar steadily without a
  state swap — and re-render — for every single file."
  64)

(defn- begin-progress!
  "Reset the UI progress bar and return a fresh shared accumulator {:done :total}
  that the (possibly concurrent) source and sink scans both feed."
  [state-atom]
  (swap! state-atom state/set-progress {:done 0 :total 0})
  (atom {:done 0 :total 0}))

(defn- scan-progress!
  "Fold one scan event into the shared `prog` accumulator and, on a throttled
  cadence, publish it to state's :progress so the bar advances. :listing events
  grow the total by a directory's child count (so the total climbs as the walk
  recurses deeper); :entry events advance done toward it."
  [state-atom prog ev]
  (case (:type ev)
    :listing (let [p (swap! prog update :total + (:count ev))]
               (swap! state-atom state/set-progress (select-keys p [:done :total])))
    :entry   (let [p (swap! prog update :done inc)]
               (when (zero? (mod (:done p) progress-update-stride))
                 (swap! state-atom state/set-progress (select-keys p [:done :total]))))
    nil))

(defn- scan-callback
  "An on-scan callback that accumulates overall scan progress into `prog` and logs
  progress (see scan-logger), but first aborts the walk, by throwing, once
  `superseded?` reports a newer scan has started."
  [state-atom label superseded? prog]
  (let [log (scan-logger label)]
    (fn [ev]
      (when (superseded?)
        (throw (ex-info "scan superseded" {:dapr/abort true})))
      (scan-progress! state-atom prog ev)
      (log ev))))

(defn- set-catalogs-from-cache!
  "Replace the source/sink catalogs in state with the cache's current view (no
  walk). Sink free space is the one uncached input, so it is read — a usable-space
  query, not a walk. Pre-selects tracks already on the sink (state/set-catalogs)."
  [state-atom conn src snk]
  (swap! state-atom state/set-catalogs
         (cache/library-catalog (d/db conn) (:id src))
         (cache/library-catalog (d/db conn) (:id snk))
         (sync/library-free! snk)))

(defn- load-cached-catalogs!
  "Instant first paint of the source/sink catalogs from the cache, before the
  background refresh re-scans (see reload-catalogs!). `snk` may be nil — a source
  chosen without a sink shows the source's tracks alone (the sink catalog and free
  space come back empty/0, see set-catalogs-from-cache!)."
  [state-atom conn src snk]
  (set-catalogs-from-cache! state-atom conn src snk)
  (t/log! (if snk
            (format "Loaded '%s' → '%s' from cache; refreshing…" (:name src) (:name snk))
            (format "Loaded '%s' (no sink) from cache; refreshing…" (:name src)))))

(defn- reload-catalogs!
  "Refresh the source/sink catalogs. First loads them instantly from the cache so
  the table renders right away, then re-scans the libraries in the background
  (reusing cached tags for unchanged files), updates the cache, and refreshes the
  catalogs + capacity. Pre-selects tracks already on the sink (via
  state/set-catalogs). Supersedes any refresh still running from an earlier
  source/sink change.

  Runs whenever a source is chosen, with or without a sink: a sink-less source
  shows its tracks alone (empty sink catalog, 0 free, nothing pre-selected) so the
  table is populated for browsing before a sink is picked — Preview/Sync stay
  disabled until both are set (see fmt/can-preview?)."
  [state-atom {:keys [conn path]}]
  (let [s   @state-atom
        src (state/library-by-id s (:source-id s))
        snk (state/library-by-id s (:sink-id s))]
    (when src
      ;; Bump the scan generation *before* painting from the cache, so any scan
      ;; still in flight from an earlier selection is superseded and can't swap its
      ;; now-stale catalogs in after this refresh has started.
      (let [superseded? (begin-scan! state-atom)
            prog        (begin-progress! state-atom)]
        (load-cached-catalogs! state-atom conn src snk)
        (swap! state-atom state/set-status :scanning)
        (try
          ;; Scan source and sink concurrently — melt-jfs serializes per device, so
          ;; this overlaps work whenever they are on different devices (the common
          ;; case: one local, one MTP) and is harmless when they share one. Both feed
          ;; the one shared progress accumulator and reconcile their own cache entry.
          ;; With no sink, only the source is scanned.
          (let [src-fut (future (sync/scan-into-cache! conn (:id src) src
                                                       (scan-callback state-atom (:name src) superseded? prog)))
                snk-cat (when snk
                          (sync/scan-into-cache! conn (:id snk) snk
                                                 (scan-callback state-atom (:name snk) superseded? prog)))
                src-cat @src-fut
                snk-cat (or snk-cat {})
                free    (if snk (sync/library-free! snk) 0)]
            (cache/snapshot! conn path)
            (when-not (superseded?)
              (swap! state-atom
                     (fn [s] (-> s
                                 (state/set-catalogs src-cat snk-cat free)
                                 (state/set-progress nil)
                                 (state/set-status :idle))))
              (t/log! (format "Source %d · Sink %d tracks · %s free."
                              (count src-cat) (count snk-cat) (fmt/human-bytes free)))))
          (catch Throwable t
            (when-not (superseded-ex? t)
              (log-error! state-atom "Scan failed: " t))))))))

(defn- run-preview!
  "Compute the selection plan and move to :planned. Reuses the catalogs already
  scanned into state when the source/sink were chosen (see reload-catalogs!), so
  previewing doesn't re-walk the libraries — only the sink's per-root free space
  is re-read, which is cheap. Falls back to a fresh scan only if a catalog is
  empty (e.g. the selection scan is still in flight)."
  [state-atom]
  (let [{:keys [source-catalog sink-catalog selected] :as s} @state-atom
        src       (state/library-by-id s (:source-id s))
        snk       (state/library-by-id s (:sink-id s))
        handling  (state/setting s :sink-only-handling :keep)
        src-roots (when (= handling :add-to-source) (sync/library-roots! src))]
    (swap! state-atom state/set-status :scanning)
    (t/log! "Computing plan…")
    (try
      (let [actions (if (and (seq source-catalog) (seq sink-catalog))
                      (plan/selection-plan {:source-catalog     source-catalog
                                            :sink-catalog       sink-catalog
                                            :selected           selected
                                            :sink-roots         (sync/sink-roots! snk)
                                            :sink-only-handling handling
                                            :source-roots       src-roots})
                      (sync/build-plan! src snk selected
                                        {:sink-only-handling handling
                                         :source-roots       src-roots}))
            summ    (plan/summary actions)]
        (swap! state-atom (fn [s] (-> s
                                      (state/set-plan actions summ)
                                      (state/set-progress nil))))
        (t/log! (fmt/plan-summary-text summ)))
      (catch Throwable t
        (log-error! state-atom "Plan failed: " t)))))

(defn- run-sync! [state-atom {:keys [conn path]}]
  (let [{:keys [source-catalog sink-catalog source-id sink-id] :as s0} @state-atom
        actions (get-in s0 [:plan :actions])]
    (swap! state-atom state/set-status :syncing)
    (t/log! "Syncing…")
    (try
      (let [result (sync/execute-plan!
                    actions
                    {:on-progress (fn [p] (swap! state-atom state/set-progress
                                                 (select-keys p [:done :total])))})]
        ;; Update the sink's cache entry directly from the executed plan, so a
        ;; sync needs no re-walk; then refresh the catalogs from the cache.
        (sync/apply-plan-to-cache! conn sink-id source-catalog actions)
        ;; Register copied-back sink-only tracks (:add-to-source) on the source.
        (sync/apply-source-adds-to-cache! conn source-id sink-catalog actions)
        (cache/snapshot! conn path)
        (let [s   @state-atom
              src (state/library-by-id s source-id)
              snk (state/library-by-id s sink-id)]
          (when (and src snk) (set-catalogs-from-cache! state-atom conn src snk)))
        (swap! state-atom (fn [s] (-> s
                                      (state/set-status :done)
                                      (state/set-progress nil))))
        (t/log! (format "Done. Added %d, deleted %d, to source %d."
                        (:add result) (:delete result) (:add-to-source result))))
      (catch Throwable t
        (log-error! state-atom "Sync failed: " t)))))

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
  "Re-probe availability, then drop any source/sink selection that has become
  unavailable (and reload the remaining catalogs). Used at launch and on the
  manual refresh action."
  [state-atom cache]
  (probe-availability! state-atom)
  (swap! state-atom (fn [s] (state/clear-unavailable-selection s (:library-availability s))))
  (when (:source-id @state-atom)
    (reload-catalogs! state-atom cache)))

(defn start!
  "Once the UI is mounted, probe library availability, drop any pre-selected
  default whose device is unreachable, then load the source's tracks if a source
  remains selected (see reload-catalogs!)."
  [state-atom cache]
  (future (refresh-availability! state-atom cache)))

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

(defonce ^:private handler*
  ;; Holds the live event handler so raw JavaFX listeners that cljfx can't express
  ;; as props (e.g. the log window's scrollTop listener) can feed the normal event
  ;; flow via dispatch!.
  (atom nil))

(defn dispatch!
  "Dispatch an event map through the current cljfx event handler (see make-handler).
  A no-op before the handler is installed."
  [event]
  (when-let [h @handler*] (h event)))

(defn make-handler
  "Return a cljfx event handler closing over `state-atom` and the `cache`
  component {:conn :path} that owns library/scan persistence."
  [state-atom {:keys [conn] :as cache}]
  (reset!
   handler*
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
                            (swap! state-atom state/delete-library (:id event))
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
           (do (cache/upsert-library! conn library)
               (cache/snapshot! conn (:path cache))
               (refresh-libraries! state-atom conn)
               (swap! state-atom state/cancel-editor)
               (future (probe-availability! state-atom)))
           (t/log! "Library needs a name and at least one file://, mtp:// or smb:// root.")))
       ::editor-cancel (swap! state-atom state/cancel-editor)

      ;; sync workflow
       ::select-source (do (swap! state-atom state/select-source
                                  (library-id-by-name state-atom (:fx/event event)))
                           (future (reload-catalogs! state-atom cache)))
       ::select-sink   (do (swap! state-atom state/select-sink
                                  (library-id-by-name state-atom (:fx/event event)))
                           (future (reload-catalogs! state-atom cache)))
       ::toggle-track  (swap! state-atom state/toggle-track (:key event))

      ;; column-browser filter — the list's "All" entry (and a cleared selection)
      ;; both mean no constraint (nil).
       ::filter-artist (swap! state-atom state/set-filter-artist (filter-value (:fx/event event)))
       ::filter-album  (swap! state-atom state/set-filter-album (filter-value (:fx/event event)))
       ::filter-search-artist (swap! state-atom state/set-filter-search :artist (:fx/event event))
       ::filter-search-album  (swap! state-atom state/set-filter-search :album (:fx/event event))
       ::refresh-availability (future (refresh-availability! state-atom cache))
       ::preview       (future (run-preview! state-atom))
       ::sync          (future (run-sync! state-atom cache))

      ;; logging — the live log window + its log-dir picker
       ::view-logs      (swap! state-atom state/open-log)
       ::log-close      (swap! state-atom state/close-log)
       ::log-follow     (swap! state-atom state/follow-log)
       ::log-scrolled   (swap! state-atom state/log-scrolled (:fx/event event))
       ::choose-log-dir (choose-log-dir! state-atom cache)

       ::quit          (do (Platform/exit) (System/exit 0))
       nil))))
