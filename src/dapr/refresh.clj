(ns dapr.refresh
  "Background library refresh: the single owner of device scanning.

  One daemon worker walks libraries into the cache, so the UI never has to. The
  table always paints from the cache (dapr.library.catalogs) and the only device
  work a user action does is the sync copy/delete itself. That matters because a
  scan over MTP/SMB is slow — every directory listing is a blocking native
  round-trip — and device access is serial, so an in-flight scan used to block a
  user's sync of the same device until it finished.

  It walks only what the user has *chosen* — the source, the sink, or a library
  just saved (see refresh!). A DAP that is unplugged and a share that is offline
  are the normal case, not the exception, so scanning every configured library
  would spend its time failing to reach devices to fill catalogs nothing is about
  to read.

  Two mechanisms make the refresh yield rather than block:

  - **Preemption.** A walk holds its device's lock (dapr.device.coordinator) and
    polls `queued?` at directory boundaries. The moment a foreground operation asks
    for that device the walk *check-points* — saving the remaining directory
    frontier and the keys seen so far — releases the lock, and re-queues itself.
    Resuming continues from the frontier instead of re-listing every directory
    already visited, which is the expensive part.
  - **Incremental, upsert-only writes.** Each batch of scanned tracks is upserted
    into the cache as it is found, so the catalog freshens progressively. Presences
    for tracks that are *gone* are only retracted when a walk runs end to end (see
    dapr.db.cache/reconcile-library-tracks!): a partial walk knows nothing about
    absence, so an interrupted refresh leaves the cache a stale superset — safe to
    browse, and the reason a sync against a library that has not completed its
    refresh asks the user to confirm (see dapr.ui.events)."
  (:require [dapr.db.cache :as cache]
            [dapr.device.coordinator :as coord]
            [dapr.fs.nio :as nio]
            [dapr.library.catalogs :as catalogs]
            [dapr.log :as log]
            [dapr.state :as state]
            [datascript.core :as d]
            [taoensso.telemere :as t])
  (:import (java.util.concurrent LinkedBlockingDeque TimeUnit)))

(def ^:private poll-millis
  "How long the worker blocks waiting for the next library before re-checking the
  running flag — the upper bound on how long an idle refresher delays shutdown."
  250)

(def ^:private stop-timeout-millis
  "How long halt waits for the worker to finish its current directory. A walk that
  is stuck in a native listing is left to die with the (daemon) JVM rather than
  holding up shutdown."
  5000)

(def ^:private progress-stride
  "Publish refresh progress to the UI only every Nth visited entry (plus on every
  directory listing), so a large walk advances the indicator steadily without a
  state swap — and re-render — per file."
  64)

;; --- scan callbacks ----------------------------------------------------------

(defn- scan-callback
  "on-scan callback for one library's walk: folds scan events into the shared
  `prog` accumulator (published to the UI on a throttled cadence) and logs them.
  Each directory entered is logged at :info — the last such line before a freeze
  pinpoints the directory whose listing hung — while per-file lines are :debug,
  since the progress indicator already covers file granularity.

  :listing events grow the total by a directory's child count (so the total climbs
  as the walk descends) and :entry events advance done toward it; both survive a
  pause, because `prog` is check-pointed alongside the walk."
  [state-atom label prog]
  (let [publish! (fn [p] (swap! state-atom state/set-refresh-progress
                                (select-keys p [:done :total])))]
    (fn [{:keys [type rel track] children :count}]
      (case type
        :dir     (t/log! (format "  [%s] scanning %s/" label (or rel "")))
        :listing (publish! (swap! prog update :total + children))
        :entry   (let [p (swap! prog update :done inc)]
                   (when (zero? (mod (:done p) progress-stride)) (publish! p)))
        :file    (t/log! :debug (format "  [%s] %s" label (or (:rel track) (:name track))))
        nil))))

;; --- one library -------------------------------------------------------------

