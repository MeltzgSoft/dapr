(ns dapr.device.mtp.events
  (:require [dapr.device.events :as device-events]
            [dapr.device.fs :as device-fs]
            [dapr.device.mtp.fs :as mtp-fs]
            [dapr.state :as state]))

(defmethod device-events/open-browser! :mtp [_ state-atom]
  (swap! state-atom state/set-browser
         {:phase :device :device/type :mtp :device nil :devices []
          :cwd nil :crumbs [] :entries [] :loading? true})
  (future
    (let [devices (try (vec (mtp-fs/devices!))
                       (catch Throwable _ []))]
      (swap! state-atom state/browser-set-devices devices))))

(defmethod device-events/choose-device! :mtp [_ state-atom {:keys [name uri]}]
  (swap! state-atom state/browser-start-browse
         {:device {:name name :uri uri} :cwd uri})
  true)

(defmethod device-events/browser-entries! :mtp [{:keys [cwd]}]
  (device-fs/dir-children! cwd))
