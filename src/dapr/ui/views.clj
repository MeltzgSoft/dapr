(ns dapr.ui.views
  "Pure hiccup view descriptions for the Dapr web UI. Every function takes the
  application state map (plus, for the track table, its sort/window parameters the
  request carried) and returns hiccup data — no side effects, no I/O.

  The page is assembled from *regions*, each an element with a stable id:
  `#workspace`, `#track-table`, `#status-bar` and so on. A user action POSTs to
  `/actions/...` and gets back the regions that changed (see dapr.web.routes);
  a region that also has to reflect work happening in the background re-fetches
  itself from `/fragments/<region>` on a timer, sending the digest it currently
  shows so an unchanged region costs an empty 204 (see dapr.ui.digest). Between
  the two, the browser holds no application state of its own: every control
  carries what it needs in its URL. The virtual table's transient scroll position
  is the deliberate exception and resets on reload."
  (:require [clojure.string :as str]
            [dapr.device.file.views]
            [dapr.device.format :as device-format]
            [dapr.device.mtp.views]
            [dapr.device.smb.views]
            [dapr.device.views :as device-views]
            [dapr.domain.library :as lib]
            [dapr.track-window.transforms :as track-window]
            [dapr.ui.digest :as digest]
            [dapr.ui.format :as fmt]
            [dapr.ui.html :as html]))

(def ^:private default-view
  "Table view parameters a request that carries none is read as."
  {:sort nil :dir :asc :start 0})

(defn- view-params
  "The table's view as URL parameters, for a control that must preserve it."
  [{:keys [sort dir start]}]
  {:sort (some-> sort name) :dir (some-> dir name) :start start})

;; --- track table -------------------------------------------------------------

(defn- text-cell [v] (some-> v str))

(def ^:private columns
  "The track table's data columns, in display order: the field each shows (and
  sorts by), its header, how it renders, and whether it is numeric (right
  aligned). The leading checkbox column is not here — it sorts by nothing and
  carries no value of its own."
  [{:field :disc-number     :label "Disc"     :num? true}
   {:field :track-number    :label "Track"    :num? true}
   {:field :title           :label "Title"}
   {:field :duration-millis :label "Duration" :num? true :render fmt/duration-mmss}
   {:field :artist          :label "Artist"}
   {:field :album           :label "Album"}
   {:field :genre           :label "Genre"}
   {:field :size            :label "Size"     :num? true
    :render #(when (some? %) (fmt/human-bytes %))}
   {:field :sink-rel        :label "On sink"}])

