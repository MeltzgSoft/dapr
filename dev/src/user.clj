(ns user
  "REPL workflow helpers (Integrant). Start the app with (go), reload changed
  namespaces and restart with (reset), and stop it with (halt).

  The dev system does *not* open a browser: (reset) is something you do dozens of
  times an hour, and each one would be another tab. Open the printed URL once and
  leave it — an open page picks new code up on its next poll, or on a refresh."
  (:require [integrant.repl :as ig-repl]
            [dapr.system :as system]))

(defn config
  "The system config with the browser launch turned off (see the ns docstring)."
  []
  (assoc-in (system/config!) [:dapr/server :open-browser?] false))

(ig-repl/set-prep! config)
(defn go [] (ig-repl/go))
(defn reset [] (ig-repl/reset))
(defn halt [] (ig-repl/halt))

(comment
  (go)
  (reset)
  (halt))
