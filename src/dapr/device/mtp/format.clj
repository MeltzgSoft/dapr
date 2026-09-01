(ns dapr.device.mtp.format
  (:require [dapr.device.format :as device]
            [dapr.device.mtp.fs :as mtp-fs]))

(defmethod device/supported? :mtp [_] true)

(defmethod device/root-device-key :mtp [uri]
  (try
    (str "mtp://" (.getAuthority (java.net.URI. ^String uri)))
    (catch java.net.URISyntaxException _ nil)))

;; The strong case: melt-jfs serializes calls per MTP device (one native session,
;; reached through a singleton bridge), so concurrent users cannot overlap at all —
;; they queue in the driver, where a user's sync has no way to jump the queue ahead
;; of a running scan. Arbitrating here is what makes that queue preemptible.
(defmethod device/arbitrate-access? :mtp [_] true)

(defmethod device/with-access! :mtp [_ f]
  (mtp-fs/with-session! f))

(defmethod device/selectable-root? :mtp [uri]
  (boolean (device/scheme uri)))

(defmethod device/library-menu-label :mtp [_]
  "📱  MTP device (mtp://)")

(defmethod device/browser-root-label :mtp [{:keys [device]}]
  (str "📱 " (:name device)))

(defmethod device/browser-current-location :mtp [{:keys [cwd]}]
  (or cwd "Pick a location to start"))
