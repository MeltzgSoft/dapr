(ns dapr.sync-test
  "Unit coverage for the cache-reconciling scan glue. nio/catalog! is stubbed so
  these tests exercise the cache wiring without touching the filesystem; the real
  filesystem walk is covered by dapr.fs.nio-test and the integration suite."
  (:require [clojure.test :refer [deftest is testing]]
            [dapr.cache :as cache]
            [dapr.fs.nio :as nio]
            [dapr.sync :as sync]
            [datascript.core :as d]))

(def ^:private tracks
  [{:rel "A/B/One.mp3" :size 10 :mtime 5 :artist "X" :album "B" :title "One"
    :root "file:///r/" :key ["A/B/One.mp3" 10]}
   {:rel "Two.flac" :size 20 :mtime 6 :title "Two" :root "file:///r/" :key ["Two.flac" 20]}])

(deftest scan-into-cache-test
  (let [conn (cache/empty-conn)
        lib  (cache/upsert-library! conn {:name "L" :roots ["file:///r/"]})
        seen (atom [])]
    (with-redefs [nio/catalog! (fn [_roots _on-scan _ext known]
                                 (swap! seen conj known)
                                 tracks)]
      (testing "returns the scanned catalog keyed by [rel size]"
        (let [cat (sync/scan-into-cache! conn lib {:roots ["file:///r/"]})]
          (is (= #{["A/B/One.mp3" 10] ["Two.flac" 20]} (set (keys cat))))
          (is (= "X" (:artist (get cat ["A/B/One.mp3" 10]))))))

      (testing "the scan is persisted into the cache"
        (is (= #{["A/B/One.mp3" 10] ["Two.flac" 20]}
               (set (keys (cache/library-catalog (d/db conn) lib))))))

      (testing "the first scan's known lookup is empty; the next reflects the cache"
        (let [first-known (first @seen)]
          (is (nil? (first-known "A/B/One.mp3" 10))))
        (sync/scan-into-cache! conn lib {:roots ["file:///r/"]})
        (let [next-known (last @seen)]
          (is (= 5 (:mtime (next-known "A/B/One.mp3" 10))))
          (is (= "X" (:artist (next-known "A/B/One.mp3" 10)))))))))

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
        (let [cat (cache/library-catalog (d/db conn) snk)]
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
        (let [cat (cache/library-catalog (d/db conn) src)]
          (is (= #{["Back.mp3" 50]} (set (keys cat))))
          (is (= "file:///s/" (:root (get cat ["Back.mp3" 50]))))
          (is (= "Art" (:artist (get cat ["Back.mp3" 50]))))))
      (testing "the copied-back track is now recorded on both libraries"
        (is (= #{src snk} (set (cache/track-libraries (d/db conn) "Back.mp3" 50))))))))
