(ns dapr.device.smb.tag
  "smb:// audio-tag reader. Reads a track's own embedded tags from a small slice of
  its header over SMB *ranged reads* — never transferring the whole file, so it
  stays tenable on shares with tens of thousands of tracks.

  jaudiotagger reads only a java.io.File, so it can't touch an smb:// NIO path
  without staging a whole-file local copy. Instead we reuse melt-jfs's
  device-agnostic header parsers (org.meltzg.audio.AudioTagReaders), which
  parse FLAC/MP3/MP4/Ogg-Opus/WAV tags over a RangedByteSource — an interface that
  asks only for the byte ranges each format's header actually needs. We back that
  source with smb-nio's SeekableByteChannel, whose position() honours seeks (jcifs
  SmbRandomAccessFile), so each range becomes a targeted SMB READ rather than a
  full download.

  Per field the parsed value falls back to the path-derived one (dapr.domain.tags);
  a format melt-jfs doesn't parse (e.g. aac/wma), an unreadable header, or any
  failure degrades to path tags — never worse than dapr.device.tag's default, and
  never a whole-file read. Cost is a few KB per track on the first scan; the tag
  cache (dapr.fs.nio/track-tags!) makes even that a one-time cost per file."
  (:require [clojure.string :as str]
            [dapr.device.tag :as tag]
            [dapr.domain.tags :as tags])
  (:import (java.nio ByteBuffer)
           (java.nio.channels SeekableByteChannel)
           (java.nio.file Files OpenOption Path StandardOpenOption)
           (org.meltzg.audio AudioTagReaders AudioTags RangedByteSource)))

(defn channel-source
  "A melt-jfs RangedByteSource that reads byte ranges from the already-open
  SeekableByteChannel `ch`: seek to `offset`, then read up to `maxBytes`. Returns
  the bytes actually read — shorter than requested near end-of-file, empty at or
  past it — as the RangedByteSource contract requires. Over smb-nio each call is a
  ranged SMB READ, so only the header slices a parser asks for cross the wire."
  ^RangedByteSource [^SeekableByteChannel ch]
  (reify RangedByteSource
    (read [_ offset maxBytes]
      (when (or (neg? offset) (neg? maxBytes))
        (throw (IllegalArgumentException. "negative offset/maxBytes")))
      (if (zero? maxBytes)
        (byte-array 0)
        (let [^ByteBuffer buf (ByteBuffer/allocate maxBytes)]
          (.position ch (long offset))
          (loop []
            (when (and (.hasRemaining buf) (pos? (.read ch buf)))
              (recur)))
          (let [out (byte-array (.position buf))]
            (.flip buf)
            (.get buf out)
            out))))))

(defn audio-tags->tags
  "Map a melt-jfs AudioTags (or nil) onto dapr's full tag map — {:artist :album
  :title :genre :track-number :disc-number :duration-millis :source} — falling back
  per field to `fallback` (a path-derived map). :source is :embedded only when the
  file actually reported at least one of artist/album/title — a header with no
  usable tags (or nil) keeps `fallback` untouched, so the cache isn't misled into
  preferring path values marked :embedded. Text fields fall back to the path value
  when blank; the numeric fields (which path derivation can't supply) are nil when
  unreported — melt-jfs uses 0 as the 'unreported' sentinel."
  [^AudioTags at fallback]
  (if (and at (not (.isEmpty at)))
    (let [text      (fn [v fb] (if (str/blank? v) fb v))
          num       (fn [v] (when-not (zero? v) v))
          reported? (boolean (some #(not (str/blank? %))
                                   [(.artist at) (.album at) (.title at)]))]
      (if reported?
        {:artist          (text (.artist at) (:artist fallback))
         :album           (text (.album at) (:album fallback))
         :title           (text (.title at) (:title fallback))
         :genre           (text (.genre at) (:genre fallback))
         :track-number    (num (.trackNumber at))
         :disc-number     (num (.discNumber at))
         :duration-millis (num (.durationMillis at))
         :source          :embedded}
        fallback))
    fallback))

(defn- ranged-tags!
  "AudioTags parsed from `path`'s header via melt-jfs ranged reads, or nil when the
  format is unsupported or the header can't be read. Opens a read-only channel and
  reads only the ranges the parser requests. `size` is the file's total size (from
  the scan; pass a non-positive value when unknown) — some parsers use it."
  ^AudioTags [^Path path ^String filename size]
  (when (AudioTagReaders/isSupported filename)
    (with-open [ch (Files/newByteChannel
                    path (into-array OpenOption [StandardOpenOption/READ]))]
      (AudioTagReaders/read filename (channel-source ch) (long size)))))

(defmethod tag/tags! :smb [track ^Path path]
  (let [fallback (assoc (tags/from-path track) :source :path)]
    (try
      (audio-tags->tags
       (ranged-tags! path (str (.getFileName path)) (or (:size track) -1))
       fallback)
      ;; Throwable, not Exception: a native/parse failure must degrade to path
      ;; tags, never abort the scan (matches the file:// reader's stance).
      (catch Throwable _ fallback))))
