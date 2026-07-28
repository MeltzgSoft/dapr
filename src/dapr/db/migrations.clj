(ns dapr.db.migrations
  "Named, run-once data migrations for the cache DB (dapr.db.cache).

  Each migration is a map `{:migration/id <keyword> :migration/migrate <fn of conn>}`;
  the id is conventionally namespaced (e.g. `:migration/mtp-tag-sources`). The DB
  records every applied migration as its own entity (a migration-info collection:
  :migration/id, :migration/applied-at), so `applied-ids` is the set of migrations
  already run. On startup `run-migrations!` applies every registered migration whose
  id is not yet in that set, in registry (vector) order, recording each as it goes.

  Migrations are keyed by name, not by a hand-assigned version number: adding one is
  just appending a `{:migration/id … :migration/migrate …}` entry, and two branches
  that each add a migration never collide on a number (they append distinct ids).
  Registry order is the application order.

  This is distinct from `dapr.db.cache/snapshot-version`, which versions the on-disk
  EDN *shape* (an unreadable/old snapshot is discarded). Migrations track *data*
  fix-ups applied to a readable DB.

  Idempotency comes from the applied-id set, not DataScript uniqueness, so no schema
  change is needed and an old snapshot simply reports an empty set. Each migration is
  recorded only after its `:migrate` fn returns, so one that throws is left unrecorded
  and retried next startup — migrations should therefore be written to be safely
  re-runnable."
  (:require [dapr.device.format :as device]
            [datascript.core :as d]))

;; --- migrations (registered below; run in registry order) --------------------

(defn migrate-mtp-tag-sources!
  "The :migration/mtp-tag-sources migration. For the arrival of the mtp:// tag reader
  (dapr.device.mtp.tag): retract :track/tag-source from every track cached
  path-derived (:source :path) that has a presence under an mtp:// root, so the next
  scan re-reads it through the device reader instead of reusing its stale path tags.
  MTP tracks scanned before the reader landed were cached :source :path with an
  unchanged mtime, and dapr.fs.nio/track-tags! reuses any entry that has a recorded
  source — so without this they'd keep path-derived tags until their mtime changed.

  Run exactly once via the applied-id gate (`run-migrations!`), so it can't re-clear
  the source of genuinely tagless mtp files (a device whose media scanner reported
  nothing, left at :path by the re-read) and force a wasted device read every startup.
  Returns the number of tracks cleared (may be 0). Retract-only, no I/O."
  [conn]
  (let [eids (->> (d/q '[:find ?t ?root
                         :where
                         [?t :track/tag-source :path]
                         [?p :presence/track ?t]
                         [?p :presence/root ?root]]
                       (d/db conn))
                  (into #{} (comp (filter (fn [[_ root]] (= :mtp (device/device-type root))))
                                  (map first))))]
    (when (seq eids)
      (d/transact! conn (mapv (fn [t] [:db/retract t :track/tag-source :path]) eids)))
    (count eids)))

(defn migrate-extended-tag-fields!
  "The :migration/extended-tag-fields migration. For the arrival of the extended
  embedded fields (genre/track-number/disc-number/duration-millis) in both the
  file:// and mtp:// readers: retract :track/tag-source from every track with a
  recorded source under a file:// or mtp:// root, so the next scan re-reads it
  through the now-richer reader and backfills those fields. dapr.fs.nio/track-tags!
  reuses any entry that has a recorded source and an unchanged mtime, so without this
  an already-cached track keeps its old field-less tags until its mtime changes.
  smb:// roots are skipped — their tags are pure path derivation, so a re-read gains
  none of the new fields.

  Retracts the source at whatever value it holds (:embedded or :path), unlike
  migrate-mtp-tag-sources! which targets only :path — the missing fields affect
  embedded-sourced tracks too. Run exactly once via the applied-id gate
  (`run-migrations!`). Returns the number of tracks cleared (may be 0). Retract-only,
  no I/O."
  [conn]
  (let [tx (->> (d/q '[:find ?t ?src ?root
                       :where
                       [?t :track/tag-source ?src]
                       [?p :presence/track ?t]
                       [?p :presence/root ?root]]
                     (d/db conn))
                (into #{} (comp (filter (fn [[_ _ root]]
                                          (contains? #{:file :mtp} (device/device-type root))))
                                (map (fn [[t src _]] [:db/retract t :track/tag-source src])))))]
    (when (seq tx)
      (d/transact! conn (vec tx)))
    (count tx)))

(def registry
  "Ordered migrations, each {:migration/id <keyword> :migration/migrate <fn of conn>}.
  Ids must be distinct keywords, conventionally namespaced (e.g.
  `:migration/mtp-tag-sources`); `run-migrations!` applies, in vector order, any whose
  id is not yet recorded."
  [{:migration/id :migration/mtp-tag-sources :migration/migrate migrate-mtp-tag-sources!}
   {:migration/id :migration/extended-tag-fields :migration/migrate migrate-extended-tag-fields!}])

;; --- framework: applied-id ledger + runner -----------------------------------

(defn- valid-registry?
  "True when every migration has a keyword :migration/id and the ids are distinct."
  [migrations]
  (let [ids (map :migration/id migrations)]
    (and (every? keyword? ids)
         (= (count ids) (count (distinct ids))))))

(defn applied-ids
  "The set of migration ids already applied to the DB (empty on a fresh DB)."
  [db]
  (set (d/q '[:find [?id ...] :where [_ :migration/id ?id]] db)))

(defn applied
  "Every applied migration as {:migration/id :migration/applied-at}, in application
  order. Ordered by entity id (assigned in ascending transaction order), so it is
  stable even when two migrations recorded in the same millisecond share an
  :migration/applied-at timestamp."
  [db]
  (->> (d/q '[:find [(pull ?e [:db/id :migration/id :migration/applied-at]) ...]
              :where [?e :migration/id]]
            db)
       (sort-by :db/id)
       (mapv #(dissoc % :db/id))))

(defn record-applied!
  "Record migration `id` as applied, stamped with the current time. Called by
  `run-migrations!` after a migration runs."
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
                     {:ids (map :migration/id migrations)})))
   (let [done    (applied-ids (d/db conn))
         pending (remove #(contains? done (:migration/id %)) migrations)]
     (doseq [{:migration/keys [id migrate]} pending]
       (migrate conn)
       (record-applied! conn id))
     (mapv :migration/id pending))))
