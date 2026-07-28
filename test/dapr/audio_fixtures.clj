(ns dapr.audio-fixtures
  "Byte-level audio fixtures for tag-reader tests. Builds a minimal valid tagged
  FLAC in memory — the same shape as melt-jfs's own SyntheticFlac fixture — so its
  FlacMetadataReader parses the tags, letting tests exercise the real read path
  without an external encoder or a committed binary."
  (:import (java.io ByteArrayOutputStream)))

(defn- le32! [^ByteArrayOutputStream o v]
  (doseq [s [0 8 16 24]] (.write o (int (bit-and (unsigned-bit-shift-right v s) 0xFF)))))

(defn- len-prefixed! [^ByteArrayOutputStream o ^String s]
  (let [b (.getBytes s "UTF-8")] (le32! o (alength b)) (.write o b 0 (alength b))))

(defn- vorbis-comment ^bytes [title artist album]
  (let [o (ByteArrayOutputStream.)]
    (len-prefixed! o "dapr-test")
    (le32! o 3)
    (len-prefixed! o (str "TITLE=" title))
    (len-prefixed! o (str "ARTIST=" artist))
    (len-prefixed! o (str "ALBUM=" album))
    (.toByteArray o)))

(defn- block-header ^bytes [type last? len]
  (byte-array [(unchecked-byte (bit-or (if last? 0x80 0) (bit-and type 0x7F)))
               (unchecked-byte (unsigned-bit-shift-right len 16))
               (unchecked-byte (unsigned-bit-shift-right len 8))
               (unchecked-byte len)]))

(defn- stream-info ^bytes []
  ;; A valid 34-byte STREAMINFO: 44100 Hz, stereo, 16-bit, 88200 samples.
  (let [si     (byte-array 34)
        packed (bit-or (bit-shift-left 44100 44) (bit-shift-left 1 41)
                       (bit-shift-left 15 36) 88200)]
    (aset si 0 (unchecked-byte (unsigned-bit-shift-right 4096 8)))
    (aset si 1 (unchecked-byte 4096))
    (aset si 2 (unchecked-byte (unsigned-bit-shift-right 4096 8)))
    (aset si 3 (unchecked-byte 4096))
    (dotimes [i 8]
      (aset si (+ 10 i) (unchecked-byte (unsigned-bit-shift-right packed (- 56 (* 8 i))))))
    si))

(defn flac-bytes
  "A minimal valid FLAC (fLaC + STREAMINFO + a last-block VORBIS_COMMENT carrying
  `title`/`artist`/`album`) as a byte array."
  ^bytes [title artist album]
  (let [o (ByteArrayOutputStream.)
        c (vorbis-comment title artist album)]
    (.write o (.getBytes "fLaC" "US-ASCII"))
    (.write o (block-header 0 false 34))
    (.write o (stream-info))
    (.write o (block-header 4 true (alength c)))
    (.write o c)
    (.toByteArray o)))
