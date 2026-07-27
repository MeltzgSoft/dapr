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
