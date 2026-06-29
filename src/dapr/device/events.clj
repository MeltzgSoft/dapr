(ns dapr.device.events
  "Side-effecting device browser hooks. Common UI events delegate here by device
  type; device namespaces own setup, connection, chooser, and listing behavior."
  (:require [dapr.state :as state]
            [taoensso.telemere :as t]))

(defmulti open-browser!
  "Open the folder browser for a device type."
  (fn [device-type _state-atom] device-type))

(defmulti connect!
  "Advance a device-specific connection form. Returns true when browsing should
  load entries for the new cwd."
  (fn [device-type _state-atom] device-type))

(defmulti choose-device!
  "Choose a concrete device/root from a device-specific chooser. Returns true
  when browsing should load entries for the new cwd."
  (fn [device-type _state-atom _device] device-type))

(defmulti browser-entries!
  "Return entries for the current browser state."
  (fn [browser] (:device/type browser)))

(defmethod connect! :default [_ _] false)
(defmethod choose-device! :default [_ _ _] false)

(defn load-browser-entries!
  "Load the current browser entries on a background thread and store them in
  state. Device-specific failures are logged and clear the visible list."
  [state-atom]
  (future
    (try
      (let [entries (browser-entries! (:browser @state-atom))]
        (swap! state-atom state/browser-set-entries entries))
      (catch Throwable t
        (swap! state-atom state/browser-set-entries [])
        (t/log! {:level :error :error t :msg (str "Browse failed: " (.getMessage t))})))))
