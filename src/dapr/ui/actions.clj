(ns dapr.ui.actions
  "Side-effecting operations behind the UI's controls. Pure state transitions live
  in dapr.state; filesystem work in dapr.fs.nio / dapr.sync; library and scan
  persistence in dapr.db.cache. Each function here applies a transition to the
  state atom and, where the work can block, hands it to a background thread —
  the HTTP request must come back with the fragment now, not when a device
  finally answers.

  No action scans a device. The table is always painted from the cache
  (dapr.library.catalogs) and all walking is owned by the background refresher
  (dapr.refresh), which a selection change merely re-prioritizes. The only
  foreground device work left is the sync's copies/deletes and the sink's
  free-space query, both taken under the device lock (dapr.device.coordinator) so
  they preempt an in-flight refresh."
  (:require [clojure.java.io :as io]
            [dapr.db.cache :as cache]
            [dapr.device.availability :as availability]
            [dapr.device.coordinator :as coord]
            [dapr.device.events :as device-events]
            [dapr.device.file.events]
            [dapr.device.format :as device]
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
            [taoensso.telemere :as t]))

(defn- refresh-libraries!
  "Re-read the library projection in state from the cache DB (the system of
  record) after a mutation."
  [state-atom conn]
  (swap! state-atom state/set-libraries (cache/libraries (d/db conn))))

