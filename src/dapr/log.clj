(ns dapr.log
  "Application logging via Telemere. Business code emits Telemere signals
  (taoensso.telemere/log!); this namespace owns the handlers:

    - a **file** handler writing to a fresh `dapr.N.log` per launch (never
      overwriting an existing log), under the `:log-dir` setting or the system
      temp dir; and
    - a **UI** ring-buffer handler that mirrors each signal into the app state's
      activity log (state/append-log) so the live log window renders it reactively.

  Telemere's default min level is set to :info, so the chatty per-file scan lines
  (emitted at :debug) stay out of the file and UI unless the level is lowered."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [dapr.state :as state]
            [taoensso.telemere :as t])
  (:import (java.time ZoneId)
           (java.time.format DateTimeFormatter)))

(def ^:private time-fmt
  (.withZone (DateTimeFormatter/ofPattern "HH:mm:ss") (ZoneId/systemDefault)))

(defn log-dir
  "Directory log files are written to: the `:log-dir` app setting, or the system
  temp dir when it is unset (nil)."
  [settings]
  (or (:log-dir settings) (System/getProperty "java.io.tmpdir")))

(defn next-log-file
  "Path of the next free `dapr.N.log` in `dir` — the smallest unused N from 0 — so a
  new launch never overwrites an earlier run's log. Creates `dir` if needed."
  [dir]
  (let [d (io/file dir)]
    (.mkdirs d)
    (loop [n 0]
      (let [f (io/file d (format "dapr.%d.log" n))]
        (if (.exists f) (recur (inc n)) (.getPath f))))))

(defn signal->line
  "Format a Telemere signal as one activity-log line: `HH:mm:ss LEVEL message`."
  [{:keys [inst level msg_]}]
  (format "%s %-5s %s"
          (.format time-fmt inst)
          (str/upper-case (name (or level :info)))
          (force msg_)))

(defn- install-file-handler!
  "(Re)point the file handler at a fresh dapr.N.log under `dir`, record the path in
  `state-atom` (so the settings UI can show it), and announce it. Returns the path."
  [state-atom dir]
  (t/remove-handler! :dapr/file)
  (let [path (next-log-file dir)]
    (t/add-handler! :dapr/file (t/handler:file {:path path}))
    (swap! state-atom state/set-log-file path)
    (t/log! (str "Logging to " path))
    path))

(defn configure!
  "Install the file + UI handlers and pin the min level to :info. The UI handler
  mirrors each signal into `state-atom` (state/append-log) for the live log window;
  the file handler writes to a fresh dapr.N.log under `dir`. Returns the log path."
  [state-atom dir]
  (t/set-min-level! :info)
  (t/add-handler! :dapr/ui
                  (fn ([] nil)
                    ([signal] (swap! state-atom state/append-log (signal->line signal)))))
  (install-file-handler! state-atom dir))

(defn set-dir!
  "Switch file logging to a fresh dapr.N.log under `dir` (e.g. when the user picks a
  new log directory). Returns the new path."
  [state-atom dir]
  (install-file-handler! state-atom dir))

(defn shutdown!
  "Remove Dapr's handlers (the file handler flushes/closes on stop)."
  []
  (t/remove-handler! :dapr/file)
  (t/remove-handler! :dapr/ui))
