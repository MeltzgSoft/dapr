(ns dapr.device.smb.fs-integration-test
  "Integration tests that exercise the real SMB code path — smb-nio / jcifs round
  trips against a live SMB server, the one thing the jimfs-backed unit tests
  cannot cover. Part of `clojure -M:integration` (not the hermetic default
  `clojure -X:test`).

  The :once fixture picks its SMB backend by OS and by whether one was handed to
  it, because the runners differ (see .forgejo/workflows/tests.yml):

    - Forge CI: TEST_SMB_GUEST_URL/TEST_SMB_AUTH_URL point at persistent native
      shares on the host-mode Linux and macOS runners, and the fixture starts
      nothing. Those jobs get no docker socket, so Testcontainers cannot run
      there at all.
    - Linux otherwise: start a dperson/samba server in a Docker container via
      Testcontainers, so no host setup is needed. Bound to the host's port 445
      (jcifs ignores a non-default SMB port, so a random mapped port would not
      work); that port must be free. This stays the local-dev default.
    - macOS / Windows: those hosts can't run the Linux Samba image (no Docker on
      macOS; Windows runs only Windows containers), so the *native* SMB server is
      used instead, with Music and Private shares on port 445 reachable by user
      dapr. Native guest SMB is unavailable there (Windows hardens it off; macOS
      needs GUI-granted Full Disk Access for smbd), so the fixture authenticates
      every host — the anonymous code path is exercised by the Linux run.

      On the forge's persistent Windows and macOS runners the shares are
      provisioned once as admin, because the runner services are non-admin and
      their machine state persists. The fixture only needs the URLs and cannot
      tell how the native server was provisioned, which is the point.

  Either way there is no graceful skip: if the Linux container can't start, or the
  native shares aren't reachable, the tests fail rather than silently pass."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [dapr.device.fs :as device-fs]
            [dapr.device.smb.fs :as smb]
            [dapr.fs.nio :as nio]
            [dapr.test-fs :as tfs])
  (:import (java.nio.file FileSystem Files Path)
           (java.nio.file.attribute FileAttribute)
           (org.testcontainers.containers FixedHostPortGenericContainer)
           (org.testcontainers.containers.wait.strategy Wait)))

;; The guest share is reached via one host string and the authenticated share via
;; another — distinct hosts so dapr.device.smb.fs's per-host FileSystem cache keeps
;; the anonymous and authenticated connections apart. With Testcontainers, and on the
;; native macOS/Windows servers, that is one server on port 445 under two names.
;;
;; The Forge integration jobs run in host mode with no docker socket, so
;; Testcontainers cannot work there; TEST_SMB_GUEST_URL/TEST_SMB_AUTH_URL point
;; at the persistent native shares instead (.forgejo/workflows/tests.yml). Unset
;; locally, so the zero-setup Testcontainers path is unchanged.
;; Public because the tag integration test shares this fixture and its URLs.
(def guest-url (or (System/getenv "TEST_SMB_GUEST_URL") "smb://127.0.0.1/Music/"))
(def auth-url  (or (System/getenv "TEST_SMB_AUTH_URL")  "smb://localhost/Private/"))

