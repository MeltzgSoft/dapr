(ns dapr.ui.html
  "Small helpers shared by every view: URL building and the htmx attribute
  patterns the fragments are wired with.

  Everything a control needs to say is carried in its URL — the track key it
  toggles, the table's sort and page, the digest a poll is checking against — so
  no view state has to be kept in the browser and no request body has to be
  hand-assembled in JavaScript. Pure string building; no side effects."
  (:require [clojure.string :as str]
            [dapr.state :as state]
            [ring.util.codec :as codec]))

(defn qs
  "`params` as a query string prefixed with `?`, or \"\" when nothing is left after
  dropping nil values. Keys may be keywords or strings; values are stringified and
  percent-encoded by ring.util.codec — the same library whose params middleware
  decodes them on the way back in.

  Values are stringified *before* encoding on purpose: form-encode expands a
  collection value into one pair per element, which would scatter a track key (a
  vector) across several parameters rather than carrying it whole."
  [params]
  (let [encoded (codec/form-encode (into {} (for [[k v] params
                                                  :when (some? v)]
                                              [(name k) (str v)])))]
    (if (str/blank? encoded) "" (str "?" encoded))))

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

(defn poll
  "htmx attributes making an element replace itself from its fragment endpoint —
  on a server notification for its region, and failing that on the fallback timer
  configured for this system (see dapr.state/fallback-seconds). The re-fetched
  element carries the fresh digest in its own URL, so the cycle keeps itself
  current with nothing stored client-side."
  [state region digest & [params]]
  {:hx-get     (fragment-url region digest params)
   :hx-trigger (format "sse:region-%s, every %ds"
                       (name region) (state/fallback-seconds state))
   :hx-swap    "outerHTML"})

(defn classes
  "Space-joined class attribute from `xs`, dropping nils and falses — so classes
  can be written as `(classes \"job\" (when failed? \"failed\"))`."
  [& xs]
  (str/join " " (remove (fn [x] (or (nil? x) (false? x) (= "" x))) xs)))
