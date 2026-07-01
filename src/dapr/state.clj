(ns dapr.state
  "Application state: a single map describing the configured libraries, the
  current source/sink selection, the chosen tracks, and sync status. The
  transition functions here are pure (state -> state); the atom that holds the
  state is created and managed by the Integrant system (dapr.system), and the
  side-effecting event handlers (dapr.ui.events) apply these transitions via
  swap! (and persist libraries to disk)."
  (:require [clojure.string :as str]
            [dapr.domain.capacity :as cap]
            [dapr.domain.library :as lib]))

(def initial-state
  "The state map a freshly started system begins with."
  {:libraries      []     ; persisted [{:id :name :roots}]
   :store-path     nil    ; where libraries are persisted
   :source-id      nil
   :sink-id        nil
   :library-availability {} ; library id -> bool; absent = not yet probed (treated available)
   :source-catalog {}     ; key -> track
   :sink-catalog   {}     ; key -> track
   :selected       #{}    ; set of selected track keys
   :filter         {:artist nil :album nil} ; iTunes-style column-browser filter (nil = All)
   :filter-search  {:artist "" :album ""}   ; per-column search text narrowing the facet lists
   :free-bytes     0      ; usable bytes across the sink's distinct devices
   :capacity       {:used 0 :budget 0 :free 0}
   :plan           nil    ; {:actions [...] :summary {...}}
   :settings-open? false  ; whether the library-management modal is showing
   :editor         nil    ; library being added/edited, or nil
   :browser        nil    ; folder browser, or nil
   :settings       {}     ; persisted app settings (theme, log dir, …); see dapr.cache
   :os-color-scheme nil   ; OS-reported scheme (:dark/:light); drives the :system theme
   :status         :idle  ; :idle :scanning :planned :syncing :done :error
   :scan-gen       0      ; bumped per scan; lets a new scan supersede a running one
   :progress       nil    ; {:done n :total t}
   :log            []     ; vector of message strings (capped at max-log-lines)
   :log-appends    0      ; total lines ever appended; drives log auto-scroll
   :log-open?      false  ; whether the live log window is showing
   :log-follow?    true   ; live log window auto-scrolls to the newest line
   :log-scroll     0.0    ; last observed log text-area scrollTop (for freeze/detect)
   :log-file       nil    ; path of the current log file (see dapr.log)
   :error          nil})

(def max-log-lines
  "Upper bound on retained activity-log lines; older lines are dropped."
  500)

;; --- libraries ---------------------------------------------------------------

(defn set-libraries [state libraries] (assoc state :libraries (vec libraries)))

