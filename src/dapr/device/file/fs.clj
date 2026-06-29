(ns dapr.device.file.fs
  "Local file:// device support."
  (:require [dapr.device.fs :as dfs]
            [dapr.fs.paths :as paths])
  (:import (java.io File)
           (java.net URI)
           (java.nio.file Files LinkOption Paths)))

(defmethod dfs/root-path! :file [uri-str]
  (Paths/get (URI. ^String uri-str)))

(defmethod dfs/dir-children! :file [uri]
  (dfs/directory-children! (dfs/root-path! uri) dfs/directory?))

(defmethod dfs/available? :file [uri-str]
  (try
    (Files/isDirectory (dfs/root-path! uri-str) (make-array LinkOption 0))
    (catch Exception _ false)))

(defn local-places!
  "Top-level local browsing locations: each filesystem root plus the user's home
  directory, as {:name :uri :dir? true} entries."
  []
  (let [home  (paths/user-home)
        entry (fn [^File f label] {:name label :uri (str (.toURI f)) :dir? true})]
    (-> (mapv (fn [^File r] (entry r (str "Computer " (.getPath r))))
              (File/listRoots))
        (conj (entry home (str "Home " (.getPath home)))))))
