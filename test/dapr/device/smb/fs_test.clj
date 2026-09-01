(ns dapr.device.smb.fs-test
  "Unit coverage for SMB's scoped access lease. Real FileSystem open/close behavior
  is covered by dapr.device.smb.fs-integration-test against Samba."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [dapr.device.fs :as device-fs]
            [dapr.device.smb.fs :as smb-fs]))

(use-fixtures :each
  (fn [f]
    (reset! @#'smb-fs/session-users 0)
    (reset! @#'smb-fs/filesystems {})
    (try
      (f)
      (finally
        (reset! @#'smb-fs/session-users 0)
        (reset! @#'smb-fs/filesystems {})))))

(deftest session-lifecycle-test
  (let [closes (atom 0)]
    (with-redefs [smb-fs/close-all! #(swap! closes inc)]
      (testing "one operation closes cached connections on exit"
        (is (= :result (smb-fs/with-session! (fn [] :result))))
        (is (= 1 @closes)))

      (testing "nested operations share the outer session"
        (is (= :nested
               (smb-fs/with-session!
                 #(smb-fs/with-session! (fn [] :nested)))))
        (is (= 2 @closes)))

      (testing "the filesystem access multimethod delegates to the SMB lease"
        (is (= :dispatched
               (device-fs/with-access! {:type :smb :key "smb://nas/Music"}
                 (fn [] :dispatched))))
        (is (= 3 @closes)))

      (testing "an exception still closes cached connections"
        (is (thrown-with-msg? RuntimeException #"gone"
                              (smb-fs/with-session!
                                #(throw (RuntimeException. "gone")))))
        (is (= 4 @closes))))))

(deftest concurrent-sessions-close-after-last-user-test
  (let [closes    (atom 0)
        first-in  (promise)
        second-in (promise)
        release   (promise)]
    (with-redefs [smb-fs/close-all! #(swap! closes inc)]
      (let [first  (future (smb-fs/with-session!
                             #(do (deliver first-in true) @release :first)))
            _      @first-in
            second (future (smb-fs/with-session!
                             #(do (deliver second-in true) @release :second)))]
        @second-in
        (testing "overlapping operations keep the shared filesystems open"
          (is (zero? @closes)))
        (deliver release true)
        (is (= :first @first))
        (is (= :second @second))
        (testing "the final user closes the shared filesystems"
          (is (= 1 @closes)))))))
