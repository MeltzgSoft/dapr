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
           (javafx.collections ListChangeListener)
           (javafx.geometry Orientation)
           (javafx.scene Parent)
           (javafx.scene.control ListView ScrollBar TitledPane)
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

(defn- sync-bar
  "Source/sink pickers and the ↻ Refresh action. The background refresh (see
  dapr.refresh) — the scanning that used to freeze this bar, and now runs behind
  it — reports itself in the status bar along the window bottom, a row per
  library, rather than as a summary line here."
  [libraries source-id sink-id availability]
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
                           :text "Re-check which devices are reachable and re-scan the chosen source and sink"}
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
  maps for the track table, one per track: {:key :disc-number :track-number :title
  :duration-millis :artist :album :genre :size :sink-rel :in-source? :on? :disable}.
  Rows sort by disc/track/album/artist (the table
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
         (sort-by (juxt :disc-number :track-number
                        (comp fmt/sort-key :album) (comp fmt/sort-key :artist) :rel))
         (mapv (fn [t]
                 (let [k          (:key t)
                       in-source? (contains? source-catalog k)
                       on?        (contains? selected k)]
                   {:key             k
                    :disc-number     (:disc-number t)
                    :track-number    (:track-number t)
                    :title           (:title t)
                    :duration-millis (:duration-millis t)
                    :artist          (:artist t)
                    :album           (:album t)
                    :genre           (:genre t)
                    :size            (:size t)
                    :sink-rel        (:rel (get sink-catalog k))
                    :in-source?      in-source?
                    :on?             (if (and (not in-source?) locked?) true on?)
                    :disable         (cond
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
  `field` case-insensitively (see fmt/compare-field). Sink-only rows
  (`:in-source? false`) render red."
  [text field width render]
  {:fx/type            :table-column
   :text               text
   :pref-width         width
   :cell-value-factory identity
   :comparator         (fn [a b] (fmt/compare-field (field a) (field b)))
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
  'All'. Clicking an entry dispatches `click-event`: a single click filters by it
  ('All' clears the filter), a double-click checks/unchecks every track under it
  without narrowing the view (see events/facet-click!). Typing dispatches
  `search-event`."
  [title values search-text search-event click-event]
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
                  :tooltip     {:fx/type :tooltip
                                :text "Click to filter · double-click to check/uncheck all its tracks"}
                  ;; Filter on click-count (not the selection model) so a double-click
                  ;; can toggle the group without leaving the view filtered.
                  :on-mouse-clicked {:event/type click-event}}]})

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
                                 ::events/filter-search-artist ::events/facet-click-artist)
                  (filter-column "Album" albums (:album filter-search)
                                 ::events/filter-search-album ::events/facet-click-album)]}))

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
                          (track-column "Disc" :disc-number 55 #(some-> % str))
                          (track-column "Track" :track-number 55 #(some-> % str))
                          (track-column "Title" :title 200 identity)
                          (track-column "Duration" :duration-millis 80 fmt/duration-mmss)
                          (track-column "Artist" :artist 160 identity)
                          (track-column "Album" :album 160 identity)
                          (track-column "Genre" :genre 120 identity)
                          (track-column "Size" :size 90 #(when (some? %) (fmt/human-bytes %)))
                          (track-column "On sink" :sink-rel 160 identity)]})

(defn- task-row
  "One job as a pair of grid cells on row `i`: its name in the first column, and in
  the second its progress bar over its status text. The bar is omitted (rather than
  drawn empty, which reads as stalled) when there is no meaningful fraction —
  queued, failed, or still counting — leaving those jobs a single line of text.

  The name goes in a column of its own so the names line up, and the grid sizes
  that column to the longest of them: a fixed width either wastes the panel on
  short names or clips long ones. A failed row is red and keeps its reason in a
  tooltip as well, for when wrapping still isn't enough."
  [i {:keys [label detail progress error?]}]
  [{:fx/type              :label
    :text                 label
    :grid-pane/row        i
    :grid-pane/column     0
    :grid-pane/valignment :top}
   {:fx/type          :v-box
    :grid-pane/row    i
    :grid-pane/column 1
    :spacing          2
    :children
    (cond-> []
      progress (conj {:fx/type    :progress-bar
                      :max-width  Double/MAX_VALUE
                      :min-height 14
                      :progress   progress})
      :always  (conj (cond-> {:fx/type   :label
                              :text      detail
                              :wrap-text true
                              :style     (if error? "-fx-text-fill: red;" "")}
                       error? (assoc :tooltip {:fx/type :tooltip :text detail}))))}])

