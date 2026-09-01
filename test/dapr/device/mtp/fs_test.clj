(ns dapr.device.mtp.fs-test
  "Unit coverage for the parts of the MTP device layer that must decide *whether*
  to reach for the hardware. The reaching itself is covered by
  dapr.device.mtp.fs-integration-test, which needs a real device."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [dapr.device.mtp.fs :as mtp-fs]))

(use-fixtures :each
  (fn [f]
    (reset! @#'mtp-fs/bridge-touched? false)
    (reset! @#'mtp-fs/session-users 0)
    (try
      (f)
      (finally
        (reset! @#'mtp-fs/bridge-touched? false)
        (reset! @#'mtp-fs/session-users 0)))))

(deftest close-only-releases-what-was-opened-test
  (let [closes (atom 0)]
    (with-redefs-fn {#'mtp-fs/close-bridge! (fn [] (swap! closes inc))}
      (fn []
        (testing "halting without ever having touched MTP closes nothing"
          ;; MTPDeviceBridge/getInstance *creates* the bridge — detecting devices
          ;; and opening a session to each — so closing unconditionally reaches for
          ;; the hardware just to let go of it. With a device attached that hung the
          ;; system halt, and with it (reset) in the REPL.
          (mtp-fs/close!)
          (is (zero? @closes)))

        (testing "having opened a device session, halting releases it"
          (#'mtp-fs/mark-touched!)
          (mtp-fs/close!)
          (is (= 1 @closes)))

        (testing "and only once — a second halt has nothing left to release"
          (mtp-fs/close!)
          (is (= 1 @closes)))))))

(deftest session-lifecycle-test
  (let [opens  (atom 0)
        closes (atom 0)
        opened (fn []
                 (#'mtp-fs/mark-touched!)
                 (swap! opens inc))]
    (with-redefs-fn {#'mtp-fs/open-bridge!  opened
                     #'mtp-fs/close-bridge! (fn [] (swap! closes inc))}
      (fn []
        (testing "one operation opens on entry and closes on exit"
          (is (= :result (mtp-fs/with-session! (fn [] :result))))
          (is (= [1 1] [@opens @closes])))

        (testing "nested operations share the outer session"
          (is (= :nested
                 (mtp-fs/with-session!
                   #(mtp-fs/with-session! (fn [] :nested)))))
          (is (= [2 2] [@opens @closes])))

        (testing "an exception still releases the session"
          (is (thrown-with-msg? RuntimeException #"gone"
                                (mtp-fs/with-session!
                                  #(throw (RuntimeException. "gone")))))
          (is (= [3 3] [@opens @closes])))))))

(deftest concurrent-sessions-close-after-last-user-test
  (let [opens        (atom 0)
        closes       (atom 0)
        first-in     (promise)
        second-in    (promise)
        release      (promise)
        opened       (fn []
                       (#'mtp-fs/mark-touched!)
                       (swap! opens inc))]
    (with-redefs-fn {#'mtp-fs/open-bridge!  opened
                     #'mtp-fs/close-bridge! (fn [] (swap! closes inc))}
      (fn []
        (let [first  (future (mtp-fs/with-session!
                               #(do (deliver first-in true) @release :first)))
              _      @first-in
              second (future (mtp-fs/with-session!
                               #(do (deliver second-in true) @release :second)))]
          @second-in
          (testing "overlapping operations open only one bridge and keep it live"
            (is (= 1 @opens))
            (is (zero? @closes)))
          (deliver release true)
          (is (= :first @first))
          (is (= :second @second))
          (testing "the final user closes the shared bridge"
            (is (= 1 @closes))))))))
