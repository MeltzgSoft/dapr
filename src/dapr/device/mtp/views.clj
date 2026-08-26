(ns dapr.device.mtp.views
  (:require [dapr.device.format :as device-format]
            [dapr.device.views :as device-views]
            [dapr.ui.html :as html]))

(defmethod device-views/library-menu-item :mtp [device-type]
  (device-views/menu-item device-type))

(defn- device-chooser
  "Choose which connected MTP device to browse. A device that would mix with the
  library's existing MTP root (`allowed`) is disabled."
  [allowed {:keys [devices loading?]}]
  [:div.stack
   [:p.muted "Select an MTP device"]
   [:div.entry-list
    (cond
      loading?      [:p.muted "Detecting devices…"]
      (seq devices) (map (fn [d]
                           [:button.btn
                            {:hx-post   (html/url "/actions/browser/device"
                                                  {:uri (:uri d) :name (:name d)})
                             :hx-target "#browser-panel"
                             :hx-swap   "outerHTML"
                             :disabled  (and (some? allowed)
                                             (not= allowed (device-format/root-device-key (:uri d))))}
                            (str "📱  " (:name d))])
                         devices)
      :else         [:p.muted "(no MTP devices found)"])]])

(defmethod device-views/browser-content [:mtp :device] [allowed browser]
  (device-chooser allowed browser))

(defmethod device-views/browser-content [:mtp :browse] [_ browser]
  (device-views/folder-browser browser))
