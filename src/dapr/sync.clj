(ns dapr.sync
  "Side-effecting execution of a selective library sync: the plan is computed by
  the pure dapr.domain.plan from catalogs the cache already holds, and this
  namespace queries capacity and performs the add/delete operations (all fns end
  in !). Scanning libraries is not here — the background refresher
  (dapr.refresh) owns every device walk."
  (:require [dapr.db.cache :as cache]
            [dapr.device.fs :as device-fs]
            [dapr.fs.nio :as nio]))

(defn apply-plan-to-cache!
  "Reflect an executed selection plan in the sink's cache entry under library
  entity `sink-id`: add a presence (carrying the source's tags from
  `source-catalog`) for each :add action and drop one for each :delete, so the
  cache stays correct without re-walking the sink. Added presences omit mtime (the
  freshly copied files have new mtimes not stat'd here), so a later sink scan
  simply re-reads those few tags. :skip/:blocked actions are ignored."
  [conn sink-id source-catalog actions]
  (doseq [a actions]
    (case (:op a)
      :add    (let [t (get source-catalog (:key a))]
                (cache/add-presence! conn sink-id
                                     {:rel             (get-in a [:target :rel])
                                      :size            (:size a)
                                      :root            (get-in a [:target :root])
                                      :artist          (:artist t)
                                      :album           (:album t)
                                      :title           (:title t)
                                      :genre           (:genre t)
                                      :track-number    (:track-number t)
                                      :disc-number     (:disc-number t)
                                      :duration-millis (:duration-millis t)}))
      :delete (cache/remove-presence! conn sink-id (get-in a [:at :rel]) (:size a))
      nil)))

(defn library-roots!
  "Per-root free space for a library, in library order (placement input). Used as
  the sink's add targets and the source's :add-to-source targets."
  [{:keys [roots]}]
  (mapv nio/root-free! roots))

(defn apply-source-adds-to-cache!
  "Register a presence on the source library `source-id` for each :add-to-source
  action (a sink-only track copied back into the source), carrying the track's tags
  from `sink-catalog`. Other ops are ignored, so this is safe to call alongside
  apply-plan-to-cache! on any plan."
  [conn source-id sink-catalog actions]
  (doseq [a actions :when (= :add-to-source (:op a))]
    (let [t (get sink-catalog (:key a))]
      (cache/add-presence! conn source-id
                           {:rel             (get-in a [:target :rel])
                            :size            (:size a)
                            :root            (get-in a [:target :root])
                            :artist          (:artist t)
                            :album           (:album t)
                            :title           (:title t)
                            :genre           (:genre t)
                            :track-number    (:track-number t)
                            :disc-number     (:disc-number t)
                            :duration-millis (:duration-millis t)}))))

(defn library-free!
  "Total usable bytes across the distinct devices backing a library's roots."
  [{:keys [roots]}]
  (nio/library-free! roots))

(defn execute-plan!
  "Execute plan `actions`: copies and deletes flow through dapr.fs.nio. An :add
  copies a source track onto the sink; an :add-to-source copies a sink-only track
  back onto the source (both are src->target file copies). :skip and :blocked
  actions are ignored. When supplied, calls (on-progress {:done n :total t :action
  a}) after each performed action. Returns {:add n :add-to-source n :delete n}."
  ([actions] (execute-plan! actions nil))
  ([actions {:keys [on-progress]}]
   (let [resolve-root (memoize device-fs/root-path!)
         todo  (remove (comp #{:skip :blocked} :op) actions)
         total (count todo)]
     (reduce
      (fn [acc [i a]]
        (case (:op a)
          (:add :add-to-source) (nio/copy-file! (resolve-root (get-in a [:src :root]))
                                                (resolve-root (get-in a [:target :root]))
                                                (get-in a [:src :rel]))
          :delete (nio/delete-file! (resolve-root (get-in a [:at :root]))
                                    (get-in a [:at :rel])))
        (when on-progress
          (on-progress {:done (inc i) :total total :action a}))
        (update acc (:op a) (fnil inc 0)))
      {:add 0 :add-to-source 0 :delete 0}
      (map-indexed vector todo)))))
