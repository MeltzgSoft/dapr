(ns dapr.device.smb.fs
  "SMB/CIFS share access for the sync engine. smb-nio registers a java.nio
  \"smb://\" FileSystemProvider, so the generic NIO code in dapr.fs.nio reaches
  SMB shares unchanged — this namespace only opens the (authenticated) FileSystem
  and resolves a persisted root URI to a Path on it.

  Persisted roots are credential-free (smb://host/share/dir/): credentials are
  fetched per host from the OS keystore (dapr.fs.credentials) and handed to
  smb-nio through its env-map, never embedded in a URI. So no password is written
  to disk, and smb-nio's SMBPath.toUri stays credential-free for the folder
  browser. One FileSystem is opened per host and reused across the concurrent
  source/sink scans and the copy/delete pass.

  This namespace imports no smb-nio/jcifs classes — it works purely through
  java.nio.file, reaching the provider via the FileSystems SPI."
  (:require [clojure.string :as str]
            [dapr.device.fs :as dfs]
            [dapr.device.smb.format :as smb-format]
            [dapr.fs.credentials :as credentials])
  (:import (java.net URI)
           (java.nio.file FileSystem FileSystems Files LinkOption Path)))

(defn host-of
  "Lower-cased host of an smb:// URI string (the OS-keystore account key and the
  per-host FileSystem cache key), or nil if unparseable."
  [uri-str]
  (try (some-> (URI. ^String uri-str) (.getHost) (str/lower-case))
       (catch Exception _ nil)))

(defn- share-path
  "The share-qualified, absolute path of `uri` within its server's filesystem,
  e.g. smb://nas/Music/sub/ -> \"/Music/sub/\". smb-nio requires a trailing slash
  for directories, which a directory root URI already carries."
  [^URI uri]
  (.getPath uri))

(def ^:dynamic *credential-lookup*
  "Function host -> {:username :password :workgroup} (or nil) used to authenticate
  an SMB host. Defaults to the OS keystore; rebindable so integration tests can
  inject credentials without a running keyring daemon."
  credentials/lookup)

(defn- creds-env
  "An smb-nio env map of credentials for `host`, looked up via *credential-lookup*,
  or an empty map for guest/anonymous access (also when no credentials are stored).
  Credentials MUST use jcifs's own configuration-property keys: smb-nio threads the
  env map straight into jcifs as a PropertyConfiguration, so plain
  'username'/'password' keys are silently ignored (the connection then falls back to
  anonymous and the share denies access)."
  [host]
  (let [{:keys [workgroup username password]} (*credential-lookup* host)]
    (cond-> {}
      workgroup (assoc "jcifs.smb.client.domain" workgroup)
      username  (assoc "jcifs.smb.client.username" username)
      password  (assoc "jcifs.smb.client.password" password))))

;; One FileSystem per host, reused across scans/copies. defonce so a dev reload
;; keeps live connections; the lock serializes the open so two concurrent scans
;; of the same host do not both try to create (and clash on) the FileSystem.
(defonce ^:private filesystems (atom {}))
(def ^:private open-lock (Object.))

(defn- open-filesystem!
  "Open (or reuse) the FileSystem for `uri`'s host, authenticating from the OS
  keystore. smb-nio caches FileSystems by a credential-bearing authority that our
  credential-free URI cannot reproduce, so we keep our own host-keyed cache and
  resolve Paths via the FileSystem object directly."
  ^FileSystem [^URI uri]
  (let [k (host-of (str uri))]
    (locking open-lock
      (let [^FileSystem fs (get @filesystems k)]
        (if (and fs (.isOpen fs))
          fs
          (let [fs (FileSystems/newFileSystem uri (creds-env k))]
            (swap! filesystems assoc k fs)
            fs))))))

(defn resolve-root-path!
  "Resolve a persisted smb:// root URI string to a Path on its (authenticated,
  cached) FileSystem. The returned Path's toUri is credential-free, so it is safe
  to surface in the folder browser and persist as a library root."
  ^Path [uri-str]
  (let [uri (URI. ^String uri-str)]
    (.getPath (open-filesystem! uri) (share-path uri) (make-array String 0))))

(defmethod dfs/root-path! :smb [uri-str]
  (resolve-root-path! uri-str))

(defmethod dfs/available? :smb [uri-str]
  ;; Resolving opens (or reuses) the authenticated FileSystem for the host, so an
  ;; unreachable share or a connect/auth failure surfaces here and degrades to
  ;; unavailable rather than throwing.
  (try
    (Files/isDirectory (resolve-root-path! uri-str) (make-array LinkOption 0))
    (catch Exception _ false)))

(defmethod dfs/dir-children! :smb [uri]
  (let [smb-shares? (smb-format/host-root? uri)
        keep?       (fn [^Path p]
                      (if smb-shares?
                        (not (str/ends-with? (str (.getFileName p)) "$"))
                        (Files/isDirectory p (make-array LinkOption 0))))]
    (dfs/directory-children! (dfs/root-path! uri) keep?)))
