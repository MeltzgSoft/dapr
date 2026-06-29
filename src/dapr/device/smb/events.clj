(ns dapr.device.smb.events
  (:require [clojure.string :as str]
            [dapr.device.events :as device-events]
            [dapr.device.fs :as device-fs]
            [dapr.device.smb.fs :as smb-fs]
            [dapr.fs.credentials :as credentials]
            [dapr.state :as state]
            [taoensso.telemere :as t]))

(defn- normalize-url
  "Trim an entered SMB URL and ensure it ends with '/', since smb-nio treats a
  trailing slash as marking a directory."
  [url]
  (let [u (str/trim (or url ""))]
    (cond-> u (not (str/ends-with? u "/")) (str "/"))))

(def ^:private url-re
  #"(?i)^smb://[^/]+")

(defn- save-credentials!
  "Persist the connect form's credentials for the share's host to the OS keystore.
  A blank username means guest access, so nothing is stored."
  [url {:keys [username] :as creds}]
  (when-not (str/blank? username)
    (try
      (let [host (smb-fs/host-of url)]
        (credentials/save! host creds)
        (t/log! (format "Saved SMB credentials for %s to the OS keystore." host)))
      (catch Throwable t
        (t/log! {:level :error :error t
                 :msg (str "Couldn't save SMB credentials (is an OS keystore available?): "
                           (.getMessage t))})))))

(defmethod device-events/open-browser! :smb [_ state-atom]
  (swap! state-atom state/set-browser
         {:phase :connect :device/type :smb :device nil :devices []
          :url "smb://" :username "" :password "" :workgroup ""
          :cwd nil :crumbs [] :entries [] :loading? false}))

(defmethod device-events/connect! :smb [_ state-atom]
  (let [{:keys [url username password workgroup]} (:browser @state-atom)
        url (normalize-url url)]
    (if (re-find url-re url)
      (do
        (save-credentials! url
                           {:username username :password password :workgroup workgroup})
        (swap! state-atom state/browser-start-browse
               {:device {:name url :uri url} :cwd url})
        true)
      (do
        (t/log! "Enter an SMB URL like smb://host/ (to list shares) or smb://host/share/ before connecting.")
        false))))

(defmethod device-events/browser-entries! :smb [{:keys [cwd]}]
  (device-fs/dir-children! cwd))
