(ns dapr.device.mtp.fs-test
  "Unit coverage for the parts of the MTP device layer that must decide *whether*
  to reach for the hardware. The reaching itself is covered by
  dapr.device.mtp.fs-integration-test, which needs a real device."
  (:require [clojure.test :refer [deftest is testing]]
            [dapr.device.mtp.fs :as mtp-fs]))

(deftest close-only-releases-what-was-opened-test
  (let [closes (atom 0)]
    (with-redefs-fn {#'mtp-fs/close-bridge! (fn [] (swap! closes inc))}
      (fn []
        (reset! @#'mtp-fs/bridge-touched? false)

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
