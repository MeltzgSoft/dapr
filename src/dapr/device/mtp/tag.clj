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

  Both views carry title/artist/album/genre (strings) and trackNumber/
  durationMillis (numbers); the \"audio\" view additionally exposes discNumber
  (the mtp index has no disc field). read-view requests \"*\", so each view
  returns whatever it supports and a field one view lacks just reads as absent.
  Tags are layered audio over mtp over path-derived values, per field, so the
  best available source wins for each. When neither view reports
  anything (a non-track object, an unindexed file with no embedded tags) or the
  melt-jfs on the classpath predates a view (it throws
  UnsupportedOperationException), the read falls back to path derivation,
  matching dapr.device.tag's default (path can only supply artist/album/title)."
  (:require [clojure.string :as str]
            [dapr.device.tag :as tag]
            [dapr.domain.tags :as tags])
  (:import (java.nio.file Files LinkOption Path)))

(def ^:private fields
  "Canonical tag key <- device-view attribute name, in the order the views expose
  them. The first three are the identity fields path derivation can also supply;
  the rest have no path fallback."
  [[:title "title"]
   [:artist "artist"]
   [:album "album"]
   [:genre "genre"]
   [:track-number "trackNumber"]
   [:disc-number "discNumber"]
   [:duration-millis "durationMillis"]])

(def ^:private identity-attrs
  "The view attributes whose presence marks a track's tags as :embedded. Only the
  identity fields count — a device index almost always reports a duration even for
  a track whose title/artist/album it can't supply, and :source describes how those
  identity tags were obtained (embedded vs path-derived)."
  ["title" "artist" "album"])

(defn- reported
  "The device-reported value `v` if it carries real information, else nil: a blank
  string or a zero number (both melt-jfs's \"unreported\" sentinels) count as absent."
  [v]
  (cond
    (nil? v)     nil
    (string? v)  (when-not (str/blank? v) v)
    (number? v)  (when-not (zero? v) v)
    :else        v))

(defn merge-device-tags
  "Merge the device-reported attribute map `attrs` (keyed by view attribute name,
  values possibly nil / blank / zero) over `fallback` tags. Each unreported field
  keeps its fallback value. :source becomes :embedded when this layer reported any
  identity field (title/artist/album), and stays :embedded if the fallback already
  was — so layering audio over mtp over path preserves the embedded marking even
  when the outer layer is blank, while a fully path-derived result stays :path so a
  later re-read can upgrade the cache entry."
  [fallback attrs]
  (let [merged    (reduce (fn [m [k a]]
                            (assoc m k (or (reported (get attrs a)) (get fallback k))))
                          {} fields)
        reported? (boolean (some #(reported (get attrs %)) identity-attrs))]
    (assoc merged :source (if (or reported? (= :embedded (:source fallback))) :embedded :path))))

(defn- read-view
  "Read all metadata attributes of `view` (\"audio\"/\"mtp\") for `path`, returning
  the attribute map keyed by short attribute name — or {} when the view is
  unavailable or the read fails. Requests \"*\" rather than a fixed field list
  because the two views expose different sets (the mtp index has no discNumber);
  \"*\" returns whatever the view supports, so `merge-device-tags` picks each field
  by name and a field a view lacks simply reads as absent. Throwable is swallowed so
  a native-layer failure or an older melt-jfs missing the view can never abort a
  whole scan; the track just keeps whatever the other layers supply (matches the
  file:// reader's stance)."
  [^Path path view]
  (try
    (Files/readAttributes path (str view ":*")
                          (make-array LinkOption 0))
    (catch Throwable _ {})))

(defmethod tag/tags! :mtp [track ^Path path]
  (-> (assoc (tags/from-path track) :source :path)
      (merge-device-tags (read-view path "mtp"))
      (merge-device-tags (read-view path "audio"))))
