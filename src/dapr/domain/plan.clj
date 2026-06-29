(ns dapr.domain.plan
  "Pure computation of a selective sync plan: make the sink hold exactly the
  selected tracks, via add / move / delete / skip actions. No I/O.

  Input map:
    :source-catalog  key -> track (from the source library)
    :sink-catalog    key -> track (from the sink library)
    :selected        set of selected track keys
    :sink-roots      ordered [{:uri :free-bytes}] for add placement
    :sink-only-handling  :keep (default) / :delete / :add-to-source — how to treat
                         tracks on the sink that are absent from the source
    :source-roots    ordered [{:uri :free-bytes}] for :add-to-source placement

  Track identity is [rel size] (root-excluded), so a track present on the sink at
  the same relative path is the same track regardless of which sink device holds
  it (-> :skip). A selected track with no match on the sink is added; an on-sink
  track that is not selected is deleted. (There is no move op: with rel-based
  identity a file moved to a new relative path is simply a delete of the old path
  plus an add of the new one.)

  Placement: a newly added track keeps its source-relative subpath (:rel) and is
  written under the first sink root whose remaining free space fits it; remaining
  free is threaded through placement so successive adds account for earlier ones.
  An add that fits no single root becomes a :blocked action.")

(defn- place
  "Choose a target {:root :rel} for adding `track` given `roots` (ordered) and
  `free` (a vector of remaining free bytes, parallel to roots). Returns
  [target updated-free], or [nil free] when no root fits."
  [track roots free]
  (let [n (count roots)]
    (loop [i 0]
      (cond
        (>= i n) [nil free]
        (>= (nth free i) (:size track))
        [{:root (:uri (nth roots i)) :rel (:rel track)}
         (update free i - (:size track))]
        :else (recur (inc i))))))

(defn- selected-step
  [roots {:keys [acc free]} src snk k]
  (cond
    snk
    {:acc (conj acc {:op :skip :key k}) :free free}

    :else
    (let [[target free'] (place src roots free)]
      (if target
        {:acc  (conj acc {:op     :add :key k
                          :src    {:root (:root src) :rel (:rel src)}
                          :target target
                          :size   (:size src)})
         :free free'}
        {:acc  (conj acc {:op :blocked :key k :reason :no-room :size (:size src)})
         :free free}))))

(defn- sink-only-keys
  "Sorted track keys present on the sink but absent from the source."
  [source-catalog sink-catalog]
  (sort (for [k (keys sink-catalog) :when (not (contains? source-catalog k))] k)))

(defn- sink-only-kept?
  "Whether sink-only tracks are retained (never deleted) under `handling`: :keep and
  :add-to-source both keep them; only :delete removes an unselected one."
  [handling]
  (contains? #{:keep :add-to-source} handling))

(defn- source-add-actions
  "Under :add-to-source handling, copy every sink-only track back into the source
  library: place each under the source roots (free threaded across adds) as an
  :add-to-source action, or :blocked when no source root fits. Mirrors the sink-add
  placement, but the target roots are the source's."
  [sink-catalog sink-only source-roots]
  (let [roots (vec source-roots)]
    (first
     (reduce (fn [[acc free] k]
               (let [t              (get sink-catalog k)
                     [target free'] (place t roots free)]
                 (if target
                   [(conj acc {:op   :add-to-source :key k
                               :src  {:root (:root t) :rel (:rel t)}
                               :target target
                               :size (:size t)})
                    free']
                   [(conj acc {:op :blocked :key k :reason :no-room :size (:size t)})
                    free])))
             [[] (mapv :free-bytes roots)]
             sink-only))))

(defn selection-plan
  "Compute the ordered vector of actions that makes the sink hold exactly the
  selected tracks. See the namespace docstring for the input map and placement.

  Sink-only tracks (on the sink, absent from the source) are governed by
  `:sink-only-handling` (default :keep):
    :keep           — retained (never deleted), regardless of selection
    :delete         — deleted when unselected, like any other on-sink track
    :add-to-source  — retained *and* copied back into the source library, placed
                      under `:source-roots` (an :add-to-source action, or :blocked)."
  [{:keys [source-catalog sink-catalog selected sink-roots sink-only-handling source-roots]}]
  (let [handling    (or sink-only-handling :keep)
        roots       (vec sink-roots)
        sel         (sort (filter #(contains? source-catalog %) selected))
        placed      (reduce (fn [state k]
                              (selected-step roots state
                                             (get source-catalog k)
                                             (get sink-catalog k)
                                             k))
                            {:acc [] :free (mapv :free-bytes roots)}
                            sel)
        sink-only   (sink-only-keys source-catalog sink-catalog)
        source-adds (when (= handling :add-to-source)
                      (source-add-actions sink-catalog sink-only source-roots))
        deletes     (sort-by :key
                             (for [[k t] sink-catalog
                                   :when (and (not (contains? selected k))
                                              ;; sink-only tracks are kept under
                                              ;; :keep / :add-to-source
                                              (not (and (sink-only-kept? handling)
                                                        (not (contains? source-catalog k)))))]
                               {:op :delete :key k
                                :at {:root (:root t) :rel (:rel t)} :size (:size t)}))]
    (vec (concat (:acc placed) (or source-adds []) deletes))))

(defn summary
  "Summarize a plan: op counts and bytes added (to the sink), copied to the source,
  and freed."
  [plan]
  (let [by-op (group-by :op plan)
        bytes (fn [op] (reduce + 0 (map :size (get by-op op))))]
    {:add             (count (:add by-op))
     :add-to-source   (count (:add-to-source by-op))
     :delete          (count (:delete by-op))
     :skip            (count (:skip by-op))
     :blocked         (count (:blocked by-op))
     :bytes-added     (bytes :add)
     :bytes-to-source (bytes :add-to-source)
     :bytes-freed     (bytes :delete)}))
