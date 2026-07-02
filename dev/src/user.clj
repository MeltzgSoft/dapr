(ns user
  "REPL workflow helpers (Integrant). Start the app with (go), reload changed
  namespaces and restart with (reset), and stop it with (halt)."
  (:require [integrant.repl :as ig-repl]
            [dapr.system :as system]))

(ig-repl/set-prep! system/config!)
(defn go [] (ig-repl/go))
(defn reset [] (ig-repl/reset))
(defn halt [] (ig-repl/halt))

(comment
  (go)
  (reset)
  (halt))

