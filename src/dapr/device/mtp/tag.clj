(ns dapr.device.mtp.tag
  "mtp:// audio-tag reader. melt-jfs surfaces two metadata-only NIO attribute
  views over an MTP object, neither of which pulls the file's bytes to a temp
  file (see docs/mtp-tags.md):

  - \"audio\" — the file's *own* embedded tags, parsed from a few KB of header
    read over MTP ranged reads (GetPartialObject). It recovers the real tags
    even on devices whose media scanner hasn't run or reports the filename as
    the title, so it is preferred.
  - \"mtp\" — the device's media *index* (per-object Artist/AlbumName/Name
    properties), a metadata-only USB exchange. Used as a fallback for fields the
    audio reader can't supply (e.g. an unsupported container).

  Tags are layered audio over mtp over path-derived values, per field, so the
  best available source wins for each of title/artist/album. When neither view
  reports anything (a non-track object, an unindexed file with no embedded tags)
  or the melt-jfs on the classpath predates a view (it throws
  UnsupportedOperationException), the read falls back to path derivation,
  matching dapr.device.tag's default."
  (:require [clojure.string :as str]
            [dapr.device.tag :as tag]
            [dapr.domain.tags :as tags])
  (:import (java.nio.file Files LinkOption Path)))

(defn merge-device-tags
  "Merge the device-reported attribute map `attrs` ({\"title\" .. \"artist\" ..
  \"album\" ..}, values possibly nil) over `fallback` tags. Each blank field
  keeps its fallback value. :source becomes :embedded when this layer reported
  anything, and stays :embedded if the fallback already was — so layering audio
  over mtp over path preserves the embedded marking even when the outer layer is
  blank, while a fully path-derived result stays :path so a later re-read can
  upgrade the cache entry."
  [fallback attrs]
  (let [pick      (fn [k fb] (let [v (get attrs k)] (if (str/blank? v) fb v)))
        reported? (boolean (some #(not (str/blank? (get attrs %)))
                                 ["artist" "album" "title"]))]
    {:artist (pick "artist" (:artist fallback))
     :album  (pick "album" (:album fallback))
     :title  (pick "title" (:title fallback))
     :source (if (or reported? (= :embedded (:source fallback))) :embedded :path)}))

(defn- read-view
  "Read the title/artist/album attributes of `view` (\"audio\"/\"mtp\") for
  `path`, returning the attribute map — or {} when the view is unavailable or
  the read fails. Throwable is swallowed so a native-layer failure or an older
  melt-jfs missing the view can never abort a whole scan; the track just keeps
  whatever the other layers supply (matches the file:// reader's stance)."
  [^Path path view]
  (try
    (Files/readAttributes path (str view ":title,artist,album")
                          (make-array LinkOption 0))
    (catch Throwable _ {})))

(defmethod tag/tags! :mtp [track ^Path path]
  (-> (assoc (tags/from-path track) :source :path)
      (merge-device-tags (read-view path "mtp"))
      (merge-device-tags (read-view path "audio"))))
