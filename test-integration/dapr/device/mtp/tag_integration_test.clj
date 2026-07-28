(ns dapr.device.mtp.tag-integration-test
  "Integration tests for the mtp:// audio-tag reader (dapr.device.mtp.tag) against a
  real attached MTP device — the hardware verification the unit tests can't do
  (the FFM struct layout and MTP GetPartialObject ranged reads only run on-device).

  The device is discovered like dapr.device.mtp.fs-integration-test; the tests skip
  when none is attached (CI, or no device). Each test writes a fixture into a
  uniquely named temp dir on the device's first storage and removes it (and the dir)
  in a finally, so an attached device is left exactly as it was found — the reader is
  read-only, only the fixture setup writes.

  Verified on hardware:
  - the \"audio\" view — embedded tags parsed from the file's own header over MTP
    ranged reads (GetPartialObject) — gated on the device supporting partial reads;
  - the \"mtp\" view — the device's media index of object properties — read and
    asserted well-formed, with values checked when the device's scanner populated it
    (population is device-dependent);
  - the layered merge in tags! (audio over mtp over path), and the path fallback for
    a format neither view can supply."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dapr.audio-fixtures :as fixtures]
            [dapr.device.fs :as device-fs]
            [dapr.device.mtp.fs :as mtp]
            [dapr.device.mtp.tag]
            [dapr.device.tag :as tag]
            [dapr.fs.nio :as nio])
  (:import (java.nio.file Files LinkOption OpenOption Path)
           (java.nio.file.attribute FileAttribute)
           (org.meltzg.fs.mtp MTPDeviceBridge)))

(def ^:private devices
  "Attached MTP devices whose storage can be browsed, or nil when none is connected
  (in which case the tests skip). Mirrors fs-integration-test's discovery."
  (try
    (seq (filter #(try (device-fs/dir-children! (:uri %)) true
                       (catch Throwable _ false))
                 (mtp/devices!)))
    (catch Throwable _ nil)))

(defn- skip [why]
  (println (str "  (skipping MTP tag integration test — " why ")")))

(defn- supports-partial-reads? []
  (try (.supportsPartialReads (MTPDeviceBridge/getInstance)) (catch Throwable _ false)))

(defn- read-view
  "The title/artist/album of `view` (\"audio\"/\"mtp\") for device path `p`, as a
  Clojure map with string keys (nil values when the field is absent)."
  [^Path p view]
  (into {} (Files/readAttributes p (str view ":title,artist,album") (make-array LinkOption 0))))

(defn- with-device-fixture
  "Write `bytes` as `fname` into a fresh temp dir on `storage`, resolve the device
  Path of the written file, call (f device-path rel), and remove the file and its
  temp dir afterward (best-effort, even if an assertion throws)."
  [storage fname ^bytes bytes f]
  (let [td       (str "dapr-tagtest-" (System/currentTimeMillis) "-" (Math/abs (hash fname)))
        rel      (str td "/" fname)
        dst-root (device-fs/root-path! (:uri storage))
        file-uri (str (:uri storage) td "/" fname)
        local    (Files/createTempDirectory "mtp-tag-it" (make-array FileAttribute 0))
        lfile    (.resolve local ^String rel)]
    (Files/createDirectories (.getParent lfile) (make-array FileAttribute 0))
    (Files/write lfile bytes (make-array OpenOption 0))
    (try
      (nio/copy-file! (device-fs/root-path! (str (.toUri local))) dst-root rel)
      (f (device-fs/root-path! file-uri) rel)
      (finally
        (nio/delete-file! dst-root rel)
        (nio/delete-file! dst-root td)))))

(defn- first-storage []
  (first (device-fs/dir-children! (:uri (first devices)))))

(deftest reads-embedded-tags-from-device-test
  (if-not devices
    (skip "no MTP device attached")
    (if-let [storage (first-storage)]
      (with-device-fixture
        storage "fixture.flac" (fixtures/flac-bytes "Real Title" "Real Artist" "Real Album")
        (fn [^Path p rel]
          (testing "the audio view parses the embedded FLAC tags over MTP ranged reads"
            (if (supports-partial-reads?)
              (is (= {"title" "Real Title" "artist" "Real Artist" "album" "Real Album"}
                     (read-view p "audio"))
                  "GetPartialObject ranged reads recover the embedded VORBIS_COMMENT")
              (skip "device does not support MTP partial reads — audio view unavailable")))
          (testing "the mtp index view is well-formed; matches when the device populated it"
            (let [mtp-view (read-view p "mtp")]
              (is (= #{"title" "artist" "album"} (set (keys mtp-view)))
                  "the mtp view returns exactly the requested object properties")
              (when (some (comp not str/blank?) (vals mtp-view))
                (is (= {"title" "Real Title" "artist" "Real Artist" "album" "Real Album"} mtp-view)
                    "when the device indexed the upload, its properties match the embedded tags"))))
          (testing "tags! returns the embedded tags from the device, not path-derived"
            (is (= {:artist "Real Artist" :album "Real Album" :title "Real Title" :source :embedded}
                   (tag/tags! {:root (:uri storage) :rel rel :name "fixture.flac"} p))
                "audio (and/or mtp) wins over the junk path-derived values"))))
      (skip "device exposes no storage to write to"))))

(deftest degrades-to-path-for-unsupported-format-test
  (if-not devices
    (skip "no MTP device attached")
    (if-let [storage (first-storage)]
      (with-device-fixture
        storage "not-really.xyz" (.getBytes "not an audio file" "US-ASCII")
        (fn [^Path p rel]
          (testing "a format neither view can supply falls back to path-derived tags (:path)"
            (let [result (tag/tags! {:root (:uri storage) :rel rel :name "not-really.xyz"} p)]
              (is (= :path (:source result)))
              (is (= "not-really" (:title result)) "title derived from the filename")))))
      (skip "device exposes no storage to write to"))))
