(ns dapr.sync-test
  "Unit coverage for the cache side of executing a sync — how an executed plan is
  reflected back into the cache without re-walking either library. The transfers
  themselves are covered by the integration suite; scanning belongs to
  dapr.refresh and dapr.fs.nio."
  (:require [clojure.test :refer [deftest is testing]]
            [dapr.db.cache :as cache]
            [dapr.sync :as sync]
            [datascript.core :as d]))

(defn- by-file
  "Re-index a catalog (keyed by the domain track key) by physical file [rel size],
  so a test can look a track up by its stable file identity."
  [cat]
  (into {} (map (fn [[_ t]] [[(:rel t) (:size t)] t])) cat))

(deftest apply-plan-to-cache-test
  (let [conn   (cache/empty-conn)
        src    (cache/upsert-library! conn {:name "S" :roots ["file:///s/"]})
        snk    (cache/upsert-library! conn {:name "K" :roots ["file:///k/"]})
        ;; Source holds two tracks; sink starts with one of them already present.
        src-cat {["New.mp3" 10]  {:key ["New.mp3" 10] :rel "New.mp3" :size 10
                                  :artist "Art" :album "Alb" :title "New" :root "file:///s/"}
                 ["Keep.mp3" 20] {:key ["Keep.mp3" 20] :rel "Keep.mp3" :size 20
                                  :artist "K" :title "Keep" :root "file:///s/"}}]
    (cache/replace-library-tracks! conn src [{:rel "New.mp3" :size 10 :root "file:///s/" :artist "Art"}
                                             {:rel "Keep.mp3" :size 20 :root "file:///s/" :artist "K"}])
    (cache/replace-library-tracks! conn snk [{:rel "Gone.mp3" :size 30 :root "file:///k/"}
                                             {:rel "Keep.mp3" :size 20 :root "file:///k/"}])
    (let [actions [{:op :add :key ["New.mp3" 10] :size 10
                    :src {:root "file:///s/" :rel "New.mp3"}
                    :target {:root "file:///k/" :rel "New.mp3"}}
                   {:op :delete :key ["Gone.mp3" 30] :size 30
                    :at {:root "file:///k/" :rel "Gone.mp3"}}
                   {:op :skip :key ["Keep.mp3" 20]}]]
      (sync/apply-plan-to-cache! conn snk src-cat actions)
      (testing "the sink cache reflects the add and delete, leaving skips alone"
        (let [cat (by-file (cache/library-catalog (d/db conn) snk))]
          (is (= #{["New.mp3" 10] ["Keep.mp3" 20]} (set (keys cat))))
          (testing "the added presence lives under the sink root and carries source tags"
            (is (= "file:///k/" (:root (get cat ["New.mp3" 10]))))
            (is (= "Art" (:artist (get cat ["New.mp3" 10])))))))
      (testing "the added track is now recorded on both libraries"
        (is (= #{src snk} (set (cache/track-libraries (d/db conn) "New.mp3" 10))))))))

(deftest apply-source-adds-to-cache-test
  (let [conn (cache/empty-conn)
        src  (cache/upsert-library! conn {:name "S" :roots ["file:///s/"]})
        snk  (cache/upsert-library! conn {:name "K" :roots ["file:///k/"]})
        ;; A sink-only track copied back into the source under :add-to-source.
        sink-cat {["Back.mp3" 50] {:key ["Back.mp3" 50] :rel "Back.mp3" :size 50
                                   :artist "Art" :album "Alb" :title "Back"
                                   :root "file:///k/"}}]
    (cache/replace-library-tracks! conn snk [{:rel "Back.mp3" :size 50 :root "file:///k/"
                                              :artist "Art" :album "Alb" :title "Back"}])
    (let [actions [{:op :add-to-source :key ["Back.mp3" 50] :size 50
                    :src {:root "file:///k/" :rel "Back.mp3"}
                    :target {:root "file:///s/" :rel "Back.mp3"}}
                   {:op :skip :key ["Other.mp3" 10]}]]
      (sync/apply-source-adds-to-cache! conn src sink-cat actions)
      (testing "the source cache gains a presence under its root carrying sink tags"
        (let [cat (by-file (cache/library-catalog (d/db conn) src))]
          (is (= #{["Back.mp3" 50]} (set (keys cat))))
          (is (= "file:///s/" (:root (get cat ["Back.mp3" 50]))))
          (is (= "Art" (:artist (get cat ["Back.mp3" 50]))))))
      (testing "the copied-back track is now recorded on both libraries"
        (is (= #{src snk} (set (cache/track-libraries (d/db conn) "Back.mp3" 50))))))))