(defn- job-list
  "The per-job rows (see fmt/tasks) as a two-column grid: names sized to their
  content, everything else taking the remaining width. Says plainly when there is
  nothing running — this panel is opened deliberately to answer \"what is it
  doing?\", so an empty one must answer rather than leave a blank gap."
  [rows]
  {:fx/type            :grid-pane
   :hgap               8
   :vgap               8
   :padding            8
   :pref-width         300
   :max-width          420
   ;; Names take exactly the width they need and no more; the bar column absorbs
   ;; the slack, and gives it back (down to :min-width) when a long name needs the
   ;; room — text has to be read, where a progress bar only has to be seen.
   ;; :use-pref-size is what makes the name column *content*-sized: without a min
   ;; width of its own it is the column with slack, so GridPane shrinks it — and
   ;; ellipsises the names — to keep the progress bars at their preferred width.
   ;; Pinned to its content it takes exactly what the longest name needs, and the
   ;; bar column absorbs the slack or yields it back (down to :min-width): text has
   ;; to be read, where a progress bar only has to be seen.
   :column-constraints [{:fx/type :column-constraints :hgrow :never
                         :min-width :use-pref-size}
                        {:fx/type :column-constraints :hgrow :always :fill-width true
                         :min-width 90}]
   :children           (if (seq rows)
                         (into [] (comp (map-indexed task-row) cat) rows)
                         [{:fx/type          :label
                           :text             "Nothing running."
                           :style            "-fx-text-fill: gray;"
                           :grid-pane/row    0
                           :grid-pane/column 0}])})

(def ^:private collapsed-width
  "Width the jobs panel is clamped to while collapsed — its header and no more.
  Comfortably fits \"Jobs (99)\" plus the disclosure arrow; a longer title would
  ellipsise, which is self-evident rather than misleading."
  150)

(defn- jobs-panel
  "The activity window's jobs sidebar, collapsible so the log can have the whole
  window when the jobs aren't what you came to read. Expanded state lives in app
  state (see state/set-jobs-open), not in the TitledPane: the rows re-render
  constantly while a scan runs, and collapsing is the user's decision to keep. The
  count stays in the title, which is all that's left of the panel when collapsed."
  [{:keys [jobs-open?] :as state}]
  (let [rows (fmt/tasks state)]
    (cond-> {:fx/type     :titled-pane
             :text        (if (seq rows) (format "Jobs (%d)" (count rows)) "Jobs")
             :collapsible true
             ;; The user's click is read back by a raw listener, not a prop: cljfx's
             ;; titled-pane exposes :expanded as a setter only, with no change
             ;; callback (see ensure-jobs-expanded-listener!, same arrangement as
             ;; the log scrollbar).
             :expanded    (boolean jobs-open?)
             ;; The content stays put when collapsed. Dropping it (to stop a closed
             ;; panel reserving its content's width) breaks the pane permanently:
             ;; TitledPaneSkin's collapse animation captures the content node and
             ;; calls .setVisible on it when the transition ends, so removing it
             ;; mid-collapse NPEs on the JavaFX pulse, kills the transition, and the
             ;; body never lays out again on re-expand.
             :content     (job-list rows)}
      ;; So the width is handed back by clamping the *pane* instead — a collapsed
      ;; TitledPane is only a title bar, and this is what stops it holding the
      ;; sidebar's full width open behind one.
      (not jobs-open?) (assoc :max-width collapsed-width))))

(def ^:private activity-hint
  "Tooltip for the status summary. Lives on its labels rather than on the strip
  itself: :tooltip is a Control prop, and an HBox is a plain Region."
  {:fx/type :tooltip
   :text    "Open the activity window for every job and the live log"})

(defn- status-summary
  "Strip pinned to the main window's bottom: a spinner while work is in flight and
  a one-line digest of it (see fmt/status-summary), the whole strip clicking
  through to the activity window for the per-job detail. nil when nothing is
  running, so an idle app shows no strip at all."
  [state]
  (when-let [{:keys [text running? error?]} (fmt/status-summary state)]
    {:fx/type   :h-box
     :padding   8
     :spacing   8
     :alignment :center-left
     :cursor    :hand
     :on-mouse-clicked {:event/type ::events/view-logs}
     :children  (cond-> []
                  running? (conj {:fx/type    :progress-indicator
                                  :progress   -1.0        ; indeterminate: spins
                                  :max-width  16
                                  :max-height 16})
                  :always  (conj {:fx/type :label
                                  :text    text
                                  :tooltip activity-hint
                                  :style   (if error? "-fx-text-fill: red;" "")}
                                 {:fx/type :region :h-box/hgrow :always}
                                 {:fx/type :label
                                  :text    "Details ▸"
                                  :tooltip activity-hint
                                  :style   "-fx-text-fill: gray; -fx-underline: true;"}))}))

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
     :items [{:fx/type :menu-item :text "Activity & Logs…"
              :on-action {:event/type ::events/view-logs}}]}]})

