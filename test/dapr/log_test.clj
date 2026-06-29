(ns dapr.log-test
  "Unit coverage for the pure parts of dapr.log: log-dir resolution, file naming,
  and signal formatting. The Telemere handler wiring (configure!/set-dir!) is
  side-effecting and exercised manually."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [dapr.log :as log])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)
           (java.time Instant)))

(defn- temp-dir []
  (str (Files/createTempDirectory "dapr-log-test" (make-array FileAttribute 0))))

(deftest log-dir-test
  (testing "uses :log-dir when set, else the system temp dir"
    (is (= "/var/log/dapr" (log/log-dir {:log-dir "/var/log/dapr"})))
    (is (= (System/getProperty "java.io.tmpdir") (log/log-dir {})))
    (is (= (System/getProperty "java.io.tmpdir") (log/log-dir nil)))))

(deftest next-log-file-test
  (let [dir (temp-dir)]
    (testing "starts at dapr.0.log in an empty dir"
      (is (= (.getPath (io/file dir "dapr.0.log")) (log/next-log-file dir))))
    (testing "skips existing logs to the next free integer"
      (spit (io/file dir "dapr.0.log") "")
      (spit (io/file dir "dapr.1.log") "")
      (is (= (.getPath (io/file dir "dapr.2.log")) (log/next-log-file dir))))
    (testing "creates the directory when missing"
      (let [sub (str (io/file dir "sub" "deeper"))]
        (is (= (.getPath (io/file sub "dapr.0.log")) (log/next-log-file sub)))
        (is (.isDirectory (io/file sub)))))))

(deftest signal->line-test
  (testing "formats HH:mm:ss LEVEL message, forcing a delayed message"
    (let [line (log/signal->line {:inst  (Instant/parse "2026-06-29T13:45:09Z")
                                  :level :warn
                                  :msg_  (delay "disk almost full")})]
      (is (re-find #"^\d\d:\d\d:\d\d WARN  disk almost full$" line))))
  (testing "defaults a missing level to INFO and accepts a plain-string message"
    (is (re-find #" INFO  hi$" (log/signal->line {:inst (Instant/now) :level nil :msg_ "hi"})))))
