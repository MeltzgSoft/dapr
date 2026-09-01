(ns dapr.state
  "Application state: a single map describing the configured libraries, the
  current source/sink selection, the chosen tracks, and sync status. The
  transition functions here are pure (state -> state); the atom that holds the
  state is created and managed by the Integrant system (dapr.system), and the
  side-effecting actions behind the UI's controls (dapr.ui.actions) apply these
  transitions via swap! (and persist libraries to the cache DB)."
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
   ;; Bumped every time the catalogs are repainted from the cache (see
   ;; set-catalogs/update-catalogs). The UI polls fragments with the version it
   ;; last rendered, and the server answers "unchanged" unless this has moved —
   ;; which is how a background scan's findings reach the open page without the
   ;; page holding any state of its own (see dapr.web.fragments).
   :catalog-version 0
   :plan           nil    ; {:actions [...] :summary {...}}
   :settings-open? false  ; whether the library-management modal is showing
   :editor         nil    ; library being added/edited, or nil
   :browser        nil    ; folder browser, or nil
   :settings       {}     ; persisted app settings (theme, log dir, …); see dapr.db.cache
   :ui             {}     ; UI config from config.edn (see set-ui); not user-editable
   :status         :idle  ; :idle :scanning :planned :syncing :done :error
   :progress       nil    ; {:done n :total t}
   ;; Background library refresh (see dapr.refresh): per-library status
   ;; (:pending/:scanning/:paused/:complete/:error), failure reason and {:done
   ;; :total} counters — all keyed by library id, since the status bar draws a row
   ;; per library and a paused one keeps the counts it had reached. Deliberately
   ;; separate from :status, which tracks the *foreground* op — a refresh must
   ;; never disable Preview/Sync.
   :refresh        {:status {} :errors {} :progress {}}
   :confirm        nil    ; pending confirmation dialog, or nil (see open-confirm)
   :log            []     ; vector of message strings (capped at max-log-lines)
   :log-appends    0      ; total lines ever appended; drives log auto-scroll
   :log-open?      false  ; whether the activity panel is showing
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
  dapr.device.availability/probe!)."
  [state availability]
  (assoc state :library-availability (or availability {})))

(defn set-library-available
  "Record one library's latest reachability without disturbing other probes."
  [state lib-id available?]
  (assoc-in state [:library-availability lib-id] (boolean available?)))

(defn library-unreachable?
  "True when library `lib-id`'s device has been probed and came back unavailable,
  so there is no point walking it (see dapr.refresh/refresh!). A library that has
  never been probed (absent from the map) is not assumed unreachable — the walk
  itself will say. Mirrors dapr.ui.format/library-unavailable?, which asks the same
  question of the raw map for the library pickers."
  [state lib-id]
  (false? (get-in state [:library-availability lib-id])))