(defn- log-error!
  "Emit an error signal carrying `t` (so its stack trace lands in the log file) with
  a one-line `prefix` message, and set the UI error field to its summary."
  [state-atom prefix ^Throwable t]
  (let [summary (log/error-summary t)]
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
  "React to a new source/sink choice: paint their tracks from the cache, then queue
  a background refresh of them. Choosing a library is what schedules a scan of it —
  nothing else does (see refresh/refresh!). Runs off the request thread (the
  free-space query can block on the device).

  Works with or without a sink: a sink-less source shows its tracks alone (empty
  sink catalog, 0 free, nothing pre-selected) so the table is browsable before a
  sink is picked — Preview/Sync stay disabled until both are set (see
  fmt/can-preview?)."
  [state-atom cache refresher]
  (paint-catalogs! state-atom cache true)
  (let [s @state-atom]
    (refresh/refresh! refresher [(:source-id s) (:sink-id s)])))

(defn- run-preview!
  "Compute the selection plan from the catalogs already in state and move to
  :planned. Purely cache-driven — the libraries are never re-walked here; the only
  device touch is the sink's per-root free space, which the placement math needs
  and which is taken under the sink's device lock so it preempts a running
  refresh."
  [state-atom]
  (let [{:keys [source-catalog sink-catalog selected] :as s} @state-atom
        src      (state/library-by-id s (:source-id s))
        snk      (state/library-by-id s (:sink-id s))
        handling (state/setting s :sink-only-handling :keep)]
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

(defn- refresh-selection!
  "Probe availability, drop any source/sink selection that has become unavailable,
  repaint the remaining catalogs from the cache, and re-queue a background refresh
  of the chosen source and sink. Used at launch and by the ↻ Refresh button — which
  is how a user forces an already-completed library to be re-walked after changing
  it on the device side. Disconnect/reconnect availability is also kept current by
  dapr.device.availability's device-generic monitor.

  Only the *chosen* libraries are walked, here as everywhere: the others have no
  reader waiting on their catalogs, and reaching for a device that isn't attached
  costs a blocking probe per root and reports a failure the user can do nothing
  about."
  [state-atom cache refresher preselect?]
  (availability/probe! state-atom)
  (swap! state-atom (fn [s] (state/clear-unavailable-selection s (:library-availability s))))
  (when (:source-id @state-atom)
    (paint-catalogs! state-atom cache preselect?))
  (let [s @state-atom]
    (refresh/refresh! refresher [(:source-id s) (:sink-id s)])))

(defn start!
  "Once the server is up, probe library availability, drop any pre-selected default
  whose device is unreachable, paint the source's tracks from the cache (instant,
  no walk), and queue a refresh of the source and sink."
  [state-atom cache refresher]
  (future (refresh-selection! state-atom cache refresher true)))

(def ^:private mixed-device-msg
  "A library's roots must all live on one device — remove the existing roots first to switch device.")

;; --- source / sink / selection ----------------------------------------------

(defn select-source! [state-atom cache refresher id]
  (swap! state-atom state/select-source id)
  (future (select-libraries! state-atom cache refresher)))

(defn select-sink! [state-atom cache refresher id]
  (swap! state-atom state/select-sink id)
  (future (select-libraries! state-atom cache refresher)))

(defn toggle-track! [state-atom track-key]
  (swap! state-atom state/toggle-track track-key))

(defn set-filter!
  "Apply a column-browser facet as the filter (a nil value clears it)."
  [state-atom col value]
  (swap! state-atom (case col
                      :artist state/set-filter-artist
                      :album  state/set-filter-album)
         value))

(defn set-filter-search! [state-atom col text]
  (swap! state-atom state/set-filter-search col text))

(defn toggle-facet!
  "Check or uncheck every track under a column-browser facet, without narrowing the
  view. Matching keys come from the union catalog — an album is scoped to the
  active artist filter so same-named albums across artists don't collide."
  [state-atom col value]
  (let [{:keys [source-catalog sink-catalog filter]} @state-atom
        flt (case col
              :artist {:artist value :album nil}
              :album  {:artist (:artist filter) :album value})
        ks  (keys (fmt/filter-catalog (merge sink-catalog source-catalog) flt))]
    (swap! state-atom state/toggle-keys ks)))

(defn refresh! [state-atom cache refresher]
  (future (refresh-selection! state-atom cache refresher false)))

(defn preview! [state-atom]
  (future (run-preview! state-atom)))

(defn sync!
  "Start a sync, or ask first when a chosen library's background refresh hasn't
  finished this session (its cached catalog is then a superset of the device)."
  [state-atom cache]
  (if-let [confirm (sync-confirmation @state-atom)]
    (swap! state-atom state/open-confirm confirm)
    (future (run-sync! state-atom cache))))

(defn confirm-accept!
  "Accept the pending confirmation. No re-check: if the refresh finished while the
  dialog was open the sync is simply safer than advertised, and the user has
  already said go."
  [state-atom cache]
  (let [kind (get-in @state-atom [:confirm :kind])]
    (swap! state-atom state/close-confirm)
    (when (= :sync kind)
      (future (run-sync! state-atom cache)))))

(defn confirm-cancel! [state-atom]
  (swap! state-atom state/close-confirm))

;; --- settings ---------------------------------------------------------------

(defn settings-open! [state-atom] (swap! state-atom state/open-settings))
(defn settings-close! [state-atom] (swap! state-atom state/close-settings))

(defn set-setting!
  "Update an app setting in memory and persist it to the cache DB."
  [state-atom {:keys [conn path]} k v]
  (swap! state-atom state/set-setting k v)
  (cache/set-app-setting! conn k v)
  (cache/snapshot! conn path))

(defn activity-open! [state-atom] (swap! state-atom state/open-log))
(defn activity-close! [state-atom] (swap! state-atom state/close-log))

;; --- libraries --------------------------------------------------------------

(defn library-new! [state-atom device-type]
  (swap! state-atom state/set-editor
         {:id nil :name "" :roots [] :device/type device-type}))

(defn library-edit! [state-atom id]
  (when-let [l (state/library-by-id @state-atom id)]
    (swap! state-atom state/set-editor
           (assoc l :device/type (device/device-type (first (:roots l)))))))

(defn library-delete! [state-atom {:keys [conn path]} id]
  (cache/delete-library! conn id)
  (cache/snapshot! conn path)
  (swap! state-atom (fn [s] (-> s
                                (state/delete-library id)
                                (state/forget-refresh id))))
  (refresh-libraries! state-atom conn)
  (future (availability/probe! state-atom)))

(defn library-default!
  "Mark or clear a library as the default source or sink (applied at next launch,
  see start!). The current session's selection is left as-is."
  [state-atom {:keys [conn path]} role id]
  (cache/set-default! conn role id)
  (cache/snapshot! conn path)
  (refresh-libraries! state-atom conn))

;; --- library editor ---------------------------------------------------------

(defn editor-name! [state-atom name] (swap! state-atom state/editor-name name))
(defn editor-remove-root! [state-atom uri] (swap! state-atom state/editor-remove-root uri))
(defn editor-cancel! [state-atom] (swap! state-atom state/cancel-editor))

(defn editor-save!
  "Persist the edited library, then start a walk of it right away rather than
  behind whatever else is queued: a new or re-rooted library needs one before its
  cache means anything, and an edited one may point somewhere else entirely."
  [state-atom {:keys [conn path]} refresher]
  (let [library (select-keys (:editor @state-atom) [:id :name :roots])]
    (if (lib/library-valid? library)
      (let [lib-id (cache/upsert-library! conn library)]
        (cache/snapshot! conn path)
        (refresh-libraries! state-atom conn)
        (swap! state-atom state/cancel-editor)
        ;; Probe *first*: refresh! skips a library the last probe called
        ;; unreachable, and this edit may be the one that fixed its roots.
        (future (availability/probe! state-atom)
                (refresh/refresh! refresher [lib-id]))
        true)
      (do (t/log! "Library needs a name and at least one file://, mtp:// or smb:// root.")
          false))))

;; --- folder browser ---------------------------------------------------------
;; Each device type owns how its browser opens (see dapr.device.*.events):
;; file:// navigates folders directly, mtp:// first picks a connected device,
;; smb:// first enters a share URL + credentials. Once a cwd is established the
;; navigation actions below are device-generic.

(defn editor-browse! [state-atom]
  (device-events/open-browser! (get-in @state-atom [:editor :device/type]) state-atom))

(defn log-dir-browse!
  "Open the local folder browser to choose the log directory. `:purpose` marks it
  so browser-select! knows the pick is a log directory rather than a library root —
  a native directory chooser is not available to a page, and the browser Dapr
  already has does the same job over every backend it supports."
  [state-atom]
  (device-events/open-browser! :file state-atom)
  (swap! state-atom assoc-in [:browser :purpose] :log-dir))

(defn browser-field! [state-atom field value]
  (swap! state-atom state/browser-field field value))

(defn browser-connect! [state-atom]
  (when (device-events/connect! (get-in @state-atom [:browser :device/type]) state-atom)
    (device-events/load-browser-entries! state-atom)))

(defn browser-device! [state-atom device]
  (when (device-events/choose-device! (get-in @state-atom [:browser :device/type])
                                      state-atom device)
    (device-events/load-browser-entries! state-atom)))

(defn browser-enter! [state-atom child]
  (swap! state-atom state/browser-enter child)
  (device-events/load-browser-entries! state-atom))

(defn browser-crumb! [state-atom idx]
  (swap! state-atom state/browser-to-crumb idx)
  (device-events/load-browser-entries! state-atom))

(defn browser-places! [state-atom]
  (swap! state-atom state/browser-to-places)
  (device-events/load-browser-entries! state-atom))

(defn browser-cancel! [state-atom]
  (swap! state-atom state/browser-close))

(defn- set-log-dir!
  "Persist the chosen log directory and repoint the file log there (a fresh
  dapr.N.log)."
  [state-atom {:keys [conn path]} dir]
  (swap! state-atom state/set-setting :log-dir dir)
  (cache/set-app-setting! conn :log-dir dir)
  (cache/snapshot! conn path)
  (log/set-dir! state-atom dir))

(defn browser-select!
  "Accept the browser's current folder: as the log directory when the browse was
  opened for one, otherwise as a root of the library being edited."
  [state-atom cache]
  (let [{:keys [browser editor]} @state-atom
        uri (:cwd browser)]
    (cond
      (nil? uri) nil

      (= :log-dir (:purpose browser))
      (let [dir (.getPath (io/file (java.net.URI. uri)))]
        (set-log-dir! state-atom cache dir)
        (swap! state-atom state/browser-close))

      (lib/root-addable? (:roots editor) uri)
      (swap! state-atom (fn [s] (-> s
                                    (state/editor-add-root uri)
                                    (state/browser-close))))

      :else (t/log! mixed-device-msg))))
