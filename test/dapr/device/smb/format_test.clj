(ns dapr.device.smb.format-test
  (:require [clojure.test :refer [deftest is testing]]
            [dapr.device.smb.format :as smb]))

(deftest share-test
  (testing "returns the first path segment as the share name"
    (is (= "Music" (smb/share "smb://nas/Music/")))
    (is (= "Music" (smb/share "smb://nas/Music/sub/dir/")))
    (is (= "Music" (smb/share "smb://nas/Music"))))
  (testing "nil for a bare server root with no share chosen"
    (is (nil? (smb/share "smb://nas/")))
    (is (nil? (smb/share "smb://nas"))))
  (testing "nil for an unparseable URI"
    (is (nil? (smb/share "smb://n as/Music")))))

(deftest host-root?-test
  (testing "true only for an SMB server root with no share yet"
    (is (true? (smb/host-root? "smb://nas/")))
    (is (true? (smb/host-root? "smb://nas"))))
  (testing "false once a share is chosen"
    (is (false? (smb/host-root? "smb://nas/Music/")))
    (is (false? (smb/host-root? "smb://nas/Music/sub/"))))
  (testing "false for non-SMB URIs"
    (is (false? (smb/host-root? "file:///music")))
    (is (false? (smb/host-root? "mtp://1:2:a/S")))))