(defn library-by-id [state id] (first (filter #(= (:id %) id) (:libraries state))))

(defn upsert-library
  "Insert `library` or replace the existing one with the same :id."
  [state {:keys [id] :as library}]
  (let [libs (vec (:libraries state))
        idx  (first (keep-indexed (fn [i l] (when (= (:id l) id) i)) libs))]
    (assoc state :libraries (if idx (assoc libs idx library) (conj libs library)))))

(defn delete-library
  "Remove the library with `id`, clearing it from source/sink if selected."
  [state id]
  (-> state
      (update :libraries (fn [libs] (vec (remove #(= (:id %) id) libs))))
      (cond-> (= id (:source-id state)) (assoc :source-id nil)
              (= id (:sink-id state))   (assoc :sink-id nil))))

;; --- library availability ----------------------------------------------------

(defn set-library-availability
  "Record the probed reachability of libraries as an id->bool map (see
  dapr.ui.events/probe-availability!)."
  [state availability]
  (assoc state :library-availability (or availability {})))

(defn clear-unavailable-selection
  "Drop the source and/or sink selection when its library has been probed
  unavailable (explicitly false in `availability`), invalidating any plan. Unprobed
  libraries (absent from the map) are left selected. Used at launch so a persisted
  default on an unreachable device isn't pre-selected, and on a manual refresh."
  [state availability]
  (let [src-bad? (and (:source-id state) (false? (get availability (:source-id state))))
        snk-bad? (and (:sink-id state) (false? (get availability (:sink-id state))))]
    (cond-> state
      src-bad? (assoc :source-id nil
                      :filter {:artist nil :album nil}
                      :filter-search {:artist "" :album ""})
      snk-bad? (assoc :sink-id nil)
      (or src-bad? snk-bad?) (assoc :plan nil :status :idle))))

;; --- source / sink / selection ----------------------------------------------

(defn- recompute-capacity [{:keys [selected source-catalog sink-catalog free-bytes] :as state}]
  (assoc state :capacity (cap/usage selected source-catalog sink-catalog free-bytes)))

(defn- invalidate-plan
  "Drop any computed plan and return to :idle. Used when the source/sink changes,
  so a plan built for the previous pair can't be synced (it would otherwise stay
  :planned — and Sync enabled — until the background reload runs)."
  [state]
  (assoc state :plan nil :status :idle))

(defn select-source
  "Choose the source library, clearing the column-browser filter and searches so
  they start fresh for the new library's tags, and invalidating any stale plan."
  [state id]
  (-> state
      (assoc :source-id id
             :filter {:artist nil :album nil}
             :filter-search {:artist "" :album ""})
      (invalidate-plan)))

(defn select-sink
  "Choose the sink library, invalidating any plan built for the previous pair."
  [state id]
  (-> state (assoc :sink-id id) (invalidate-plan)))

(defn set-filter-artist
  "Set the column-browser artist filter (nil = All), clearing the album filter
  since the available albums change with the artist."
  [state artist]
  (assoc state :filter {:artist artist :album nil}))

(defn set-filter-album
  "Set the column-browser album filter (nil = All)."
  [state album]
  (assoc-in state [:filter :album] album))

(defn set-filter-search
  "Set the search text narrowing the facet list of column `col` (:artist/:album)."
  [state col text]
  (assoc-in state [:filter-search col] text))

(defn set-catalogs
  "Record freshly scanned catalogs and free space, pre-select the tracks already
  on the sink, and recompute capacity."
  [state source-catalog sink-catalog free-bytes]
  (-> state
      (assoc :source-catalog source-catalog
             :sink-catalog sink-catalog
             :free-bytes free-bytes
             :selected (lib/initial-selection sink-catalog))
      (recompute-capacity)))

(defn toggle-track
  "Toggle selection of track `k`. Selecting is refused (no-op) when it would
  exceed the sink's capacity. Deselecting always succeeds."
  [{:keys [selected source-catalog sink-catalog free-bytes] :as state} k]
  (cond
    (contains? selected k)
    (-> state (update :selected disj k) (recompute-capacity))

    (cap/would-fit? k selected source-catalog sink-catalog free-bytes)
    (-> state (update :selected conj k) (recompute-capacity))

    :else state))

;; --- app settings ------------------------------------------------------------
;; The :settings map mirrors the persisted app config (dapr.cache); the event
;; handler persists alongside these pure transitions (see dapr.ui.events).

(defn set-settings
  "Replace the whole settings map (loaded from the cache on startup)."
  [state settings]
  (assoc state :settings (or settings {})))

(defn set-setting
  "Set a single app setting key. A nil value clears it, mirroring how the cache
  persists settings."
  [state k v]
  (if (nil? v)
    (update state :settings dissoc k)
    (assoc-in state [:settings k] v)))

(defn setting
  "Read app setting `k`, falling back to `default` (nil) when unset."
  ([state k] (setting state k nil))
  ([state k default] (get (:settings state) k default)))

(defn set-os-color-scheme
  "Record the OS-reported colour scheme (:dark/:light, or nil when unknown). Not a
  persisted setting — it tracks the live OS preference so the :system theme can
  follow it (see dapr.ui.format/active-theme)."
  [state scheme]
  (assoc state :os-color-scheme scheme))

;; --- settings modal ----------------------------------------------------------

(defn open-settings [state] (assoc state :settings-open? true))

(defn close-settings
  "Hide the settings modal, discarding any in-progress editor/browser."
  [state]
  (assoc state :settings-open? false :editor nil :browser nil))

;; --- editor ------------------------------------------------------------------

(defn set-editor [state editor] (assoc state :editor editor))
(defn cancel-editor [state] (assoc state :editor nil))
(defn editor-name [state name] (assoc-in state [:editor :name] name))

(defn editor-add-root
  "Append `uri` to the library being edited, ignoring blanks, duplicates, and any
  root that would mix devices (see dapr.domain.library/root-addable?)."
  [state uri]
  (let [roots (get-in state [:editor :roots])]
    (if (and (not (str/blank? uri))
             (not (some #{uri} roots))
             (lib/root-addable? roots uri))
      (assoc-in state [:editor :roots] (conj (vec roots) uri))
      state)))

(defn editor-remove-root
  [state uri]
  (update-in state [:editor :roots] (fn [roots] (vec (remove #(= % uri) roots)))))

;; --- folder browser ----------------------------------------------------------
;; A list+breadcrumb browser scoped to a single device. The generic state shape is
;; {:device/type :phase :device :cwd :crumbs :entries :loading?}, with device
;; namespaces free to add fields for their own forms or chooser phases. During
;; :browse, :cwd is the directory currently shown, :crumbs is the trail of
;; {:label :uri} maps, and :entries is the list of child {:name :uri :dir?} maps.

(defn set-browser [state browser] (assoc state :browser browser))

(defn browser-field
  "Update one editable field of the current browser."
  [state field value]
  (assoc-in state [:browser field] value))

(defn browser-start-browse
  "Enter the generic folder-browsing phase at `cwd`, recording `device` as the
  root target for the breadcrumb root button."
  [state {:keys [device cwd]}]
  (-> state
      (assoc-in [:browser :phase] :browse)
      (assoc-in [:browser :device] device)
      (assoc-in [:browser :cwd] cwd)
      (assoc-in [:browser :crumbs] [])
      (assoc-in [:browser :entries] [])
      (assoc-in [:browser :loading?] true)))

(defn browser-set-devices
  "Record freshly detected/available browser devices and clear the loading flag."
  [state devices]
  (-> state
      (assoc-in [:browser :devices] (vec devices))
      (assoc-in [:browser :loading?] false)))

(defn browser-close [state] (assoc state :browser nil))

(defn browser-set-entries
  "Record freshly listed `entries` and clear the loading flag."
  [state entries]
  (-> state
      (assoc-in [:browser :entries] (vec entries))
      (assoc-in [:browser :loading?] false)))

(defn browser-enter
  "Descend into child folder `{:keys [name label uri]}`: push a breadcrumb, make
  it the current directory, and mark loading."
  [state {:keys [uri] :as child}]
  (-> state
      (update-in [:browser :crumbs] (fnil conj []) {:label (or (:label child) (:name child)) :uri uri})
      (assoc-in [:browser :cwd] uri)
      (assoc-in [:browser :entries] [])
      (assoc-in [:browser :loading?] true)))

(defn browser-to-places
  "Return to the browser root. A nil root URI means the device namespace will show
  its top-level places list."
  [state]
  (-> state
      (assoc-in [:browser :cwd] (get-in state [:browser :device :uri]))
      (assoc-in [:browser :crumbs] [])
      (assoc-in [:browser :entries] [])
      (assoc-in [:browser :loading?] true)))

(defn browser-to-crumb
  "Jump back to the breadcrumb at index `idx`, dropping any deeper crumbs."
  [state idx]
  (let [crumbs (vec (take (inc idx) (get-in state [:browser :crumbs])))]
    (-> state
        (assoc-in [:browser :crumbs] crumbs)
        (assoc-in [:browser :cwd] (:uri (last crumbs)))
        (assoc-in [:browser :entries] [])
        (assoc-in [:browser :loading?] true))))

;; --- misc status -------------------------------------------------------------

(defn set-status [state status] (assoc state :status status))
(defn set-progress [state progress] (assoc state :progress progress))

(defn set-plan
  "Record a freshly computed plan and move to the :planned status."
  [state actions summary]
  (-> state
      (assoc :plan {:actions actions :summary summary})
      (assoc :status :planned)))

(defn append-log
  "Append a message line to the activity log, keeping only the most recent
  max-log-lines. :log-appends counts every append (never reset) so the view can
  detect new lines and auto-scroll even once the capped log stops growing.

  This is now the single sink for the Telemere UI handler (see dapr.log) — business
  code emits Telemere signals rather than calling this directly."
  [state msg]
  (-> state
      (update :log (fn [log]
                     (let [log (conj log msg)
                           n   (count log)]
                       (if (> n max-log-lines)
                         (subvec log (- n max-log-lines))
                         log))))
      (update :log-appends inc)))

(defn set-log-file
  "Record the path of the log file currently being written (see dapr.log)."
  [state path]
  (assoc state :log-file path))

(defn open-log
  "Show the live log window, re-engaging tail-following so it opens at the newest
  line."
  [state]
  (assoc state :log-open? true :log-follow? true))

(defn close-log
  "Hide the live log window."
  [state]
  (assoc state :log-open? false))

(defn follow-log
  "Re-engage tail-following (the live log window's 'jump to bottom' button), so the
  next render snaps the view back to the newest line."
  [state]
  (assoc state :log-follow? true))

(def ^:private log-scroll-epsilon
  "Minimum scrollTop drop (px) treated as a deliberate scroll up, rather than
  rounding noise from the programmatic pin-to-bottom."
  2.0)

(defn log-scrolled
  "Record the live log text-area's new `pos` (scrollTop). A drop below the last
  position while following means the user scrolled up to read scrollback, so
  tail-following is disengaged (the view then freezes at `pos` until they jump back
  to the bottom — see follow-log). Programmatic pin-to-bottom only ever increases
  scrollTop, so it never trips this."
  [state pos]
  (let [pos (double pos)
        up? (and (:log-follow? state) (< pos (- (:log-scroll state) log-scroll-epsilon)))]
    (cond-> (assoc state :log-scroll pos)
      up? (assoc :log-follow? false))))

(defn set-error
  "Record an error message and move to the :error status."
  [state msg]
  (-> state
      (assoc :error msg)
      (assoc :status :error)))
