(ns dapr.fs.nio
  "Side-effecting filesystem adapter built purely on java.nio.file, so it works
  unchanged across providers once a device-specific namespace has resolved a root
  URI to a Path. Pure data shaping lives in dapr.domain.*; everything here
  performs I/O (all fns end in !)."
  (:require [clojure.string :as str]
            [dapr.device.file.fs :as file-fs]
            ;; Loaded for their tags! method registrations; file:// reads embedded
            ;; tags via jaudiotagger, mtp:// and smb:// read embedded tags over
            ;; ranged reads, and the rest fall back to dapr.device.tag's path-based
            ;; default.
            [dapr.device.file.tag]
            [dapr.device.fs :as device-fs]
            [dapr.device.mtp.fs]
            [dapr.device.mtp.tag]
            [dapr.device.smb.fs]
            [dapr.device.smb.tag]
            [dapr.device.tag :as device-tag]
            [dapr.domain.library :as lib])
  (:import (java.nio.file CopyOption DirectoryStream FileStore
                          Files LinkOption Path StandardCopyOption)
           (java.nio.file.attribute BasicFileAttributes FileAttribute)))

(defn- relative-key
  "Relative path of `p` under `root`, as a string with '/' separators (so paths
  are comparable across filesystems with different separators)."
  [^Path root ^Path p]
  (-> (.relativize root p)
      (str)
      (str/replace "\\" "/")))

