(ns dapr.fs.nio-test
  "Unit coverage for the incremental tag-reuse path of the scan walk, using jimfs
  for real Path I/O over a non-default provider. The walk's URI-driven entry
  points (catalog!) need a registered provider, so they live in the integration
  suite; here we drive the private walker with a jimfs root directly."
  (:require [clojure.test :refer [deftest is testing]]
            [dapr.domain.library :as lib]
            [dapr.device.tag :as device-tag]
            [dapr.fs.nio :as nio]
            [dapr.test-fs :as tfs])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileTime)))

(def ^:private walk! #'nio/walk-audio-tracks!)

(defn- scan
  "Walk `root` (jimfs Path) with `known`, counting how many files actually had
  their tags read (vs reused). Returns {:tracks :reads}."
  [root uri known]
  (let [reads (atom 0)]
    (with-redefs [device-tag/tags! (fn [_m _p] (swap! reads inc) {:artist "read" :album nil :title nil :source :embedded})]
      {:tracks (walk! root uri lib/default-audio-extensions nil known)
       :reads  @reads})))

(defn- by-file
  "Index tracks by physical file [rel size] — the :key now carries the tags, so a
  size/mtime-driven test wants to look tracks up by their stable file identity."
  [tracks]
  (into {} (map (juxt (juxt :rel :size) identity)) tracks))

(deftest incremental-tag-reuse-test
  (with-open [fs (tfs/unix-fs)]
    (let [root (tfs/root fs "/music")
          uri  "file:///music/"]
      (tfs/write! (.resolve root "A/x.mp3") "xxx")
      (tfs/write! (.resolve root "B/y.flac") "yy")

      (testing "with no cache, every audio file is read"
        (let [{:keys [tracks reads]} (scan root uri nil)]
          (is (= 2 reads))
          (is (= #{["read" nil nil 3 "A/x.mp3"] ["read" nil nil 2 "B/y.flac"]}
                 (set (map :key tracks))))
          (is (= "read" (:artist (first tracks))))))

      (let [known-map (by-file (:tracks (scan root uri nil)))
            ;; Pretend the cache holds distinct tags so reuse is observable. Tag
            ;; reuse is looked up by physical file [rel size], not the domain key.
            cached    (into {} (map (fn [[_ t]] [[(:rel t) (:size t)] (assoc t :artist "cached")]))
                            known-map)
            known     (fn [rel size] (get cached [rel size]))]

        (testing "an unchanged tree reuses every cached tag and reads nothing"
          (let [{:keys [tracks reads]} (scan root uri known)]
            (is (= 0 reads))
            (is (every? #(= "cached" (:artist %)) tracks))))

        (testing "a file whose mtime changed is re-read; the rest are reused"
          (Files/setLastModifiedTime (.resolve root "A/x.mp3") (FileTime/fromMillis 0))
          (let [{:keys [tracks reads]} (scan root uri known)]
            (is (= 1 reads))
            (is (= "read" (:artist (get (by-file tracks) ["A/x.mp3" 3]))))
            (is (= "cached" (:artist (get (by-file tracks) ["B/y.flac" 2]))))))

        (testing "a file whose size changed misses the cache (new key) and is re-read"
          (tfs/write! (.resolve root "B/y.flac") "yyyy")
          (let [{:keys [tracks]} (scan root uri known)]
            (is (contains? (by-file tracks) ["B/y.flac" 4]))
            (is (= "read" (:artist (get (by-file tracks) ["B/y.flac" 4]))))))))))

(deftest deep-nesting-no-overflow-test
  (with-open [fs (tfs/unix-fs)]
    (let [root (tfs/root fs "/m")
          ;; Far deeper than any call-stack recursion could handle — the iterative
          ;; walk must traverse it without a StackOverflowError.
          deep (reduce (fn [^java.nio.file.Path p _] (.resolve p "d")) root (range 20000))]
      (tfs/write! (.resolve root "top.mp3") "x")
      (tfs/write! (.resolve deep "bottom.mp3") "y")
      (let [tracks (walk! root "file:///m/" lib/default-audio-extensions nil nil)
            names  (set (map :name tracks))]
        (testing "every file is found regardless of nesting depth (no stack overflow)"
          (is (contains? names "top.mp3"))
          (is (contains? names "bottom.mp3")))))))