(defn- vertical-scrollbar
  "The ListView's vertical scrollbar, or nil before the skin has laid it out (a short
  list has none)."
  ^ScrollBar [^ListView lv]
  (->> (.lookupAll lv ".scroll-bar")
       (filter #(and (instance? ScrollBar %)
                     (= Orientation/VERTICAL (.getOrientation ^ScrollBar %))))
       first))

(def ^:private log-scroll-wired
  "Key stashed on the ListView's properties to wire the scrollbar listener exactly
  once, the first render at which the scrollbar exists."
  ::log-scroll-wired)

(defn- ensure-log-scrollbar-listener!
  "Attach the freeze detector to the ListView's vertical scrollbar the first time it
  exists (it is created lazily once the list overflows). cljfx has no scrollbar-value
  prop, so we listen directly and feed the value back through events/on-log-scroll!."
  [^ListView lv]
  (let [props (.getProperties lv)]
    (when-not (.get props log-scroll-wired)
      (when-let [sb (vertical-scrollbar lv)]
        (.put props log-scroll-wired true)
        (.addListener (.valueProperty sb)
                      (reify ChangeListener
                        (changed [_ _ _ nv]
                          (events/on-log-scroll! (double nv)))))))))

(defn- scroll-log-to-tail!
  "Pin the ListView to its newest line (only while following). Deferred to the next
  pulse so the freshly appended cells are laid out before we scroll."
  [^ListView lv]
  (when (:log-follow? (events/log-state))
    (let [n (.size (.getItems lv))]
      (when (pos? n)
        (Platform/runLater #(.scrollTo lv (dec n)))))))

(def ^:private jobs-expanded-wired
  "Key stashed on the jobs TitledPane's properties so its listener is wired once."
  ::jobs-expanded-wired)

(defn- ensure-jobs-expanded-listener!
  "Report the jobs sidebar's expanded state back to app state when the user clicks
  its header. cljfx's titled-pane exposes :expanded as a setter with no change
  callback, so — as with the log scrollbar above — we listen to the property
  directly and feed the value back through events/on-jobs-expanded!. Setting
  :expanded from state re-enters here with the value state already holds, so there
  is no feedback loop."
  [^TitledPane tp]
  (let [props (.getProperties tp)]
    (when-not (.get props jobs-expanded-wired)
      (.put props jobs-expanded-wired true)
      (.addListener (.expandedProperty tp)
                    (reify ChangeListener
                      (changed [_ _ _ nv]
                        (events/on-jobs-expanded! (boolean nv))))))))

(defn- attach-log-window!
  "On the activity window root's creation, wire the ListView's tail-follow. cljfx
  reuses the ListView instance and .setAll's each new :log into its own items list, so
  a single ListChangeListener fires on every appended line: while following we re-pin to
  the tail; a ListView keeps its scroll position otherwise, so a frozen view stays put
  as lines stream in. Also wires the scrollbar freeze detector and does the initial
  pin so the window opens at the newest line, plus the jobs sidebar's collapse
  listener."
  [^Parent root]
  (when-let [lv (.lookup root ".list-view")]
    (let [^ListView lv lv]
      (ensure-log-scrollbar-listener! lv)
      (scroll-log-to-tail! lv)
      (.addListener (.getItems lv)
                    (reify ListChangeListener
                      (onChanged [_ _]
                        (ensure-log-scrollbar-listener! lv)
                        (scroll-log-to-tail! lv))))))
  (when-let [tp (.lookup root ".titled-pane")]
    (ensure-jobs-expanded-listener! tp)))

(defn- log-window
  "On-demand activity window (shown via :log-open?, the View ▸ Activity… menu and
  the main window's status strip): what the app is working on right now, in a
  sidebar of per-job rows, beside the live log of what it has done. A read-only
  ListView follows the tail — re-pinned to the newest line as Telemere signals
  stream in (see dapr.log) — until the user scrolls up, which freezes the view at
  their position (see state/log-scrolled); the ⤓ button re-engages following and
  snaps back to the newest line.

  attach-log-window! finds that ListView by CSS lookup from this root, so the jobs
  sidebar beside it must stay free of list views."
  [{:keys [log log-follow? log-open?] :as state}]
  {:fx/type  :stage
   :showing  (boolean log-open?)
   :title    "Dapr — Activity"
   :width    1040
   :height   460
   :on-close-request {:event/type ::events/log-close}
   :scene
   {:fx/type     :scene
    :stylesheets (theme-stylesheets state)
    :root
    {:fx/type    fx/ext-on-instance-lifecycle
     :on-created attach-log-window!
     :desc
     {:fx/type :border-pane
      :left    (jobs-panel state)
      :center
      {:fx/type  :v-box
       :spacing  8
       :padding  8
       :children [{:fx/type     :list-view
                   :v-box/vgrow :always
                   :items       log}
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
                                :on-action {:event/type ::events/log-close}}]}]}}}}})

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

(def ^:private confirm-events
  "Event dispatched when a confirmation of each :kind is accepted. Cancelling is
  generic (::confirm-cancel just closes the dialog)."
  {:sync {:event/type ::events/sync-confirm}})

(defn- confirm-stage
  "Modal yes/no dialog, shown whenever :confirm is set (see state/open-confirm).
  Currently only the sync gate uses it: a sync against a library whose background
  refresh hasn't finished plans from a catalog that may still list tracks the
  device no longer has."
  [{:keys [confirm] :as state}]
  (let [{:keys [kind title message confirm-text]} confirm]
    {:fx/type  :stage
     :showing  (boolean confirm)
     :modality :application-modal
     :title    (or title "Confirm")
     :width    460
     :height   190
     :on-close-request {:event/type ::events/confirm-cancel}
     :scene
     {:fx/type     :scene
      :stylesheets (theme-stylesheets state)
      :root
      {:fx/type  :v-box
       :spacing  12
       :padding  16
       :children [{:fx/type     :label
                   :wrap-text   true
                   :v-box/vgrow :always
                   :text        (or message "")}
                  {:fx/type   :h-box
                   :spacing   8
                   :alignment :center-right
                   :children  [{:fx/type   :button
                                :text      "Cancel"
                                :cancel-button true
                                :on-action {:event/type ::events/confirm-cancel}}
                               {:fx/type   :button
                                :text      (or confirm-text "OK")
                                :default-button true
                                :on-action (get confirm-events kind
                                                {:event/type ::events/confirm-cancel})}]}]}}}))

(defn- main-stage
  "The primary window: menu bar over the sync workspace, with a one-line status
  summary pinned along the bottom while there is a job to report. Both the detail
  behind that summary and the activity log live in the on-demand activity window
  (View ▸ Activity & Logs…, or a click on the summary — see log-window), so the
  workspace itself stays for the libraries."
  [state]
  (let [bar (status-summary state)]
    {:fx/type :stage
     :showing true
     :title   "Dapr — music library sync"
     :width   860
     :height  680
     :on-close-request {:event/type ::events/quit}
     :scene
     {:fx/type :scene
      :stylesheets (theme-stylesheets state)
      ;; :bottom is omitted, not nil, when nothing is running: cljfx builds a
      ;; component per prop value and has no lifecycle for nil.
      :root
      (cond-> {:fx/type :border-pane
               :top     (menu-bar)
               :center  (sync-pane state)}
        bar (assoc :bottom bar))}}))

(defn root-view
  "Render the whole application: the main window plus the (modal) settings and
  confirmation windows and the (on-demand) live log window, whose visibility is
  driven by the state."
  [state]
  {:fx/type fx/ext-many
   :desc    [(main-stage state) (settings-stage state)
             (confirm-stage state) (log-window state)]})
