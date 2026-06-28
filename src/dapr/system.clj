(ns dapr.system
  "Integrant system definition. The only stateful components are the application
  state atom and the cljfx renderer that mounts onto it; all logic lives in the
  pure namespaces. (No HTTP server or DB, so the reitit and Integrant DB-pool
  conventions from the backend best-practices do not apply.)"
  (:require [cljfx.api :as fx]
            [clojure.java.io :as io]
            [dapr.cache :as cache]
            [dapr.library.store :as store]
            [dapr.state :as state]
            [dapr.ui.events :as events]
            [dapr.ui.views :as views]
            [datascript.core :as d]
            [integrant.core :as ig]))

(defn config
  "Read the Integrant system configuration from resources/config.edn."
  []
  (-> (io/resource "config.edn")
      (slurp)
      (ig/read-string)))

(defmethod ig/init-key :dapr/cache [_ _]
  (let [path   (cache/default-path!)
        conn   (cache/load! path)
        legacy (store/default-path!)]
    ;; First run on an existing install: import libraries.edn into the DB (which
    ;; then becomes the system of record) and persist the import.
    (when (and (empty? (cache/libraries (d/db conn))) (.exists (io/file legacy)))
      (cache/migrate-from-edn! conn (store/load! legacy))
      (cache/snapshot! conn path))
    {:conn conn :path path}))

(defmethod ig/halt-key! :dapr/cache [_ {:keys [conn path]}]
  (when (and conn path)
    (cache/snapshot! conn path)))

(defmethod ig/init-key :dapr/state [_ {:keys [cache]}]
  (let [db (d/db (:conn cache))]
    (atom (-> state/initial-state
              (state/set-libraries (cache/libraries db))
              (state/set-settings (cache/app-settings db))
              ;; Pre-select the persisted default source/sink so a launch lands
              ;; ready to sync (their catalogs are loaded by the renderer once it
              ;; mounts — see events/start!).
              (assoc :source-id (cache/default-library db :source)
                     :sink-id   (cache/default-library db :sink))))))

(defmethod ig/halt-key! :dapr/state [_ state-atom]
  (reset! state-atom state/initial-state))

(defmethod ig/init-key :dapr/renderer [_ {:keys [state-atom cache]}]
  (let [handler  (events/make-handler state-atom cache)
        renderer (fx/create-renderer
                  :middleware (fx/wrap-map-desc (fn [s] (views/root-view s)))
                  :opts {:fx.opt/map-event-handler handler})]
    (fx/mount-renderer state-atom renderer)
    ;; Kick off the initial catalog load for any persisted default source/sink.
    (events/start! state-atom cache)
    {:renderer renderer :state-atom state-atom}))

(defmethod ig/halt-key! :dapr/renderer [_ {:keys [renderer state-atom]}]
  (when (and renderer state-atom)
    (fx/unmount-renderer state-atom renderer)))
