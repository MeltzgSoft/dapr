(ns dapr.fs.nio-integration-test
  (:require [clojure.test :refer [deftest is testing]]
            [dapr.device.fs :as device-fs]
            [dapr.fs.nio :as nio]
            [dapr.test-fs :as tfs]))

(deftest copy-and-delete-across-filesystems-test
  (testing "copy creates parents and content across two providers; delete removes it"
    (with-open [src-fs (tfs/unix-fs)
                dst-fs (tfs/unix-fs)]
      (let [src (tfs/root src-fs "/src")
            dst (tfs/root dst-fs "/dst")]
        (tfs/write! (.resolve src "music/song.mp3") "hello")
        (nio/copy-file! src dst "music/song.mp3")
        (is (= "hello" (tfs/slurp-path (.resolve dst "music/song.mp3"))))
        (nio/delete-file! dst "music/song.mp3")
        (is (not (tfs/exists? (.resolve dst "music/song.mp3"))))))))

(deftest catalog!-test
  (testing "scans audio files across multiple roots, tagging :root and :rel"
    (let [d1 (tfs/temp-dir!)
          d2 (tfs/temp-dir!)]
      (try
        (tfs/write! (.resolve d1 "a.mp3") "aaa")
        (tfs/write! (.resolve d1 "cover.jpg") "img")
        (tfs/write! (.resolve d1 "sub/b.flac") "bb")
        (tfs/write! (.resolve d2 "c.ogg") "ccc")
        (let [tracks (nio/catalog! [(tfs/uri-of d1) (tfs/uri-of d2)])
              by-key (into {} (map (juxt :key identity)) tracks)
              ;; no embedded tags -> path-derived: [artist album title size rel]
              a      [nil nil "a" 3 "a.mp3"]
              b      ["sub" nil "b" 2 "sub/b.flac"]
              c      [nil nil "c" 3 "c.ogg"]]
          (is (= #{a b c} (set (keys by-key))))
          (is (= (tfs/uri-of d1) (:root (by-key a))))
          (is (= "sub/b.flac" (:rel (by-key b))))
          (is (= (tfs/uri-of d2) (:root (by-key c))))
          (testing "non-audio files are ignored"
            (is (not (contains? by-key [nil nil "cover" 3 "cover.jpg"])))))
        (testing "on-scan emits a :file event once per scanned audio file"
          (let [files  (atom [])
                dirs   (atom [])
                tracks (nio/catalog! [(tfs/uri-of d1) (tfs/uri-of d2)]
                                     (fn [{:keys [type rel track]}]
                                       (case type
                                         :file (swap! files conj (:key track))
                                         :dir  (swap! dirs conj rel)
                                         nil)))]
            (is (= (set (map :key tracks)) (set @files)))
            (is (= (count tracks) (count @files)))
            (testing "and a :dir event for each directory entered, including the root and subdirs"
              (is (some #{""} @dirs))
              (is (some #{"sub"} @dirs)))))
        (finally
          (tfs/delete-tree! d1)
          (tfs/delete-tree! d2))))))

(deftest scan-progress-events-test
  (testing ":listing counts and :entry ticks balance, so done reaches total"
    (let [d (tfs/temp-dir!)]
      (try
        (tfs/write! (.resolve d "a.mp3") "aaa")
        (tfs/write! (.resolve d "cover.jpg") "img")
        (tfs/write! (.resolve d "sub/b.flac") "bb")
        (let [total   (atom 0)                       ; Σ directory child counts
              done    (atom 0)                       ; entries visited
              entered (atom [])]                     ; :dir events, in order
          (nio/catalog! [(tfs/uri-of d)]
                        (fn [{:keys [type rel] :as ev}]
                          (case type
                            :dir     (swap! entered conj rel)
                            :listing (swap! total + (:count ev))
                            :entry   (swap! done inc)
                            nil)))
          (testing "every child is both counted (total) and visited (done)"
            ;; root has 3 children (a.mp3, cover.jpg, sub), sub has 1 (b.flac)
            (is (= 4 @total))
            (is (= @total @done)))
          (testing "each directory is entered (a :dir event) before its listing"
            (is (= ["" "sub"] @entered))))
        (finally
          (tfs/delete-tree! d))))))

(deftest walk-abort-propagates-test
  (testing "on-scan throwing :dapr/abort unwinds the walk (not swallowed as a dir error)"
    (let [d (tfs/temp-dir!)]
      (try
        (tfs/write! (.resolve d "sub/b.mp3") "b")
        (is (thrown? clojure.lang.ExceptionInfo
                     (nio/catalog! [(tfs/uri-of d)]
                                   (fn [{:keys [type]}]
                                     (when (= :file type)
                                       (throw (ex-info "stop" {:dapr/abort true})))))))
        (finally
          (tfs/delete-tree! d))))))

(deftest dir-children!-test
  (testing "lists immediate sub-directories with round-trippable URIs, skips files"
    (let [d (tfs/temp-dir!)]
      (try
        (tfs/root (.getFileSystem d) (str d "/Internal"))
        (tfs/root (.getFileSystem d) (str d "/SD Card"))
        (tfs/write! (.resolve d "loose.mp3") "x")
        (let [entries (device-fs/dir-children! (tfs/uri-of d))]
          (is (= ["Internal" "SD Card"] (mapv :name entries)))
          (is (every? :dir? entries))
          (testing "child URIs resolve back to the same path (so descent works)"
            (is (= #{(.resolve d "Internal") (.resolve d "SD Card")}
                   (set (map #(device-fs/root-path! (:uri %)) entries))))))
        (finally
          (tfs/delete-tree! d))))))

(deftest library-free!-test
  (testing "dedupes roots on the same device and returns a positive total"
    (let [d (tfs/temp-dir!)]
      (try
        (let [free (nio/library-free! [(tfs/uri-of d)])]
          (is (pos? free))
          (testing "two roots on the same device are not double counted"
            (is (= free (nio/library-free! [(tfs/uri-of d) (tfs/uri-of d)])))))
        (finally
          (tfs/delete-tree! d))))))
