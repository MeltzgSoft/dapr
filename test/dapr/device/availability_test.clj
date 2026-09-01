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

(deftest failed-mtp-probe-greys-and-clears-selection-test
  (let [library    {:id 2 :name "Player" :roots ["mtp://1:2:a/SD/"]}
        state-atom (atom (-> state/initial-state
                             (state/set-libraries [library])
                             (assoc :source-id 2)
                             (state/set-library-available 2 true)))]
    (with-redefs [availability/library-available? (constantly false)]
      (is (false? (availability/probe-mtp-library! state-atom library)))
      (is (false? (get-in @state-atom [:library-availability 2])))
      (is (nil? (:source-id @state-atom))))))

(deftest monitor-reenables-replugged-mtp-library-test
  (let [mtp         {:id 2 :name "Player" :roots ["mtp://1:2:a/SD/"]}
        local       {:id 1 :name "Local" :roots ["file:///music/"]}
        state-atom  (atom (-> state/initial-state
                              (state/set-libraries [local mtp])
                              (state/set-library-availability {1 true 2 false})))
        probes      (atom 0)
        probed-ids  (atom [])
        plugged?    (atom false)]
    (with-redefs-fn {#'availability/library-available-background?
                     (fn [library]
                       (swap! probed-ids conj (:id library))
                       (swap! probes inc)
                       @plugged?)}
      (fn []
        (let [monitor (availability/start! {:state-atom state-atom :interval-millis 5})]
          (try
            (testing "an absent player stays grey until a probe sees it"
              (is (wait-for #(pos? @probes)))
              (is (false? (get-in @state-atom [:library-availability 2]))))
            (testing "a later successful probe makes it selectable again"
              (reset! plugged? true)
              (is (wait-for #(true? (get-in @state-atom [:library-availability 2]))))
              (is (true? (get-in @state-atom [:library-availability 1]))
                  "non-MTP availability is preserved")
              (is (= #{2} (set @probed-ids))
                  "only MTP libraries are hot-plug polled"))
            (finally (availability/stop! monitor))))))))
