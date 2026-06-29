(ns dapr.device.mtp.fs
  "MTP device enumeration via melt-jfs. This is the ONLY namespace that touches
  melt-jfs classes; device discovery runs on a background thread off the browser
  (see dapr.device.mtp.events/open-browser!), and any failure degrades to an empty
  device list so tests and lint never have to reach the native libraries.

  Device discovery needs native libmtp/WPD access at runtime, enabled by the JVM
  option --enable-native-access=ALL-UNNAMED (set in the :run and :dev aliases).
  File copy/delete/scan against an mtp:// URI go through the generic NIO code in
  dapr.fs.nio; only device discovery and FileSystem opening are here."
  (:require [clojure.string :as str]
            [dapr.device.fs :as dfs])
  (:import (java.net URI)
           (java.nio.file FileSystemNotFoundException FileSystems Files LinkOption Paths)
           (org.meltzg.fs.mtp MTPDeviceBridge)))

(defn- ensure-filesystem!
  "Ensure the MTP filesystem addressed by `uri` is open."
  [^URI uri]
  (try
    (FileSystems/getFileSystem uri)
    (catch FileSystemNotFoundException _
      (FileSystems/newFileSystem uri {}))))

(defmethod dfs/root-path! :mtp [uri-str]
  (let [uri (URI. ^String uri-str)]
    (ensure-filesystem! uri)
    (Paths/get uri)))

(defmethod dfs/dir-children! :mtp [uri]
  (dfs/directory-children! (dfs/root-path! uri) dfs/directory?))

(defmethod dfs/available? :mtp [uri-str]
  ;; Opening the MTP filesystem for a disconnected device (or with no native MTP
  ;; access) throws; catch Throwable so a native/linkage failure degrades to
  ;; unavailable rather than crashing the probe.
  (try
    (Files/isDirectory (dfs/root-path! uri-str) (make-array LinkOption 0))
    (catch Throwable _ false)))

(defn- device-label
  "A non-blank display name for a device. libmtp often returns an empty
  friendlyName (the user never named the device), so fall back to the
  description, then the manufacturer, then the raw id."
  [info id-str]
  (or (->> [(.friendlyName info) (.description info) (.manufacturer info)]
           (remove str/blank?)
           (first))
      id-str))

(defn devices!
  "Detect connected MTP devices and return a vector of endpoints
  {:id <vendor:product:serial> :name <display-name> :uri \"mtp://<id>/\"}."
  []
  (let [bridge (MTPDeviceBridge/getInstance)]
    (->> (.getDeviceInfo bridge)
         (mapv (fn [entry]
                 (let [id-str (.toString ^Object (key entry))
                       info   (val entry)]
                   {:id   id-str
                    :name (device-label info id-str)
                    :uri  (str "mtp://" id-str "/")}))))))
