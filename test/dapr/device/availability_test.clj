(ns dapr.device.availability-test
  (:require [clojure.test :refer [deftest is testing]]
            [dapr.device.availability :as availability]
            [dapr.state :as state]))

(defn- wait-for
  [pred]
  (let [deadline (+ (System/currentTimeMillis) 5000)]
    (loop []
      (cond
        (pred) true
        (< (System/currentTimeMillis) deadline) (do (Thread/sleep 5) (recur))
        :else false))))

(deftest probe-all-libraries-test
  (let [libraries  [{:id 1 :name "Here" :roots ["file:///here/"]}
                    {:id 2 :name "Gone" :roots ["mtp://1:2:a/SD/"]}]
        state-atom (atom (state/set-libraries state/initial-state libraries))]
    (with-redefs [availability/library-available? #(= 1 (:id %))]
      (is (= {1 true 2 false} (availability/probe! state-atom)))
      (is (= {1 true 2 false} (:library-availability @state-atom))))))

(deftest failed-device-probe-greys-and-clears-selection-test
  (let [library    {:id 2 :name "Share" :roots ["smb://nas/Music/"]}
        state-atom (atom (-> state/initial-state
                             (state/set-libraries [library])
                             (assoc :source-id 2)
                             (state/set-library-available 2 true)))]
    (with-redefs [availability/library-available? (constantly false)]
      (is (false? (availability/probe-library! state-atom library)))
      (is (false? (get-in @state-atom [:library-availability 2])))
      (is (nil? (:source-id @state-atom))))))

(deftest monitor-reenables-reconnected-libraries-test
  (let [mtp         {:id 2 :name "Player" :roots ["mtp://1:2:a/SD/"]}
        local       {:id 1 :name "Local" :roots ["file:///music/"]}
        smb         {:id 3 :name "Share" :roots ["smb://nas/Music/"]}
        state-atom  (atom (-> state/initial-state
                              (state/set-libraries [local mtp smb])
                              (state/set-library-availability {1 true 2 false 3 false})))
        probes      (atom 0)
        probed-ids  (atom [])
        reachable   (atom {1 true 2 false 3 false})]
    (with-redefs-fn {#'availability/library-available-background?
                     (fn [library]
                       (swap! probed-ids conj (:id library))
                       (swap! probes inc)
                       (get @reachable (:id library)))}
      (fn []
        (let [monitor (availability/start! {:state-atom state-atom :interval-millis 5})]
          (try
            (testing "unreachable devices stay grey until a probe sees them"
              (is (wait-for #(<= 3 @probes)))
              (is (false? (get-in @state-atom [:library-availability 2])))
              (is (false? (get-in @state-atom [:library-availability 3]))))
            (testing "later successful probes make every backend selectable again"
              (reset! reachable {1 true 2 true 3 true})
              (is (wait-for #(every? true? (vals (:library-availability @state-atom)))))
              (is (true? (get-in @state-atom [:library-availability 1]))
                  "local availability is preserved")
              (is (= #{1 2 3} (set @probed-ids))
                  "file, MTP, and SMB libraries are all monitored"))
            (finally (availability/stop! monitor))))))))
