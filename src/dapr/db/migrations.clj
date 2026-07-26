(ns dapr.db.migrations
  "Named, run-once data migrations for the cache DB (dapr.db.cache).

  Each migration has a stable `:id` (a keyword) and a `:migrate` fn of the
  DataScript `conn`. The DB records every applied migration as its own entity (a
  migration-info collection: :migration/id, :migration/applied-at), so `applied-ids`
  is the set of migrations already run. On startup `run-migrations!` applies every
  registered migration whose id is not yet in that set, in registry (vector) order,
  recording each as it goes.

  Migrations are keyed by name, not by a hand-assigned version number: adding one is
  just appending a `{:id … :migrate …}` entry, and two branches that each add a
  migration never collide on a number (they append distinct ids). Registry order is
  the application order.

  This is distinct from `dapr.db.cache/snapshot-version`, which versions the on-disk
  EDN *shape* (an unreadable/old snapshot is discarded). Migrations track *data*
  fix-ups applied to a readable DB.

  Idempotency comes from the applied-id set, not DataScript uniqueness, so no schema
  change is needed and an old snapshot simply reports an empty set. Each migration is
  recorded only after its `:migrate` fn returns, so one that throws is left unrecorded
  and retried next startup — migrations should therefore be written to be safely
  re-runnable."
  (:require [datascript.core :as d]))

(def registry
  "Ordered migrations, each {:id <keyword> :migrate <fn of conn>}. Ids must be
  distinct keywords; `run-migrations!` applies, in vector order, any whose id is not
  yet recorded. Empty until a migration is registered."
  [])

(defn- valid-registry?
  "True when every migration has a keyword :id and the ids are distinct."
  [migrations]
  (let [ids (map :id migrations)]
    (and (every? keyword? ids)
         (= (count ids) (count (distinct ids))))))

(defn applied-ids
  "The set of migration ids already applied to the DB (empty on a fresh DB)."
  [db]
  (set (d/q '[:find [?id ...] :where [_ :migration/id ?id]] db)))

(defn applied
  "Every applied migration as {:id :applied-at}, in application order. Ordered by
  entity id (assigned in ascending transaction order), so it is stable even when two
  migrations recorded in the same millisecond share an :applied-at timestamp."
  [db]
  (->> (d/q '[:find [(pull ?e [:db/id :migration/id :migration/applied-at]) ...]
              :where [?e :migration/id]]
            db)
       (sort-by :db/id)
       (mapv (fn [m] {:id (:migration/id m) :applied-at (:migration/applied-at m)}))))

(defn record-applied!
  "Record migration `id` as applied, stamped with the current time. Used both by
  `run-migrations!` after a migration runs and to seed a baseline for a migration a
  prior release already performed by other means."
  [conn id]
  (d/transact! conn [{:migration/id id :migration/applied-at (java.util.Date.)}]))

(defn run-migrations!
  "Run every migration in `migrations` (default `registry`) whose :id is not yet
  recorded in the DB, in registry (vector) order, recording each in the migration-info
  collection as it completes. Returns the vector of ids applied this run — empty when
  the DB is already current — so the caller knows whether the snapshot needs
  persisting."
  ([conn] (run-migrations! conn registry))
  ([conn migrations]
   (when-not (valid-registry? migrations)
     (throw (ex-info "Invalid migration registry: ids must be distinct keywords"
                     {:ids (map :id migrations)})))
   (let [done    (applied-ids (d/db conn))
         pending (remove #(contains? done (:id %)) migrations)]
     (doseq [{:keys [id migrate]} pending]
       (migrate conn)
       (record-applied! conn id))
     (mapv :id pending))))
