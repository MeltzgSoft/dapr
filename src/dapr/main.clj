(ns dapr.main
  "Application entry point: starts the Integrant system — whose last component is
  the local web server the UI is served from — and installs a shutdown hook to
  stop it cleanly (which is what snapshots the cache on the way out).

  The process then parks: nothing else here needs a thread, and the app should
  outlive the launching shell until the user quits it from the UI (or the JVM is
  signalled). `--no-browser` is what an Electron shell wants: it starts Dapr and
  points its own window at the printed URL."
  (:require [clojure.string :as str]
            [clojure.tools.cli :as cli]
            [integrant.core :as ig]
            [dapr.system :as system])
  (:gen-class))

(def cli-options
  "Command-line interface, in clojure.tools.cli's format — which also validates
  the values and generates the --help summary, rather than each being hand-rolled
  here."
  [["-p" "--port PORT" "Port to serve the UI on (0 asks the OS for a free one)"
    :parse-fn parse-long
    :validate [#(<= 0 % 65535) "must be a port number between 0 and 65535"]]
   ["-H" "--host HOST" "Interface to bind; the default is loopback only"]
   [nil "--no-browser" "Start the server without opening a browser at it"]
   ["-h" "--help" "Print this message and exit"]])

(defn- env
  "Non-blank value of environment variable `n`, or nil."
  [n]
  (some-> (System/getenv n) str/trim not-empty))

(defn parse-args
  "Server overrides from the command line, falling back to the environment:
  `--port N` / DAPR_PORT, `--host H` / DAPR_HOST, `--no-browser` / DAPR_NO_BROWSER.
  Only the keys actually given are returned, so the rest keep their config.edn
  values.

  Returns {:overrides {...}} to run with, or {:exit-message s :ok? bool} when the
  process should print and stop instead (--help, or a bad argument)."
  [args]
  (let [{:keys [options errors summary]} (cli/parse-opts args cli-options)
        port        (or (:port options) (some-> (env "DAPR_PORT") parse-long))
        host        (or (:host options) (env "DAPR_HOST"))
        no-browser? (or (:no-browser options) (some? (env "DAPR_NO_BROWSER")))]
    (cond
      (:help options) {:exit-message (str "Dapr — sync a music library to a device.\n\n"
                                          "Options:\n" summary)
                       :ok?          true}
      errors          {:exit-message (str/join \newline errors) :ok? false}
      :else           {:overrides (cond-> {}
                                    port        (assoc :port port)
                                    host        (assoc :host host)
                                    no-browser? (assoc :open-browser? false))})))

(defn config
  "The system configuration with `overrides` applied to the server component, plus
  the quit action: the UI's Quit stops the JVM, and the shutdown hook halts the
  system."
  [overrides]
  (update (system/config!) :dapr/server merge
          overrides
          ;; Answer the request first, then go down — a browser that never got its
          ;; \"Dapr has stopped\" page would just look broken.
          {:on-quit (fn [] (future (Thread/sleep 250) (System/exit 0)))}))

(defn -main [& args]
  (let [{:keys [overrides exit-message ok?]} (parse-args args)]
    (if exit-message
      (do (println exit-message)
          (System/exit (if ok? 0 1)))
      (let [sys (ig/init (config overrides))]
        (.addShutdownHook (Runtime/getRuntime)
                          (Thread. ^Runnable (fn [] (ig/halt! sys))))
        @(promise)))))
