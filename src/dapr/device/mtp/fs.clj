(ns dapr.device.mtp.fs
  "MTP device enumeration via melt-jfs. This is the ONLY namespace that touches
  melt-jfs classes; device discovery runs on a background thread off the browser
  (see dapr.device.mtp.events/open-browser!), and any failure degrades to an empty
  device list so tests and lint never have to reach the native libraries.

  Device discovery needs native libmtp/WPD access at runtime, enabled by the JVM
  option --enable-native-access=ALL-UNNAMED (set in the :run and :dev aliases).
  File copy/delete/scan against an mtp:// URI go through the generic NIO code in
  dapr.fs.nio; only device discovery and FileSystem opening are here."
  (:require [clojure.string :as str]
            [dapr.device.fs :as dfs]
            [taoensso.telemere :as t])
  (:import (java.net URI)
           (java.nio.file FileSystemNotFoundException FileSystems Files LinkOption Paths)
           (org.meltzg.fs.mtp MTPDeviceBridge)))

(defonce ^:private bridge-touched?
  ;; Whether this process has actually reached for the native bridge. MTPDeviceBridge
  ;; is an enum singleton whose getInstance *detects devices and opens a session per
  ;; device* — so it is not free to call, and in particular closing "just in case"
  ;; summons the very thing it means to release (see close!). defonce so a REPL
  ;; namespace reload can't forget a session this process still holds.
  (atom false))

(defn- mark-touched!
  "Record that we are about to reach for the bridge, so halt knows there is
  something to release. Called *before* the reach: a detection that opened sessions
  and then threw still needs closing."
  []
  (reset! bridge-touched? true))

(defn- ensure-filesystem!
  "Ensure the MTP filesystem addressed by `uri` is open."
  [^URI uri]
  (try
    (FileSystems/getFileSystem uri)
    (catch FileSystemNotFoundException _
      (mark-touched!)
      (FileSystems/newFileSystem uri {}))))

(defmethod dfs/root-path! :mtp [uri-str]
  (let [uri (URI. ^String uri-str)]
    (ensure-filesystem! uri)
    (Paths/get uri)))

(defmethod dfs/dir-children! :mtp [uri]
  (dfs/directory-children! (dfs/root-path! uri) dfs/directory?))

(defmethod dfs/available? :mtp [uri-str]
  ;; Opening the MTP filesystem for a disconnected device (or with no native MTP
  ;; access) throws; catch Throwable so a native/linkage failure degrades to
  ;; unavailable rather than crashing the probe.
  (try
    (Files/isDirectory (dfs/root-path! uri-str) (make-array LinkOption 0))
    (catch Throwable _ false)))

(defn- device-label
  "A non-blank display name for a device. libmtp often returns an empty
  friendlyName (the user never named the device), so fall back to the
  description, then the manufacturer, then the raw id."
  [info id-str]
  (or (->> [(.friendlyName info) (.description info) (.manufacturer info)]
           (remove str/blank?)
           (first))
      id-str))

(def ^:private close-timeout-millis
  "How long close! waits for the native bridge to release the device before giving
  up on it. libmtp can sit in a USB reset for a long time when a device is wedged
  (PTP_ERROR_IO ... trying again after resetting USB interface), and this runs on
  the system halt — so an unbounded wait hangs shutdown, and with it an ig-repl
  reset."
  5000)

(defn- close-bridge!
  "Close the native bridge. Split out from close! so the decision to call it at all
  is testable without native access."
  []
  (.close (MTPDeviceBridge/getInstance)))

(defn close!
  "Release every open native MTP device session so the device isn't left locked for
  other apps.

  **A no-op unless this process actually opened one.** `getInstance` creates the
  bridge if it does not exist, detecting devices and opening a session to each, so
  an unconditional close reaches for the hardware precisely to let go of it: on a
  machine with a phone plugged in that cost a multi-second USB round trip on every
  halt — and, against a wedged device, an unbounded PTP_ERROR_IO reset loop — even
  when the app had never touched MTP. That is what hung `(reset)` in the REPL.

  Bounded by close-timeout-millis for the case where we *did* open a session and the
  device will not let go: the halt moves on and leaves the rest to the JVM exit,
  mirroring dapr.refresh/stop!. Best-effort otherwise — no device or no native
  libmtp means nothing to close. The bridge re-detects on the next getInstance, so
  this is safe across a REPL reset."
  []
  (when (compare-and-set! bridge-touched? true false)
    (let [done (future (try (close-bridge!) (catch Throwable _ nil)))]
      (when (= ::timeout (deref done close-timeout-millis ::timeout))
        (t/log! {:level :warn
                 :msg   "MTP device did not release in time; leaving it to the JVM exit."})))))

(defn devices!
  "Detect connected MTP devices and return a vector of endpoints
  {:id <vendor:product:serial> :name <display-name> :uri \"mtp://<id>/\"}."
  []
  (mark-touched!)
  (let [bridge (MTPDeviceBridge/getInstance)]
    (->> (.getDeviceInfo bridge)
         (mapv (fn [entry]
                 (let [id-str (.toString ^Object (key entry))
                       info   (val entry)]
                   {:id   id-str
                    :name (device-label info id-str)
                    :uri  (str "mtp://" id-str "/")}))))))
