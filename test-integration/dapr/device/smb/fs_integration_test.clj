(ns dapr.device.smb.fs-integration-test
  "Integration tests that exercise the real SMB code path — smb-nio / jcifs round
  trips against a live Samba server, the one thing the jimfs-backed unit tests
  cannot cover. Part of `clojure -M:integration` (not the hermetic default
  `clojure -X:test`).

  A :once fixture starts a guest + authenticated Samba server in a Docker container
  via Testcontainers, so there's no manual setup — a run needs only Docker, and the
  tests skip when it is unavailable. The container is bound to the host's port 445
  (jcifs ignores a non-default SMB port, so a random mapped port would not work);
  that port must be free."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [dapr.device.fs :as device-fs]
            [dapr.device.smb.fs :as smb]
            [dapr.fs.nio :as nio])
  (:import (java.nio.file FileSystem Files Path)
           (java.nio.file.attribute FileAttribute)
           (org.testcontainers.containers FixedHostPortGenericContainer)
           (org.testcontainers.containers.wait.strategy Wait)))

;; The container binds host 445; the guest share is reached via 127.0.0.1 and the
;; authenticated share via localhost — distinct host strings so dapr.device.smb.fs's
;; per-host FileSystem cache keeps the anonymous and authenticated connections apart.
(def ^:private guest-url  "smb://127.0.0.1/Music/")
(def ^:private auth-url   "smb://localhost/Private/")
(def ^:private auth-creds {:username "dapr" :password "secretpass"})

(defonce ^:private container (atom nil))

(defn- start-samba!
  "Start a dperson/samba container with a guest share (Music) and an
  authenticated share (Private, user dapr). `-p` makes it create + permission the
  share directories itself, so no host volume is needed."
  []
  (doto (FixedHostPortGenericContainer. "dperson/samba:latest")
    (.withFixedExposedPort (int 445) (int 445))
    (.withCommand (into-array String
                              ["-p"
                               "-u" "dapr;secretpass"
                               "-g" "server min protocol = SMB2"
                               "-g" "map to guest = Bad User"
                               "-s" "Music;/share/music;yes;no;yes;all;all;all"
                               "-s" "Private;/share/private;yes;no;no;dapr;dapr;dapr"]))
    (.waitingFor (Wait/forListeningPort))
    (.start)))

(defn- with-samba
  "Once-per-namespace fixture: start the Samba container, run the tests, stop it.
  No graceful skip — if the container can't start (e.g. Docker unavailable), the
  exception propagates and the namespace's tests fail."
  [run-tests]
  (let [c (start-samba!)]
    (reset! container c)
    (try
      (run-tests)
      (finally
        (when c (.stop c))
        (reset! container nil)))))

(use-fixtures :once with-samba)

(defn- running? [] (some? @container))

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
        present? (fn [] (some #(= rel (:rel %)) (nio/catalog! [url])))]
    (try
      (nio/copy-file! src-root dst-root rel)
      (let [track (first (filter #(= rel (:rel %)) (nio/catalog! [url])))]
        (is (some? track) (str "copied track '" rel "' should be discovered by catalog!"))
        (is (= size (:size track)) "track size should match the copied content"))
      (finally
        (nio/delete-file! dst-root rel)))
    (is (not (present?)) (str "track '" rel "' should be gone after delete-file!"))))

(deftest share-enumeration-test
  (when (running?)
    (testing "listing the SMB server root returns its shares, minus admin shares"
      (let [names (set (map :name (device-fs/dir-children! "smb://127.0.0.1/")))]
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
      (let [names (set (map :name (device-fs/dir-children! "smb://127.0.0.1/")))]
        (is (contains? names "Music")
            (str "operations should work again after close-all!, got " names))))))
