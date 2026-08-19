(ns dapr.library.catalogs
  "Painting the in-memory catalogs (dapr.state) from the persisted scan cache
  (dapr.db.cache). The UI never walks a device to fill its table: the background
  refresher owns all scanning and writes what it finds into the cache, and this
  namespace is the one seam that projects that cache into the app state — on
  startup, when the source/sink changes, and each time a refresh makes progress.

  Side-effecting (it reads the sink's free space and swaps the state atom), so it
  sits outside the pure layers."
  (:require [dapr.db.cache :as cache]
            [dapr.device.coordinator :as coord]
            [dapr.state :as state]
            [dapr.sync :as sync]
            [datascript.core :as d]))

(defn library-free!
  "Usable bytes across `library`'s distinct devices — the one catalog input the
  cache cannot answer, so it is a real device query. Taken under the library's
  device lock, so it queues behind (and preempts) a background walk of the same
  device rather than racing it. 0 for no library."
  [library]
  (if library
    (coord/with-device! (coord/library-device library) #(sync/library-free! library))
    0))

(defn paint!
  "Replace the source/sink catalogs in `state-atom` with the cache's current view,
  plus the sink's free space. A no-op without a chosen source; a chosen source with
  no sink paints its tracks alone (empty sink catalog, 0 free) so the table is
  browsable before a sink is picked.

  `preselect?` chooses the transition: true pre-selects the tracks already on the
  sink (state/set-catalogs — for a fresh source/sink choice), false keeps whatever
  the user has ticked (state/update-catalogs — for a background refresh landing
  mid-session). Returns {:source n :sink n :free bytes}, or nil when there is no
  source."
  [state-atom conn {:keys [preselect?]}]
  (let [s   @state-atom
        src (state/library-by-id s (:source-id s))
        snk (state/library-by-id s (:sink-id s))]
    (when src
      (let [db      (d/db conn)
            src-cat (cache/library-catalog db (:id src))
            snk-cat (if snk (cache/library-catalog db (:id snk)) {})
            free    (library-free! snk)]
        (swap! state-atom
               (if preselect? state/set-catalogs state/update-catalogs)
               src-cat snk-cat free)
        {:source (count src-cat) :sink (count snk-cat) :free free}))))