(defn- resolve-rel
  "Resolve a '/'-separated relative path string against `root` segment by segment,
  so it is valid on `root`'s filesystem regardless of its separator. Every segment
  but the last is marked as a folder (trailing '/'), because smb-nio refuses to
  resolve a child against a path it considers a file — without this, copy/delete of
  any nested path over SMB throws (file:// and mtp:// are unaffected, normalizing
  the trailing slash away)."
  ^Path [^Path root rel-path]
  (let [segs (vec (remove str/blank? (str/split rel-path #"/")))
        last-i (dec (count segs))]
    (reduce (fn [^Path acc [i ^String seg]]
              (.resolve acc (if (= i last-i) seg (str seg "/"))))
            root
            (map-indexed vector segs))))

(defn- track-tags!
  "Artist/album/title/source for the audio file at `p` described by `m`. Reuses
  tags from the `known` lookup (rel size -> cached track) when it has an entry for
  this [rel size] with the same mtime *and a recorded :source* — so an unchanged
  file is not re-read, which is the expensive part over MTP/SMB. A cached entry
  without a :source predates source tracking, so it is re-read to record one
  (letting embedded tags be preferred over path-derived — see dapr.db.cache)."
  [known {:keys [rel size mtime] :as m} ^Path p]
  (let [cached (when known (known rel size))]
    (if (and cached (:source cached) (= mtime (:mtime cached)))
      (select-keys cached [:artist :album :title :genre
                           :track-number :disc-number :duration-millis :source])
      (device-tag/tags! m p))))

(defn- audio-track
  "Build a track map for audio file `p` (Path) under root `uri` (Path `root`)
  from the already-read `attrs`, enriched with its artist/album/title tags
  (embedded for file://, path-derived elsewhere — see dapr.device.tag; reused
  from `known` when unchanged — see track-tags!)."
  [^Path root uri ^Path p ^BasicFileAttributes attrs known]
  (let [base {:name  (str (.getFileName p))
              :size  (.size attrs)
              :mtime (.toMillis (.lastModifiedTime attrs))
              :root  uri
              :rel   (relative-key root p)}
        m    (merge base (track-tags! known base p))]
    ;; :key is derived from the tags, so it must be computed after the merge.
    (assoc m :key (lib/track-key m))))

(defn- visit-dir!
  "Visit one directory of a walk: notify `on-scan`, list it, and split its children
  into sub-directories and audio track maps. Returns [child-dirs tracks], or nil
  when the directory can't be listed (skipped, matching Files/walkFileTree's
  visitFileFailed=CONTINUE). The stream is closed before the caller descends, so no
  handle is held per ancestor -- which is what makes the remaining work stack a
  complete, resumable frontier.

  Listing is done by opening the directory's stream explicitly (rather than via
  Files/walkFileTree) so `on-scan` can be notified *before* the listing call --
  which over MTP is a single blocking native round-trip -- making it possible to
  pinpoint a directory whose listing hangs (the last :dir event before the freeze
  names it). `on-scan`, when supplied, is called with:
    {:type :dir     :rel <dir rel path>} as the directory is *visited*, before its
                                         listing call;
    {:type :listing :count <n>}          once its children are listed, so progress
                                         totals can grow as the walk descends (n is
                                         its immediate child count);
    {:type :entry}                       for every child visited, advancing the
                                         done count toward the total;
    {:type :file    :track <track map>}  for each audio file found.
  Entries that fail to stat are skipped. If `on-scan` throws an ex-info carrying
  :dapr/abort, the whole walk unwinds (used to cancel a scan whose library is gone)."
  [^Path root uri ^Path dir extensions on-scan known]
  (when on-scan (on-scan {:type :dir :rel (relative-key root dir)}))
  ;; on-scan's :dapr/abort is raised outside this try, so it still unwinds the walk.
  (when-let [entries (try
                       (with-open [^DirectoryStream stream (Files/newDirectoryStream dir)]
                         (vec (iterator-seq (.iterator stream))))
                       (catch Exception _ nil))]
    (when on-scan (on-scan {:type :listing :count (count entries)}))
    (reduce
     (fn [acc ^Path p]
       (when on-scan (on-scan {:type :entry}))
       (if-let [^BasicFileAttributes attrs
                (try (Files/readAttributes p BasicFileAttributes (make-array LinkOption 0))
                     (catch Exception _ nil))]
         (cond
           (.isDirectory attrs)
           (update acc 0 conj p)

           (and (.isRegularFile attrs)
                (lib/audio-file? (str (.getFileName p)) extensions))
           (let [track (audio-track root uri p attrs known)]
             (when on-scan (on-scan {:type :file :track track}))
             (update acc 1 conj track))

           :else acc)
         acc))
     [[] []]
     entries)))

(def ^:private default-batch-size
  "Tracks accumulated before an `on-batch` callback fires (see walk-root!). Matches
  the cache's transaction batch size, so an incremental refresh writes one
  transaction per batch."
  256)

(defn- walk-root!
  "Depth-first walk of `root`, resuming from the directory work `stack` (a vector of
  Paths, deepest last; `[root]` starts a fresh walk). Driven by an explicit stack
  rather than call-stack recursion, so an arbitrarily deep tree (or a symlink/
  junction cycle on a share, which gets no OS ELOOP protection) can't overflow the
  JVM stack. Each directory is visited via visit-dir!, which reads one attribute set
  per entry, the same as Files/walkFileTree would.

  Tracks are handed to `on-batch` in batches of `batch-size` rather than returned,
  so a walk of any size stays memory-bounded (the caller writes them straight into
  the cache); the walk accumulates only their identities, as `seen`. Those are
  **physical file** identities [rel size], not the tag-derived domain :key, because
  `seen` exists to tell the cache which *presences* survive (see
  dapr.db.cache/reconcile-library-tracks!).

  `pause?`, when supplied, is polled at every directory boundary -- the one point at
  which no directory stream is open, so the remaining stack is a complete frontier.
  Once it returns true the pending batch is flushed and the walk returns
  {:paused? true :stack <remaining> :seen <keys so far>} for a later resume; a walk
  that finishes returns the same map without :paused? and with an empty stack.

  `on-scan`, when supplied, receives the per-directory/per-file scan events
  documented on visit-dir!. `known` (rel size -> cached track), when supplied, lets
  unchanged files reuse their cached tags (see track-tags!)."
  [^Path root uri extensions {:keys [on-scan known on-batch batch-size pause?]} stack seen]
  (let [batch-size (or batch-size default-batch-size)
        flush!     (fn [batch] (when (and on-batch (seq batch)) (on-batch batch)) [])]
    (loop [stack stack
           batch []
           seen  seen]
      (cond
        (empty? stack)
        (do (flush! batch) {:stack [] :seen seen})

        (and pause? (pause?))
        (do (flush! batch) {:paused? true :stack stack :seen seen})

        :else
        (let [^Path dir (peek stack)
              stack     (pop stack)]
          (if-let [[child-dirs tracks] (visit-dir! root uri dir extensions on-scan known)]
            (let [batch (into batch tracks)]
              ;; Push children reversed so the first child is popped first (DFS in
              ;; directory order).
              (recur (into stack (rseq child-dirs))
                     (if (>= (count batch) batch-size) (flush! batch) batch)
                     (into seen (map (juxt :rel :size)) tracks)))
            (recur stack batch seen)))))))

