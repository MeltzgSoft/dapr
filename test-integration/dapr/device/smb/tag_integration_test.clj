(ns dapr.device.smb.tag-integration-test
  "Integration tests for the smb:// audio-tag reader (dapr.device.smb.tag),
  exercised against the same live SMB backend as the fs integration tests: a
  dperson/samba Testcontainer on Linux, or the CI-provisioned native SMB server
  on macOS/Windows (see dapr.device.smb.fs-integration-test for the backend
  fixture and OS split).

  This is the one thing the unit tests cannot cover: that a real tagged file on an
  SMB share is read back as :embedded through jcifs, over ranged reads (never a
  whole-file transfer). A second test proves the mechanism the reader relies on —
  smb-nio's SeekableByteChannel honours position(), so a range becomes a targeted
  SMB READ."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [dapr.audio-fixtures :as fixtures]
            [dapr.device.fs :as device-fs]
            [dapr.device.smb.fs-integration-test :as smb-it]
            [dapr.fs.nio :as nio]
            [dapr.test-fs :as tfs])
  (:import (java.nio ByteBuffer)
           (java.nio.file Files OpenOption Path StandardOpenOption)
           (java.nio.file.attribute FileAttribute)))

;; Reuse the shared SMB backend fixture (one container for the whole run) and its
;; guest share URL.
(use-fixtures :once smb-it/with-smb-backend)

;; Taken from the fixture rather than repeated: on the forge the guest share is a
;; sidecar named by TEST_SMB_GUEST_URL, not 127.0.0.1, and a second literal here
;; would silently keep pointing at a server this job never starts.
(def ^:private guest-url smb-it/guest-url)

(defn- seed-file-at!
  "Write `bytes` to a fresh temp dir at the nested relative path `rel` (creating
  parent dirs) and return the file:// root Path. Mirrors
  fs-integration-test/seed-local!: copy-file! resolves `rel` under the root, so the
  source file must live at root/rel."
  ^Path [rel ^bytes bytes]
  (let [dir (Files/createTempDirectory "dapr-smb-tag-it" (make-array FileAttribute 0))
        f   (.resolve dir ^String rel)]
    (Files/createDirectories (.getParent f) (make-array FileAttribute 0))
    (Files/write f bytes (make-array OpenOption 0))
    (device-fs/root-path! (str (.toUri dir)))))

(deftest reads-embedded-tags-over-smb-test
  (when (smb-it/running?)
    (testing "a tagged FLAC on an SMB share reads back as :embedded, not path-derived"
      (let [rel      "path-artist/path-album/Fixture.flac"
            src-root (seed-file-at! rel (fixtures/flac-bytes {:title  "Embedded Title"
                                                              :artist "Embedded Artist"
                                                              :album  "Embedded Album"}))
            dst-root (device-fs/root-path! guest-url)]
        (try
          (nio/copy-file! src-root dst-root rel)
          (let [track (first (filter #(= rel (:rel %)) (tfs/scan-tracks! [guest-url])))]
            (is (some? track) "the copied FLAC should be catalogued")
            (is (= :embedded (:source track))
                "tags come from the file's own embedded VORBIS_COMMENT, not the path")
            ;; Embedded values win over what the path (path-artist/path-album)
            ;; would derive — proving the ranged read really parsed the header.
            (is (= "Embedded Artist" (:artist track)))
            (is (= "Embedded Album" (:album track)))
            (is (= "Embedded Title" (:title track))))
          (finally
            (nio/delete-file! dst-root rel)))))))

(deftest degrades-to-path-tags-for-untagged-file-test
  (when (smb-it/running?)
    (testing "an SMB file whose header carries no tags falls back to path-derived tags (:path)"
      (let [rel      "The Band/The Record/Track.mp3"
            src-root (seed-file-at! rel (.getBytes "not really an mp3" "US-ASCII"))
            dst-root (device-fs/root-path! guest-url)]
        (try
          (nio/copy-file! src-root dst-root rel)
          (let [track (first (filter #(= rel (:rel %)) (tfs/scan-tracks! [guest-url])))]
            (is (= {:artist "The Band" :album "The Record" :title "Track"}
                   (select-keys track [:artist :album :title])))
            (is (= :path (:source track))
                "an unreadable header degrades to path tags without aborting the scan"))
          (finally
            (nio/delete-file! dst-root rel)))))))

(deftest smb-channel-supports-ranged-reads-test
  (when (smb-it/running?)
    (testing "smb-nio's SeekableByteChannel honours position() — the mechanism the
              header-only (ranged) tag read relies on"
      (let [rel      "ranged/probe.bin"
            content  "0123456789ABCDEF"
            src-root (seed-file-at! rel (.getBytes content "US-ASCII"))
            dst-root (device-fs/root-path! guest-url)]
        (try
          (nio/copy-file! src-root dst-root rel)
          (let [^Path p (.resolve dst-root rel)
                buf     (ByteBuffer/allocate 4)]
            (with-open [ch (Files/newByteChannel
                            p (into-array OpenOption [StandardOpenOption/READ]))]
              (is (= 16 (.size ch)) "channel reports the full size")
              ;; Seek past the first 10 bytes and read 4 — a ranged read, not a
              ;; whole-file transfer.
              (.position ch 10)
              (.read ch buf)
              (is (= "ABCD" (String. (.array buf) "US-ASCII"))
                  "a mid-file ranged read returns exactly the seeked slice")))
          (finally
            (nio/delete-file! dst-root rel)))))))
