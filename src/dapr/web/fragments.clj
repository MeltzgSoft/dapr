(ns dapr.web.fragments
  "The page's regions: how each one is rendered, and whether a poll of it has
  anything new to say.

  A region is named by a keyword and rendered by a function of the application
  state and the table's view parameters. `render` produces one; `unchanged?`
  answers the poll question — the client sends the digest it is showing, and a
  matching digest means the server can say 204 and let the page be (see
  dapr.ui.digest)."
  (:require [dapr.ui.digest :as digest]
            [dapr.ui.views :as views]))

(def ^:private renderers
  "region -> (fn [state view]) returning that region's hiccup. Regions with a
  digest (dapr.ui.digest) can also be polled; the rest are only ever pushed back
  as the result of an action."
  {:workspace (fn [s v] (views/workspace s v))
   :table     (fn [s v] (views/track-table s v))
   :sync-bar  (fn [s _] (views/sync-bar s))
   :capacity  (fn [s _] (views/capacity-bar s))
   :controls  (fn [s _] (views/controls s))
   :facets    (fn [s _] (views/facets s))
   :artists   (fn [s _] (views/artist-list s))
   :albums    (fn [s _] (views/album-list s))
   :status    (fn [s _] (views/status-bar s))
   :activity  (fn [s _] (views/activity s))
   :jobs      (fn [s _] (views/jobs-list s))
   :log       (fn [s _] (views/log-lines s))
   :overlay   (fn [s _] (views/overlay s))
   :browser   (fn [s _] (views/browser-panel s))})

(defn region
  "The region keyword named by `s`, or nil when there is no such region — so an
  unknown path 404s instead of rendering something arbitrary."
  [s]
  (let [k (keyword s)]
    (when (contains? renderers k) k)))

(defn render
  "`region`'s hiccup for the current state."
  [state view region]
  (when-let [f (renderers region)]
    (f state view)))

(defn unchanged?
  "True when `client-digest` is exactly what `region` would render from now, so the
  poll needs no answer. A region with no digest function always counts as changed;
  so does a request that carries no digest (a control's link, rather than a poll)."
  [state view region client-digest]
  (boolean (and (seq client-digest)
                (= client-digest (digest/digest state view region)))))
