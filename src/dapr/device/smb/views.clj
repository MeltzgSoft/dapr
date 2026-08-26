(ns dapr.device.smb.views
  (:require [dapr.device.views :as device-views]))

(defmethod device-views/library-menu-item :smb [device-type]
  (device-views/menu-item device-type))

(def ^:private field-attrs
  "Each connect-form field posts itself into state as it is edited, and asks for no
  swap in return: the form must not be re-rendered under the user's cursor, and
  Connect needs the values to already be in state when it runs."
  {;; `input`, not `keyup`: a password manager filling this field fires no
   ;; keystroke, and Connect reads these values back out of state.
   :hx-trigger "input changed delay:400ms, change"
   :hx-swap    "none"})

(defn- connect-form
  "Enter the share URL and optional credentials, then connect. A blank username
  connects as guest; credentials entered here are saved to the OS keystore."
  [{:keys [url username password workgroup loading?]}]
  [:div.stack
   [:p.muted "Connect to an SMB server or share"]
   [:input (merge field-attrs
                  {:type        "text"
                   :name        "value"
                   :value       (or url "")
                   :placeholder "smb://host/  (lists shares)  or  smb://host/share/"
                   :hx-post     "/actions/browser/field?field=url"})]
   [:div.row
    [:input.grow (merge field-attrs
                        {:type        "text"
                         :name        "value"
                         :value       (or username "")
                         :placeholder "Username (blank = guest)"
                         :hx-post     "/actions/browser/field?field=username"})]
    [:input.grow (merge field-attrs
                        {:type        "text"
                         :name        "value"
                         :value       (or workgroup "")
                         :placeholder "Workgroup (optional)"
                         :hx-post     "/actions/browser/field?field=workgroup"})]]
   [:input (merge field-attrs
                  {:type        "password"
                   :name        "value"
                   :value       (or password "")
                   :placeholder "Password"
                   :hx-post     "/actions/browser/field?field=password"})]
   [:p.muted "The password is stored in your OS keystore, not in the library file."]
   [:button.primary
    {:hx-post   "/actions/browser/connect"
     :hx-target "#browser-panel"
     :hx-swap   "outerHTML"
     :disabled  (boolean loading?)}
    (if loading? "Connecting…" "Connect")]])

(defmethod device-views/browser-content [:smb :connect] [_ browser]
  (connect-form browser))

(defmethod device-views/browser-content [:smb :browse] [_ browser]
  (device-views/folder-browser browser))
