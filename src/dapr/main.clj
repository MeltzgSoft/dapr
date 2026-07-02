(ns dapr.main
  "Application entry point: starts the Integrant system and installs a shutdown
  hook to stop it cleanly. The JavaFX Application Thread (a non-daemon thread
  started by cljfx) keeps the JVM alive; closing the window quits the app via
  the ::events/quit handler."
  (:require [integrant.core :as ig]
            [dapr.system :as system])
  (:import (javafx.application Platform))
  (:gen-class))

(defn -main [& _args]
  (Platform/setImplicitExit true)
  (let [sys (ig/init (system/config!))]
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. ^Runnable (fn [] (ig/halt! sys))))
    sys))
