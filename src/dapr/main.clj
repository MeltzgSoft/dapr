(ns dapr.main
  "Application entry point: starts the Integrant system — whose last component is
  the local web server the UI is served from — and installs a shutdown hook to
  stop it cleanly (which is what snapshots the cache on the way out).

  The process then parks: nothing else here needs a thread, and the app should
  outlive the launching shell until the user quits it from the UI (or the JVM is
  signalled). `--no-browser` is what an Electron shell wants: it starts Dapr and
  points its own window at the printed URL."
  (:require [clojure.string :as str]
            [integrant.core :as ig]
            [dapr.system :as system])
  (:gen-class))

(defn- env
  "Non-blank value of environment variable `n`, or nil."
  [n]
  (some-> (System/getenv n) str/trim not-empty))

(defn- arg-value
  "The value following `flag` in `args`, or nil when the flag is absent or last."
  [args flag]
  (let [i (.indexOf ^java.util.List args flag)]
    (when (and (nat-int? i) (< (inc i) (count args)))
      (nth args (inc i)))))

(defn parse-args
  "Server overrides from the command line, falling back to the environment:
  `--port N` / DAPR_PORT, `--host H` / DAPR_HOST, `--no-browser` / DAPR_NO_BROWSER.
  Only the keys actually given are returned, so the rest keep their config.edn
  values."
  [args]
  (let [args        (vec args)
        port        (or (some-> (arg-value args "--port") str/trim parse-long)
                        (some-> (env "DAPR_PORT") parse-long))
        host        (or (arg-value args "--host") (env "DAPR_HOST"))
        no-browser? (or (some? (some #{"--no-browser"} args)) (some? (env "DAPR_NO_BROWSER")))]
    (cond-> {}
      port        (assoc :port port)
      host        (assoc :host host)
      no-browser? (assoc :open-browser? false))))

(defn config
  "The system configuration with `args`' server overrides applied, plus the quit
  action: the UI's Quit stops the JVM, and the shutdown hook halts the system."
  [args]
  (update (system/config!) :dapr/server merge
          (parse-args args)
          ;; Answer the request first, then go down — a browser that never got its
          ;; \"Dapr has stopped\" page would just look broken.
          {:on-quit (fn [] (future (Thread/sleep 250) (System/exit 0)))}))

(defn -main [& args]
  (let [sys (ig/init (config args))]
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. ^Runnable (fn [] (ig/halt! sys))))
    @(promise)))
