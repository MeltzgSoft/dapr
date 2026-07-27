(ns dapr.fs.nio
  "Side-effecting filesystem adapter built purely on java.nio.file, so it works
  unchanged across providers once a device-specific namespace has resolved a root
  URI to a Path. Pure data shaping lives in dapr.domain.*; everything here
  performs I/O (all fns end in !)."
  (:require [clojure.string :as str]
            [dapr.device.file.fs :as file-fs]
            ;; Loaded for their tags! method registrations; file:// reads embedded
            ;; tags, the rest fall back to dapr.device.tag's path-based default.
            [dapr.device.file.tag]
            [dapr.device.fs :as device-fs]
            [dapr.device.mtp.fs]
            [dapr.device.smb.fs]
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
      (select-keys cached [:artist :album :title :source])
      (device-tag/tags! m p))))

(defn- audio-track
  "Build a track map for audio file `p` (Path) under root `uri` (Path `root`)
  from the already-read `attrs`, enriched with its artist/album/title tags
  (embedded for file://, path-derived elsewhere — see dapr.device.tag; reused
  from `known` when unchanged — see track-tags!)."
  [^Path root uri ^Path p ^BasicFileAttributes attrs known]
  (let [m {:name  (str (.getFileName p))
           :size  (.size attrs)
           :mtime (.toMillis (.lastModifiedTime attrs))
           :root  uri
           :rel   (relative-key root p)}]
    (-> m
        (merge (track-tags! known m p))
        (assoc :key (lib/track-key m)))))

(defn- walk-audio-tracks!
  "Depth-first walk of `root`, collecting a track map for every audio file. Driven
  by an explicit work stack of directories rather than call-stack recursion, so an
  arbitrarily deep tree (or a symlink/junction cycle on a share, which gets no OS
  ELOOP protection) can't overflow the JVM stack. Opens each directory's stream
  explicitly (rather than via Files/walkFileTree) and notifies `on-scan` *before*
  the per-directory listing call -- which over MTP is a single blocking native
  round-trip -- making it possible to pinpoint a directory whose listing hangs (the
  last :dir event before the freeze names it). Reads one attribute set per entry,
  the same as Files/walkFileTree would.

  `on-scan`, when supplied, is called with:
    {:type :dir     :rel <dir rel path>} as each directory is *visited*, before its
                                         listing call (so the last :dir before a
                                         freeze names the directory whose listing
                                         hung over MTP);
    {:type :listing :count <n>}          once that directory's children are listed,
                                         so progress totals can grow as the walk
                                         descends (n is its immediate child count);
    {:type :entry}                       for every child visited, advancing the
                                         done count toward the total;
    {:type :file    :track <track map>}  for each audio file found.
  Entries that fail to stat, and sub-directories that fail to open, are skipped
  (matching Files/walkFileTree's visitFileFailed=CONTINUE). If `on-scan` throws an
  ex-info carrying :dapr/abort, the whole walk unwinds (used to cancel a scan that
  a newer one has superseded). `known` (rel size -> cached track), when supplied,
  lets unchanged files reuse their cached tags (see track-tags!)."
  [^Path root uri extensions on-scan known]
  (loop [stack  [root]
         tracks []]
    (if (empty? stack)
      tracks
      (let [^Path dir (peek stack)
            stack     (pop stack)]
        (when on-scan (on-scan {:type :dir :rel (relative-key root dir)}))
        ;; List the directory (closing its stream before descending, so we never
        ;; hold a handle open per ancestor). A directory that fails to open is
        ;; skipped; on-scan's :dapr/abort is raised outside this try, so it still
        ;; unwinds the whole walk.
        (if-let [entries (try
                           (with-open [^DirectoryStream stream (Files/newDirectoryStream dir)]
                             (vec (iterator-seq (.iterator stream))))
                           (catch Exception _ nil))]
          (do
            (when on-scan (on-scan {:type :listing :count (count entries)}))
            (let [[child-dirs new-tracks]
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
                   entries)]
              ;; Push children reversed so the first child is popped first (DFS in
              ;; directory order).
              (recur (into stack (rseq child-dirs))
                     (into tracks new-tracks))))
          (recur stack tracks))))))

(defn catalog!
  "Scan every `root` URI of a library and return a seq of track maps for each
  audio file, tagged with the :root it lives under and its :rel path. `on-scan`,
  when supplied, receives per-directory and per-file scan events (see
  walk-audio-tracks!) for progress reporting and diagnostics. `known` (rel size ->
  cached track), when supplied, lets unchanged files reuse their cached tags
  instead of re-reading them (see walk-audio-tracks!)."
  ([roots] (catalog! roots nil))
  ([roots on-scan] (catalog! roots on-scan lib/default-audio-extensions))
  ([roots on-scan extensions] (catalog! roots on-scan extensions nil))
  ([roots on-scan extensions known]
   (mapcat (fn [uri] (walk-audio-tracks! (device-fs/root-path! uri) uri extensions on-scan known)) roots)))

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
