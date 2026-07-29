(ns dapr.fs.nio-test
  "Unit coverage for the scan walk — tag reuse, pause/resume, and deep trees —
  using jimfs for real Path I/O over a non-default provider. jimfs URIs have no
  registered provider, so these drive nio/scan-roots! with device-fs/root-path!
  stubbed to hand back the jimfs root; the URI-driven path itself is covered by
  the integration suite."
  (:require [clojure.test :refer [deftest is testing]]
            [dapr.device.fs :as device-fs]
            [dapr.device.tag :as device-tag]
            [dapr.fs.nio :as nio]
            [dapr.test-fs :as tfs])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileTime)))

(defn- scan
  "Scan the jimfs `root` (addressed as `uri`) with `known`, counting how many files
  actually had their tags read (vs reused). Returns {:tracks :reads}."
  [root uri known]
  (let [reads  (atom 0)
        tracks (volatile! [])]
    (with-redefs [device-tag/tags!     (fn [_m _p] (swap! reads inc)
                                         {:artist "read" :album nil :title nil :source :embedded})
                  device-fs/root-path! (fn [_uri] root)]
      (nio/scan-roots! [uri] {:known    known
                              :on-batch (fn [batch] (vswap! tracks into batch))})
      {:tracks @tracks :reads @reads})))

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

(defn- files-of
  "Physical file identities [rel size] of `tracks` — the :key now carries the tags,
  so a test about *which files* a walk found compares by file."
  [tracks]
  (into #{} (map (juxt :rel :size)) tracks))

(defn- populate!
  "A small multi-directory tree under `root`, returning the [rel size] keys it holds."
  [root]
  (doseq [[rel content] [["a.mp3" "1"] ["A/x.mp3" "22"] ["A/deep/y.flac" "333"]
                         ["B/z.m4a" "4444"] ["B/notes.txt" "ignored"]]]
    (tfs/write! (.resolve ^java.nio.file.Path root rel) content))
  #{["a.mp3" 1] ["A/x.mp3" 2] ["A/deep/y.flac" 3] ["B/z.m4a" 4]})

(defn- scan-roots
  "Drive nio/scan-roots! over jimfs `roots` ({uri -> Path}), counting tag reads and
  collecting every batched track. `pause?` is threaded straight through, and a
  paused scan is resumed from its checkpoint until it completes — the way
  dapr.refresh does. Returns {:tracks :reads :resumes}."
  [roots pause?]
  (let [reads   (atom 0)
        tracks  (atom [])
        resumes (atom 0)]
    (with-redefs [device-tag/tags!    (fn [_m _p] (swap! reads inc) {:artist "read" :source :embedded})
                  device-fs/root-path! (fn [uri] (get roots uri))]
      (loop [checkpoint nil]
        (let [res (nio/scan-roots! (vec (keys roots))
                                   {:known      nil
                                    :pause?     pause?
                                    :checkpoint checkpoint
                                    :batch-size 2
                                    :on-batch   (fn [batch] (swap! tracks into batch))})]
          (if (= :paused (:status res))
            (do (swap! resumes inc) (recur (:checkpoint res)))
            {:tracks @tracks :reads @reads :resumes @resumes :seen (:seen res)}))))))

(deftest resumable-scan-test
  (with-open [fs (tfs/unix-fs)]
    (let [root  (tfs/root fs "/music")
          roots {"file:///music/" root}
          want  (populate! root)]

      (testing "an uninterrupted scan reports every track once and completes"
        (let [{:keys [tracks reads resumes seen]} (scan-roots roots nil)]
          (is (= want (files-of tracks)))
          (is (= want seen))
          (is (= 4 (count tracks)) "no duplicates")
          (is (= 4 reads))
          (is (zero? resumes))))

      (testing "a scan paused at every directory boundary resumes to the same result"
        ;; Pause on every other poll, so the walk stops (and check-points) part way
        ;; through most directories but still makes progress.
        (let [n (atom 0)
              {:keys [tracks reads resumes seen]} (scan-roots roots #(odd? (swap! n inc)))]
          (is (pos? resumes) "the walk actually paused")
          (is (= want (files-of tracks)))
          (is (= want seen))
          (is (= 4 (count tracks)) "a resumed directory is not re-walked")
          (is (= 4 reads) "no track's tags are read twice"))))))

(deftest resumable-scan-multi-root-test
  (with-open [fs (tfs/unix-fs)]
    (let [r1    (tfs/root fs "/one")
          r2    (tfs/root fs "/two")
          roots {"file:///one/" r1 "file:///two/" r2}]
      (tfs/write! (.resolve r1 "A/first.mp3") "1")
      (tfs/write! (.resolve r2 "B/second.mp3") "22")
      (testing "a checkpoint carries the roots not yet finished"
        (let [n (atom 0)
              {:keys [tracks reads resumes]} (scan-roots roots #(odd? (swap! n inc)))]
          (is (pos? resumes))
          (is (= #{["A/first.mp3" 1] ["B/second.mp3" 2]} (files-of tracks)))
          (is (= 2 reads) "neither root is re-walked"))))))

(deftest deep-nesting-no-overflow-test
  (with-open [fs (tfs/unix-fs)]
    (let [root (tfs/root fs "/m")
          ;; Far deeper than any call-stack recursion could handle — the iterative
          ;; walk must traverse it without a StackOverflowError.
          deep (reduce (fn [^java.nio.file.Path p _] (.resolve p "d")) root (range 20000))]
      (tfs/write! (.resolve root "top.mp3") "x")
      (tfs/write! (.resolve deep "bottom.mp3") "y")
      (let [tracks (:tracks (scan root "file:///m/" nil))
            names  (set (map :name tracks))]
        (testing "every file is found regardless of nesting depth (no stack overflow)"
          (is (contains? names "top.mp3"))
          (is (contains? names "bottom.mp3")))))))
