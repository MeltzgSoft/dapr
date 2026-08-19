(ns dapr.device.format
  "Pure device metadata and URI-derived formatting hooks. Device namespaces
  install methods keyed by the library root URI scheme keyword (:file, :mtp,
  :smb), keeping common domain/UI code free of scheme-specific branches."
  (:require [clojure.string :as str])
  (:import (java.net URI URISyntaxException)))

(def types
  "Supported library device types, in the order shown by the UI."
  [:file :mtp :smb])

(defn scheme
  "Lowercased URI scheme of `uri-str`, or nil if not a string or unparseable."
  [uri-str]
  (when (string? uri-str)
    (try
      (some-> (URI. ^String uri-str) (.getScheme) (str/lower-case))
      (catch URISyntaxException _ nil))))

(defn device-type
  "Device type keyword for a URI string, derived from its scheme."
  [uri-str]
  (some-> (scheme uri-str) keyword))

(defmulti supported?
  "True when `device-type` is a supported library root kind."
  identity)

(defmulti root-device-key
  "Key identifying the concrete device/share a root lives on."
  (fn [uri-str] (device-type uri-str)))

(defmulti arbitrate-access?
  "True when access to a device of this type should be handed out to one holder at a
  time, so a user's operation can preempt the background refresher rather than
  compete with it (see dapr.device.coordinator). Two different reasons lead here —
  the driver genuinely serializes access (mtp://), or the device is reached over one
  slow shared connection that a walk would otherwise saturate (smb://) — while a
  device that is cheap and parallel-safe (file://) declares false.

  Defaults to true: over-arbitrating a new device type merely serializes it, whereas
  under-arbitrating one lets a background scan starve a user's sync."
  identity)

(defmulti selectable-root?
  "True when `uri-str` points at a folder that can be saved as a library root."
  (fn [uri-str] (device-type uri-str)))

(defmulti library-menu-label
  "Label for the New Library device menu."
  identity)

(defmulti browser-root-label
  "Label for the root/places breadcrumb button."
  (fn [browser] (:device/type browser)))

(defmulti browser-current-location
  "Human-readable text for the browser's current selected location."
  (fn [browser] (:device/type browser)))

(defmethod supported? :default [_] false)
(defmethod root-device-key :default [_] nil)
(defmethod arbitrate-access? :default [_] true)
(defmethod selectable-root? :default [_] false)
(defmethod library-menu-label :default [device-type] (name device-type))
(defmethod browser-root-label :default [_] "Places")
(defmethod browser-current-location :default [{:keys [cwd]}] (or cwd "Pick a location to start"))

(defn supported-root?
  "True when `uri-str` uses a supported device scheme."
  [uri-str]
  (supported? (device-type uri-str)))
