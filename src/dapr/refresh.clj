(ns dapr.refresh
  "Background library refresh: the single owner of device scanning.

  A small pool of daemon workers walks libraries into the cache, so the UI never
  has to. The table always paints from the cache (dapr.library.catalogs) and the
  only device work a user action does is the sync copy/delete itself. That matters
  because a scan over MTP/SMB is slow — every directory listing is a blocking
  native round-trip — and device access is serial, so an in-flight scan used to
  block a user's sync of the same device until it finished.

  The pool is bounded by **device leases**, not by threads: a coordinated device is
  walked by one worker at a time (see claim!), so a user with a DAP and a NAS gets
  both scanned at once instead of back to back, while two libraries on one device
  still take turns. Local `file://` libraries need no lease and run as wide as the
  pool. A worker never *waits* on a leased device — it rotates past it — because a
  background waiter deliberately does not preempt a background holder
  (dapr.device.coordinator), so waiting would trade a running walk for a parked
  thread.

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
    refresh asks the user to confirm (see dapr.ui.actions)."
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

(def ^:private lease-retry-millis
  "How long a worker waits when every queued library is on a device another worker
  is already walking. Short, because the event it is waiting for — a walk reaching
  a directory boundary and finishing — is not something the queue can signal."
  25)

(def ^:private default-workers
  "Pool size when the config names none. Two, because the refresher only ever walks
  what the user has chosen: a source and a sink (plus a library just saved in the
  editor). Those two are what parallelism buys — the DAP-and-NAS sync — and a
  bigger pool would only add threads to sit in claim!'s rotation. Libraries sharing
  a device are serialized by the lease no matter how wide the pool is."
  2)

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
  [state-atom lib-id label prog]
  (let [publish! (fn [p] (swap! state-atom state/set-refresh-progress lib-id
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
    ;; Background acquire: waiting here must not cost another walk its frontier.
    ;; Under the device leases (see claim!) this only ever contends with a
    ;; foreground op, which holds the lock briefly.
    (coord/with-device-background! dev
      (fn []
        (swap! state-atom (fn [s] (-> s
                                      (state/set-refresh-status lib-id :scanning)
                                      (state/set-refresh-progress lib-id (select-keys @prog [:done :total])))))
        (nio/scan-roots!
         (:roots library)
         {:known      (fn [rel size] (get by-file [rel size]))
          :checkpoint (:checkpoint saved)
          :on-scan    (scan-callback state-atom lib-id (:name library) prog)
          :on-batch   (fn [tracks] (cache/upsert-library-tracks! conn lib-id tracks known-cat))
          ;; Yield the device to any foreground op — and stop promptly on halt.
          :pause?     (fn [] (or (not @running?) (coord/queued? dev)))})))))

(defn- finish-scan!
  "Apply a completed walk: retract the presences it did not find, persist the
  cache, and mark the library :complete (so a sync against it needs no
  confirmation). Its counters go with the checkpoint — a complete library has no
  row in the status bar, and a later re-queue starts its own count."
  [{:keys [state-atom cache checkpoints]} lib-id library {:keys [seen]}]
  (let [{:keys [conn path]} cache]
    (cache/reconcile-library-tracks! conn lib-id seen)
    (cache/snapshot! conn path)
    (swap! checkpoints dissoc lib-id)
    (swap! state-atom (fn [s] (-> s
                                  (state/set-refresh-status lib-id :complete)
                                  (state/set-refresh-progress lib-id nil))))
    (t/log! (format "Refreshed '%s' — %d tracks." (:name library) (count seen)))))

(defn- pause-scan!
  "Save a paused walk's checkpoint (and its progress counters) so the next turn
  resumes from the frontier rather than re-listing the tree. The published
  counters stay put, so the library's status-bar row keeps showing how far it got
  while it waits for another turn."
  [{:keys [state-atom checkpoints]} lib-id library {:keys [checkpoint]} prog]
  (swap! checkpoints assoc lib-id {:checkpoint checkpoint :progress @prog})
  (swap! state-atom state/set-refresh-status lib-id :paused)
  (t/log! :debug (format "  [%s] paused — device wanted elsewhere." (:name library))))

;; --- queue -------------------------------------------------------------------

(defn- queue-last!
  [{:keys [^LinkedBlockingDeque queue]} lib-id]
  (when-not (.contains queue lib-id) (.addLast queue lib-id)))

(defn- queue-first!
  [{:keys [^LinkedBlockingDeque queue]} lib-id]
  (.remove queue lib-id)
  (.addFirst queue lib-id))

;; --- device leases -----------------------------------------------------------

(defn- lease-key
  "The device key a walk of `library` must hold exclusively, or nil when its device
  needs no arbitration. `:file` answers false to arbitrate-access? and every local
  library shares the one \"file\" key, so leasing it would serialize unrelated local
  libraries — exactly what dapr.device.file.format opts out of."
  [library]
  (let [dev (coord/library-device library)]
    (when (coord/coordinated? dev) (:key dev))))

(defn- lease!
  "Try to reserve device key `k` for this worker. True when it was free (or there is
  nothing to reserve). Compare-and-set, so two workers claiming the same device at
  once cannot both win."
  [leases k]
  (or (nil? k)
      (let [[old new] (swap-vals! leases (fn [held] (if (contains? held k) held (conj held k))))]
        (not= old new))))

(defn- release! [leases k]
  (when k (swap! leases disj k)))

(defn- claim!
  "Take the first queued library this worker may walk, reserving its device.

  A library whose device another worker is already walking is rotated to the back
  rather than waited on: parking a pool thread on that lock would hold it for the
  length of a whole library walk, and the walk holding it would not yield — a
  background waiter deliberately does not trip `coord/queued?` (see
  dapr.device.coordinator). Rotating instead lets this worker get on with a
  library on some *other* device, which is the entire point of the pool.

  Returns {:lib-id :lease}, or `:leased` when everything queued is spoken for, or
  `:empty` when there is nothing queued. The rotation is bounded by the queue
  length at entry so a round always terminates."
  [{:keys [^LinkedBlockingDeque queue leases state-atom]}]
  (loop [budget (.size queue)]
    (if-let [lib-id (.pollFirst queue)]
      (let [k (lease-key (state/library-by-id @state-atom lib-id))]
        (if (lease! leases k)
          {:lib-id lib-id :lease k}
          (do (.addLast queue lib-id)
              (if (pos? (dec budget))
                (recur (dec budget))
                :leased))))
      :empty)))

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
  screen. Runs *outside* the device lock, and takes no device lock of its own:
  `:free :keep` reuses the free space already in state rather than querying the
  sink, so a worker finishing one library never blocks on — or preempts — another
  worker's walk of the sink's device. See catalogs/paint!."
  [{:keys [state-atom cache]} lib-id]
  (let [s @state-atom]
    (when (some #{lib-id} [(:source-id s) (:sink-id s)])
      (try
        (catalogs/paint! state-atom (:conn cache) {:preselect? false :free :keep})
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
              (swap! state-atom state/set-refresh-error lib-id summary)
              (t/log! {:level :error :error t
                       :msg   (format "Refresh of '%s' failed: %s" (:name library) summary)}))))
        (repaint! refresher lib-id)))))

