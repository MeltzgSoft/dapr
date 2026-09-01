(ns dapr.system
  "Integrant system definition. The stateful components are the application state
  atom, the DataScript cache, the background refresher and the HTTP server the UI
  is served from; all logic lives in the pure namespaces."
  (:require [clojure.java.io :as io]
            [dapr.db.cache :as cache]
            [dapr.db.migrations :as migrations]
            [dapr.device.availability :as availability]
            [dapr.device.coordinator :as coord]
            [dapr.device.mtp.fs :as mtp-fs]
            [dapr.device.smb.fs :as smb-fs]
            [dapr.library.store :as store]
            [dapr.log :as log]
            [dapr.refresh :as refresh]
            [dapr.state :as state]
            [dapr.track-window.db :as track-index]
            [dapr.ui.actions :as actions]
            [dapr.web.server :as server]
            [datascript.core :as d]
            [integrant.core :as ig]))

(defn config!
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
    ;; Run any pending DB migrations, persisting when one changed the DB.
    (when (seq (migrations/run-migrations! conn))
      (cache/snapshot! conn path))
    {:conn conn :path path}))

(defmethod ig/halt-key! :dapr/cache [_ {:keys [conn path]}]
  (when (and conn path)
    (cache/snapshot! conn path)))

(defmethod ig/init-key :dapr/state [_ {:keys [cache ui]}]
  (let [db (d/db (:conn cache))]
    (atom (-> state/initial-state
              (state/set-libraries (cache/libraries db))
              (state/set-settings (cache/app-settings db))
              (state/set-ui ui)
              ;; Pre-select the persisted default source/sink so a launch lands
              ;; ready to sync (their catalogs are painted from the cache once the
              ;; server is up — see actions/start!).
              (assoc :source-id (cache/default-library db :source)
                     :sink-id   (cache/default-library db :sink))))))

(defmethod ig/halt-key! :dapr/state [_ state-atom]
  (reset! state-atom state/initial-state))

(defmethod ig/init-key :dapr/track-index [_ opts]
  (track-index/create! opts))

(defmethod ig/halt-key! :dapr/track-index [_ index-cache]
  (track-index/clear! index-cache))

(defmethod ig/init-key :dapr/log [_ {:keys [cache state-atom]}]
  (let [settings (cache/app-settings (d/db (:conn cache)))
        path     (log/configure! state-atom (log/log-dir settings))]
    {:path path}))

(defmethod ig/halt-key! :dapr/log [_ _]
  (log/shutdown!))

(defmethod ig/init-key :dapr/devices [_ _]
  ;; Owns no state of its own. The SMB FileSystem cache and the MTP device bridge are
  ;; process-globals reached via the java.nio provider SPI (the device-generic scan
  ;; walker resolves a root URI to a Path with no component in hand), so this component
  ;; exists only to close those external sessions on halt — SMB so jcifs's non-daemon
  ;; connection threads don't outlive the app, MTP so the device isn't left locked.
  {})

(defmethod ig/halt-key! :dapr/devices [_ _]
  (smb-fs/close-all!)
  (mtp-fs/close!))

(defmethod ig/init-key :dapr/coordinator [_ _]
  ;; Like :dapr/devices, this owns no state of its own: the per-device locks are
  ;; process-globals (device access is reached through the java.nio provider SPI,
  ;; which carries no component context — see dapr.device.coordinator). The
  ;; component exists so a fresh system starts with no device held, and so the
  ;; refresher can depend on it.
  (coord/reset-locks!)
  {})

(defmethod ig/halt-key! :dapr/coordinator [_ _]
  (coord/reset-locks!))

(defmethod ig/init-key :dapr/availability [_ {:keys [state-atom interval-millis]}]
  (availability/start! {:state-atom state-atom :interval-millis interval-millis}))

(defmethod ig/halt-key! :dapr/availability [_ monitor]
  (availability/stop! monitor))

(defmethod ig/init-key :dapr/refresher [_ {:keys [state-atom cache workers]}]
  (refresh/start! {:state-atom state-atom :cache cache :workers workers}))

(defmethod ig/halt-key! :dapr/refresher [_ refresher]
  ;; Ordered before :dapr/devices (which this component refs), so the worker has
  ;; left the device before its session is closed.
  (refresh/stop! refresher))

(defmethod ig/init-key :dapr/server [_ {:keys [state-atom cache refresher] :as opts}]
  (let [srv (server/start! (assoc opts :on-quit (:on-quit opts (fn [] nil))))]
    ;; Paint any persisted default source/sink from the cache and queue a
    ;; background refresh of those two — the only libraries scanned unasked.
    (actions/start! state-atom cache refresher)
    srv))

(defmethod ig/halt-key! :dapr/server [_ srv]
  (server/stop! srv))
