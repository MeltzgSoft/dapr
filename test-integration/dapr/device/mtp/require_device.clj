(ns dapr.device.mtp.require-device
  "Whether an absent MTP device is a skip or a failure.

  The MTP integration tests self-skip when no device is attached, which is what
  makes `clojure -M:integration` runnable anywhere. On the device runners that
  same behaviour is a trap: a job that acquires the device lock, hot-plugs the
  controller and then quietly skips every test reports success while proving
  nothing. Set DAPR_REQUIRE_DEVICE=1 there and an absent device fails instead --
  the same job melt-jfs's `-PrequireDevice` does.

  Scope is deliberate: this covers DEVICE ABSENCE only. Skips for what a
  particular device cannot do (no partial-read support, no writable storage) stay
  skips even when this is set, because failing them would red-line CI for an
  attached, working device that simply lacks a capability."
  (:require [clojure.string :as str]
            [clojure.test :refer [is]]))

(def required?
  "True when DAPR_REQUIRE_DEVICE asks for a device to be present."
  (contains? #{"1" "true" "yes"}
             (some-> (System/getenv "DAPR_REQUIRE_DEVICE") str/trim str/lower-case)))

(defn skip-or-fail
  "Report `why` there is no device: a printed skip normally, a test failure when
  DAPR_REQUIRE_DEVICE is set. Call inside a deftest -- the failure is an
  assertion, so it lands on the running test."
  [what why]
  (if required?
    (is false (str what ": " why
                   ". DAPR_REQUIRE_DEVICE is set, so this is a failure rather than"
                   " a skip -- the job asked for hardware and did not get it."))
    (println (str "  (skipping " what " — " why ")"))))
