(ns dapr.device.fs-test
  (:require [clojure.test :refer [deftest is testing]]
            [dapr.device.fs :as dfs]
            ;; loaded for its :file available? method registration
            [dapr.device.file.fs])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(deftest available?-test
  (testing "file:// is available when the root resolves to an existing directory"
    (let [dir (Files/createTempDirectory "dapr-avail" (make-array FileAttribute 0))
          uri (str (.toUri dir))]
      (try
        (is (true? (dfs/available? uri)))
        (testing "and unavailable once the directory is gone"
          (Files/delete dir)
          (is (false? (dfs/available? uri))))
        (finally
          (Files/deleteIfExists dir)))))
  (testing "an unsupported / unparseable scheme is unavailable (never throws)"
    (is (false? (dfs/available? "ftp://example/x/")))
    (is (false? (dfs/available? "not a uri")))))