(def sortable-fields
  "Fields a request may sort the table by. A request naming anything else is read
  as no sort at all, rather than sorting by a field that does not exist."
  (into #{} (map :field) columns))

(defn- sort-indicator [view field]
  (when (= field (:sort view))
    (if (= :desc (:dir view)) " ▾" " ▴")))

(defn- next-dir
  "Clicking the column already sorted on reverses it; clicking any other starts
  ascending."
  [view field]
  (if (and (= field (:sort view)) (= :asc (:dir view))) :desc :asc))

(defn- header-cell [view {:keys [field label num?]}]
  [:th {:class (when num? "num")}
   [:button {:hx-get    (html/url "/fragments/table"
                                  {:sort (name field) :dir (name (next-dir view field)) :start 0})
             :hx-target "#track-table"
             :hx-swap   "outerHTML"
             :title     (str "Sort by " label)}
    label (sort-indicator view field)]])

(defn- track-row [view index row]
  (let [key-param (assoc (view-params view) :key (pr-str (:key row)))]
    [:tr {:class         (html/classes "track-row" (when-not (:in-source? row) "sink-only"))
          :aria-rowindex (+ index 2)}
     [:td.tick
      [:input {:type      "checkbox"
               :checked   (boolean (:on? row))
               :disabled  (boolean (:disabled? row))
               :title     (when (:disabled? row)
                            (if (:in-source? row)
                              "Adding this track would overflow the sink"
                              "On the sink but not the source — kept by the current setting"))
               :hx-post   (html/url "/actions/toggle-track" key-param)
               :hx-target "#track-table"
               :hx-swap   "outerMorph"}]]
     (for [{:keys [field num? render]} columns
           :let [v ((or render text-cell) (get row field))]]
       [:td {:class (when num? "num") :title v} v])]))

(defn- spacer-row [class height]
  (when (pos? height)
    [:tr {:class (html/classes "virtual-spacer" class) :aria-hidden "true"}
     [:td {:colspan (inc (count columns)) :style (str "height: " height "px")}]]))

(defn- table-window
  [state view]
  (let [ordered (or (:ordered-keys view) (track-window/ordered-keys state view))
        window  (track-window/window ordered (:start view))]
    (assoc window :rows (fmt/track-rows-for-keys state (:keys window)))))

(defn- track-table-body*
  [state view {:keys [start end total rows top-height bottom-height]}]
  [:tbody {:data-start  start
           :data-end    end
           :data-total  total
           :data-sort   (some-> (:sort view) name)
           :data-dir    (name (:dir view))
           :data-digest (digest/digest state (assoc view :start start) :table)
           :data-state-digest (digest/digest state (assoc view :start 0) :table)}
   (spacer-row "top" top-height)
   (if (seq rows)
     (map-indexed (fn [offset row] (track-row (assoc view :start start) (+ start offset) row)) rows)
     (when (zero? total)
       [:tr [:td.empty {:colspan (inc (count columns))}
             (if (:source-id state)
               "No tracks match this filter."
               "Pick a source library to see its tracks.")]]))
   (spacer-row "bottom" bottom-height)])

(defn track-table-body
  "The bounded moving tbody fetched by the browser's scroll controller."
  [state view]
  (let [view   (merge default-view view)
        window (table-window state view)]
    (track-table-body* state view window)))

(defn track-table
  "The virtualized track picker. Spacer rows represent the full filtered catalog,
  while only one bounded window of real rows reaches the DOM."
  [state view]
  (let [view   (merge default-view view)
        window (table-window state view)
        start  (:start window)
        total  (:total window)
        view   (assoc view :start start)]
    [:section#track-table.card
     (merge (html/poll state :table (digest/digest state view :table) (view-params view))
            {:data-window-url (html/url "/fragments/table-body"
                                        {:sort (some-> (:sort view) name)
                                         :dir  (name (:dir view))})})
     [:header
      [:span.title (format "Tracks (%d)" total)]
      [:span.grow]
      (when (:sort view)
        [:button.ghost {:hx-get    (html/url "/fragments/table" {:start 0})
                        :hx-target "#track-table"
                        :hx-swap   "outerHTML"
                        :title     "Restore Artist, Album, Disc, Track order"}
         "Reset sort"])
      (when (> total track-window/window-size)
        [:span.muted "Scroll to browse all tracks"])]
     ;; The table's own sort/window, for controls that live outside it (the column
     ;; browser, the sink-only setting) and must not reset the user's view when
     ;; they re-render it.
     [:form#track-view {:hidden true}
      [:input {:type "hidden" :name "sort" :value (some-> (:sort view) name)}]
      [:input {:type "hidden" :name "dir" :value (name (:dir view))}]
      [:input {:type "hidden" :name "start" :value start}]]
     [:div#track-scroll.table-scroll
      [:table.tracks {:aria-rowcount (inc total)}
       [:thead [:tr [:th.tick.plain ""] (map (partial header-cell view) columns)]]
       (track-table-body* state view window)]]]))

;; --- column browser ----------------------------------------------------------

(defn- facet-item
  "One facet: its label filters the table, and the ✓ beside it checks or unchecks
  every track under it without narrowing the view. JavaFX packed both onto one
  list cell and told them apart by click count; two targets say it plainly."
  [col value label on?]
  [:li {:class (when on? "on")}
   [:button.pick {:hx-post    (html/url "/actions/filter" {:col (name col) :value value})
                  :hx-include "#track-view"
                  :hx-target  "#facets"
                  :hx-swap    "outerHTML"
                  :title      (str "Show only " label)}
    label]
   (when value
     [:button.toggle-all {:hx-post    (html/url "/actions/toggle-facet"
                                                {:col (name col) :value value})
                          :hx-include "#track-view"
                          :hx-target  "#track-table"
                          :hx-swap    "outerMorph"
                          :title      "Check / uncheck all its tracks"}
      "✓"])])

(defn- facet-list [state region col values selected]
  [:ul.facet-list
   (assoc (html/poll state region (digest/digest state nil region)) :id (name region))
   (facet-item col nil "All" (nil? selected))
   (for [v values]
     (facet-item col v v (= v selected)))])

(defn artist-list [state]
  (let [values (fmt/search-filter (fmt/artists (:source-catalog state))
                                  (get-in state [:filter-search :artist]))]
    (facet-list state :artists :artist values (get-in state [:filter :artist]))))

(defn album-list [state]
  (let [values (fmt/search-filter (fmt/albums (:source-catalog state)
                                              (get-in state [:filter :artist]))
                                  (get-in state [:filter-search :album]))]
    (facet-list state :albums :album values (get-in state [:filter :album]))))

(defn- facet-column [state title col list-id list-hiccup]
  [:section.facet.card
   [:header
    [:span.title title]
    [:input {:type        "search"
             :name        "q"
             :value       (get-in state [:filter-search col])
             :placeholder (str "Filter " (str/lower-case title) "…")
             :hx-post     (html/url "/actions/filter-search" {:col (name col)})
             ;; `input`, not `keyup`: a value can arrive by paste, autofill or
             ;; IME without a key ever coming up. `search` is what the clear
             ;; button in a search field fires.
             :hx-trigger  "input changed delay:250ms, search"
             :hx-target   (str "#" list-id)
             :hx-swap     "outerHTML"}]]
   list-hiccup])

(defn facets
  "iTunes-style column browser: an Artist column and an Album column scoped to the
  selected artist, each with a search field narrowing its values."
  [state]
  [:div#facets
   (facet-column state "Artist" :artist "artists" (artist-list state))
   (facet-column state "Album" :album "albums" (album-list state))])

;; --- sync bar, capacity, controls --------------------------------------------

(defn- library-select
  "Source/sink picker for `role` (:source/:sink). A library whose device was probed
  unreachable renders disabled, so it can't be chosen. The select carries an id of
  its own and the label points at it: the two pickers list the same library names,
  so the label text alone does not say which is which."
  [state role selected-id]
  (let [{:keys [libraries library-availability]} state
        select-id (str (name role) "-library")]
    [:label.field {:for select-id}
     [:span.label (str/capitalize (name role))]
     [:select {:id        select-id
               :name      "id"
               :hx-post   (str "/actions/select-" (name role))
               :hx-target "#workspace"
               :hx-swap   "outerHTML"}
      [:option {:value "" :selected (nil? selected-id)} "—"]
      (for [l libraries]
        [:option {:value    (str (:id l))
                  :selected (= (:id l) selected-id)
                  :disabled (fmt/library-unavailable? library-availability (:id l))}
         (:name l)])]]))

(defn sync-bar
  "Source and sink pickers. The scanning they kick off reports itself in the status
  strip along the bottom, not here."
  [state]
  [:div#sync-bar (html/poll state :sync-bar (digest/digest state nil :sync-bar))
   (library-select state :source (:source-id state))
   (library-select state :sink (:sink-id state))])

(defn capacity-bar
  "Capacity meter for the chosen sink — how full it would be after the selected
  sync. With no sink chosen capacity is undefined, so the bar says so rather than
  showing a misleading 0 B / 0 B."
  [state]
  (let [{:keys [capacity sink-id libraries]} state
        sink-name (when sink-id (fmt/library-name libraries sink-id))
        over?     (and sink-name (fmt/over-capacity? capacity))]
    [:div#capacity.row (html/poll state :capacity (digest/digest state nil :capacity))
     [:span.label (if sink-name (str "Capacity — " sink-name) "Capacity")]
     [:span {:class (html/classes "meter" (when over? "over"))}
      [:span {:style (format "width: %.1f%%"
                             (* 100.0 (if sink-name (fmt/capacity-fraction capacity) 0.0)))}]]
     [:span {:class (when over? "error")}
      (if sink-name (fmt/capacity-text capacity) "Select a sink")]]))

(defn controls
  "Preview and Sync, plus the plan summary they produce."
  [state]
  [:div#controls (html/poll state :controls (digest/digest state nil :controls))
   [:button.btn {:hx-post   "/actions/preview"
                 :hx-target "#controls"
                 :hx-swap   "outerHTML"
                 :disabled  (not (fmt/can-preview? state))}
    "Preview"]
   [:button.primary {:hx-post   "/actions/sync"
                     :hx-target "#overlay"
                     :hx-swap   "outerHTML"
                     :disabled  (not (fmt/can-sync? state))}
    "Sync"]
   [:span.plan (fmt/plan-summary-text (get-in state [:plan :summary]))]])

(defn workspace
  "The main working area: pickers, capacity, the column browser, the track table
  and the actions. Replaced whole when the source or sink changes, since all of it
  is about the pair."
  [state view]
  [:main#workspace.workspace
   (sync-bar state)
   (capacity-bar state)
   (facets state)
   (track-table state view)
   (controls state)])

;; --- status strip ------------------------------------------------------------

(defn status-bar
  "Strip along the window bottom: a spinner while work is in flight and a one-line
  digest of it, clicking through to the activity panel for the per-job detail.
  With nothing running it collapses to nothing, so an idle app shows no strip."
  [state]
  (let [{:keys [text running? error?]} (fmt/status-summary state)]
    [:footer#status-bar
     (merge (html/poll state :status (digest/digest state nil :status))
            {:class (if text "active" "idle")})
     (when text
       [:button.ghost.grow.row {:hx-post   "/actions/activity/open"
                                :hx-target "#activity"
                                :hx-swap   "outerHTML"
                                :title     "Open the activity panel for every job and the live log"}
        (when running? [:span.spinner])
        [:span {:class (when error? "error")} text]
        [:span.grow]
        [:span.details "Details ▸"]])]))

;; --- activity panel ----------------------------------------------------------

(defn- job-row [{:keys [label detail progress error?]}]
  [:div {:class (html/classes "job" (when error? "failed"))}
   [:div.job-name label]
   (when progress
     [:span.meter.slim [:span {:style (format "width: %.1f%%" (* 100.0 progress))}]])
   [:div.job-detail {:title detail} detail]])

(defn jobs-list
  "A row per running job. Says plainly when there is nothing running — this panel
  is opened deliberately to answer \"what is it doing?\", so an empty one must
  answer rather than leave a blank gap."
  [state]
  (let [rows (fmt/tasks state)]
    [:div#jobs-list (html/poll state :jobs (digest/digest state nil :jobs))
     (if (seq rows)
       (map job-row rows)
       [:p.muted "Nothing running."])]))

(defn log-lines
  "The live log. Rendered newest-first into a column-reverse box, which pins the
  view to the newest line and holds the reader's place when they scroll up — with
  no scripting, where JavaFX needed a scrollbar listener to tell the two apart.

  Each line is its own element, which is load-bearing twice over. `column-reverse`
  orders *flex items*, so a single text node of joined lines gave it nothing to
  reverse: the newest line ended up at the top of a block pinned to the bottom,
  i.e. off screen, and the reader saw the oldest of the retained lines. And a
  morph swap can only preserve what it can match — per-line elements let it
  prepend one line and leave the other 499 untouched."
  [state]
  [:pre#log-lines.log (html/poll state :log (digest/digest state nil :log))
   (for [line (reverse (:log state))]
     [:span.log-line line])])

(defn activity
  "The activity panel: running jobs beside the live log. Opened from the View menu
  or the status strip; empty (and so invisible) when closed."
  [state]
  [:div#activity
   (when (:log-open? state)
     [:aside.drawer
      [:header
       [:span "Activity"]
       [:span.grow]
       [:button.btn {:hx-post   "/actions/activity/close"
                     :hx-target "#activity"
                     :hx-swap   "outerHTML"}
        "Close"]]
      [:div.body
       [:div.jobs
        (let [rows (fmt/tasks state)]
          [:details {:open true}
           [:summary (if (seq rows) (format "Jobs (%d)" (count rows)) "Jobs")]
           (jobs-list state)])]
       (log-lines state)]])])

;; --- settings: libraries, editor, folder browser -----------------------------

(defn- default-chip [l role on?]
  [:button {:class     (html/classes "chip" (when on? "on"))
            :hx-post   (html/url "/actions/library/default"
                                 {:role (name role) :id (:id l)})
            :hx-target "#overlay"
            :hx-swap   "outerHTML"
            :title     (format "Pre-select as the sync %s on launch" (name role))}
   (name role)])

(defn- library-row [l]
  [:div.library-row
   [:span.name.grow (:name l)]
   [:span.muted (format "%d dirs" (count (:roots l)))]
   [:span.muted "Default:"]
   (default-chip l :source (:default-source? l))
   (default-chip l :sink (:default-sink? l))
   [:button.btn {:hx-post   (html/url "/actions/library/edit" {:id (:id l)})
                 :hx-target "#overlay"
                 :hx-swap   "outerHTML"}
    "Edit"]
   [:button.btn.danger {:hx-post   (html/url "/actions/library/delete" {:id (:id l)})
                        :hx-target "#overlay"
                        :hx-swap   "outerHTML"
                        :hx-confirm (format "Delete the library \"%s\"?" (:name l))}
    "Delete"]])

(defn- library-list [libraries]
  [:fieldset
   [:legend "Libraries"]
   [:div.row.wrap
    [:span.muted "New:"]
    (map device-views/library-menu-item device-format/types)]
   (if (seq libraries)
     (map library-row libraries)
     [:p.muted "No libraries yet."])])

(defn browser-panel
  "The folder browser. It re-fetches itself while a device or directory listing is
  in flight (those run on a background thread); a connect *form* never does, or it
  would wipe what is being typed into it."
  [state]
  (let [browser (:browser state)
        allowed (lib/roots-device-key (get-in state [:editor :roots]))]
    [:fieldset#browser-panel
     (if (device-views/browser-polls? browser)
       (assoc (html/poll state :browser (digest/digest state nil :browser))
              ;; Browser loading states need a fresh htmx timer after each
              ;; directory change; morphing preserves the old element state.
              :hx-swap "outerHTML")
       {})
     [:legend "Browse for a folder"]
     (device-views/browser-content allowed browser)
     [:div.row
      [:button.btn {:hx-post   "/actions/browser/cancel"
                    :hx-target "#overlay"
                    :hx-swap   "outerHTML"}
       "Cancel"]]]))

(defn- root-row [uri]
  [:div.root-row
   [:span.grow uri]
   [:button.btn {:hx-post   (html/url "/actions/editor/remove-root" {:uri uri})
                 :hx-target "#overlay"
                 :hx-swap   "outerHTML"}
    "Remove"]])

(defn- editor-panel [state]
  (let [{:keys [name roots]} (:editor state)]
    [:div#editor-panel.stack
     [:label.field
      [:span.label "Name"]
      [:input.grow {:type       "text"
                    :name       "name"
                    :value      (or name "")
                    :placeholder "Library name"
                    :hx-post    "/actions/editor/name"
                    ;; See the facet search box: `input` covers every way a
                    ;; value arrives, and `change` is the backstop on blur.
                    :hx-trigger "input changed delay:400ms, change"
                    :hx-swap    "none"}]]
     [:fieldset
      [:legend "Roots"]
      (if (seq roots)
        (map root-row roots)
        [:p.muted "(no roots yet)"])
      [:div.row
       [:button.btn {:hx-post   "/actions/editor/browse"
                     :hx-target "#overlay"
                     :hx-swap   "outerHTML"
                     :disabled  (some? (:browser state))}
        "Browse…"]]]
     (when (:browser state) (browser-panel state))
     [:div.row
      [:button.primary {:hx-post   "/actions/editor/save"
                        :hx-target "#overlay"
                        :hx-swap   "outerHTML"}
       "Save"]
      [:button.btn {:hx-post   "/actions/editor/cancel"
                    :hx-target "#overlay"
                    :hx-swap   "outerHTML"}
       "Cancel"]]]))

(defn- radio-group
  "A persisted app setting as a set of radios. Only the one matching `current`
  renders checked, so the group stays mutually exclusive across re-renders."
  [legend setting current choices & [extra]]
  [:fieldset
   [:legend legend]
   (for [[value label] choices]
     [:label
      [:input (merge {:type    "radio"
                      :name    (name setting)
                      ;; The POST carries the choice in its URL; the value is here
                      ;; so the markup is a real radio group — one a form would
                      ;; submit, and one a reader can tell apart.
                      :value   (name value)
                      :checked (= current value)
                      :hx-post (html/url "/actions/setting"
                                         {:key (name setting) :value (name value)})}
                     extra)]
      label])])

(defn- log-settings [state]
  [:fieldset
   [:legend "Logs"]
   [:p.muted (str "Current log: " (or (:log-file state) "—"))]
   [:div.row
    [:button.btn {:hx-post   "/actions/log-dir/browse"
                  :hx-target "#overlay"
                  :hx-swap   "outerHTML"}
     "Change log folder…"]]])

(defn- settings-body [state]
  (let [{:keys [libraries editor browser settings]} state]
    (cond
      editor  (editor-panel state)
      ;; A browse with no editor open is the log-directory picker.
      browser (browser-panel state)
      :else   (list
               (library-list libraries)
               (radio-group "Tracks on the sink but not the source" :sink-only-handling
                            (get settings :sink-only-handling :keep)
                            [[:keep "Keep on sink"]
                             [:delete "Delete from sink"]
                             [:add-to-source "Copy back to source"]]
                            {:hx-target  "#overlay"
                             :hx-swap    "outerHTML"
                             :hx-include "#track-view"})
               (radio-group "Theme" :theme (get settings :theme :system)
                            [[:system "System"] [:light "Light"] [:dark "Dark"]]
                            {:hx-swap "none"})
               (log-settings state)))))

(defn- settings-modal [state]
  [:div.scrim
   [:div.modal
    [:header
     [:span (if (:editor state) "Library" "Libraries & Settings")]]
    [:div.body (settings-body state)]
    [:footer
     [:button.btn {:hx-post   "/actions/settings/close"
                   :hx-target "#overlay"
                   :hx-swap   "outerHTML"}
      "Close"]]]])

(defn- confirm-modal [{:keys [title message confirm-text]}]
  [:div.scrim
   [:div.modal.narrow
    [:header [:span (or title "Confirm")]]
    [:div.body [:p (or message "")]]
    [:footer
     [:button.btn {:hx-post   "/actions/confirm/cancel"
                   :hx-target "#overlay"
                   :hx-swap   "outerHTML"}
      "Cancel"]
     [:button.primary {:hx-post   "/actions/confirm/accept"
                       :hx-target "#overlay"
                       :hx-swap   "outerHTML"}
      (or confirm-text "OK")]]]])

(defn overlay
  "The modal layer: a confirmation if one is pending, otherwise the settings
  panel if it is open, otherwise nothing. Empty rather than absent, so it stays
  addressable as a swap target."
  [state]
  [:div#overlay
   (cond
     (:confirm state)        (confirm-modal (:confirm state))
     (:settings-open? state) (settings-modal state))])

;; --- page assembly -----------------------------------------------------------

(defn- topbar [_state]
  [:header.topbar
   [:span.brand "Dapr" [:small "music library sync"]]
   [:span.spacer]
   [:button.btn {:hx-post   "/actions/refresh"
                 :hx-target "#workspace"
                 :hx-swap   "outerHTML"
                 :title     "Re-check which devices are reachable and re-scan the chosen source and sink"}
    "↻ Refresh"]
   [:button.btn {:hx-post   "/actions/settings/open"
                 :hx-target "#overlay"
                 :hx-swap   "outerHTML"}
    "Libraries & Settings"]
   [:button.btn {:hx-post   "/actions/activity/open"
                 :hx-target "#activity"
                 :hx-swap   "outerHTML"}
    "Activity & Logs"]
   [:button.btn {:hx-post   "/actions/quit"
                 :hx-target "body"
                 :hx-swap   "innerHTML"
                 :hx-confirm "Quit Dapr? This stops the server."}
    "Quit"]])

(defn page
  "The whole document. The script URLs come from dapr.web.assets rather than being
  hard-coded, so the view stays free of classpath concerns."
  [state view {:keys [htmx-src htmx-sse-src]}]
  [:html {:lang "en" :data-theme (fmt/theme-attr (get-in state [:settings :theme] :system))}
   [:head
    [:meta {:charset "utf-8"}]
    [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
    [:title "Dapr — music library sync"]
    [:link {:rel "stylesheet" :href "/dapr.css"}]
    [:script {:src htmx-src :defer true}]
    [:script {:src htmx-sse-src :defer true}]
    [:script {:src "/virtual-tracks.js" :defer true}]]
   ;; One event stream for the page. The server pushes "region X moved" and the
   ;; regions below re-fetch themselves; see dapr.web.events.
   ;; htmx 4's SSE extension dispatches the named server event on this body, and
   ;; each live region listens with `from:body`.
   [:body {:hx-sse:connect "/events"}
    [:div.app
     (topbar state)
     (workspace state view)
     (status-bar state)]
    (overlay state)
    (activity state)]])

(defn stopped-page
  "What replaces the page when the user quits: the server is going away, so
  nothing on this page can work any more, and it should say so rather than sit
  there looking live."
  []
  [:div.app
   [:header.topbar [:span.brand "Dapr"]]
   [:main.workspace
    [:p "Dapr has stopped. You can close this tab."]]])
