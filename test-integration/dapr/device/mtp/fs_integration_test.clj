(ns dapr.device.mtp.fs-integration-test
  "Integration tests for the mtp:// backend (melt-jfs + native libmtp) — the one
  thing neither the jimfs unit tests nor a container can cover, since it needs a
  real device. Part of the `clojure -M:integration` suite; runs only when an MTP
  device is attached, otherwise (CI, or no device) it skips — no env var or other
  setup, like melt-jfs's own integration tests.

  Discovery, storage listing, and capacity are read-only. The copy -> catalog ->
  delete round-trip does write to an attached device, but only inside a uniquely
  named temp directory under its first storage, which it removes afterward — so a
  run leaves the device as it was found and never scans its whole (potentially
  huge) library."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dapr.device.fs :as device-fs]
            [dapr.device.mtp.fs :as mtp]
            [dapr.device.mtp.require-device :as require-device]
            [dapr.fs.nio :as nio])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(def ^:private devices
  "Attached MTP devices, or nil when discovery fails (no native libmtp) or none is
  connected — in which case these tests skip. Devices whose storage list can't be
  browsed are dropped as phantoms: Windows enumerates WPD device-interface GUIDs
  (e.g. mtp://0:0:{b486c821-...}) that aren't real, browseable MTP devices."
  (try
    (seq (filter #(try (device-fs/dir-children! (:uri %)) true
                       (catch Throwable _ false))
                 (mtp/devices!)))
    (catch Throwable _ nil)))

(defn- skip
  "Device absence: a printed skip normally, a failure under DAPR_REQUIRE_DEVICE
  (set on the device runners, where a silent all-skip would prove nothing)."
  [why]
  (require-device/skip-or-fail "MTP integration test" why))

(deftest device-discovery-test
  (if-not devices
    (skip "no MTP device attached")
    (testing "devices! reports each attached device with an id, name and mtp:// uri"
      (doseq [d devices]
        (is (not (str/blank? (:id d))))
        (is (not (str/blank? (:name d))))
        (is (str/starts-with? (str (:uri d)) "mtp://"))))))

(deftest storages-and-capacity-test
  (if-not devices
    (skip "no MTP device attached")
    (testing "the device's storages list (read-only) and report capacity"
      (let [storages (device-fs/dir-children! (:uri (first devices)))]
        (is (seq storages) "device should expose at least one storage")
        (is (every? :dir? storages))
        (is (<= 0 (nio/library-free! [(:uri (first storages))]))
            "library-free! should report non-negative capacity for a storage")))))

(deftest write-scan-roundtrip-test
  (if-not devices
    (skip "no MTP device attached")
    ;; Auto-derive a writable location from the device's first storage — no env var.
    ;; Everything is written under a uniquely named temp dir that is removed in the
    ;; finally, so an attached device is left as it was found and catalog! only ever
    ;; walks the small temp dir, never the whole device.
    (let [storage (first (device-fs/dir-children! (:uri (first devices))))]
      (if-not storage
        (skip "device exposes no storage to write to")
        (testing "copy a file into a temp dir on the device, catalog! finds it, delete removes it"
          (let [content    "dapr-mtp-integration"
                size       (count (.getBytes ^String content))
                file-name  "dapr-integration-test.mp3"
                test-dir   (str "dapr-it-" (System/currentTimeMillis))
                rel        (str test-dir "/" file-name)
                dst-root   (device-fs/root-path! (:uri storage))
                test-url   (str (:uri storage) test-dir "/")
                local      (Files/createTempDirectory "mtp-it" (make-array FileAttribute 0))
                local-file (.resolve local ^String rel)]
            (Files/createDirectories (.getParent local-file) (make-array FileAttribute 0))
            (spit (str local-file) content)
            (try
              (nio/copy-file! (device-fs/root-path! (str (.toUri local))) dst-root rel)
              (let [track (first (filter #(= file-name (:rel %)) (nio/catalog! [test-url])))]
                (is (some? track) "copied track should be discovered by catalog!")
                (is (= size (:size track)) "catalog should report the copied file's size"))
              (nio/delete-file! dst-root rel)
              (is (not (some #(= file-name (:rel %)) (nio/catalog! [test-url])))
                  "track should be gone after delete-file!")
              (finally
                ;; Best-effort teardown even if an assertion above threw: remove the
                ;; file and then the now-empty temp dir.
                (nio/delete-file! dst-root rel)
                (nio/delete-file! dst-root test-dir)))))))))