;; The guest host's server root, for share enumeration: "smb://h/Music/" -> "smb://h/".
(def ^:private guest-root (second (re-find #"^(smb://[^/]+/)" guest-url)))

;; A sidecar-provided server means there is no container for this JVM to start.
(def ^:private sidecar? (some? (System/getenv "TEST_SMB_GUEST_URL")))

;; Password satisfies Windows' local-account complexity policy (New-LocalUser
;; rejects a simple one), so the same credentials work on every backend.
(def ^:private auth-creds {:username "dapr" :password "Secretpass1!"})

(def ^:private linux?
  (str/includes? (str/lower-case (System/getProperty "os.name")) "linux"))

;; Holds the single running Testcontainer on Linux, started once and shared across
;; every SMB test namespace (see with-smb-backend); stays nil on macOS/Windows
;; where the native server is provisioned/owned by the CI workflow.
(defonce ^:private container (atom nil))
;; True while the fixture's backend is up, whichever OS. Tests read it via running?.
(defonce ^:private backend-ready? (atom false))

(defn- start-samba!
  "Start a dperson/samba container with a guest share (Music) and an
  authenticated share (Private, user dapr). `-p` makes it create + permission the
  share directories itself, so no host volume is needed."
  []
  (doto (FixedHostPortGenericContainer. "dperson/samba:latest")
    (.withFixedExposedPort (int 445) (int 445))
    (.withCommand (into-array String
                              ["-p"
                               "-u" "dapr;Secretpass1!"
                               "-g" "server min protocol = SMB2"
                               "-g" "map to guest = Bad User"
                               "-s" "Music;/share/music;yes;no;yes;all;all;all"
                               "-s" "Private;/share/private;yes;no;no;dapr;dapr;dapr"]))
    (.waitingFor (Wait/forListeningPort))
    (.start)))

(defn- ensure-backend!
  "Bring up the SMB backend once for the whole JVM run. On Linux it starts the
  Samba container on first call and registers a JVM shutdown hook to stop it,
  then no-ops on later calls — so the fixed port 445 is bound exactly once. This
  matters because multiple SMB test namespaces each install with-smb-backend as a
  :once fixture: restarting a fresh container on 445 between namespaces raced
  smbd readiness / docker-proxy connection tracking and reset the next
  namespace's connections. Keeping one container up for the whole run removes that
  race. On macOS/Windows there is no container — the native CI server is used
  as-is — so this only flips backend-ready?."
  []
  (when (and linux? (not sidecar?) (nil? @container))
    (let [c (start-samba!)]
      (reset! container c)
      (.addShutdownHook (Runtime/getRuntime)
                        (Thread. #(try (.stop c) (catch Throwable _ nil))))))
  (reset! backend-ready? true))

(defn with-smb-backend
  "Per-namespace :once fixture, shared with dapr.device.smb.tag-integration-test.
  Ensures the single shared SMB backend is up (see ensure-backend!) and clears
  dapr.device.smb.fs's process-wide FileSystem cache so the namespace starts from
  a clean handle. On macOS/Windows there is no guest share, so it authenticates
  every host for the whole run (the guest/anonymous path is covered by the Linux
  run). No graceful skip — a container that won't start, or unreachable native
  shares, surface as test failures. The container is torn down by ensure-backend!'s
  JVM shutdown hook, not here, so it survives across namespaces."
  [run-tests]
  (ensure-backend!)
  (smb/close-all!)
  (if linux?
    (run-tests)
    (binding [smb/*credential-lookup* (constantly auth-creds)]
      (run-tests))))

(use-fixtures :once with-smb-backend)

(defn running?
  "True while the SMB backend is up. Public so the tag integration test can gate
  on the same fixture."
  []
  @backend-ready?)

(defn- seed-local!
  "Create a local temp directory containing `content` at relative path `rel`, and
  return its file:// root Path (the copy source)."
  ^Path [rel content]
  (let [dir (Files/createTempDirectory "dapr-smb-it" (make-array FileAttribute 0))
        f   (.resolve dir ^String rel)]
    (Files/createDirectories (.getParent f) (make-array FileAttribute 0))
    (spit (str f) content)
    (device-fs/root-path! (str (.toUri dir)))))

(defn- copy-catalog-delete!
  "Round-trip over SMB against `url` for relative path `rel`: copy a file in, assert
  catalog! finds it at the expected rel/size, then delete it and assert it is gone."
  [url rel]
  (let [content  "integration-test-bytes"
        size     (count (.getBytes ^String content))
        src-root (seed-local! rel content)
        dst-root (device-fs/root-path! url)
        present? (fn [] (some #(= rel (:rel %)) (tfs/scan-tracks! [url])))]
    (try
      (nio/copy-file! src-root dst-root rel)
      (let [track (first (filter #(= rel (:rel %)) (tfs/scan-tracks! [url])))]
        (is (some? track) (str "copied track '" rel "' should be discovered by catalog!"))
        (is (= size (:size track)) "track size should match the copied content"))
      (finally
        (nio/delete-file! dst-root rel)))
    (is (not (present?)) (str "track '" rel "' should be gone after delete-file!"))))

(deftest share-enumeration-test
  (when (running?)
    (testing "listing the SMB server root returns its shares, minus admin shares"
      (let [names (set (map :name (device-fs/dir-children! guest-root)))]
        (is (contains? names "Music") (str "expected 'Music' among " names))
        (is (contains? names "Private") (str "expected 'Private' among " names))
        (is (not-any? #(str/ends-with? % "$") names)
            (str "admin shares (e.g. IPC$) should be hidden, got " names))))))

(deftest copy-catalog-delete-test
  (when (running?)
    (testing "copy files into a guest share over SMB, catalog finds them, delete removes them"
      ;; Both a nested path and a top-level file (whose parent is the share root).
      (copy-catalog-delete! guest-url "albums/artist/song.mp3")
      (copy-catalog-delete! guest-url "loose-track.mp3"))))

(deftest authenticated-copy-catalog-delete-test
  (when (running?)
    (testing "the same round-trip against a password-protected share authenticates"
      ;; Inject the credentials in place of the OS keystore, so the auth path runs
      ;; without a keyring daemon — the production default is the keystore lookup.
      (binding [smb/*credential-lookup* (constantly auth-creds)]
        (copy-catalog-delete! auth-url "albums/artist/song.mp3")
        (copy-catalog-delete! auth-url "loose-track.mp3")))))

(deftest library-free-test
  (when (running?)
    (testing "library-free! reports the share's free space as a positive number"
      (is (pos? (nio/library-free! [guest-url]))))))

(deftest close-all!-test
  (when (running?)
    (testing "close-all! closes the cached FileSystem"
      (let [path             (device-fs/root-path! guest-url)
            ^FileSystem fs   (.getFileSystem path)]
        (is (.isOpen fs) "sanity: resolving a root opens (and caches) its FileSystem")
        (smb/close-all!)
        (is (not (.isOpen fs)) "close-all! should close the cached FileSystem")))
    (testing "the cache reopens on demand after close-all! (safe across a system reset)"
      (let [names (set (map :name (device-fs/dir-children! guest-root)))]
        (is (contains? names "Music")
            (str "operations should work again after close-all!, got " names))))))
