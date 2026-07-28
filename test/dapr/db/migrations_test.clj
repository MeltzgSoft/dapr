(ns dapr.db.migrations-test
  (:require [clojure.test :refer [deftest is testing]]
            [dapr.db.cache :as cache]
            [dapr.db.migrations :as migrations]
            [datascript.core :as d]))

(defn- marker-migration
  "A migration whose :migration/migrate transacts a marker fact so a test can see it ran."
  [id]
  {:migration/id      id
   :migration/migrate (fn [conn] (d/transact! conn [{:marker/id id}]))})

(defn- markers
  "The set of :marker/id facts the marker-migrations left behind."
  [db]
  (set (d/q '[:find [?id ...] :where [_ :marker/id ?id]] db)))

(deftest run-migrations-applies-pending-in-registry-order-test
  (let [conn (cache/empty-conn)
        reg  [(marker-migration :migration/a) (marker-migration :migration/b)]]
    (testing "a fresh DB runs every migration, in registry order, and records each id"
      (is (= [:migration/a :migration/b] (migrations/run-migrations! conn reg)))
      (is (= #{:migration/a :migration/b} (markers (d/db conn))))
      (is (= #{:migration/a :migration/b} (migrations/applied-ids (d/db conn))))
      (is (= [:migration/a :migration/b] (map :migration/id (migrations/applied (d/db conn))))))
    (testing "recorded migrations carry an applied-at timestamp"
      (is (every? inst? (map :migration/applied-at (migrations/applied (d/db conn))))))
    (testing "re-running the same registry is a no-op"
      (is (= [] (migrations/run-migrations! conn reg)))
      (is (= #{:migration/a :migration/b} (migrations/applied-ids (d/db conn)))))))

(deftest run-migrations-only-runs-unapplied-test
  (let [conn (cache/empty-conn)]
    (migrations/run-migrations! conn [(marker-migration :migration/a) (marker-migration :migration/b)])
    (testing "appending a new migration runs only the not-yet-applied one"
      (let [ran (atom [])
            reg [(marker-migration :migration/a)
                 (marker-migration :migration/b)
                 {:migration/id      :migration/c
                  :migration/migrate (fn [conn] (swap! ran conj :migration/c)
                                       (d/transact! conn [{:marker/id :migration/c}]))}]]
        (is (= [:migration/c] (migrations/run-migrations! conn reg)))
        (is (= [:migration/c] @ran) "already-applied ids were not re-run")
        (is (= #{:migration/a :migration/b :migration/c} (migrations/applied-ids (d/db conn))))))))

(deftest applied-ids-empty-on-fresh-db-test
  (is (= #{} (migrations/applied-ids (d/db (cache/empty-conn))))))

(deftest run-migrations-rejects-invalid-registry-test
  (let [conn (cache/empty-conn)]
    (testing "duplicate ids are rejected"
      (is (thrown? clojure.lang.ExceptionInfo
                   (migrations/run-migrations! conn [(marker-migration :migration/a)
                                                     (marker-migration :migration/a)]))))
    (testing "a non-keyword id is rejected"
      (is (thrown? clojure.lang.ExceptionInfo
                   (migrations/run-migrations! conn [{:migration/id "a" :migration/migrate identity}]))))))

(deftest record-applied-seeds-a-baseline-test
  (let [conn (cache/empty-conn)]
    (testing "recording an id without running it marks that migration applied"
      (migrations/record-applied! conn :migration/seeded)
      (is (= #{:migration/seeded} (migrations/applied-ids (d/db conn))))
      ;; a registry whose only migration is that id is then a no-op
      (let [ran (atom false)]
        (is (= [] (migrations/run-migrations!
                   conn [{:migration/id :migration/seeded :migration/migrate (fn [_] (reset! ran true))}])))
        (is (false? @ran))))))

;; --- the real registry (the :migration/mtp-tag-sources migration) ----------------------

(defn- track [rel size & {:keys [artist source root]}]
  {:rel rel :size size :artist artist :album nil :title nil
   :root (or root "file:///r/") :mtime nil :source source})

(defn- mtp-path-track
  "A catalog track map cached path-derived under an mtp:// root."
  [rel size]
  (track rel size :artist "A" :source :path :root "mtp://dev/"))

(deftest migrate-mtp-tag-sources-test
  (let [conn (cache/empty-conn)
        f    (cache/upsert-library! conn {:name "F" :roots ["file:///r/"]})
        m    (cache/upsert-library! conn {:name "M" :roots ["mtp://dev/"]})]
    (cache/replace-library-tracks! conn f [(track "f.mp3" 3 :artist "F" :source :path
                                                  :root "file:///r/")])
    (cache/replace-library-tracks! conn m [(track "song.mp3" 1 :artist "Path" :source :path
                                                  :root "mtp://dev/")
                                           (track "emb.mp3" 2 :artist "Emb" :source :embedded
                                                  :root "mtp://dev/")])
    (testing "clears :path tag-source only on mtp-rooted tracks, so they re-read"
      (is (= 1 (migrations/migrate-mtp-tag-sources! conn)))
      (let [cat-m (cache/library-catalog (d/db conn) m)
            cat-f (cache/library-catalog (d/db conn) f)]
        (is (nil? (:source (get cat-m ["song.mp3" 1]))) "mtp :path source cleared")
        (is (= :embedded (:source (get cat-m ["emb.mp3" 2]))) "mtp :embedded left alone")
        (is (= :path (:source (get cat-f ["f.mp3" 3]))) "file :path left alone")))
    (testing "re-running finds nothing left to clear (run-once is enforced by the
              applied-id gate, not this fn)"
      (is (= 0 (migrations/migrate-mtp-tag-sources! conn)))
      (is (nil? (:source (get (cache/library-catalog (d/db conn) m) ["song.mp3" 1])))))))

(deftest migrate-extended-tag-fields-test
  (let [conn (cache/empty-conn)
        f    (cache/upsert-library! conn {:name "F" :roots ["file:///r/"]})
        m    (cache/upsert-library! conn {:name "M" :roots ["mtp://dev/"]})
        s    (cache/upsert-library! conn {:name "S" :roots ["smb://host/share/"]})]
    (cache/replace-library-tracks! conn f [(track "f-emb.flac" 1 :artist "F" :source :embedded
                                                  :root "file:///r/")])
    (cache/replace-library-tracks! conn m [(track "m-emb.flac" 2 :artist "M" :source :embedded
                                                  :root "mtp://dev/")
                                           (track "m-path.flac" 3 :artist "MP" :source :path
                                                  :root "mtp://dev/")])
    (cache/replace-library-tracks! conn s [(track "s-path.flac" 4 :artist "S" :source :path
                                                  :root "smb://host/share/")])
    (testing "clears tag-source (any value) on file:// and mtp:// tracks so they re-read;
              smb:// is left alone (handled by :migration/smb-tag-sources)"
      (is (= 3 (migrations/migrate-extended-tag-fields! conn)))
      (is (nil? (:source (get (cache/library-catalog (d/db conn) f) ["f-emb.flac" 1])))
          "file :embedded cleared")
      (is (nil? (:source (get (cache/library-catalog (d/db conn) m) ["m-emb.flac" 2])))
          "mtp :embedded cleared")
      (is (nil? (:source (get (cache/library-catalog (d/db conn) m) ["m-path.flac" 3])))
          "mtp :path cleared")
      (is (= :path (:source (get (cache/library-catalog (d/db conn) s) ["s-path.flac" 4])))
          "smb :path untouched"))
    (testing "re-running finds nothing left to clear (run-once is enforced by the gate)"
      (is (= 0 (migrations/migrate-extended-tag-fields! conn))))))

(deftest migrate-smb-tag-sources-test
  (let [conn (cache/empty-conn)
        f    (cache/upsert-library! conn {:name "F" :roots ["file:///r/"]})
        s    (cache/upsert-library! conn {:name "S" :roots ["smb://host/Music/"]})]
    (cache/replace-library-tracks! conn f [(track "f.mp3" 3 :artist "F" :source :path
                                                  :root "file:///r/")])
    (cache/replace-library-tracks! conn s [(track "song.flac" 1 :artist "Path" :source :path
                                                  :root "smb://host/Music/")
                                           (track "emb.flac" 2 :artist "Emb" :source :embedded
                                                  :root "smb://host/Music/")])
    (testing "clears :path tag-source only on smb-rooted tracks, so they re-read"
      (is (= 1 (migrations/migrate-smb-tag-sources! conn)))
      (let [cat-s (cache/library-catalog (d/db conn) s)
            cat-f (cache/library-catalog (d/db conn) f)]
        (is (nil? (:source (get cat-s ["song.flac" 1]))) "smb :path source cleared")
        (is (= :embedded (:source (get cat-s ["emb.flac" 2]))) "smb :embedded left alone")
        (is (= :path (:source (get cat-f ["f.mp3" 3]))) "file :path left alone")))
    (testing "re-running finds nothing left to clear"
      (is (= 0 (migrations/migrate-smb-tag-sources! conn))))))

(deftest registry-runs-migrations-once-test
  (let [conn (cache/empty-conn)
        m    (cache/upsert-library! conn {:name "M" :roots ["mtp://dev/"]})]
    (cache/replace-library-tracks! conn m [(mtp-path-track "song.mp3" 1)])
    (testing "running the real registry applies every migration, in order, and records them"
      (is (= [:migration/mtp-tag-sources :migration/extended-tag-fields :migration/smb-tag-sources]
             (migrations/run-migrations! conn)))
      (is (nil? (:source (get (cache/library-catalog (d/db conn) m) ["song.mp3" 1]))))
      (is (= #{:migration/mtp-tag-sources :migration/extended-tag-fields :migration/smb-tag-sources}
             (migrations/applied-ids (d/db conn)))))
    (testing "a second run is a no-op — the applied-id set, not the fns, enforces once"
      (cache/replace-library-tracks! conn m [(mtp-path-track "song.mp3" 1)]) ; re-scan re-adds :path
      (is (= [] (migrations/run-migrations! conn)))
      (is (= :path (:source (get (cache/library-catalog (d/db conn) m) ["song.mp3" 1])))
          "not re-cleared: the migrations are already recorded"))))
