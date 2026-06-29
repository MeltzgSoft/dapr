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

(defmethod root-path! :default [uri]
  (throw (ex-info (str "Unsupported root URI: " uri) {:uri uri})))

(defmethod dir-children! :default [uri]
  (throw (ex-info (str "Unsupported browse URI: " uri) {:uri uri})))

(defmethod available? :default [_] false)

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
