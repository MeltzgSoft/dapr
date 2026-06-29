(ns dapr.sync-integration-test
  (:require [clojure.test :refer [deftest is testing]]
            [dapr.sync :as sync]
            [dapr.test-fs :as tfs]))

(deftest selective-sync-end-to-end-test
  (testing "add and delete make the sink hold exactly the selected tracks"
    (let [src-dir (tfs/temp-dir!)
          snk-dir (tfs/temp-dir!)]
      (try
        ;; source library
        (tfs/write! (.resolve src-dir "a.mp3") "aaa")          ; already on sink -> skip
        (tfs/write! (.resolve src-dir "Album/b.mp3") "bbbb")   ; not on sink -> add
        (tfs/write! (.resolve src-dir "c.mp3") "cc")           ; not on sink -> add
        ;; sink library (a matches; d and old/b are extraneous)
        (tfs/write! (.resolve snk-dir "a.mp3") "aaa")
        (tfs/write! (.resolve snk-dir "old/b.mp3") "bbbb")
        (tfs/write! (.resolve snk-dir "d.mp3") "dd")
        (let [src-lib  {:id "s" :name "S" :roots [(tfs/uri-of src-dir)]}
              snk-lib  {:id "k" :name "K" :roots [(tfs/uri-of snk-dir)]}
              ;; identity is [rel size]: select a, Album/b, c
              selected #{["a.mp3" 3] ["Album/b.mp3" 4] ["c.mp3" 2]}
              ;; :delete handling so the sink-only old/b.mp3 + d.mp3 are removed
              ;; (the default :keep would retain them — see sink-only-add-to-source-test).
              actions  (sync/build-plan! src-lib snk-lib selected {:sink-only-handling :delete})
              progress (atom [])
              result   (sync/execute-plan!
                        actions {:on-progress (fn [p] (swap! progress conj (:done p)))})]
          (testing "op counts"
            (is (= {:add 2 :add-to-source 0 :delete 2} result)))
          (testing "sink content matches the selection"
            (is (= "aaa" (tfs/slurp-path (.resolve snk-dir "a.mp3"))))
            (is (= "bbbb" (tfs/slurp-path (.resolve snk-dir "Album/b.mp3"))))
            (is (= "cc" (tfs/slurp-path (.resolve snk-dir "c.mp3"))))
            (is (not (tfs/exists? (.resolve snk-dir "old/b.mp3"))))
            (is (not (tfs/exists? (.resolve snk-dir "d.mp3")))))
          (testing "progress reported once per performed action"
            (is (= [1 2 3 4] @progress)))
          (testing "re-planning the same selection is a no-op"
            (let [again (sync/build-plan! src-lib snk-lib selected)]
              (is (every? #(= :skip (:op %)) again)))))
        (finally
          (tfs/delete-tree! src-dir)
          (tfs/delete-tree! snk-dir))))))

(deftest sink-only-add-to-source-test
  (testing ":add-to-source copies a sink-only track back into the source library"
    (let [src-dir (tfs/temp-dir!)
          snk-dir (tfs/temp-dir!)]
      (try
        ;; a is on both; sink also holds extra/d.mp3 which the source lacks.
        (tfs/write! (.resolve src-dir "a.mp3") "aaa")
        (tfs/write! (.resolve snk-dir "a.mp3") "aaa")
        (tfs/write! (.resolve snk-dir "extra/d.mp3") "dddd")
        (let [src-lib    {:id "s" :name "S" :roots [(tfs/uri-of src-dir)]}
              snk-lib    {:id "k" :name "K" :roots [(tfs/uri-of snk-dir)]}
              src-roots  (sync/library-roots! src-lib)
              actions    (sync/build-plan! src-lib snk-lib #{["a.mp3" 3]}
                                           {:sink-only-handling :add-to-source
                                            :source-roots       src-roots})
              result     (sync/execute-plan! actions)]
          (testing "op counts: one copy back to the source, nothing deleted"
            (is (= {:add 0 :add-to-source 1 :delete 0} result)))
          (testing "the sink-only file now exists under the source root, at its rel"
            (is (= "dddd" (tfs/slurp-path (.resolve src-dir "extra/d.mp3")))))
          (testing "it remains on the sink (kept, not moved)"
            (is (tfs/exists? (.resolve snk-dir "extra/d.mp3")))))
        (finally
          (tfs/delete-tree! src-dir)
          (tfs/delete-tree! snk-dir))))))

(deftest cross-root-match-test
  (testing "a track matches by [rel size] across roots/devices (no re-transfer)"
    (let [src-root (tfs/temp-dir!)        ; source ROOT1
          snk-int  (tfs/temp-dir!)        ; sink INTERNAL
          snk-sd   (tfs/temp-dir!)]       ; sink SD
      (try
        ;; same relative path under source ROOT1 and sink SD
        (tfs/write! (.resolve src-root "foo/bar/file.mp3") "data")
        (tfs/write! (.resolve snk-sd "foo/bar/file.mp3") "data")
        (let [src-lib  {:id "s" :name "S" :roots [(tfs/uri-of src-root)]}
              snk-lib  {:id "k" :name "K" :roots [(tfs/uri-of snk-int) (tfs/uri-of snk-sd)]}
              actions  (sync/build-plan! src-lib snk-lib #{["foo/bar/file.mp3" 4]})
              result   (sync/execute-plan! actions)]
          (testing "the track is considered present -> skip, nothing transferred"
            (is (every? #(= :skip (:op %)) actions))
            (is (= {:add 0 :add-to-source 0 :delete 0} result))
            (is (not (tfs/exists? (.resolve snk-int "foo/bar/file.mp3"))))
            (is (tfs/exists? (.resolve snk-sd "foo/bar/file.mp3")))))
        (finally
          (tfs/delete-tree! src-root)
          (tfs/delete-tree! snk-int)
          (tfs/delete-tree! snk-sd))))))

(deftest catalog-of!-test
  (testing "scans a library's roots into a catalog keyed by [rel size]"
    (let [d (tfs/temp-dir!)]
      (try
        (tfs/write! (.resolve d "sub/x.mp3") "xyz")
        (let [cat (sync/catalog-of! {:roots [(tfs/uri-of d)]})]
          (is (= #{["sub/x.mp3" 3]} (set (keys cat))))
          (is (= "sub/x.mp3" (:rel (cat ["sub/x.mp3" 3])))))
        (finally
          (tfs/delete-tree! d))))))