(defn- scan-library!
  "Walk `library` into the cache under the device lock, resuming from any saved
  checkpoint. Returns nio/scan-roots!'s result (see its docstring)."
  [{:keys [state-atom cache running?]} lib-id library saved prog]
  (let [{:keys [conn]} cache
        dev       (coord/library-device library)
        known-cat (cache/library-catalog (d/db conn) lib-id)
        ;; known-cat is keyed by the domain track key (which carries the tags), but
        ;; tag reuse is looked up by physical file [rel size] — before any tag has
        ;; been read — so index it by that.
        by-file   (into {} (map (juxt (juxt :rel :size) identity)) (vals known-cat))]
    (coord/with-device! dev
      (fn []
        (swap! state-atom (fn [s] (-> s
                                      (state/set-refresh-status lib-id :scanning)
                                      (state/set-refresh-active lib-id)
                                      (state/set-refresh-progress (select-keys @prog [:done :total])))))
        (nio/scan-roots!
         (:roots library)
         {:known      (fn [rel size] (get by-file [rel size]))
          :checkpoint (:checkpoint saved)
          :on-scan    (scan-callback state-atom (:name library) prog)
          :on-batch   (fn [tracks] (cache/upsert-library-tracks! conn lib-id tracks known-cat))
          ;; Yield the device to any foreground op — and stop promptly on halt.
          :pause?     (fn [] (or (not @running?) (coord/queued? dev)))})))))

(defn- finish-scan!
  "Apply a completed walk: retract the presences it did not find, persist the
  cache, and mark the library :complete (so a sync against it needs no
  confirmation)."
  [{:keys [state-atom cache checkpoints]} lib-id library {:keys [seen]}]
  (let [{:keys [conn path]} cache]
    (cache/reconcile-library-tracks! conn lib-id seen)
    (cache/snapshot! conn path)
    (swap! checkpoints dissoc lib-id)
    (swap! state-atom (fn [s] (-> s
                                  (state/set-refresh-status lib-id :complete)
                                  (state/set-refresh-active nil))))
    (t/log! (format "Refreshed '%s' — %d tracks." (:name library) (count seen)))))

(defn- pause-scan!
  "Save a paused walk's checkpoint (and its progress counters) so the next turn
  resumes from the frontier rather than re-listing the tree."
  [{:keys [state-atom checkpoints]} lib-id library {:keys [checkpoint]} prog]
  (swap! checkpoints assoc lib-id {:checkpoint checkpoint :progress @prog})
  (swap! state-atom (fn [s] (-> s
                                (state/set-refresh-status lib-id :paused)
                                (state/set-refresh-active nil))))
  (t/log! :debug (format "  [%s] paused — device wanted elsewhere." (:name library))))

;; --- queue -------------------------------------------------------------------

(defn- queue-last!
  [{:keys [^LinkedBlockingDeque queue]} lib-id]
  (when-not (.contains queue lib-id) (.addLast queue lib-id)))

(defn- queue-first!
  [{:keys [^LinkedBlockingDeque queue]} lib-id]
  (.remove queue lib-id)
  (.addFirst queue lib-id))

(defn refresh!
  "Queue `lib-ids` for a background refresh, in front of anything already waiting,
  and mark them :pending. This is the *only* way a library gets scanned: the app
  walks what the user has actually chosen — the source, the sink, or a library
  just saved in the editor — and nothing else. Queueing every configured library
  instead meant reaching for devices that were not attached, spending a blocking
  probe per root and leaving a failed row per absent player, to fill a cache
  nothing was about to read.

  Re-queues a library that already completed this session: the device may have
  changed since, and choosing a library is exactly when the user wants its list to
  be right.

  Skipped: a library being walked *right now* (the in-flight walk is already the
  fresh one, and re-queueing would only restart it), and one the last probe found
  unreachable (see state/library-unreachable?) — the pickers already refuse to
  select an unavailable library, but the editor can still save one."
  [{:keys [state-atom] :as refresher} lib-ids]
  (doseq [lib-id (reverse (remove nil? lib-ids))
          :let   [s @state-atom]
          :when  (and (not= :scanning (state/refresh-status s lib-id))
                      (not (state/library-unreachable? s lib-id)))]
    (swap! state-atom state/set-refresh-status lib-id :pending)
    (queue-first! refresher lib-id)))

