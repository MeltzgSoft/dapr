(ns dapr.ui.html
  "Small helpers shared by every view: URL building and the htmx attribute
  patterns the fragments are wired with.

  Everything a control needs to say is carried in its URL — the track key it
  toggles, the table's sort and page, the digest a poll is checking against — so
  no view state has to be kept in the browser and no request body has to be
  hand-assembled in JavaScript. Pure string building; no side effects."
  (:require [clojure.string :as str])
  (:import (java.net URLEncoder)
           (java.nio.charset StandardCharsets)))

(defn- encode [v]
  (URLEncoder/encode (str v) StandardCharsets/UTF_8))

(defn qs
  "`params` as a query string prefixed with `?`, or \"\" when nothing is left after
  dropping nil values. Keys may be keywords or strings; values are stringified and
  percent-encoded, so an EDN track key round-trips intact."
  [params]
  (let [pairs (for [[k v] params
                    :when (some? v)]
                (str (encode (name k)) "=" (encode v)))]
    (if (seq pairs) (str "?" (str/join "&" pairs)) "")))

(defn url
  "`path` with `params` appended as a query string."
  ([path] path)
  ([path params] (str path (qs params))))

(defn fragment-url
  "Re-fetch URL for `region`, carrying the digest the client currently shows plus
  any view parameters the fragment renders from. The server answers 204 (which
  htmx leaves alone) when its own digest still matches, so a notification for a
  region this client is not actually showing differently costs one empty
  response rather than a re-render."
  [region digest params]
  (url (str "/fragments/" (name region)) (assoc params :d digest)))

(def fallback-seconds
  "How often a region re-fetches itself *without* being told to. The server pushes
  a notification the moment a region's data moves (see dapr.web.events), so this
  timer is not the mechanism — it is the safety net for a stream that never
  connected or quietly died, and the reason such a page goes slightly stale
  rather than frozen. Slow enough to be nearly free, quick enough that a broken
  stream is an annoyance rather than a bug report."
  15)

(defn poll
  "htmx attributes making an element replace itself from its fragment endpoint —
  on a server notification for its region, and failing that on a slow timer (see
  fallback-seconds). The re-fetched element carries the fresh digest in its own
  URL, so the cycle keeps itself current with nothing stored client-side."
  [region digest & [params]]
  {:hx-get     (fragment-url region digest params)
   :hx-trigger (format "sse:region-%s, every %ds" (name region) fallback-seconds)
   :hx-swap    "outerHTML"})

(defn classes
  "Space-joined class attribute from `xs`, dropping nils and falses — so classes
  can be written as `(classes \"job\" (when failed? \"failed\"))`."
  [& xs]
  (str/join " " (remove (fn [x] (or (nil? x) (false? x) (= "" x))) xs)))
