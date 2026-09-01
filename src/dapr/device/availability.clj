(ns dapr.device.availability
  "Reachability probes and MTP hot-plug monitoring.

  A probe is real device I/O, so it is always coordinated and always runs off
  request threads. MTP's access wrapper gives each probe a short-lived bridge
  session; no native connection remains open between monitor turns."
  (:require [dapr.device.coordinator :as coord]
            [dapr.device.format :as device]
            [dapr.device.fs :as dfs]
            [dapr.state :as state]
            [taoensso.telemere :as t]))

(def ^:private default-interval-millis 5000)
(def ^:private stop-timeout-millis 5000)

(defn- reachable?
  [library with-device!]
  (boolean
   (and (seq (:roots library))
        (with-device! (coord/library-device library)
          #(every? dfs/available? (:roots library))))))

(defn library-available?
  "Foreground reachability probe for `library`. It may ask a background scan to
  yield, which is appropriate for launch, Refresh, and an explicit library edit."
  [library]
  (reachable? library coord/with-device!))

(defn- library-available-background?
  "Low-priority form used by the hot-plug monitor. It waits behind a scan/sync
  instead of preempting useful work just to refresh a picker option."
  [library]
  (reachable? library coord/with-device-background!))

(defn probe!
  "Probe all configured libraries and replace the availability projection in
  `state-atom`. Returns the id->boolean result."
  [state-atom]
  (let [libraries (:libraries @state-atom)
        result    (into {} (map (juxt :id library-available?)) libraries)]
    (swap! state-atom state/set-library-availability result)
    result))

(defn- record-result!
  "Record one monitor result only if the library still exists, then clear a
  source/sink selection that has just become unavailable."
  [state-atom lib-id available?]
  (swap! state-atom
         (fn [s]
           (if (state/library-by-id s lib-id)
             (let [s' (state/set-library-available s lib-id available?)]
               (if available?
                 s'
                 (state/clear-unavailable-selection s' (:library-availability s'))))
             s))))

(defn probe-mtp-library!
  "Re-probe one MTP library after an operation failed. Returns its reachability
  and immediately projects an unplug into the source/sink pickers."
  [state-atom library]
  (let [available? (library-available? library)]
    (record-result! state-atom (:id library) available?)
    available?))

(defn- mtp-libraries
  [state]
  (filter #(= :mtp (device/device-type (first (:roots %)))) (:libraries state)))

(defn- wait-turn!
  [{:keys [running? signal interval-millis]}]
  (locking signal
    (when @running?
      (.wait signal (long interval-millis)))))

(defn- run-loop!
  [{:keys [state-atom running?] :as monitor}]
  (while @running?
    (wait-turn! monitor)
    (when @running?
      (doseq [library (mtp-libraries @state-atom)
              :while @running?]
        (try
          (record-result! state-atom (:id library)
                          (library-available-background? library))
          (catch Throwable error
            ;; A monitor failure is equivalent to unreachable for picker safety,
            ;; but stays a warning: active scans/syncs report their own errors.
            (record-result! state-atom (:id library) false)
            (t/log! {:level :warn :error error
                     :msg   (format "MTP availability probe for '%s' failed: "
                                    (:name library))})))))))

(defn start!
  "Start a daemon that re-probes configured MTP libraries. The initial all-device
  probe remains in dapr.ui.actions/start! so persisted defaults are resolved before
  they are painted; this monitor owns subsequent unplug/replug detection."
  [{:keys [state-atom interval-millis]}]
  (let [monitor {:state-atom      state-atom
                 :interval-millis (max 1 (or interval-millis default-interval-millis))
                 :running?        (atom true)
                 :signal          (Object.)}
        thread  (doto (Thread. ^Runnable #(run-loop! monitor) "dapr-mtp-availability")
                  (.setDaemon true)
                  (.start))]
    (assoc monitor :thread thread)))

(defn stop!
  "Stop the monitor without interrupting an in-flight native probe. Waiting is
  bounded so a disconnected device cannot hang application shutdown."
  [{:keys [running? signal ^Thread thread]}]
  (when running?
    (reset! running? false)
    (locking signal (.notifyAll signal))
    (when thread (.join thread stop-timeout-millis))))
