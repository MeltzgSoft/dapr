(ns dapr.track-window.db
  "Bounded in-memory cache for virtual track-table order indexes.

  An index contains only track keys. Dynamic row state (selection, capacity and
  sink-only handling) is projected after taking a window, so toggles never make
  an index stale. The component is local to one running server and owns no
  external resources.")

(def default-max-entries 16)

(defn create!
  "Create an empty least-recently-used index cache."
  [{:keys [max-entries]}]
  (atom {:max-entries (max 1 (long (or max-entries default-max-entries)))
         :order       []
         :entries     {}}))

(defn lookup-or-build!
  "Return the value cached under `k`, or build and retain it. Access refreshes
  recency; the oldest entry is evicted when the configured bound is exceeded."
  [cache k build]
  (locking cache
    (if-let [entry (find (:entries @cache) k)]
      (do
        (swap! cache update :order
               (fn [order] (conj (vec (remove #(= k %) order)) k)))
        (val entry))
      (let [value (build)]
        (swap! cache
               (fn [{:keys [max-entries order entries] :as current}]
                 (let [order   (conj (vec (remove #(= k %) order)) k)
                       overflow (- (count order) max-entries)
                       evicted (if (pos? overflow) (subvec order 0 overflow) [])
                       order   (if (pos? overflow) (subvec order overflow) order)
                       entries (-> (apply dissoc entries evicted)
                                   (assoc k value))]
                   (assoc current :order order :entries entries))))
        value))))

(defn clear!
  "Drop every derived index while retaining the cache configuration."
  [cache]
  (swap! cache assoc :order [] :entries {}))
