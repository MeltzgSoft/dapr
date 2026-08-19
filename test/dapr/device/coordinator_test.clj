(ns dapr.device.coordinator-test
  "Unit coverage for device arbitration. Threads are real (the whole point is
  cross-thread hand-off), but every wait is bounded by a deadline so a regression
  fails the test rather than hanging the suite."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [dapr.device.coordinator :as coord]))

(use-fixtures :each (fn [f] (coord/reset-locks!) (f) (coord/reset-locks!)))

(def ^:private timeout-ms
  "Upper bound on any hand-off in these tests; generous enough for a loaded CI box,
  short enough that a deadlock fails fast."
  5000)

(defn- await!
  "Block until `pred` holds, up to timeout-ms. Returns true if it held."
  [pred]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (cond
        (pred)                                    true
        (> (System/currentTimeMillis) deadline)   false
        :else                                     (do (Thread/sleep 5) (recur))))))

(def ^:private local
  "A local library's device — declared parallel-safe by dapr.device.file.format."
  (coord/library-device {:roots ["file:///music/"]}))

(defn- mtp-device [authority]
  (coord/library-device {:roots [(str "mtp://" authority "/SD/Music")]}))

(deftest library-device-test
  (testing "a library's device descriptor comes from its roots"
    (is (= {:key "file" :type :file} local))
    (is (= {:key "mtp://1:2:a" :type :mtp} (mtp-device "1:2:a")))
    (is (= {:key "smb://nas/Music" :type :smb}
           (coord/library-device {:roots ["smb://nas/Music/sub/"]}))))
  (testing "a library with no roots names no device"
    (is (nil? (coord/library-device {:roots []})))))

(deftest parallel-safe-device-test
  (testing "a device type that declares access parallel-safe is never locked"
    (is (false? (coord/coordinated? local)))
    (is (= :ran (coord/with-device! local (fn [] :ran))))
    (is (false? (coord/queued? local))))

  (testing "two threads hold a local library's device at the same time"
    (let [in-a  (promise)
          go    (promise)
          fut   (future (coord/with-device! local (fn [] (deliver in-a true) @go :a)))]
      (is (await! #(realized? in-a)))
      ;; b must not block behind a — file access is parallel-safe.
      (is (= :b (coord/with-device! local (fn [] :b))))
      (deliver go true)
      (is (= :a @fut))))

  (testing "a device with no key (an unknown or root-less library) is not locked"
    (is (false? (coord/coordinated? nil)))
    (is (false? (coord/coordinated? {:key nil :type :mtp})))
    (is (= :ran (coord/with-device! nil (fn [] :ran))))))

(deftest foreground-preempts-background-test
  (testing "a background holder sees queued? and yields the device to a waiter"
    (let [dev      (mtp-device "1:2:a")
          holding  (promise)
          yielded  (atom false)
          ;; The background worker holds the device and polls queued? the way the
          ;; refresher does at directory boundaries.
          bg       (future
                     (coord/with-device! dev
                       (fn []
                         (deliver holding true)
                         (await! #(coord/queued? dev))
                         (reset! yielded (coord/queued? dev))
                         :bg)))]
      (is (await! #(realized? holding)))
      (is (false? (coord/queued? dev)) "nobody waiting yet")
      (let [entered (promise)
            fg      (future (coord/with-device! dev (fn [] (deliver entered true) :fg)))]
        (is (= :bg @bg) "the background holder observed the waiter and returned")
        (is (true? @yielded))
        (is (= :fg @fg))
        (is (await! #(realized? entered)) "the foreground op got the device")))))

(deftest multi-device-no-deadlock-test
  (testing "two threads locking the same pair in opposite order both complete"
    (let [a   (mtp-device "1:2:a")
          b   (coord/library-device {:roots ["smb://nas/Music/"]})
          ;; Both hold both devices for a beat, so an order-dependent
          ;; implementation would deadlock rather than merely interleave.
          run (fn [ds] (future (coord/with-devices! ds (fn [] (Thread/sleep 20) ds))))
          f1  (run [a b])
          f2  (run [b a])]
      (is (= [a b] (deref f1 timeout-ms ::timeout)))
      (is (= [b a] (deref f2 timeout-ms ::timeout)))))

  (testing "source and sink on one device take a single (reentrant) lock"
    (let [dev (coord/library-device {:roots ["smb://nas/Music/"]})]
      (is (= :ran (coord/with-devices! [dev dev] (fn [] :ran))))))

  (testing "parallel-safe devices are skipped, so a local-only sync never blocks"
    (let [held (promise)
          go   (promise)
          fut  (future (coord/with-devices! [local] (fn [] (deliver held true) @go :first)))]
      (is (await! #(realized? held)))
      (is (= :second (coord/with-devices! [local] (fn [] :second))))
      (deliver go true)
      (is (= :first @fut)))))
