(ns dapr.device.file.tag
  "Local file:// audio-tag reader, backed by jaudiotagger. Reads embedded ID3 /
  Vorbis / MP4 tags from the real file and falls back per field to the
  path-derived value when a tag is absent, the file carries no tag at all, or the
  read fails — so a row always has at least the path's best guess, and no single
  unreadable file can abort a whole library scan. Beyond artist/album/title it
  also surfaces genre and track/disc numbers (from the tag) and the duration (from
  the audio header), matching the fields the mtp:// reader supplies."
  (:require [clojure.string :as str]
            [dapr.device.tag :as tag]
            [dapr.domain.tags :as tags])
  (:import (java.nio.file Path)
           (java.util.logging Level Logger)
           (org.jaudiotagger.audio AudioFile AudioFileIO)
           (org.jaudiotagger.tag FieldKey Tag)))

;; jaudiotagger logs verbosely through java.util.logging on every read; silence it
;; so scans don't flood stderr.
(.setLevel (Logger/getLogger "org.jaudiotagger") Level/OFF)

(defn- read-audio
  "The AudioFile at `path`, from which both the embedded Tag and the audio header
  (for duration) are read. May throw — including Errors from jaudiotagger — on a
  malformed file."
  ^AudioFile [^Path path]
  (AudioFileIO/read (.toFile path)))

(defn- parse-number
  "The leading integer of a tag number field `s` (\"3\", \"03\", or \"3/12\"), or
  nil when it is blank or has no digits."
  [s]
  (when-not (str/blank? s)
    (some-> (re-find #"\d+" s) parse-long)))

(defn- duration-millis
  "The track's duration in whole milliseconds from `af`'s audio header, or nil when
  the header reports no positive length."
  [^AudioFile af]
  (let [ms (Math/round (* 1000.0 (.getPreciseTrackLength (.getAudioHeader af))))]
    (when (pos? ms) ms)))

(defmethod tag/tags! :file [track ^Path path]
  (let [fallback (assoc (tags/from-path track) :source :path)]
    (try
      (let [af (read-audio path)]
        (if-let [^Tag tg (.getTag af)]
          ;; The file has a real tag: :embedded, even where individual fields fall
          ;; back to the path (a blank embedded field) or are absent (nil).
          (let [pick (fn [^FieldKey k fb]
                       (let [v (.getFirst tg k)] (if (str/blank? v) fb v)))]
            {:artist          (pick FieldKey/ARTIST (:artist fallback))
             :album           (pick FieldKey/ALBUM (:album fallback))
             :title           (pick FieldKey/TITLE (:title fallback))
             :genre           (pick FieldKey/GENRE nil)
             :track-number    (parse-number (.getFirst tg FieldKey/TRACK))
             :disc-number     (parse-number (.getFirst tg FieldKey/DISC_NO))
             :duration-millis (duration-millis af)
             :source          :embedded})
          fallback))
      ;; Throwable, not Exception: jaudiotagger can throw Errors (e.g. a
      ;; StackOverflowError on a malformed/deeply-nested tag) which would
      ;; otherwise escape and abort the entire scan.
      (catch Throwable _ fallback))))
