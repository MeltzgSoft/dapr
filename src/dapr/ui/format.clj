(ns dapr.ui.format
  "Pure presentation helpers for the UI: human-readable formatting and derived
  predicates over the application state. No side effects and no JavaFX, so this
  logic is unit-testable in isolation (dapr.ui.views handles the rendering)."
  (:require [clojure.string :as str]))

(defn human-bytes
  "Format a byte count as a short human-readable string."
  [n]
  (let [n (or n 0)]
    (cond
      (< n 1024)               (str n " B")
      (< n (* 1024 1024))      (format "%.1f KB" (/ (double n) 1024))
      (< n (* 1024 1024 1024)) (format "%.1f MB" (/ (double n) (* 1024 1024)))
      :else                    (format "%.2f GB" (/ (double n) (* 1024 1024 1024))))))

(defn status-text
  "Human-readable label for a status keyword."
  [status]
  (case status
    :idle     "Idle"
    :scanning "Scanning…"
    :planned  "Plan ready"
    :syncing  "Syncing…"
    :done     "Done"
    :error    "Error"
    (str status)))

(defn busy?
  "True while a scan or sync is in progress."
  [status]
  (contains? #{:scanning :syncing} status))

(defn capacity-text
  "Render a capacity map (see dapr.domain.capacity/usage) as used / budget."
  [{:keys [used budget]}]
  (format "%s / %s" (human-bytes used) (human-bytes budget)))

(defn capacity-fraction
  "Fill fraction (0.0–1.0) for the capacity meter."
  [{:keys [used budget]}]
  (if (and budget (pos? budget))
    (min 1.0 (/ (double used) budget))
    0.0))

(defn over-capacity?
  [{:keys [used budget]}]
  (boolean (and used budget (> used budget))))

(defn- distinct-sorted
  "Non-nil values of `xs`, distinct and sorted."
  [xs]
  (->> xs (remove nil?) distinct sort vec))

(defn artists
  "Sorted distinct artists present in `catalog` (a key->track map). Tracks with no
  artist tag are omitted (they remain visible under the 'All' filter)."
  [catalog]
  (distinct-sorted (map :artist (vals catalog))))

(defn albums
  "Sorted distinct albums in `catalog`, restricted to `artist` when it is non-nil."
  [catalog artist]
  (distinct-sorted (->> (vals catalog)
                        (filter (fn [t] (or (nil? artist) (= artist (:artist t)))))
                        (map :album))))

(defn search-filter
  "The values of `xs` whose string form contains `q` (case-insensitive); all of
  `xs` when `q` is blank. Used to narrow a column-browser facet list as the user
  types."
  [xs q]
  (if (str/blank? q)
    (vec xs)
    (let [needle (str/lower-case q)]
      (filterv #(str/includes? (str/lower-case (str %)) needle) xs))))

(defn filter-catalog
  "Subset of `catalog` whose tracks match `filter` {:artist :album}; a nil filter
  field imposes no constraint on that field."
  [catalog {:keys [artist album]}]
  (into {} (filter (fn [[_ t]]
                     (and (or (nil? artist) (= artist (:artist t)))
                          (or (nil? album) (= album (:album t)))))
                   catalog)))

(defn plan-summary-text
  "Render a plan summary (see dapr.domain.plan/summary) as a one-liner."
  [summary]
  (if summary
    (str (format "Add %d (%s) · Delete %d (%s) · Skip %d"
                 (:add summary) (human-bytes (:bytes-added summary))
                 (:delete summary) (human-bytes (:bytes-freed summary))
                 (:skip summary))
         (when (pos? (:add-to-source summary 0))
           (format " · To source %d (%s)"
                   (:add-to-source summary) (human-bytes (:bytes-to-source summary))))
         (when (pos? (:blocked summary 0))
           (format " · Blocked %d" (:blocked summary))))
    "No plan yet."))

(defn library-unavailable?
  "True when library `id`'s availability has been probed and came back false, so
  the UI should grey it out and refuse selection. Unprobed libraries (absent from
  the id->bool `availability` map) are treated as available."
  [availability id]
  (false? (get availability id)))

(defn active-theme
  "Resolve the effective UI theme (:dark or :light) from the persisted `:theme`
  setting and the OS-reported colour scheme. An explicit :dark/:light wins; :system
  (or an unset theme) follows the OS, defaulting to :light when the OS scheme is
  unknown (nil)."
  [theme os-color-scheme]
  (case theme
    :dark  :dark
    :light :light
    (or os-color-scheme :light)))

(defn can-preview?
  "True when distinct source and sink libraries are chosen and not busy."
  [{:keys [source-id sink-id status]}]
  (boolean (and source-id sink-id (not= source-id sink-id) (not (busy? status)))))

(defn can-sync?
  "True when a plan is ready with at least one add, move, or delete."
  [{:keys [plan status]}]
  (boolean (and plan (= status :planned)
                (pos? (+ (get-in plan [:summary :add] 0)
                         (get-in plan [:summary :move] 0)
                         (get-in plan [:summary :delete] 0)
                         (get-in plan [:summary :add-to-source] 0))))))
