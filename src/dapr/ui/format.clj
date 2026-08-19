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

(defn duration-mmss
  "Format a millisecond duration as minutes:seconds (m:ss, zero-padded seconds),
  e.g. 210000 -> \"3:30\". nil (unknown duration) formats as blank. Minutes are not
  capped, so a track over an hour reads e.g. \"72:14\"."
  [ms]
  (if (some? ms)
    (let [secs (quot (long ms) 1000)]
      (format "%d:%02d" (quot secs 60) (rem secs 60)))
    ""))

(defn sort-key
  "Case-insensitive sort key for a track field: strings are lowercased so sorting
  ignores case; non-strings (numbers) and nil pass through unchanged (nil sorts
  first). Used both for the table's default row order and its per-column sort."
  [v]
  (cond-> v (string? v) str/lower-case))

(defn compare-field
  "Comparator for a track-table column, so clicking a header sorts case-insensitively.
  Orders by sort-key (case-insensitive for strings, clojure.core/compare's handling
  of numbers and nil otherwise), with a case-sensitive tiebreak so values equal but
  for case still order deterministically rather than arbitrarily."
  [a b]
  (let [c (compare (sort-key a) (sort-key b))]
    (if (and (zero? c) (string? a) (string? b)) (compare a b) c)))

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

(defn name-list
  "Render library names as a readable list: \"'A'\", \"'A' and 'B'\", \"'A', 'B' and
  'C'\". Used in the sync confirmation, which names the libraries still refreshing."
  [names]
  (let [quoted (mapv #(str "'" % "'") names)]
    (case (count quoted)
      0 ""
      1 (first quoted)
      (str (str/join ", " (butlast quoted)) " and " (last quoted)))))

(defn- library-name
  "Display name of library `id` in `libraries`, or a placeholder if it is gone."
  [libraries id]
  (or (:name (first (filter #(= (:id %) id) libraries))) "?"))

(defn progress-fraction
  "Fill fraction (0.0–1.0) for a {:done :total} counter pair, or nil while the
  total is still unknown — a walk only learns its total as it descends, so an
  empty bar is honest until then."
  [{:keys [done total]}]
  (when (and total (pos? total))
    (min 1.0 (/ (double (or done 0)) total))))

(defn- counts-text
  "\" 30 / 120\" for a counter pair, \"\" before a total is known."
  [{:keys [done total]}]
  (if (and total (pos? total)) (format " %d / %d" (or done 0) total) ""))

(def ^:private refresh-order
  "Row order for the background jobs — and, in its keys, which refresh states count
  as a job at all (:complete is absent: a library that is up to date has no work
  outstanding). Failures come first, since they need attention and nothing else
  will move them, then what is actually running, then what is waiting."
  {:error 0 :scanning 1 :paused 2 :pending 3})

(def ^:private refresh-labels
  "How a job reads in a row of its own. :pending has no entry on purpose: a merely
  queued library has nothing particular to show — no reason, no counters, no
  progress — so it is counted rather than given a row (see tasks)."
  {:error    "Failed"
   :scanning "Scanning…"
   :paused   "Paused"})

(def ^:private max-refresh-rows
  "How many background rows the status bar draws before counting the rest instead.
  The bar is pinned to the window bottom, so an uncapped row per library would
  squeeze the workspace above it on a large configuration."
  4)

(defn tasks
  "Every job worth a row in the status bar, in display order: the foreground
  operation, then a row per library the background refresher is scanning, has
  paused or has failed on, then a single row counting whatever is merely waiting
  for a turn — including any row past `max-refresh-rows`.

  Empty when nothing is happening, so the bar disappears rather than sitting there
  saying \"Idle\" beside a progress bar that will never fill. Only a *running* op
  gets a foreground row; the exception is a failed one, which keeps its row (with
  the reason) until the next op supersedes it, since a failure that reports itself
  nowhere is a failure the user never learns about. :planned and :done need no row:
  the plan summary and the log already say so.

  Each row is {:id :label :detail :progress :running? :error?}, where :id is a
  stable identity for the row, :label names the job, :detail says how it is going,
  :running? distinguishes a job that is moving from one that is waiting or dead
  (it drives the spinner in the summary), and :progress is a 0.0–1.0 fill — nil
  when there is nothing meaningful to fill (queued, failed, or a walk that has not
  yet learned its total), which renders as no bar at all rather than an empty one."
  [{:keys [status progress error refresh libraries]}]
  (let [{lib-status :status :keys [errors] lib-progress :progress} refresh
        foreground (cond
                     (busy? status)
                     {:id       :foreground
                      :label    "Status"
                      :detail   (str (status-text status) (counts-text progress))
                      :progress (progress-fraction progress)
                      :running? true
                      :error?   false}

                     (= :error status)
                     {:id     :foreground
                      :label  "Status"
                      :detail (or error (status-text status))
                      :error? true})
        jobs       (->> lib-status
                        (filter (comp refresh-order val))
                        (sort-by (fn [[id st]] [(refresh-order st) (library-name libraries id)])))
        detailed   (->> jobs (filter (comp refresh-labels val)) (take max-refresh-rows))
        row        (fn [[id st]]
                     (let [counters (get lib-progress id)
                           failed?  (= :error st)]
                       {:id       [:refresh id]
                        :label    (library-name libraries id)
                        :detail   (if failed?
                                    (or (get errors id) (refresh-labels st))
                                    (str (refresh-labels st) (counts-text counters)))
                        ;; A failed walk's counters say how far it got before it
                        ;; died, which is not progress toward anything.
                        :progress (when-not failed? (progress-fraction counters))
                        :running? (= :scanning st)
                        :error?   failed?}))
        waiting    (- (count jobs) (count detailed))]
    (cond-> (into (if foreground [foreground] []) (map row) detailed)
      (pos? waiting) (conj {:id     :queued
                            :label  "Queued"
                            :detail (format "%d %s" waiting
                                            (if (= 1 waiting) "library" "libraries"))}))))

(defn- row-text
  "A task row as one phrase. The foreground row's label (\"Status\") says nothing a
  user needs, so only a library's name is worth prefixing."
  [{:keys [id label detail]}]
  (if (= :foreground id) detail (format "%s — %s" label detail)))

(defn status-summary
  "One-line digest of `tasks` for the main window's strip, which reports *that*
  something is happening and leaves the per-job detail to the activity window:
  {:text :running? :error?}, or nil when nothing is running (and the strip then
  isn't drawn at all).

  The text leads with a **failure** where there is one, otherwise with the first
  row, and counts the rest. Leading with the failure keeps the words and the colour
  saying the same thing: any failure reddens the whole line, and a red line reading
  \"Syncing…\" would be a puzzle — while the sync is the row the user can already
  see the result of, and the failed background scan is the one with nowhere else to
  report itself.

  :running? drives the spinner, and is false when every job is merely waiting or
  already dead: a spinner over nothing but a failed scan says the app is working on
  it, which it isn't."
  [state]
  (when-let [rows (seq (tasks state))]
    (let [lead (or (first (filter :error? rows)) (first rows))]
      {:text     (cond-> (row-text lead)
                   (next rows) (str (format " · %d more" (dec (count rows)))))
       :running? (boolean (some :running? rows))
       :error?   (boolean (some :error? rows))})))

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
  "True when a plan is ready with at least one add, move, delete, or copy-to-source."
  [{:keys [plan status]}]
  (boolean (and plan (= status :planned)
                (pos? (+ (get-in plan [:summary :add] 0)
                         (get-in plan [:summary :move] 0)
                         (get-in plan [:summary :delete] 0)
                         (get-in plan [:summary :add-to-source] 0))))))
