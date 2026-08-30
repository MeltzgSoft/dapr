(ns dapr.web.routes
  "HTTP surface of the UI.

  Three kinds of route, and no others:

  - `GET /` renders the whole page from the current application state;
  - `GET /fragments/<region>` re-renders one region, or answers 204 when the
    digest the client sent still matches (see dapr.web.fragments) — this is how
    background work reaches an open page, prompted by `GET /events`, the stream
    that tells a page which regions have moved (see dapr.web.events);
  - `POST /actions/...` performs something (dapr.ui.actions) and answers with the
    regions that changed: the first swapped into the control's target, the rest
    out of band.

  Every parameter a control needs travels in its URL or its own form value, so
  there is no session and no server-side notion of a connected client: two
  browsers pointed at Dapr see the same application, and a reload rebuilds the
  page exactly."
  (:require [clojure.edn :as edn]
            [dapr.ui.actions :as actions]
            [dapr.ui.views :as views]
            [dapr.web.assets :as assets]
            [dapr.web.events :as events]
            [dapr.web.fragments :as fragments]
            [hiccup2.core :as h]
            [reitit.ring :as ring]
            [ring.middleware.keyword-params :refer [wrap-keyword-params]]
            [ring.middleware.params :refer [wrap-params]]
            [ring.util.response :as resp]))

;; --- request/response helpers ------------------------------------------------

(defn- html
  "200 with `hiccup` rendered as an HTML fragment."
  [hiccup]
  (-> (resp/response (str (h/html hiccup)))
      (resp/content-type "text/html; charset=utf-8")))

(def ^:private no-content
  "204: htmx leaves the DOM alone. The answer to a poll that found nothing new,
  and to an action whose only job was to record something in state."
  {:status 204 :headers {} :body ""})

(defn- oob
  "Mark a rendered region as an out-of-band swap, so one response can update every
  region an action touched while still swapping the first into the target the
  control asked for."
  [element]
  (when element
    (let [[tag & body] element
          [attrs content] (if (map? (first body))
                            [(first body) (rest body)]
                            [{} body])]
      (into [tag (assoc attrs :hx-swap-oob "true")] content))))

(defn- view-of
  "The track table's sort/page from the request's parameters. An unrecognized sort
  field is read as no sort, and a missing or unparseable page as the first."
  [{:keys [sort dir page]}]
  (let [field (some-> (not-empty (str sort)) keyword)]
    {:sort (when (contains? views/sortable-fields field) field)
     :dir  (if (= "desc" dir) :desc :asc)
     :page (or (some-> page str parse-long) 0)}))

(defn- render-regions
  "Response for `regions`: the first swapped into the target, the rest out of band."
  [state view regions]
  (html (cons (fragments/render state view (first regions))
              (map (fn [r] (oob (fragments/render state view r))) (rest regions)))))

;; --- parameter coercion ------------------------------------------------------

(defn- library-id
  "A library id (a DataScript entity id) from a request parameter, or nil for the
  blank the \"—\" option submits."
  [v]
  (some-> (not-empty (str v)) parse-long))

(defn- track-key
  "The EDN track key a checkbox carries. Read with clojure.edn (never `read`), so a
  malformed parameter is an error rather than an evaluation."
  [v]
  (when (not-empty (str v))
    (try (edn/read-string (str v)) (catch Exception _ nil))))

(defn- column
  "A column-browser column keyword (:artist/:album) from a parameter."
  [v]
  (case (str v) "album" :album "artist" :artist nil))

(defn- setting-value
  "Coerce a settings radio's value. Settings are keyword-valued (a theme, a
  sink-only handling), so the parameter names one."
  [v]
  (some-> (not-empty (str v)) keyword))

;; --- handlers ----------------------------------------------------------------