(defn scan-roots!
  "Pausable, resumable scan of a library's `roots`. Tracks are streamed to
  `:on-batch` (a vector of track maps at a time) rather than returned, so a scan of
  any size stays memory-bounded, and the caller writes each batch straight into the
  cache (see dapr.db.cache/upsert-library-tracks!).

  Options:
    :on-scan    per-directory/per-file scan events (see visit-dir!);
    :extensions audio extensions (defaults to the library default set);
    :known      (fn [rel size] -> cached track) for tag reuse (see track-tags!);
    :on-batch   (fn [tracks]) called per batch of scanned tracks;
    :batch-size tracks per on-batch call;
    :pause?     (fn [] -> boolean) polled at every directory boundary;
    :checkpoint a checkpoint from an earlier paused scan, to resume from.

  Returns either

    {:status :complete :seen #{[rel size] ...}}

  -- `seen` being the [rel size] identity of every file found across all roots
  (the physical key a cached presence carries, *not* the tag-derived domain :key),
  which is what makes it safe to retract the rest (see
  dapr.db.cache/reconcile-library-tracks!) -- or

    {:status :paused :checkpoint {:roots [...] :stack [...] :seen #{...}}}

  when `pause?` fired. The checkpoint carries the roots not yet finished (the first
  being the one in progress), that root's remaining directory frontier, and the keys
  seen so far; passing it back as `:checkpoint` continues exactly where the walk
  stopped rather than re-listing directories already visited -- the expensive part
  over MTP/SMB. It holds live Paths, so it is valid only while the device's
  FileSystem is open (i.e. within one run of the app), and is deliberately not
  persisted."
  [roots {:keys [extensions checkpoint] :as opts}]
  (let [extensions (or extensions lib/default-audio-extensions)]
    (loop [pending (vec (or (:roots checkpoint) roots))
           stack   (:stack checkpoint)
           seen    (or (:seen checkpoint) #{})]
      (if (empty? pending)
        {:status :complete :seen seen}
        (let [uri  (first pending)
              root (device-fs/root-path! uri)
              res  (walk-root! root uri extensions opts (or stack [root]) seen)]
          (if (:paused? res)
            {:status     :paused
             :checkpoint {:roots pending :stack (:stack res) :seen (:seen res)}}
            (recur (vec (rest pending)) nil (:seen res))))))))

(defn copy-file!
  "Copy file `rel-path` from `src-root` to `dst-root`, creating intermediate
  directories and replacing any existing file. Attributes are intentionally not
  copied: some providers (MTP) cannot set mtimes.

  Parent directories are created only for a *nested* rel-path. For a top-level
  file the parent is `dst-root` itself, which already exists — and over SMB a
  share/library root reports isDirectory=false, so createDirectories would wrongly
  try to mkdir it and throw."
  [^Path src-root ^Path dst-root rel-path]
  (let [src (resolve-rel src-root rel-path)
        dst (resolve-rel dst-root rel-path)]
    (when (str/includes? rel-path "/")
      (Files/createDirectories (.getParent dst) (make-array FileAttribute 0)))
    (Files/copy src dst
                (into-array CopyOption [StandardCopyOption/REPLACE_EXISTING]))))

(defn delete-file!
  "Delete file `rel-path` under `dst-root` if it exists."
  [^Path dst-root rel-path]
  (Files/deleteIfExists (resolve-rel dst-root rel-path)))

(defn root-free!
  "Placement input for one root: {:uri :free-bytes} (usable space of its
  backing device)."
  [uri]
  {:uri uri :free-bytes (.getUsableSpace (Files/getFileStore (device-fs/root-path! uri)))})

(defn library-free!
  "Total usable bytes across the distinct devices backing `roots`, so two roots
  on the same device (e.g. two folders on a phone's SD card, or two folders on
  the same local disk) are not double counted. Stores are keyed by [name type],
  which distinguishes a phone's internal vs SD storage while still collapsing
  two folders on one device (JDK local FileStores do not override equals)."
  [roots]
  (->> roots
       (map (fn [uri] (Files/getFileStore (device-fs/root-path! uri))))
       (reduce (fn [acc ^FileStore fs]
                 (assoc acc [(.name fs) (.type fs)] (.getUsableSpace fs)))
               {})
       (vals)
       (reduce + 0)))

(defn local-places!
  "Top-level local browsing locations: each filesystem root plus the user's home
  directory, as {:name :uri :dir? true} entries. These seed the folder browser
  for local file:// libraries."
  []
  (file-fs/local-places!))
