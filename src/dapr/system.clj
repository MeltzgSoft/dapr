(ns dapr.system
  "Integrant system definition. The only stateful components are the application
  state atom and the cljfx renderer that mounts onto it; all logic lives in the
  pure namespaces. (No HTTP server or DB, so the reitit and Integrant DB-pool
  conventions from the backend best-practices do not apply.)"
  (:require [cljfx.api :as fx]
            [clojure.java.io :as io]
            [dapr.db.cache :as cache]
            [dapr.device.coordinator :as coord]
            [dapr.device.mtp.fs :as mtp-fs]
            [dapr.device.smb.fs :as smb-fs]
            [dapr.library.store :as store]
            [dapr.log :as log]
            [dapr.db.migrations :as migrations]
            [dapr.refresh :as refresh]
            [dapr.state :as state]
            [dapr.ui.events :as events]
            [dapr.ui.views :as views]
            [datascript.core :as d]
            [integrant.core :as ig])
  (:import (javafx.application Platform)
           (javafx.beans.value ChangeListener)))

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

(defmethod ig/init-key :dapr/refresher [_ {:keys [state-atom cache]}]
  (refresh/start! {:state-atom state-atom :cache cache}))

(defmethod ig/halt-key! :dapr/refresher [_ refresher]
  ;; Ordered before :dapr/devices (which this component refs), so the worker has
  ;; left the device before its session is closed.
  (refresh/stop! refresher))

(defn- color-scheme->kw
  "Map a javafx.application.ColorScheme to :dark/:light (nil when unrecognized)."
  [cs]
  (case (str cs)
    "DARK"  :dark
    "LIGHT" :light
    nil))

(defn- watch-os-color-scheme!
  "On the FX thread, read the OS colour scheme into state and add a listener so the
  :system theme follows the OS live (see dapr.ui.format/active-theme). Best-effort:
  on platforms/headless runs where Platform/getPreferences is unavailable it leaves
  :os-color-scheme nil (the :system theme then falls back to light)."
  [state-atom]
  (Platform/runLater
   (fn []
     (try
       (let [prefs   (Platform/getPreferences)
             record! (fn [cs] (swap! state-atom state/set-os-color-scheme (color-scheme->kw cs)))]
         (record! (.getColorScheme prefs))
         (.addListener (.colorSchemeProperty prefs)
                       (reify ChangeListener
                         (changed [_ _ _ new-val] (record! new-val)))))
       (catch Throwable _)))))

(defmethod ig/init-key :dapr/renderer [_ {:keys [state-atom cache refresher]}]
  (let [handler  (events/make-handler state-atom cache refresher)
        renderer (fx/create-renderer
                  :middleware (fx/wrap-map-desc (fn [s] (views/root-view s)))
                  :opts {:fx.opt/map-event-handler handler})]
    (fx/mount-renderer state-atom renderer)
    ;; Follow the OS colour scheme so the :system theme tracks it live.
    (watch-os-color-scheme! state-atom)
    ;; Paint any persisted default source/sink from the cache and queue the
    ;; background refresh of every library.
    (events/start! state-atom cache refresher)
    {:renderer renderer :state-atom state-atom}))

(defmethod ig/halt-key! :dapr/renderer [_ {:keys [renderer state-atom]}]
  (when (and renderer state-atom)
    (fx/unmount-renderer state-atom renderer)))