(defn- handlers
  "Every action handler, as `path -> (fn [state-atom deps params])`. Each returns
  the regions its response should carry, or a ring response of its own."
  [{:keys [cache refresher on-quit]}]
  (letfn [(regions [& rs] (vec rs))]
    {"/actions/select-source"
     (fn [state-atom params]
       (actions/select-source! state-atom cache refresher (library-id (:id params)))
       (regions :workspace :status))

     "/actions/select-sink"
     (fn [state-atom params]
       (actions/select-sink! state-atom cache refresher (library-id (:id params)))
       (regions :workspace :status))

     "/actions/toggle-track"
     (fn [state-atom params]
       (when-let [k (track-key (:key params))]
         (actions/toggle-track! state-atom k))
       (regions :table :capacity :controls :status))

     "/actions/filter"
     (fn [state-atom params]
       (actions/set-filter! state-atom (column (:col params)) (not-empty (:value params)))
       (regions :facets :table))

     "/actions/filter-search"
     (fn [state-atom params]
       (let [col (column (:col params))]
         (actions/set-filter-search! state-atom col (str (:q params)))
         (regions (if (= :album col) :albums :artists))))

     "/actions/toggle-facet"
     (fn [state-atom params]
       (actions/toggle-facet! state-atom (column (:col params)) (not-empty (:value params)))
       (regions :table :capacity :controls))

     "/actions/refresh"
     (fn [state-atom _params]
       (actions/refresh! state-atom cache refresher)
       (regions :workspace :status))

     "/actions/preview"
     (fn [state-atom _params]
       (actions/preview! state-atom)
       (regions :controls :status))

     "/actions/sync"
     (fn [state-atom _params]
       (actions/sync! state-atom cache)
       (regions :overlay :controls :status))

     "/actions/confirm/accept"
     (fn [state-atom _params]
       (actions/confirm-accept! state-atom cache)
       (regions :overlay :controls :status))

     "/actions/confirm/cancel"
     (fn [state-atom _params]
       (actions/confirm-cancel! state-atom)
       (regions :overlay))

     "/actions/settings/open"
     (fn [state-atom _params]
       (actions/settings-open! state-atom)
       (regions :overlay))

     "/actions/settings/close"
     (fn [state-atom _params]
       (actions/settings-close! state-atom)
       (regions :overlay))

     "/actions/setting"
     (fn [state-atom params]
       (let [k (setting-value (:key params))
             v (setting-value (:value params))]
         (actions/set-setting! state-atom cache k v)
         (if (= :theme k)
           ;; The palette hangs off <html data-theme>, which no fragment swap can
           ;; reach. A reload is cheap and loses nothing: what the page shows —
           ;; the open settings panel included — all lives in application state.
           (assoc no-content :headers {"HX-Refresh" "true"})
           (regions :overlay :table :capacity :controls))))

     "/actions/activity/open"
     (fn [state-atom _params]
       (actions/activity-open! state-atom)
       (regions :activity))

     "/actions/activity/close"
     (fn [state-atom _params]
       (actions/activity-close! state-atom)
       (regions :activity))

     "/actions/library/new"
     (fn [state-atom params]
       (actions/library-new! state-atom (keyword (str (:device-type params))))
       (regions :overlay))

     "/actions/library/edit"
     (fn [state-atom params]
       (actions/library-edit! state-atom (library-id (:id params)))
       (regions :overlay))

     "/actions/library/delete"
     (fn [state-atom params]
       (actions/library-delete! state-atom cache (library-id (:id params)))
       (regions :overlay :workspace :status))

     "/actions/library/default"
     (fn [state-atom params]
       (actions/library-default! state-atom cache
                                 (if (= "sink" (:role params)) :sink :source)
                                 (library-id (:id params)))
       (regions :overlay :sync-bar))

     "/actions/editor/name"
     (fn [state-atom params]
       (actions/editor-name! state-atom (str (:name params)))
       no-content)

     "/actions/editor/remove-root"
     (fn [state-atom params]
       (actions/editor-remove-root! state-atom (:uri params))
       (regions :overlay))

     "/actions/editor/browse"
     (fn [state-atom _params]
       (actions/editor-browse! state-atom)
       (regions :overlay))

     "/actions/editor/save"
     (fn [state-atom _params]
       (actions/editor-save! state-atom cache refresher)
       (regions :overlay :workspace :status))

     "/actions/editor/cancel"
     (fn [state-atom _params]
       (actions/editor-cancel! state-atom)
       (regions :overlay))

     "/actions/log-dir/browse"
     (fn [state-atom _params]
       (actions/log-dir-browse! state-atom)
       (regions :overlay))

     "/actions/browser/field"
     (fn [state-atom params]
       (actions/browser-field! state-atom (keyword (str (:field params))) (str (:value params)))
       no-content)

     "/actions/browser/connect"
     (fn [state-atom _params]
       (actions/browser-connect! state-atom)
       (regions :browser))

     "/actions/browser/device"
     (fn [state-atom params]
       (actions/browser-device! state-atom {:name (:name params) :uri (:uri params)})
       (regions :browser))

     "/actions/browser/enter"
     (fn [state-atom params]
       (actions/browser-enter! state-atom {:name (:label params) :uri (:uri params)})
       (regions :browser))

     "/actions/browser/crumb"
     (fn [state-atom params]
       (actions/browser-crumb! state-atom (or (some-> (:idx params) str parse-long) 0))
       (regions :browser))

     "/actions/browser/places"
     (fn [state-atom _params]
       (actions/browser-places! state-atom)
       (regions :browser))

     "/actions/browser/select"
     (fn [state-atom _params]
       (actions/browser-select! state-atom cache)
       (regions :overlay))

     "/actions/browser/cancel"
     (fn [state-atom _params]
       (actions/browser-cancel! state-atom)
       (regions :overlay))

     "/actions/quit"
     (fn [_state-atom _params]
       (on-quit)
       (html (views/stopped-page)))}))

