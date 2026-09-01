(ns dapr.device.fs
  "Filesystem extension points by device type. Device-specific namespaces provide
  methods for resolving root URIs and listing browser folders; the sync walker in
  dapr.fs.nio remains provider-generic once it has a Path."
  (:require [clojure.string :as str]
            [dapr.device.format :as device])
  (:import (java.nio.file DirectoryStream Files LinkOption Path)))

(defmulti root-path!
  "Resolve a persisted root URI string to a java.nio.file.Path."
  device/device-type)

(defmulti dir-children!
  "Immediate sub-directories directly under `uri`, as browser entry maps."
  device/device-type)

(defmulti available?
  "True when the device/share backing root `uri-str` is currently reachable and
  the root resolves to an existing directory. Probes I/O (a local stat, an SMB
  connect, an MTP open), so it may block and is meant to run off the UI thread;
  it must never throw — an unreachable or erroring probe returns false."
  device/device-type)

(defmulti with-access!
  "Run `f` inside any connection/session lifecycle required by `device`.

  The coordinator decides *who* may use a device, then calls this filesystem
  hook so the backend acquires native/network resources only for that use. New
  device types default to no lifecycle wrapper."
  (fn [device _f] (:type device)))

(defmulti close!
  "Release process-global filesystem resources owned by `device-type`. This is a
  shutdown safety net; backends with scoped access normally have nothing left
  open by the time it runs."
  identity)

(defmethod root-path! :default [uri]
  (throw (ex-info (str "Unsupported root URI: " uri) {:uri uri})))

(defmethod dir-children! :default [uri]
  (throw (ex-info (str "Unsupported browse URI: " uri) {:uri uri})))

(defmethod available? :default [_] false)
(defmethod with-access! :default [_ f] (f))
(defmethod close! :default [_] nil)

(defn directory-children!
  "List child paths under `root`, keeping only entries accepted by `keep?` and
  formatting them as stable browser rows. Directory URIs are normalized with a
  trailing slash because some NIO providers use it to distinguish folders."
  [^Path root keep?]
  (with-open [^DirectoryStream stream (Files/newDirectoryStream root)]
    (->> (iterator-seq (.iterator stream))
         (filter keep?)
         (map (fn [^Path p]
                (let [u (str (.toUri p))]
                  {:name (str (.getFileName p))
                   :uri  (if (str/ends-with? u "/") u (str u "/"))
                   :dir? true})))
         (sort-by :name)
         (vec))))

(defn directory?
  "Provider-neutral directory predicate for browser listings."
  [^Path p]
  (Files/isDirectory p (make-array LinkOption 0)))
