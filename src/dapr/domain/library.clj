(ns dapr.domain.library
  "Pure helpers for libraries and tracks.

  A *library* is {:id <string> :name <string> :roots [<uri-string> ...]} where
  each root addresses a directory on a java.nio filesystem; supported URI
  schemes are \"file\", \"mtp\" and \"smb\". A *track* is one audio file discovered under
  a library's roots: {:key [artist album title size rel] :name :size :mtime :root
  :rel} plus its :artist/:album/:title tags (see dapr.device.tag). A
  *catalog* is a map of track :key -> track. Track identity is
  [artist album title size rel] — the tags and byte size identify the track, with
  the root-relative path appended only as a tie-breaker so two files that share
  tags+size stay distinct. The root is excluded from :rel so the same relative
  path matches across roots/devices (e.g. source ROOT1/foo/bar.mp3 matches sink
  SD/foo/bar.mp3). Nothing here performs I/O."
  (:require [clojure.string :as str]
            [dapr.device.file.format]
            [dapr.device.format :as device]
            [dapr.device.mtp.format]
            [dapr.device.smb.format]))

(def default-audio-extensions
  "File extensions (lowercase, no dot) treated as tracks by default."
  #{"mp3" "flac" "m4a" "aac" "ogg" "opus" "wav" "wma"})

(defn roots-device-key
  "The device-key shared by `roots`, or nil when there are none. Roots are kept to
  one device (see root-addable?), so a consistent set has exactly one such key."
  [roots]
  (some-> (seq roots) first device/root-device-key))

(defn root-addable?
  "True when `uri` may be added alongside `roots`: it must use a supported scheme,
  point inside a share (not a bare SMB host), and live on the same device as the
  roots already present."
  [roots uri]
  (and (device/supported-root? uri)
       (device/selectable-root? uri)
       (let [existing (roots-device-key roots)]
         (or (nil? existing) (= existing (device/root-device-key uri))))))

(defn library-valid?
  "True when `library` has a non-blank name and at least one root, all roots using
  a supported scheme, pointing inside a share (not a bare SMB host), and living on
  a single device."
  [{:keys [name roots]}]
  (boolean (and (string? name)
                (not (str/blank? name))
                (seq roots)
                (every? device/supported-root? roots)
                (every? device/selectable-root? roots)
                (apply = (map device/root-device-key roots)))))

(defn extension
  "Lowercased extension of `filename` (without the dot), or nil."
  [filename]
  (when (string? filename)
    (let [i (str/last-index-of filename ".")]
      (when (and i (< i (dec (count filename))))
        (str/lower-case (subs filename (inc i)))))))

(defn audio-file?
  "True when `filename` has an audio extension."
  ([filename] (audio-file? filename default-audio-extensions))
  ([filename extensions] (contains? extensions (extension filename))))

(defn track-key
  "Identity of a track: [artist album title size rel] — its tags and byte size,
  with the root-relative path appended only as a tie-breaker (so two files that
  share tags+size stay distinct). The root is excluded so the same relative path
  matches across roots/devices."
  [{:keys [artist album title size rel]}]
  [artist album title size rel])

(defn track-total-size
  "Total bytes of a seq of tracks (e.g. the vals of a catalog)."
  [tracks]
  (reduce + 0 (map :size tracks)))

(defn initial-selection
  "Keys to pre-select when a sink is chosen: every track currently on the sink."
  [sink-catalog]
  (set (keys sink-catalog)))
