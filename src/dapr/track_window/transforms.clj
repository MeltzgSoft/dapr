(ns dapr.track-window.transforms
  "Pure derivation for the virtualized track table.

  The browser only keeps one moving window of rows in the DOM. This namespace
  determines the stable ordering, clamps a requested window into the catalog,
  and reports the spacer heights that make the scrollbar represent every track."
  (:require [dapr.ui.format :as fmt]))

(def window-size
  "Maximum real track rows kept in the browser at once."
  200)

(def row-height
  "Fixed rendered height of one track row, in CSS pixels. Kept in sync with
  resources/public/dapr.css and the browser scroll controller."
  31)

(defn index-key
  "Cache identity for a filtered, sorted catalog. Selection and capacity are
  deliberately absent: they change row controls, never membership or order."
  [state view]
  [(:catalog-version state) (:filter state) (:sort view) (:dir view)])

(defn ordered-keys
  "Track keys in the filtered display order requested by `view`. This is the
  expensive O(n log n) derivation cached by dapr.track-window.db."
  [state view]
  (mapv :key (fmt/sort-rows (fmt/track-rows state) (:sort view) (:dir view))))

(defn normalize-start
  "Clamp a requested zero-based row offset so a full window is shown whenever
  the catalog has enough rows. Invalid and nil offsets mean the beginning."
  [total requested]
  (let [total     (max 0 (long (or total 0)))
        requested (max 0 (long (or requested 0)))
        max-start (max 0 (- total window-size))]
    (min requested max-start)))

(defn window
  "Slice `ordered-keys` at `requested-start` and describe the bounded DOM window.
  Spacer heights preserve the full scroll range without rendering off-screen
  rows."
  [ordered-keys requested-start]
  (let [total  (count ordered-keys)
        start  (normalize-start total requested-start)
        end    (min total (+ start window-size))]
    {:start        start
     :end          end
     :total        total
     :keys         (subvec (vec ordered-keys) start end)
     :top-height   (* start row-height)
     :bottom-height (* (- total end) row-height)}))
