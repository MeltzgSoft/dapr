(ns dapr.ui.views
  "Pure cljfx view descriptions for the Dapr window. Functions take the
  application state map and return cljfx data (no side effects). User events are
  dispatched to dapr.ui.events; formatting/predicates live in dapr.ui.format and
  dapr.domain.capacity."
  (:require [cljfx.api :as fx]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [dapr.device.file.views]
            [dapr.device.format :as device-format]
            [dapr.device.mtp.views]
            [dapr.device.smb.views]
            [dapr.device.views :as device-views]
            [dapr.domain.capacity :as cap]
            [dapr.domain.library :as lib]
            [dapr.ui.events :as events]
            [dapr.ui.format :as fmt])
  (:import (javafx.application Platform)
           (javafx.beans.value ChangeListener)
           (javafx.scene Parent)
           (javafx.scene.control TextArea)
           (javafx.stage Screen)))

;; --- library manager ---------------------------------------------------------

(defn- default-toggle
  "A toggle marking library `l` as the default `role` (:source/:sink) on launch.
  Reflects the persisted flag and dispatches ::library-default to flip it."
  [l role on? tooltip]
  {:fx/type   :toggle-button
   :text      (name role)
   :selected  on?
   :tooltip   {:fx/type :tooltip :text tooltip}
   :on-action {:event/type ::events/library-default :role role :id (:id l)}})

(defn- library-list [libraries]
  {:fx/type :v-box
   :spacing 4
   :children
   (into [{:fx/type :h-box :spacing 8 :alignment :center-left
           :children [{:fx/type :label :text "Libraries" :style "-fx-font-weight: bold;"}
                      {:fx/type :menu-button :text "New…"
                       :items (mapv device-views/library-menu-item device-format/types)}]}]
         (for [l libraries]
           {:fx/type :h-box :spacing 8 :alignment :center-left
            :children [{:fx/type :label :min-width 180
                        :text (format "%s  (%d dirs)" (:name l) (count (:roots l)))}
                       {:fx/type :label :text "Default:"}
                       (default-toggle l :source (:default-source? l) "Pre-select as the sync source on launch")
                       (default-toggle l :sink (:default-sink? l) "Pre-select as the sync sink on launch")
                       {:fx/type :button :text "Edit"
                        :on-action {:event/type ::events/library-edit :id (:id l)}}
                       {:fx/type :button :text "Delete"
                        :on-action {:event/type ::events/library-delete :id (:id l)}}]}))})

(defn- root-row [uri]
  {:fx/type :h-box :spacing 8 :alignment :center-left
   :children [{:fx/type :label :h-box/hgrow :always :text uri}
              {:fx/type :button :text "Remove"
               :on-action {:event/type ::events/editor-remove-root :uri uri}}]})

(defn- browser-panel [allowed browser]
  {:fx/type :v-box :spacing 6
   :style "-fx-border-color: gray; -fx-border-radius: 4; -fx-padding: 8;"
   :children
   [{:fx/type :label :text "Browse for a folder" :style "-fx-font-weight: bold;"}
    (device-views/browser-content allowed browser)
    {:fx/type :h-box :alignment :center-right
     :children [{:fx/type :button :text "Cancel"
                 :on-action {:event/type ::events/browser-cancel}}]}]})

(defn- editor-panel [{:keys [name roots]} browser]
  {:fx/type :v-box
   :spacing 6
   :style "-fx-border-color: gray; -fx-border-radius: 4; -fx-padding: 8;"
   :children
   (cond-> [{:fx/type :h-box :spacing 8 :alignment :center-left
             :children [{:fx/type :label :min-width 60 :text "Name"}
                        {:fx/type :text-field :h-box/hgrow :always :text name
                         :on-text-changed {:event/type ::events/editor-name}}]}
            {:fx/type :label :text "Roots"}
            {:fx/type :v-box :spacing 2
             :children (if (seq roots)
                         (mapv root-row roots)
                         [{:fx/type :label :text "(no roots yet)"}])}
            {:fx/type :h-box :spacing 8 :alignment :center-left
             :children [{:fx/type :button :text "Browse…"
                         :disable (some? browser)
                         :on-action {:event/type ::events/editor-browse}}]}]
     browser (conj (browser-panel (lib/roots-device-key roots) browser))
     :always (conj {:fx/type :h-box :spacing 8
                    :children [{:fx/type :button :text "Save"
                                :on-action {:event/type ::events/editor-save}}
                               {:fx/type :button :text "Cancel"
                                :on-action {:event/type ::events/editor-cancel}}]}))})

