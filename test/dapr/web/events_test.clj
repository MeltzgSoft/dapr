(ns dapr.web.events-test
  "The push hub, exercised through subscribers rather than sockets: a subscriber
  is just a function of a string, so everything except the HTTP plumbing is
  testable in process."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dapr.state :as state]
            [dapr.ui.digest :as digest]
            [dapr.web.events :as events]))

(defn- wait-for
  "Poll `f` until it returns truthy, or give up after `ms`. The publisher settles
  a burst before sending, so an assertion has to wait for it rather than assume."
  ([f] (wait-for f 5000))
  ([f ms]
   (let [deadline (+ (System/currentTimeMillis) ms)]
     (loop []
       (or (f)
           (when (< (System/currentTimeMillis) deadline)
             (Thread/sleep 25)
             (recur)))))))

(defn- with-hub
  "Run `f` with a started hub over a fresh state atom, and an atom collecting
  everything published to one subscriber."
  [f]
  (let [state-atom (atom state/initial-state)
        hub        (events/start! state-atom)
        received   (atom [])]
    (events/subscribe! hub (fn [text] (swap! received conj text) true))
    (try
      (f state-atom received)
      (finally (events/stop! hub)))))

(defn- events-for [received region]
  (filter #(str/includes? % (str "region-" (name region))) @received))

(deftest timings-test
  (testing "the publisher's timings come from the caller (the system passes
            config.edn's), with defaults for anything unset"
    (let [state-atom (atom state/initial-state)
          hub        (events/start! state-atom {:coalesce-millis 5})]
      (try
        (is (= 5 (:coalesce-millis hub)))
        (is (= (:heartbeat-millis events/default-timings) (:heartbeat-millis hub)))
        (finally (events/stop! hub)))))
  (testing "no timings at all is all defaults"
    (let [hub (events/start! (atom state/initial-state))]
      (try
        (is (= (select-keys events/default-timings [:coalesce-millis :heartbeat-millis])
               (select-keys hub [:coalesce-millis :heartbeat-millis])))
        (finally (events/stop! hub))))))

(deftest changed-regions-test
  (testing "only the regions whose digest moved"
    (is (= #{:log} (events/changed-regions {:log "1" :status "a"} {:log "2" :status "a"}))))
  (testing "a region with no previous digest counts as changed"
    (is (= #{:log} (events/changed-regions {} {:log "1"}))))
  (testing "nothing moved, nothing to say"
    (is (= #{} (events/changed-regions {:log "1"} {:log "1"})))))

(deftest region-digests-test
  (testing "covers every pollable region, so none can be silently unwatched"
    (is (= (digest/regions) (set (keys (events/region-digests state/initial-state))))))
  (testing "taken without view parameters: what moved is the data, not one
            client's sort and page"
    (is (= (events/region-digests state/initial-state)
           (events/region-digests state/initial-state)))))

(deftest sse-message-test
  (testing "names the region and carries no markup — the content is fetched"
    (is (= "event: region-table\ndata: \n\n" (events/sse-message :table)))))

(deftest publishes-on-change-test
  (with-hub
    (fn [state-atom received]
      (swap! state-atom state/append-log "a line")
      (is (wait-for #(seq (events-for received :log)))
          "a new log line notifies the log region")
      (testing "and not regions that did not move"
        (is (empty? (events-for received :capacity)))))))

(deftest coalesces-a-burst-test
  (with-hub
    (fn [state-atom received]
      ;; A scan writes progress every 64 entries; a notification per write would
      ;; be noisier than the polling this replaced.
      (dotimes [i 200] (swap! state-atom state/append-log (str "line " i)))
      (is (wait-for #(seq (events-for received :log))))
      (Thread/sleep 400)
      (let [n (count (events-for received :log))]
        (is (<= n 3) (str "expected a burst to collapse, got " n " notifications"))))))

(deftest drops-dead-subscribers-test
  (let [state-atom (atom state/initial-state)
        hub        (events/start! state-atom)
        alive      (atom [])
        dead-calls (atom 0)]
    (try
      ;; A subscriber reports itself gone by returning false — which is how a
      ;; browser that vanished without closing cleanly is noticed.
      (events/subscribe! hub (fn [_] (swap! dead-calls inc) false))
      (events/subscribe! hub (fn [text] (swap! alive conj text) true))
      (swap! state-atom state/append-log "one")
      (is (wait-for #(seq @alive)))
      (let [after-first @dead-calls]
        (swap! state-atom state/append-log "two")
        (is (wait-for #(< 1 (count @alive))))
        (is (= after-first @dead-calls) "the dead subscriber is not written to again"))
      (finally (events/stop! hub)))))

(deftest stop-detaches-the-watch-test
  (let [state-atom (atom state/initial-state)
        hub        (events/start! state-atom)
        received   (atom [])]
    (events/subscribe! hub (fn [text] (swap! received conj text) true))
    (events/stop! hub)
    (swap! state-atom state/append-log "after stop")
    (Thread/sleep 300)
    (is (empty? @received) "a stopped hub publishes nothing and leaves no watcher")))
