(ns dapr.domain.plan-test
  (:require [clojure.test :refer [deftest is testing]]
            [dapr.domain.library :as lib]
            [dapr.domain.plan :as plan]))

(defn- track [name size root rel]
  {:name name :size size :root root :rel rel})

(defn- tk
  "The domain track key for a tag-less test track: [artist album title size rel]
  with the tags nil (see lib/track-key)."
  [size rel]
  (lib/track-key {:size size :rel rel}))

(defn- by-key [actions]
  (into {} (map (juxt :key identity)) actions))

(deftest selection-plan-test
  (let [source (lib/catalog [(track "a.mp3" 10 "src" "a.mp3")
                             (track "b.mp3" 20 "src" "Albums/b.mp3")
                             (track "c.mp3" 30 "src" "c.mp3")])
        ;; sink holds a at the same rel (under a different root -> still a match),
        ;; plus an extraneous track.
        sink   (lib/catalog [(track "a.mp3" 10 "snk" "a.mp3")
                             (track "d.mp3" 40 "snk" "d.mp3")])
        actions (plan/selection-plan
                 {:source-catalog source
                  :sink-catalog   sink
                  :selected       #{(tk 10 "a.mp3") (tk 20 "Albums/b.mp3") (tk 30 "c.mp3")}
                  :sink-roots     [{:uri "snk" :free-bytes 1000}]})
        idx (by-key actions)]
    (testing "a track already on the sink at the same rel is skipped (root excluded)"
      (is (= :skip (:op (idx (tk 10 "a.mp3"))))))
    (testing "a selected track absent from the sink is added at its source rel"
      (let [m (idx (tk 20 "Albums/b.mp3"))]
        (is (= :add (:op m)))
        (is (= {:root "snk" :rel "Albums/b.mp3"} (:target m)))
        (is (= 20 (:size m))))
      (is (= :add (:op (idx (tk 30 "c.mp3"))))))
    (testing "a sink-only track is kept (default :sink-only-handling :keep)"
      (is (nil? (idx (tk 40 "d.mp3"))))
      (is (empty? (filter #(= :delete (:op %)) actions))))
    (testing "no move op is ever produced"
      (is (empty? (filter #(= :move (:op %)) actions))))
    (testing "output is deterministic"
      (is (= actions
             (plan/selection-plan
              {:source-catalog source :sink-catalog sink
               :selected #{(tk 10 "a.mp3") (tk 20 "Albums/b.mp3") (tk 30 "c.mp3")}
               :sink-roots [{:uri "snk" :free-bytes 1000}]}))))))

(deftest placement-test
  (let [source (lib/catalog [(track "x.mp3" 30 "src" "x.mp3")])]
    (testing "skips a root without room and uses the next that fits"
      (let [m (first (plan/selection-plan
                      {:source-catalog source :sink-catalog {}
                       :selected #{(tk 30 "x.mp3")}
                       :sink-roots [{:uri "r0" :free-bytes 5} {:uri "r1" :free-bytes 100}]}))]
        (is (= :add (:op m)))
        (is (= "r1" (get-in m [:target :root])))))
    (testing "an add that fits no single root is blocked"
      (let [m (first (plan/selection-plan
                      {:source-catalog source :sink-catalog {}
                       :selected #{(tk 30 "x.mp3")}
                       :sink-roots [{:uri "r0" :free-bytes 5}]}))]
        (is (= :blocked (:op m)))
        (is (= :no-room (:reason m)))))
    (testing "successive adds consume a root's remaining free space"
      (let [src2 (lib/catalog [(track "p.mp3" 30 "src" "p.mp3")
                               (track "q.mp3" 30 "src" "q.mp3")])
            ops  (->> (plan/selection-plan
                       {:source-catalog src2 :sink-catalog {}
                        :selected #{(tk 30 "p.mp3") (tk 30 "q.mp3")}
                        :sink-roots [{:uri "r0" :free-bytes 50}]})
                      (map :op)
                      (frequencies))]
        (is (= 1 (:add ops)))
        (is (= 1 (:blocked ops)))))))

(deftest summary-test
  (let [source (lib/catalog [(track "new.mp3" 100 "src" "new.mp3")
                             (track "keep.mp3" 5 "src" "A/keep.mp3")])
        sink   (lib/catalog [(track "keep.mp3" 5 "snk" "A/keep.mp3")
                             (track "del.mp3" 40 "snk" "del.mp3")])
        ;; del.mp3 is sink-only — :delete handling so it is removed (default :keep
        ;; would retain it and zero the delete counts).
        actions (plan/selection-plan
                 {:source-catalog source :sink-catalog sink
                  :selected #{(tk 100 "new.mp3") (tk 5 "A/keep.mp3")}
                  :sink-roots [{:uri "snk" :free-bytes 1000}]
                  :sink-only-handling :delete})]
    (testing "counts ops and bytes added/freed"
      (is (= {:add 1 :add-to-source 0 :delete 1 :skip 1 :blocked 0
              :bytes-added 100 :bytes-to-source 0 :bytes-freed 40}
             (plan/summary actions)))))
  (testing "empty plan summarizes to zeros"
    (is (= {:add 0 :add-to-source 0 :delete 0 :skip 0 :blocked 0
            :bytes-added 0 :bytes-to-source 0 :bytes-freed 0}
           (plan/summary [])))))

(deftest sink-only-handling-test
  (let [source  (lib/catalog [(track "a.mp3" 10 "src" "a.mp3")])
        ;; d.mp3 lives on the sink only.
        sink    (lib/catalog [(track "a.mp3" 10 "snk" "a.mp3")
                              (track "d.mp3" 40 "snk" "d.mp3")])
        plan-with (fn [handling source-roots]
                    (plan/selection-plan
                     {:source-catalog source :sink-catalog sink
                      :selected #{(tk 10 "a.mp3")}
                      :sink-roots [{:uri "snk" :free-bytes 1000}]
                      :sink-only-handling handling
                      :source-roots source-roots}))]
    (testing ":keep retains the sink-only track (no delete, no copy)"
      (let [acts (plan-with :keep nil)]
        (is (empty? (filter #(#{:delete :add-to-source} (:op %)) acts)))))
    (testing ":delete removes the unselected sink-only track"
      (let [acts (plan-with :delete nil)
            m    (by-key acts)]
        (is (= :delete (:op (m (tk 40 "d.mp3")))))))
    (testing ":add-to-source keeps it and copies it back into the source"
      (let [acts (plan-with :add-to-source [{:uri "src" :free-bytes 1000}])
            m    (by-key acts)]
        (is (empty? (filter #(= :delete (:op %)) acts)))
        (is (= :add-to-source (:op (m (tk 40 "d.mp3")))))
        (is (= {:root "src" :rel "d.mp3"} (:target (m (tk 40 "d.mp3")))))))
    (testing ":add-to-source blocks the copy when no source root has room"
      (let [acts (plan-with :add-to-source [{:uri "src" :free-bytes 5}])
            m    (by-key acts)]
        (is (= :blocked (:op (m (tk 40 "d.mp3")))))
        (is (= :no-room (:reason (m (tk 40 "d.mp3")))))))))
