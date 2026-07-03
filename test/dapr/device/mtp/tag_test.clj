(ns dapr.device.mtp.tag-test
  (:require [clojure.test :refer [deftest is testing]]
            [dapr.device.mtp.tag :as mtp-tag]
            [dapr.device.tag :as tag])
  (:import (java.nio.file Files Path)
           (java.nio.file.attribute FileAttribute)))

(def ^:private fallback
  {:artist "PathArtist" :album "PathAlbum" :title "PathTitle" :source :path})

(deftest merge-device-tags-test
  (testing "device-reported fields win over path-derived ones, tagged :embedded"
    (is (= {:artist "A" :album "B" :title "T" :source :embedded}
           (mtp-tag/merge-device-tags fallback
                                      {"artist" "A" "album" "B" "title" "T"}))))
  (testing "blank/missing fields keep the fallback; any reported field still marks :embedded"
    (is (= {:artist "A" :album "PathAlbum" :title "PathTitle" :source :embedded}
           (mtp-tag/merge-device-tags fallback
                                      {"artist" "A" "album" "" "title" nil}))))
  (testing "nothing reported keeps everything path-derived, tagged :path so a
            later read can still upgrade the cache entry"
    (is (= fallback
           (mtp-tag/merge-device-tags fallback
                                      {"artist" nil "album" nil "title" nil})))
    (is (= fallback (mtp-tag/merge-device-tags fallback {})))))

(deftest mtp-view-unavailable-falls-back-test
  (testing "when the path's provider has no \"mtp\" attribute view (melt-jfs 0.1.1
            on the classpath, here the default filesystem) the read falls back to
            path-derived tags instead of throwing"
    (let [^Path p (Files/createTempFile "dapr-mtp-tag" ".mp3"
                                        (make-array FileAttribute 0))]
      (try
        (is (= {:artist "Artist" :album "Album" :title "Title" :source :path}
               (tag/tags! {:root "mtp://dev/root"
                           :rel  "Artist/Album/Title.mp3"}
                          p)))
        (finally
          (Files/deleteIfExists p))))))
