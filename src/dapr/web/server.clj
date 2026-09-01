(ns dapr.web.server
  "The HTTP server the UI is served from.

  It binds to the loopback interface by default: this is a desktop application
  that happens to speak HTTP, and its endpoints act on the user's own libraries
  and devices with no authentication of any kind. Anything else would put a
  device-writing API on the network. `:host` is still configurable, because an
  Electron (or container) shell may need a different interface — but the default
  is the one that is safe to forget about."
  (:require [clojure.string :as str]
            [dapr.web.events :as events]
            [dapr.web.routes :as routes]
            [org.httpkit.server :as http]
            [taoensso.telemere :as t])
  (:import (java.awt Desktop Desktop$Action)
           (java.net URI)))

(def default-port
  "Port the UI is served on unless told otherwise. Fixed rather than ephemeral so
  a bookmark — or an Electron window's start URL — keeps working across restarts;
  0 asks the OS for any free port."
  7373)

(defn- open-in-browser!
  "Best-effort: show `url` in the user's browser. Failure is logged and no more —
  the server is up either way, and the URL is on the console."
  [url]
  (future
    (try
      (if (and (Desktop/isDesktopSupported)
               (.isSupported (Desktop/getDesktop) Desktop$Action/BROWSE))
        (.browse (Desktop/getDesktop) (URI. url))
        (let [os  (str/lower-case (System/getProperty "os.name"))
              cmd (cond
                    (str/includes? os "mac") ["open" url]
                    (str/includes? os "win") ["rundll32" "url.dll,FileProtocolHandler" url]
                    :else                    ["xdg-open" url])]
          (.start (ProcessBuilder. ^java.util.List cmd))))
      (catch Throwable t
        (t/log! {:level :debug :error t :msg (str "Couldn't open a browser at " url)})))))

(defn start!
  "Start the UI server and return {:stop! :port :url}. `open-browser?` also points
  the user's browser at it, which is what makes `clojure -M:run` feel like
  launching an app; an Electron shell passes false and opens its own window."
  [{:keys [state-atom cache track-index refresher host port open-browser? on-quit events]}]
  (let [host    (or host "127.0.0.1")
        ;; Watches the state atom and pushes "region X moved" to every open page
        ;; (see dapr.web.events), on the timings config.edn gives it. Started
        ;; before the handler so no request can reach /events without one.
        hub     (events/start! state-atom events)
        handler (routes/handler state-atom {:cache     cache
                                            :track-index track-index
                                            :refresher refresher
                                            :hub       hub
                                            :on-quit   (or on-quit (fn []))})
        server  (http/run-server handler {:ip host
                                          :port (or port default-port)
                                          :legacy-return-value? false})
        port    (http/server-port server)
        url     (format "http://%s:%d/" host port)]
    (t/log! (str "Dapr UI at " url))
    (println "Dapr is running at" url)
    (when open-browser? (open-in-browser! url))
    {:stop! (fn []
              @(http/server-stop! server {:timeout 500})
              (events/stop! hub))
     :port  port
     :url   url}))

(defn stop!
  "Stop a server started by start!."
  [server]
  (when-let [f (:stop! server)]
    (f)))
