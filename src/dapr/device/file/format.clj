(ns dapr.device.file.format
  (:require [dapr.device.format :as device]))

(defmethod device/supported? :file [_] true)

(defmethod device/root-device-key :file [_]
  "file")

;; Local disk is fast and parallel-safe, so there is no queue worth arbitrating —
;; and *every* local library shares the one "file" device key, so a lock here would
;; serialize unrelated local libraries behind each other for no gain.
(defmethod device/arbitrate-access? :file [_] false)

(defmethod device/selectable-root? :file [uri]
  (boolean (device/scheme uri)))

(defmethod device/library-menu-label :file [_]
  "💻  Local files (file://)")

(defmethod device/browser-root-label :file [_]
  "Places")

(defmethod device/browser-current-location :file [{:keys [cwd]}]
  (or cwd "Pick a location to start"))
