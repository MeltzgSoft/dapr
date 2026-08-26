(ns dapr.device.file.views
  (:require [dapr.device.views :as device-views]))

(defmethod device-views/library-menu-item :file [device-type]
  (device-views/menu-item device-type))

(defmethod device-views/browser-content [:file :browse] [_ browser]
  (device-views/folder-browser browser))
