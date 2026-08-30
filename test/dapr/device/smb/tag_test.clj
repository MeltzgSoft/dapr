(ns dapr.device.smb.tag-test
  (:require [clojure.test :refer [deftest is testing]]
            [dapr.audio-fixtures :as fixtures]
            [dapr.device.smb.tag :as smb-tag]
            [dapr.device.tag :as tag])
  (:import (java.nio.file Files OpenOption Path)
           (java.nio.file.attribute FileAttribute)
           (org.meltzg.audio AudioTags)))

(defn- temp-file ^Path [ext ^bytes content]
  (let [p (Files/createTempFile "dapr-smb-tag-unit" ext (make-array FileAttribute 0))]
    (Files/write p content (make-array OpenOption 0))
    p))

;; --- channel-source ----------------------------------------------------------

(deftest channel-source-ranged-reads-test
  (testing "the RangedByteSource seeks and returns exactly the requested slice"
    (let [^Path p (temp-file ".bin" (.getBytes "0123456789ABCDEF" "US-ASCII"))]
      (try
        (with-open [ch (Files/newByteChannel p (make-array OpenOption 0))]
          (let [src (smb-tag/channel-source ch)]
            (is (= "ABCD" (String. (.read src 10 4) "US-ASCII"))
                "a mid-file range returns just those bytes")
            (is (= "0123" (String. (.read src 0 4) "US-ASCII"))
                "seeks are absolute — a later read isn't relative to the last")
            (is (= "EF" (String. (.read src 14 8) "US-ASCII"))
                "a read past EOF is truncated to what's available")
            (is (= 0 (alength (.read src 99 4))) "at/after EOF returns empty")
            (is (= 0 (alength (.read src 0 0))) "maxBytes 0 returns empty")
            (is (thrown? IllegalArgumentException (.read src -1 4)))))
        (finally (Files/deleteIfExists p))))))

;; --- audio-tags->tags mapping -------------------------------------------------

(def ^:private path-fallback
  {:artist "PathArtist" :album "PathAlbum" :title "PathTitle" :source :path})

(deftest audio-tags-mapping-test
  (testing "full embedded tags win and mark :embedded"
    (is (= {:artist "A" :album "Al" :title "T" :genre "G" :track-number 3
            :disc-number 1 :duration-millis 210000 :source :embedded}
           (smb-tag/audio-tags->tags (AudioTags. "T" "A" "Al" "G" 3 1 210000) path-fallback))))
  (testing "blank/zero fields fall back (text to the path value, numbers to nil), still :embedded"
    (is (= {:artist "A" :album "PathAlbum" :title "T" :genre nil :track-number nil
            :disc-number nil :duration-millis nil :source :embedded}
           (smb-tag/audio-tags->tags (AudioTags. "T" "A" "  " nil 0 0 0) path-fallback))))
  (testing "no usable artist/album/title (or nil) keeps the path fallback as :path"
    (is (= path-fallback (smb-tag/audio-tags->tags AudioTags/EMPTY path-fallback)))
    (is (= path-fallback (smb-tag/audio-tags->tags nil path-fallback)))))

;; --- tags! :smb over a local channel (real parse path) -----------------------

(deftest reads-embedded-flac-tags-test
  (testing "a real tagged FLAC is parsed via melt-jfs ranged reads, embedded wins over path"
    (let [^Path p (temp-file ".flac" (fixtures/flac-bytes {:title "Real Title" :artist "Real Artist"
                                                           :album "Real Album" :genre "Jazz"
                                                           :track-number 7 :disc-number 2}))]
      (try
        ;; tags! only uses the path for the channel + filename, so a local Path
        ;; under an smb:// root (the library root, as the scanner records it — the
        ;; file path lives in :rel) exercises the exact production read path. The
        ;; fixture's STREAMINFO encodes a 2000 ms duration.
        (is (= {:artist "Real Artist" :album "Real Album" :title "Real Title"
                :genre "Jazz" :track-number 7 :disc-number 2 :duration-millis 2000
                :source :embedded}
               (tag/tags! {:root "smb://host/Music/"
                           :rel  "PathArtist/PathAlbum/Real Title.flac"
                           :size (Files/size p)}
                          p)))
        (finally (Files/deleteIfExists p))))))

(deftest unsupported-format-falls-back-to-path-test
  (testing "a format melt-jfs can't parse (aac) degrades to path tags — no whole-file read"
    (let [^Path p (temp-file ".aac" (.getBytes "not really aac" "US-ASCII"))]
      (try
        (is (= {:artist "Artist" :album "Album" :title "Title" :source :path}
               (tag/tags! {:root "smb://host/Music/"
                           :rel  "Artist/Album/Title.aac"
                           :size (Files/size p)}
                          p)))
        (finally (Files/deleteIfExists p))))))

(deftest unreadable-header-falls-back-to-path-test
  (testing "a supported extension whose bytes aren't a valid container degrades to path tags"
    (let [^Path p (temp-file ".flac" (.getBytes "definitely not a flac stream" "US-ASCII"))]
      (try
        (is (= {:artist "Artist" :album "Album" :title "Title" :source :path}
               (tag/tags! {:root "smb://host/Music/"
                           :rel  "Artist/Album/Title.flac"
                           :size (Files/size p)}
                          p)))
        (finally (Files/deleteIfExists p))))))
