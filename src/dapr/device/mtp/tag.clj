(ns dapr.device.mtp.tag
  "mtp:// audio-tag reader, backed by the device's own media index rather than
  the file's bytes. MTP exposes per-object Artist/AlbumName/Name properties,
  which melt-jfs surfaces as the provider's \"mtp\" attribute view — reading
  them is a metadata-only USB exchange of a few small transactions, where
  reading the file itself would pull the whole object to a temp file (see
  docs/mtp-tags.md). Falls back per field to the path-derived value when the
  device leaves a field blank; when the device reports nothing (a file its
  media scanner hasn't indexed, a non-track object) or the melt-jfs on the
  classpath predates the view (0.1.1 throws UnsupportedOperationException),
  the whole read falls back to path derivation, matching dapr.device.tag's
  default."
  (:require [clojure.string :as str]
            [dapr.device.tag :as tag]
            [dapr.domain.tags :as tags])
  (:import (java.nio.file Files LinkOption Path)))

(defn merge-device-tags
  "Merge the device-reported attribute map `attrs` ({\"title\" .. \"artist\" ..
  \"album\" ..}, values possibly nil) over path-derived `fallback` tags. Each
  blank field keeps its fallback value. :source is :embedded when the device
  reported anything — its media index is built from the files' embedded tags —
  and :path otherwise, so a later re-read can still upgrade the entry."
  [fallback attrs]
  (let [pick      (fn [k fb] (let [v (get attrs k)] (if (str/blank? v) fb v)))
        reported? (boolean (some #(not (str/blank? (get attrs %)))
                                 ["artist" "album" "title"]))]
    {:artist (pick "artist" (:artist fallback))
     :album  (pick "album" (:album fallback))
     :title  (pick "title" (:title fallback))
     :source (if reported? :embedded :path)}))

(defmethod tag/tags! :mtp [track ^Path path]
  (let [fallback (assoc (tags/from-path track) :source :path)]
    (try
      (merge-device-tags fallback
                         (Files/readAttributes path "mtp:title,artist,album"
                                               (make-array LinkOption 0)))
      ;; Throwable so a native-layer failure can never abort a whole scan; an
      ;; unreadable track just keeps its path-derived tags (matches the file://
      ;; reader's stance).
      (catch Throwable _ fallback))))