(def ^:private resets-page
  "Actions after which the table must go back to its first page. They change which
  rows exist at all, so the page number the control carried is about a list that
  is gone — and landing on page 7 of a freshly narrowed table reads as an empty
  one. The sort is kept: that is a preference, not a position."
  #{"/actions/filter" "/actions/toggle-facet"})

(defn- action-handler
  "One Ring handler over every action route, dispatching on the request's path.
  Each action returns either regions to render or a response of its own."
  [state-atom deps]
  (let [table (handlers deps)]
    (fn [{:keys [uri params]}]
      (if-let [f (table uri)]
        (let [result (f state-atom params)
              view   (cond-> (view-of params)
                       (resets-page uri) (assoc :page 0))]
          (if (vector? result)
            (render-regions @state-atom view result)
            result))
        (resp/not-found "no such action")))))

(defn- fragment-handler
  "GET /fragments/<region>: the region's markup, or 204 when the digest the client
  sent is still current."
  [state-atom]
  (fn [{:keys [params path-params]}]
    (if-let [region (fragments/region (:region path-params))]
      (let [state @state-atom
            view  (view-of params)]
        (if (fragments/unchanged? state view region (:d params))
          no-content
          (html (fragments/render state view region))))
      (resp/not-found "no such region"))))

(defn- page-handler [state-atom]
  (fn [{:keys [params]}]
    (-> (resp/response
         (str "<!doctype html>"
              (h/html (views/page @state-atom (view-of params)
                                  {:htmx-src      (assets/htmx-src)
                                   :htmx-sse-src  (assets/htmx-sse-src)}))))
        (resp/content-type "text/html; charset=utf-8"))))

;; --- router ------------------------------------------------------------------

(defn handler
  "The application's Ring handler. `deps` carries the cache component, the
  background refresher, the event hub the page subscribes to, and `:on-quit`,
  called when the user quits."
  [state-atom {:keys [hub] :as deps}]
  (let [actions (action-handler state-atom deps)]
    (ring/ring-handler
     (ring/router
      [["/" {:get (page-handler state-atom)}]
       ["/fragments/:region" {:get (fragment-handler state-atom)}]
       ["/actions/*path" {:post actions}]
       ["/assets/:name" {:get assets/handler}]
       ;; Long-lived: one open stream per page (see dapr.web.events).
       ["/events" {:get (if hub
                          (events/handler hub)
                          ;; No hub (a test driving the handler directly): say so
                          ;; rather than 404, since the page treats the stream as
                          ;; optional and falls back to its timer either way.
                          (fn [_] {:status 503 :headers {} :body "no event hub"}))}]]
      {:data {:middleware [wrap-params wrap-keyword-params]}})
     (ring/routes
      ;; The stylesheet (and anything else under resources/public).
      (ring/create-resource-handler {:path "/" :root "public"})
      (ring/create-default-handler)))))