;; --- worker ------------------------------------------------------------------

(defn- repaint!
  "Project the freshened cache into the UI when the refreshed library is the one on
  screen. Runs *outside* the device lock: it queries the sink's free space, which
  takes that device's lock, and taking a second lock while holding the first could
  deadlock against a sync's two-device acquire."
  [{:keys [state-atom cache]} lib-id]
  (let [s @state-atom]
    (when (some #{lib-id} [(:source-id s) (:sink-id s)])
      (try
        (catalogs/paint! state-atom (:conn cache) {:preselect? false})
        (catch Throwable t
          (t/log! {:level :warn :error t :msg "Could not repaint catalogs after a refresh: "}))))))

(defn- refresh-library!
  "One turn of work on library `lib-id`: scan it under its device lock until it
  finishes or yields, then apply the outcome and repaint. A paused library goes to
  the back of the queue, so every other library gets a turn before it resumes."
  [{:keys [state-atom checkpoints] :as refresher} lib-id]
  (let [library (state/library-by-id @state-atom lib-id)]
    (if-not library
      ;; Deleted (or never existed) while queued — drop it silently.
      (swap! state-atom state/forget-refresh lib-id)
      (let [saved (get @checkpoints lib-id)
            prog  (atom (or (:progress saved) {:done 0 :total 0}))]
        (try
          (let [result (scan-library! refresher lib-id library saved prog)]
            (if (= :paused (:status result))
              (do (pause-scan! refresher lib-id library result prog)
                  (queue-last! refresher lib-id))
              (finish-scan! refresher lib-id library result)))
          (catch Throwable t
            ;; Drop the checkpoint: whatever went wrong (device unplugged, share
            ;; dropped) may have invalidated the frontier, so the next attempt
            ;; starts this library clean rather than resuming into the wreckage.
            (swap! checkpoints dissoc lib-id)
            (let [summary (log/error-summary t)]
              (swap! state-atom (fn [s] (-> s
                                            (state/set-refresh-error lib-id summary)
                                            (state/set-refresh-active nil))))
              (t/log! {:level :error :error t
                       :msg   (format "Refresh of '%s' failed: %s" (:name library) summary)}))))
        (repaint! refresher lib-id)))))

(defn- run-loop!
  "The worker body: take the next library and refresh it, until halted."
  [{:keys [^LinkedBlockingDeque queue running?] :as refresher}]
  (while @running?
    (try
      (when-let [lib-id (.pollFirst queue poll-millis TimeUnit/MILLISECONDS)]
        (when @running? (refresh-library! refresher lib-id)))
      (catch InterruptedException _ (reset! running? false))
      (catch Throwable t
        (t/log! {:level :error :error t :msg "Background refresh worker error: "})))))

(defn start!
  "Start the refresher over `state-atom` and the `cache` component {:conn :path}.
  Returns the component; nothing is queued until refresh! is called."
  [{:keys [state-atom cache]}]
  (let [refresher {:state-atom  state-atom
                   :cache       cache
                   :queue       (LinkedBlockingDeque.)
                   :checkpoints (atom {})
                   :running?    (atom true)}
        thread    (doto (Thread. ^Runnable #(run-loop! refresher) "dapr-refresh")
                    (.setDaemon true)
                    (.start))]
    (assoc refresher :thread thread)))

(defn stop!
  "Halt the refresher and wait briefly for the worker to leave the device. The
  in-flight walk sees the cleared running flag at its next directory boundary and
  returns, so the device is released before the session-closing :dapr/devices
  component runs. Not interrupted: interrupting a thread inside an NIO call closes
  the channel under the provider, which is worse than waiting."
  [{:keys [running? ^Thread thread ^LinkedBlockingDeque queue]}]
  (when running? (reset! running? false))
  (when queue (.clear queue))
  (when thread
    (.join thread stop-timeout-millis)
    (when (.isAlive thread)
      (t/log! :warn "Background refresh did not stop in time; leaving it to the JVM exit."))))