(defn- run-loop!
  "One worker body: claim the next library its device is free for and refresh it,
  until halted."
  [{:keys [^LinkedBlockingDeque queue leases running?] :as refresher}]
  (while @running?
    (try
      (let [claim (claim! refresher)]
        (case claim
          ;; Nothing queued. Block on the queue (rather than spin) so a refresh
          ;; the user just asked for starts at once, then hand it back for the
          ;; next round, which is where the device lease is taken.
          :empty  (when-let [lib-id (.pollFirst queue poll-millis TimeUnit/MILLISECONDS)]
                    (.addFirst queue lib-id))
          ;; Everything queued is on a device another worker is walking. Wait a
          ;; beat: the wake-up that matters is a walk finishing, which no queue
          ;; operation signals.
          :leased (Thread/sleep lease-retry-millis)
          (try
            (when @running? (refresh-library! refresher (:lib-id claim)))
            (finally (release! leases (:lease claim))))))
      (catch InterruptedException _ (reset! running? false))
      (catch Throwable t
        (t/log! {:level :error :error t :msg "Background refresh worker error: "})))))

(defn start!
  "Start the refresher over `state-atom` and the `cache` component {:conn :path}.
  Returns the component; nothing is queued until refresh! is called.

  `workers` sizes the pool (default `default-workers`). Libraries on *different*
  devices then refresh at once — a DAP and a NAS no longer wait for each other —
  while a device is still walked by one worker at a time (see claim!)."
  [{:keys [state-atom cache workers]}]
  (let [refresher {:state-atom  state-atom
                   :cache       cache
                   :queue       (LinkedBlockingDeque.)
                   :checkpoints (atom {})
                   :leases      (atom #{})
                   :running?    (atom true)}
        threads   (mapv (fn [i]
                          (doto (Thread. ^Runnable #(run-loop! refresher)
                                         (str "dapr-refresh-" i))
                            (.setDaemon true)
                            (.start)))
                        (range (max 1 (or workers default-workers))))]
    (assoc refresher :threads threads)))

(defn stop!
  "Halt the refresher and wait briefly for every worker to leave its device. Each
  in-flight walk sees the cleared running flag at its next directory boundary and
  returns, so the devices are released before the session-closing :dapr/devices
  component runs. Not interrupted: interrupting a thread inside an NIO call closes
  the channel under the provider, which is worse than waiting.

  The timeout is a deadline shared by the pool, not one per worker: the workers
  wind down concurrently, so N stuck workers must not mean N times the wait before
  the app can exit."
  [{:keys [running? threads ^LinkedBlockingDeque queue]}]
  (when running? (reset! running? false))
  (when queue (.clear queue))
  (let [deadline (+ (System/currentTimeMillis) stop-timeout-millis)]
    (doseq [^Thread t threads]
      (.join t (max 1 (- deadline (System/currentTimeMillis)))))
    (when (some (fn [^Thread t] (.isAlive t)) threads)
      (t/log! :warn "Background refresh did not stop in time; leaving it to the JVM exit."))))