(defn clear-unavailable-selection
  "Drop the source and/or sink selection when its library has been probed
  unavailable (explicitly false in `availability`), invalidating any plan. Unprobed
  libraries (absent from the map) are left selected. Used at launch so a persisted
  default on an unreachable device isn't pre-selected, on a manual refresh, and
  when the availability monitor sees a device disconnect."
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

(defn- bump-catalog-version
  "Mark the catalogs as changed, so a page rendered against the previous view
  learns it is out of date (see :catalog-version in initial-state)."
  [state]
  (update state :catalog-version (fnil inc 0)))

(defn set-catalogs
  "Record freshly scanned catalogs and free space, pre-select the tracks already
  on the sink, and recompute capacity."
  [state source-catalog sink-catalog free-bytes]
  (-> state
      (bump-catalog-version)
      (assoc :source-catalog source-catalog
             :sink-catalog sink-catalog
             :free-bytes free-bytes
             :selected (lib/initial-selection sink-catalog))
      (recompute-capacity)))

(defn update-catalogs
  "Re-point the catalogs at a freshly refreshed cache view *without* disturbing the
  user's selection — only tracks that have since disappeared from both catalogs are
  dropped from it. Used by the background refresh (see dapr.refresh), where
  set-catalogs' pre-selection would silently discard what the user had ticked."
  [state source-catalog sink-catalog free-bytes]
  (-> state
      (bump-catalog-version)
      (assoc :source-catalog source-catalog
             :sink-catalog sink-catalog
             :free-bytes free-bytes)
      (update :selected (fn [selected]
                          (into #{}
                                (filter #(or (contains? source-catalog %)
                                             (contains? sink-catalog %)))
                                selected)))
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

(defn track-locked?
  "True when track `k` is a sink-only track retained regardless of selection — on the
  sink but not the source, under :keep / :add-to-source handling — so its checkbox is
  locked on (see views/track-rows) and a group toggle must leave it untouched."
  [{:keys [source-catalog sink-catalog settings]} k]
  (and (contains? sink-catalog k)
       (not (contains? source-catalog k))
       (contains? #{:keep :add-to-source} (get settings :sink-only-handling :keep))))

(defn toggle-keys
  "Toggle a group of track keys `ks` as one unit (a double-click on a column-browser
  artist/album facet). Locked sink-only tracks are ignored. If every remaining key is
  already selected, deselect them all; otherwise select those that still fit the sink
  budget (per-track, like toggle-track — over-budget keys are skipped). Capacity is
  recomputed once."
  [{:keys [selected source-catalog sink-catalog free-bytes] :as state} ks]
  (let [togglable (remove #(track-locked? state %) ks)]
    (if (empty? togglable)
      state
      (-> (if (every? #(contains? selected %) togglable)
            (update state :selected #(reduce disj % togglable))
            (reduce (fn [st k]
                      (if (cap/would-fit? k (:selected st) source-catalog sink-catalog free-bytes)
                        (update st :selected conj k)
                        st))
                    state
                    togglable))
          (recompute-capacity)))))

;; --- UI configuration --------------------------------------------------------
;; Deployment knobs the views render from, supplied by the Integrant system from
;; resources/config.edn (see :dapr/state) rather than being compiled in. Distinct
;; from :settings below, which is the *user's* persisted preferences: nothing in
;; the UI edits these, and they are not written back to the cache.

(def default-ui
  "Fallback UI configuration, used when config.edn says nothing."
  {:fallback-seconds 15})

(defn set-ui
  "Replace the UI configuration map, filling anything absent from default-ui."
  [state ui]
  (assoc state :ui (merge default-ui ui)))

(defn fallback-seconds
  "How often a region re-fetches itself *without* being told to. The server pushes
  a notification the moment a region's data moves (see dapr.web.events), so this
  timer is not the mechanism — it is the safety net for a stream that never
  connected or quietly died, and the reason such a page goes slightly stale
  rather than frozen."
  [state]
  (get-in state [:ui :fallback-seconds] (:fallback-seconds default-ui)))

;; --- app settings ------------------------------------------------------------
;; The :settings map mirrors the persisted app config (dapr.db.cache); the event
;; action persists alongside these pure transitions (see dapr.ui.actions).

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

;; --- background refresh ------------------------------------------------------
;; Projection of the background refresher's progress (dapr.refresh) for the UI and
;; for the sync gate. A library reaches :complete only once a walk of it has run
;; end to end this session — which is what makes the cache authoritative about
;; *absence* (see dapr.db.cache/reconcile-library-tracks!) and therefore safe to
;; sync against.

(defn set-refresh-status
  "Record library `lib-id`'s refresh status (:pending / :scanning / :paused /
  :complete). Clears any recorded failure: reaching any of these means a newer
  attempt has superseded it, so the stale message must stop being shown (see
  set-refresh-error)."
  [state lib-id status]
  (-> state
      (assoc-in [:refresh :status lib-id] status)
      (update-in [:refresh :errors] dissoc lib-id)))

(defn set-refresh-error
  "Record that library `lib-id`'s refresh failed, with `message` — a one-liner the
  sync bar can show, since a background failure has no other way to reach the user
  (the full trace is in the log). Kept out of the app-wide :error/:status, which
  belong to the *foreground* op: a scan that failed must not blank a computed plan
  or make the window look broken."
  [state lib-id message]
  (-> state
      (assoc-in [:refresh :status lib-id] :error)
      (assoc-in [:refresh :errors lib-id] message)))

(defn refresh-errors
  "Failed refreshes as {lib-id message} (empty when all is well)."
  [state]
  (get-in state [:refresh :errors] {}))

(defn refresh-status
  "Refresh status of library `lib-id` this session, or nil if it has never been
  queued."
  [state lib-id]
  (get-in state [:refresh :status lib-id]))

(defn library-complete?
  "True when library `lib-id`'s background refresh has run to completion this
  session, so its cached catalog matches the device."
  [state lib-id]
  (= :complete (refresh-status state lib-id)))

(defn set-refresh-progress
  "Record {:done :total} for library `lib-id`'s walk (nil to clear it). Progress is
  per library rather than a single 'currently walking' pair so a paused library
  still shows how far it got — it resumes from exactly there."
  [state lib-id progress]
  (if progress
    (assoc-in state [:refresh :progress lib-id] progress)
    (update-in state [:refresh :progress] dissoc lib-id)))

(defn refresh-progress
  "Library `lib-id`'s {:done :total} counters, or nil before its walk has listed
  anything."
  [state lib-id]
  (get-in state [:refresh :progress lib-id]))

(defn forget-refresh
  "Drop library `lib-id` from the refresh projection (it was deleted)."
  [state lib-id]
  (-> state
      (update-in [:refresh :status] dissoc lib-id)
      (update-in [:refresh :errors] dissoc lib-id)
      (update-in [:refresh :progress] dissoc lib-id)))

(defn sync-incomplete-libraries
  "The chosen source/sink libraries whose refresh has not completed this session —
  empty when both are up to date. A sync against these would plan from a catalog
  that may still be a stale superset of the device, so the UI confirms first (see
  dapr.ui.actions)."
  [state]
  (->> [(:source-id state) (:sink-id state)]
       (remove nil?)
       (distinct)
       (remove #(library-complete? state %))
       (keep #(library-by-id state %))
       (vec)))

;; --- confirmation dialog -----------------------------------------------------

(defn open-confirm
  "Show a confirmation dialog: {:kind :title :message :confirm-text}. The event it
  dispatches on confirm is chosen by :kind in the view (see dapr.ui.views)."
  [state confirm]
  (assoc state :confirm confirm))

(defn close-confirm [state] (assoc state :confirm nil))

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
                         ;; `subvec` retains its backing vector, and conj-ing onto a
                         ;; subvec keeps growing that backing — so every line ever
                         ;; appended would stay on the heap. `into []` copies the
                         ;; window into a fresh PersistentVector, releasing the rest.
                         ;; (`vec` won't do — a SubVector is `vector?`, so `vec`
                         ;; returns it unchanged.)
                         (into [] (subvec log (- n max-log-lines)))
                         log))))
      (update :log-appends inc)))

(defn set-log-file
  "Record the path of the log file currently being written (see dapr.log)."
  [state path]
  (assoc state :log-file path))

(defn open-log
  "Show the activity panel (running jobs beside the live log)."
  [state]
  (assoc state :log-open? true))

(defn close-log
  "Hide the activity panel."
  [state]
  (assoc state :log-open? false))

(defn set-error
  "Record an error message and move to the :error status."
  [state msg]
  (-> state
      (assoc :error msg)
      (assoc :status :error)))
