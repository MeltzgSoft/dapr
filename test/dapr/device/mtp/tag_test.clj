(ns dapr.device.mtp.tag-test
  (:require [clojure.test :refer [deftest is testing]]
            [dapr.device.mtp.tag :as mtp-tag]
            [dapr.device.tag :as tag])
  (:import (java.nio.file Files Path)
           (java.nio.file.attribute FileAttribute)))

(def ^:private fallback
  {:artist "PathArtist" :album "PathAlbum" :title "PathTitle" :source :path})

;; Every merge result carries the full field set; extras (genre/track/disc/
;; duration) default to nil since path derivation can't supply them.
(defn- tags
  "A full expected tag map: the given overrides merged over all-nil fields."
  [m]
  (merge {:artist nil :album nil :title nil :genre nil :track-number nil
          :disc-number nil :duration-millis nil}
         m))

(deftest merge-device-tags-test
  (testing "device-reported fields win over path-derived ones, tagged :embedded"
    (is (= (tags {:artist "A" :album "B" :title "T" :source :embedded})
           (mtp-tag/merge-device-tags fallback
                                      {"artist" "A" "album" "B" "title" "T"}))))
  (testing "blank/missing fields keep the fallback; any reported field still marks :embedded"
    (is (= (tags {:artist "A" :album "PathAlbum" :title "PathTitle" :source :embedded})
           (mtp-tag/merge-device-tags fallback
                                      {"artist" "A" "album" "" "title" nil}))))
  (testing "genre and the numeric fields ride along a reported identity field"
    (is (= (tags {:artist "PathArtist" :album "PathAlbum" :title "T" :source :embedded
                  :genre "Rock" :track-number 3 :disc-number 1 :duration-millis 210000})
           (mtp-tag/merge-device-tags fallback
                                      {"title" "T" "genre" "Rock" "trackNumber" 3
                                       "discNumber" 1 "durationMillis" 210000})))
    (testing "a lone duration (no identity field) does not mark :embedded — :source
              describes the identity tags, and a device index reports duration for
              nearly every track"
      (is (= (tags {:artist "PathArtist" :album "PathAlbum" :title "PathTitle" :source :path
                    :duration-millis 210000})
             (mtp-tag/merge-device-tags fallback {"durationMillis" 210000})))))
  (testing "zero/blank numeric and text fields are treated as unreported"
    (is (= (tags {:artist "PathArtist" :album "PathAlbum" :title "PathTitle" :source :path})
           (mtp-tag/merge-device-tags fallback
                                      {"genre" "" "trackNumber" 0
                                       "discNumber" 0 "durationMillis" 0}))))
  (testing "nothing reported keeps everything path-derived, tagged :path so a
            later read can still upgrade the cache entry"
    (is (= (tags fallback)
           (mtp-tag/merge-device-tags fallback
                                      {"artist" nil "album" nil "title" nil})))
    (is (= (tags fallback) (mtp-tag/merge-device-tags fallback {}))))
  (testing "layering audio over mtp: an :embedded fallback stays :embedded even
            when the outer layer reports nothing"
    (let [mtp-layer (mtp-tag/merge-device-tags fallback
                                               {"artist" "M" "album" "" "title" ""})]
      (is (= (tags {:artist "M" :album "PathAlbum" :title "PathTitle" :source :embedded})
             mtp-layer))
      (is (= (tags {:artist "M" :album "PathAlbum" :title "PathTitle" :source :embedded})
             (mtp-tag/merge-device-tags mtp-layer {})))))
  (testing "layering audio over mtp: audio fields win per field, mtp fills the gaps"
    (let [mtp-layer (mtp-tag/merge-device-tags fallback
                                               {"artist" "M-artist" "album" "M-album"
                                                "title" "" "genre" "M-genre" "trackNumber" 7})]
      (is (= (tags {:artist "A-artist" :album "M-album" :title "A-title" :source :embedded
                    :genre "M-genre" :track-number 7 :disc-number 2})
             (mtp-tag/merge-device-tags mtp-layer
                                        {"artist" "A-artist" "album" "" "title" "A-title"
                                         "discNumber" 2}))))))

(deftest views-unavailable-falls-back-test
  (testing "when the path's provider has neither the \"audio\" nor the \"mtp\"
            attribute view (here the default filesystem) the read falls back to
            path-derived tags instead of throwing"
    (let [^Path p (Files/createTempFile "dapr-mtp-tag" ".mp3"
                                        (make-array FileAttribute 0))]
      (try
        (is (= (tags {:artist "Artist" :album "Album" :title "Title" :source :path})
               (tag/tags! {:root "mtp://dev/root"
                           :rel  "Artist/Album/Title.mp3"}
                          p)))
        (finally
          (Files/deleteIfExists p))))))
