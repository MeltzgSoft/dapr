(ns dapr.device.format-test
  (:require [clojure.test :refer [deftest is testing]]
            [dapr.device.file.format]
            [dapr.device.format :as device]
            [dapr.device.mtp.format]
            [dapr.device.smb.format]))

(deftest scheme-test
  (testing "extracts and lowercases the scheme"
    (is (= "file" (device/scheme "file:///music")))
    (is (= "mtp" (device/scheme "MTP://1:2:abc/Storage")))
    (is (= "smb" (device/scheme "SMB://nas/Music/"))))
  (testing "nil for non-strings or schemeless input"
    (is (nil? (device/scheme nil)))
    (is (nil? (device/scheme 42)))
    (is (nil? (device/scheme "/plain/path")))))

(deftest device-type-test
  (testing "scheme keyword for a URI string"
    (is (= :file (device/device-type "file:///music")))
    (is (= :smb (device/device-type "smb://nas/Music/"))))
  (testing "nil for non-strings or schemeless input"
    (is (nil? (device/device-type nil)))
    (is (nil? (device/device-type "/plain/path")))))

(deftest supported-root?-test
  (testing "file, mtp and smb are supported"
    (is (true? (device/supported-root? "file:///x")))
    (is (true? (device/supported-root? "mtp://1:2:a/S")))
    (is (true? (device/supported-root? "smb://nas/Music/"))))
  (testing "other schemes are not"
    (is (false? (device/supported-root? "http://example.com")))
    (is (false? (device/supported-root? "/plain/path")))))

(deftest arbitrate-access?-test
  (testing "slow devices are handed out one holder at a time (dapr.device.coordinator):
            mtp:// because its driver serializes access, smb:// because a walk would
            saturate the one connection a sync also needs"
    (is (true? (device/arbitrate-access? :mtp)))
    (is (true? (device/arbitrate-access? :smb))))
  (testing "local files are fast and parallel-safe, so they are never locked"
    (is (false? (device/arbitrate-access? :file))))
  (testing "an undeclared device type defaults to being arbitrated"
    (is (true? (device/arbitrate-access? :future-device)))))

(deftest root-device-key-test
  (testing "all file:// roots share one key, each MTP device gets its own"
    (is (= "file" (device/root-device-key "file:///music")))
    (is (= "file" (device/root-device-key "file:///other/disk")))
    (is (= "mtp://1:2:a" (device/root-device-key "mtp://1:2:a/SD/Music")))
    (is (not= (device/root-device-key "mtp://1:2:a/S") (device/root-device-key "mtp://9:9:z/S"))))
  (testing "each SMB share gets its own key, independent of sub-path"
    (is (= "smb://nas/Music" (device/root-device-key "smb://nas/Music/")))
    (is (= "smb://nas/Music" (device/root-device-key "smb://nas/Music/sub/dir/")))
    (is (not= (device/root-device-key "smb://nas/Music/") (device/root-device-key "smb://nas/Photos/")))
    (is (not= (device/root-device-key "smb://nas/Music/") (device/root-device-key "smb://other/Music/"))))
  (testing "nil for unsupported or unparseable URIs"
    (is (nil? (device/root-device-key "http://x")))
    (is (nil? (device/root-device-key nil)))))