;; --- sync workflow -----------------------------------------------------------

(defn- library-combo
  "Source/sink picker. Libraries in `unavailable` (a set of names whose device was
  probed unreachable) render greyed and disabled in the dropdown, so they can't be
  chosen (a disabled list cell isn't selectable)."
  [event-type value libraries unavailable]
  {:fx/type :combo-box
   :prompt-text "—"
   :items (mapv :name libraries)
   :value value
   :on-value-changed {:event/type event-type}
   :cell-factory {:fx/cell-type :list-cell
                  :describe (fn [nm]
                              (cond-> {:text nm}
                                (contains? unavailable nm)
                                (assoc :disable true :style "-fx-text-fill: gray;")))}})

(defn- sync-bar [libraries source-id sink-id availability]
  (let [name-of     (fn [id] (:name (first (filter #(= (:id %) id) libraries))))
        unavailable (into #{} (comp (filter #(fmt/library-unavailable? availability (:id %)))
                                    (map :name))
                          libraries)]
    {:fx/type :h-box :spacing 8 :alignment :center-left
     :children [{:fx/type :label :text "Source"}
                (library-combo ::events/select-source (name-of source-id) libraries unavailable)
                {:fx/type :label :text "Sink"}
                (library-combo ::events/select-sink (name-of sink-id) libraries unavailable)
                {:fx/type :button :text "↻ Refresh"
                 :tooltip {:fx/type :tooltip
                           :text "Re-check which libraries' devices are reachable"}
                 :on-action {:event/type ::events/refresh-availability}}]}))

(defn- capacity-bar
  "Capacity meter for the sink library `sink-name` (how full it would be after the
  selected sync), so it's clear which library the bar is about. With no sink chosen
  (sink-name nil) capacity is undefined — the bar shows a prompt rather than a
  misleading 0 B / 0 B, since the source tracks are shown for browsing only until a
  sink is picked."
  [capacity sink-name]
  {:fx/type :h-box :spacing 8 :alignment :center-left
   :children [{:fx/type :label :min-width 70
               :text (if sink-name (str "Capacity — " sink-name) "Capacity")}
              {:fx/type :progress-bar :h-box/hgrow :always :max-width Double/MAX_VALUE
               :progress (if sink-name (fmt/capacity-fraction capacity) 0.0)}
              {:fx/type :label
               :text (if sink-name (fmt/capacity-text capacity) "Select a sink")
               :style (if (and sink-name (fmt/over-capacity? capacity)) "-fx-text-fill: red;" "")}]})

(defn- track-rows
  "Resolve the union of the source and sink catalogs into a sorted vector of row
  maps for the track table, one per track: {:key :artist :album :title :size
  :sink-rel :in-source? :on? :disable}. Rows sort by artist/album/title (the table
  lets the user re-sort by any column). Capacity is checked in constant time per
  row against the selection's remaining free bytes (computed once from :capacity),
  so this stays O(n) even for libraries of many thousands of tracks — see
  dapr.domain.capacity/row-fits?. Only tracks matching the column-browser filter
  are rowed (see filter-browser); selection/capacity still span the whole catalog.

  Tracks present on the sink but absent from the source are flagged
  `:in-source? false` (rendered red by track-column). Under :keep / :add-to-source
  handling they are retained regardless of selection, so their checkbox is locked
  on (`:on? true`, `:disable true`); under :delete the checkbox spares them from
  deletion when ticked."
  [{:keys [source-catalog sink-catalog selected capacity filter settings]}]
  (let [free     (:free capacity)
        handling (get settings :sink-only-handling :keep)
        locked?  (contains? #{:keep :add-to-source} handling)]
    (->> (vals (fmt/filter-catalog (merge sink-catalog source-catalog) filter))
         (sort-by (juxt :artist :album :title :rel))
         (mapv (fn [t]
                 (let [k          (:key t)
                       in-source? (contains? source-catalog k)
                       on?        (contains? selected k)]
                   {:key        k
                    :artist     (:artist t)
                    :album      (:album t)
                    :title      (:title t)
                    :size       (:size t)
                    :sink-rel   (:rel (get sink-catalog k))
                    :in-source? in-source?
                    :on?        (if (and (not in-source?) locked?) true on?)
                    :disable    (cond
                                  (not in-source?) locked?
                                  on?              false
                                  :else            (not (cap/row-fits?
                                                         k (:size t) selected sink-catalog free)))}))))))

(defn- check-column
  "Leading selection column: a fixed-width checkbox per row, disabled when adding
  the track would overflow the sink (see track-rows). Toggling dispatches
  ::toggle-track. Carries the whole row as its cell value (identity factory)."
  []
  {:fx/type            :table-column
   :text               ""
   :sortable           false
   :resizable          false
   :pref-width         36
   :cell-value-factory identity
   :cell-factory       {:fx/cell-type :table-cell
                        ;; A recycled cell can transiently describe a nil row
                        ;; (empty=false, item=nil); return the blank {} description
                        ;; for it — a nil :selected NPEs the check-box and a
                        ;; {:graphic nil} makes cljfx try to create a nil component.
                        :describe (fn [row]
                                    (if row
                                      {:graphic {:fx/type  :check-box
                                                 :selected (boolean (:on? row))
                                                 :disable  (boolean (:disable row))
                                                 :on-selected-changed
                                                 {:event/type ::events/toggle-track :key (:key row)}}}
                                      {}))}})

(defn- track-column
  "A track-table data column carrying the whole row as its cell value
  (`:cell-value-factory identity`) so a cell can colour itself by the row while the
  column still sorts by its own `field`. `field` selects the displayed value and
  `render` formats it to a string (nil → blank); the `:comparator` orders rows by
  `field` (clojure.core/compare handles nil and numbers). Sink-only rows
  (`:in-source? false`) render red."
  [text field width render]
  {:fx/type            :table-column
   :text               text
   :pref-width         width
   :cell-value-factory identity
   :comparator         (fn [a b] (compare (field a) (field b)))
   :cell-factory       {:fx/cell-type :table-cell
                        ;; A recycled cell can transiently describe a nil row; the
                        ;; blank {} description keeps it from rendering garbage.
                        :describe (fn [row]
                                    (if row
                                      (cond-> {:text (render (field row))}
                                        (not (:in-source? row))
                                        (assoc :style "-fx-text-fill: red;"))
                                      {}))}})

(defn- filter-column
  "One column of the iTunes-style browser: a header (with a count), a search field
  that narrows the list as you type, and a virtualized list whose first entry is
  'All'. Selecting an entry dispatches `select-event` ('All' is normalized to nil
  in the handler); typing dispatches `search-event`."
  [title values search-text search-event select-event]
  {:fx/type     :v-box
   :h-box/hgrow :always
   :spacing     2
   :children    [{:fx/type :label :style "-fx-font-weight: bold;"
                  :text (format "%s (%d)" title (count values))}
                 {:fx/type         :text-field
                  :text            search-text
                  :prompt-text     (str "Filter " (str/lower-case title) "…")
                  :on-text-changed {:event/type search-event}}
                 {:fx/type     :list-view
                  ;; Grow to fill the resizable browser section rather than a fixed
                  ;; height, so dragging the divider gives the lists more room.
                  :v-box/vgrow :always
                  :items       (into ["All"] values)
                  :on-selected-item-changed {:event/type select-event}}]})

(defn- filter-browser
  "iTunes-style column browser: an Artist column and an Album column scoped to the
  selected artist, each with a search field narrowing its values. Selections
  narrow the visible tracks via the :filter in state (see track-rows). Lives in
  its own resizable split section above the table."
  [{:keys [source-catalog filter filter-search]}]
  (let [artists (fmt/search-filter (fmt/artists source-catalog) (:artist filter-search))
        albums  (fmt/search-filter (fmt/albums source-catalog (:artist filter)) (:album filter-search))]
    {:fx/type    :h-box
     :spacing    8
     ;; Floor so the browser can't be dragged shut entirely.
     :min-height 80
     :children   [(filter-column "Artist" artists (:artist filter-search)
                                 ::events/filter-search-artist ::events/filter-artist)
                  (filter-column "Album" albums (:album filter-search)
                                 ::events/filter-search-album ::events/filter-album)]}))

(defn- track-table
  "The source-track picker as a virtualized TableView: JavaFX realizes only the
  rows currently scrolled into view, not one node per track, so a multi-thousand
  -track library scrolls and sorts smoothly. A leading checkbox column drives
  selection; the remaining columns show the track's tags, size, and where it
  currently lives on the sink."
  [state]
  {:fx/type              :table-view
   ;; Floor so the table keeps usable height as the browser divider is dragged.
   :min-height           120
   :column-resize-policy :constrained
   :items                (track-rows state)
   :columns              [(check-column)
                          (track-column "Artist" :artist 160 identity)
                          (track-column "Album" :album 160 identity)
                          (track-column "Title" :title 200 identity)
                          (track-column "Size" :size 90 #(when (some? %) (fmt/human-bytes %)))
                          (track-column "On sink" :sink-rel 160 identity)]})

(defn- progress-bar [progress]
  {:fx/type    :progress-bar
   :max-width  Double/MAX_VALUE
   :min-height 18
   :progress   (if (and progress (pos? (:total progress)))
                 (/ (double (:done progress)) (:total progress))
                 0.0)})

(defn- status-bar
  "An always-visible strip pinned to the window bottom holding the status text and
  scan/sync progress bar, so they can never be clipped by the resizable split
  above them."
  [progress status]
  {:fx/type   :h-box
   :padding   8
   :spacing   8
   :alignment :center-left
   :children  [{:fx/type :label :text (str "Status: " (fmt/status-text status))}
               (assoc (progress-bar progress) :h-box/hgrow :always)]})

(defn- controls-row [state]
  {:fx/type   :h-box
   :spacing   8
   :alignment :center-left
   :children  [{:fx/type :button :text "Preview"
                :disable (not (fmt/can-preview? state))
                :on-action {:event/type ::events/preview}}
               {:fx/type :button :text "Sync"
                :disable (not (fmt/can-sync? state))
                :on-action {:event/type ::events/sync}}]})

(defn- sync-pane
  "Top section of the workspace: the source/sink pickers, capacity meter, the
  track picker (which grows to fill the section), the action buttons and the plan
  summary."
  [{:keys [libraries source-id sink-id capacity plan library-availability] :as state}]
  {:fx/type    :v-box
   :spacing    10
   :padding    12
   ;; Keep a floor on the sync area so dragging the divider all the way down can't
   ;; collapse it entirely.
   :min-height 200
   :children   [(sync-bar libraries source-id sink-id library-availability)
                (capacity-bar capacity (some #(when (= (:id %) sink-id) (:name %)) libraries))
                ;; The filter browser and the track table share a draggable
                ;; vertical split, so growing the table never squeezes the browser
                ;; shut (and vice versa).
                {:fx/type           :split-pane
                 :orientation       :vertical
                 :v-box/vgrow       :always
                 :divider-positions [0.35]
                 :items             [(filter-browser state)
                                     (track-table state)]}
                (controls-row state)
                {:fx/type :label :text (fmt/plan-summary-text (:summary plan))}]})

;; --- window assembly ---------------------------------------------------------

(def ^:private theme-css
  "Resolve a theme keyword (:dark/:light) to its stylesheet's external-form URL.
  Memoized — the classpath resource is fixed for the run."
  (memoize (fn [theme] (.toExternalForm (io/resource (str (name theme) ".css"))))))

(defn- theme-stylesheets
  "The `:stylesheets` vector for a scene, resolved from the persisted :theme setting
  and the live OS colour scheme (see fmt/active-theme). Applied to every scene so
  the whole UI re-styles when the theme setting or OS scheme changes."
  [{:keys [settings os-color-scheme]}]
  [(theme-css (fmt/active-theme (:theme settings :system) os-color-scheme))])

(defn- menu-bar []
  {:fx/type :menu-bar
   :menus
   [{:fx/type :menu :text "File"
     :items [{:fx/type :menu-item :text "Quit"
              :on-action {:event/type ::events/quit}}]}
    {:fx/type :menu :text "Settings"
     :items [{:fx/type :menu-item :text "Manage Libraries…"
              :on-action {:event/type ::events/settings-open}}]}
    {:fx/type :menu :text "View"
     :items [{:fx/type :menu-item :text "View Logs…"
              :on-action {:event/type ::events/view-logs}}]}]})

(defn- scroll-log-to-bottom!
  "After the log text changes, pin the TextArea to the newest line if follow mode is
  on. Deferred to runLater so it runs after cljfx has installed the new text (which
  resets scrollTop toward the top)."
  [^TextArea ta]
  (when (events/log-following?)
    (Platform/runLater
     (fn []
       (when (events/log-following?)
         (.setScrollTop ta Double/MAX_VALUE))))))

(defn- attach-log-scroll-listener!
  "Attach JavaFX listeners cljfx does not expose as props.

  A scrollTop listener feeds every change (including scrollbar drags and wheel/
  keyboard scrolls — anything cljfx's :scroll-top prop can't observe) into
  events/on-log-scroll!, which disengages following when the user scrolls up (see
  state/log-scroll-changed — decided from scrollTop alone, so there is no
  scrollbar/scrollTop timing skew). A text listener re-pins to the bottom after each
  append while following."
  [^Parent root]
  (when-let [ta (.lookup root ".text-area")]
    (.addListener (.scrollTopProperty ^TextArea ta)
                  (reify ChangeListener
                    (changed [_ _ _ nv]
                      (events/on-log-scroll! nv))))
    (.addListener (.textProperty ^TextArea ta)
                  (reify ChangeListener
                    (changed [_ _ _ _]
                      (scroll-log-to-bottom! ta))))
    (scroll-log-to-bottom! ta)))

(defn- log-window
  "On-demand live log window (shown via :log-open?, the View ▸ View Logs… menu). The
  read-only text-area follows the tail — :scroll-top is pinned far past the bottom so
  it clamps there and grows with each line. A user scroll away from the bottom
  freezes the view: it shows the :log-frozen snapshot held at the user's position so
  streaming lines neither move nor grow it (see state/log-scroll-changed). Scrolling
  back to the bottom, or the ⤓ button, re-engages following and snaps to the newest
  line."
  [{:keys [log log-appends log-open? log-follow? log-scroll log-frozen] :as state}]
  {:fx/type  :stage
   :showing  (boolean log-open?)
   :title    "Dapr — Logs"
   :width    760
   :height   460
   :on-close-request {:event/type ::events/log-close}
   :scene
   {:fx/type     :scene
    :stylesheets (theme-stylesheets state)
    :root
    {:fx/type    fx/ext-on-instance-lifecycle
     :on-created attach-log-scroll-listener!
     :desc
     {:fx/type  :v-box
      :spacing  8
      :padding  8
      :children [{:fx/type     :text-area
                  :v-box/vgrow :always
                  :editable    false
                  ;; Following: pin past the max so it clamps to (and grows with) the
                  ;; bottom. Frozen: hold the captured position and the snapshot text,
                  ;; so appends can't move or regrow the view (and :scroll-top is never
                  ;; removed, which would reset it to the top).
                  :text        (str/join "\n" (if log-follow? log log-frozen))
                  :scroll-top  (if log-follow? (* log-appends 1.0e7) log-scroll)}
                 {:fx/type   :h-box
                  :spacing   8
                  :alignment :center-right
                  :children  [{:fx/type   :button
                               :text      "⤓ Jump to bottom"
                               :disable   (boolean log-follow?)
                               :tooltip   {:fx/type :tooltip
                                           :text "Resume auto-scrolling to the newest line"}
                               :on-action {:event/type ::events/log-follow}}
                              {:fx/type :button :text "Close"
                               :on-action {:event/type ::events/log-close}}]}]}}}})

(defn- browser-panel-height
  "Estimated height of the open folder browser. Device-specific chooser/connect
  phases provide their own estimates; folder browsing is a fixed-height list."
  [browser]
  (+ 74 (device-views/browser-height browser)))

(defn- sink-only-options
  "Radio group choosing how tracks that are on the sink but not the source are
  treated on sync — the persisted :sink-only-handling app setting. Each choice
  dispatches ::set-setting; the buttons are mutually exclusive because only the one
  matching `handling` renders selected (re-render deselects the others)."
  [handling]
  (let [choice (fn [value label]
                 {:fx/type   :radio-button
                  :text      label
                  :selected  (= handling value)
                  :on-action {:event/type ::events/set-setting
                              :key :sink-only-handling :value value}})]
    {:fx/type :v-box :spacing 6
     :style   "-fx-border-color: gray; -fx-border-radius: 4; -fx-padding: 8;"
     :children [{:fx/type :label :style "-fx-font-weight: bold;"
                 :text "Tracks on the sink but not the source"}
                (choice :keep "Keep on sink")
                (choice :delete "Delete from sink")
                (choice :add-to-source "Copy back to source")]}))

(defn- theme-options
  "Radio group choosing the persisted :theme app setting (System / Light / Dark).
  :system follows the OS colour scheme (see fmt/active-theme). Mutually exclusive
  for the same reason as sink-only-options."
  [theme]
  (let [choice (fn [value label]
                 {:fx/type   :radio-button
                  :text      label
                  :selected  (= theme value)
                  :on-action {:event/type ::events/set-setting :key :theme :value value}})]
    {:fx/type :v-box :spacing 6
     :style   "-fx-border-color: gray; -fx-border-radius: 4; -fx-padding: 8;"
     :children [{:fx/type :label :style "-fx-font-weight: bold;" :text "Theme"}
                (choice :system "System")
                (choice :light "Light")
                (choice :dark "Dark")]}))

(defn- log-settings
  "Settings panel showing the current log file and a button to choose the log
  directory (the :log-dir setting; nil = system temp). Dispatches ::choose-log-dir."
  [log-file]
  {:fx/type :v-box :spacing 6
   :style   "-fx-border-color: gray; -fx-border-radius: 4; -fx-padding: 8;"
   :children [{:fx/type :label :style "-fx-font-weight: bold;" :text "Logs"}
              {:fx/type :label :text (str "Current log: " (or log-file "—"))}
              {:fx/type :h-box :spacing 8 :alignment :center-left
               :children [{:fx/type :button :text "Change log folder…"
                           :on-action {:event/type ::events/choose-log-dir}}]}]})

(defn- settings-height
  "Preferred settings-window height for the current content, so the window grows
  and shrinks with what it shows. Built additively from the body's actual parts —
  the library list (one row per library), or the editor (a fixed header plus one
  row per root, plus the folder browser when open) — rather than a blanket guess,
  so the window hugs its content. Capped at the screen height (less a margin),
  beyond which the body scrolls instead of growing further."
  [editor browser libraries]
  (let [chrome 96                          ; window chrome + padding + Close row
        body   (if editor
                 (+ 147                                       ; name/labels/add/save rows
                    (* 28 (max 1 (count (:roots editor))))    ; one row per root
                    (if browser (browser-panel-height browser) 0))
                 (+ 410 (* 32 (count libraries))))]           ; library list + settings panels
    (min (- (.getHeight (.getVisualBounds (Screen/getPrimary))) 60)
         (+ chrome body))))

(defn- settings-stage
  "Modal window holding the library creation/management UI. Stays in the scene
  graph at all times; its visibility tracks :settings-open? in the state. Its
  height tracks the content (see settings-height); the body sits in a scroll-pane
  so it scrolls only once the window hits its screen-bounded maximum."
  [{:keys [settings-open? libraries editor browser settings log-file] :as state}]
  {:fx/type  :stage
   :showing  (boolean settings-open?)
   :modality :application-modal
   :title    "Settings — Libraries"
   :width    640
   :height   (settings-height editor browser libraries)
   :on-close-request {:event/type ::events/settings-close}
   :scene
   {:fx/type :scene
    :stylesheets (theme-stylesheets state)
    :root
    {:fx/type :v-box
     :spacing 10
     :padding 12
     ;; While creating/editing, show only the editor; otherwise the library list.
     :children [{:fx/type :scroll-pane
                 :v-box/vgrow :always
                 :fit-to-width true
                 :content (if editor
                            (editor-panel editor browser)
                            {:fx/type :v-box :spacing 12
                             :children [(library-list libraries)
                                        (sink-only-options
                                         (get settings :sink-only-handling :keep))
                                        (theme-options (get settings :theme :system))
                                        (log-settings log-file)]})}
                {:fx/type :h-box :alignment :center-right
                 :children [{:fx/type :button :text "Close"
                             :on-action {:event/type ::events/settings-close}}]}]}}})

(defn- main-stage
  "The primary window: menu bar over the sync workspace, with the scan/sync progress
  strip pinned along the bottom. The activity log now lives in its own on-demand
  window (View ▸ View Logs…, see log-window) rather than an always-on panel."
  [state]
  {:fx/type :stage
   :showing true
   :title   "Dapr — music library sync"
   :width   860
   :height  680
   :on-close-request {:event/type ::events/quit}
   :scene
   {:fx/type :scene
    :stylesheets (theme-stylesheets state)
    :root
    {:fx/type :border-pane
     :top     (menu-bar)
     :center  (sync-pane state)
     :bottom  (status-bar (:progress state) (:status state))}}})

(defn root-view
  "Render the whole application: the main window plus the (modal) settings window
  and the (on-demand) live log window, whose visibility is driven by the state."
  [state]
  {:fx/type fx/ext-many
   :desc    [(main-stage state) (settings-stage state) (log-window state)]})
