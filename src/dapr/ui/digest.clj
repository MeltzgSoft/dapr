(ns dapr.ui.digest
  "Per-region change detection for the HTMX fragments.

  Each pollable region of the page carries, in its own poll URL, a digest of
  exactly the state it renders from. On the next poll the server digests that
  state again: unchanged means 204 and no swap, changed means a fresh fragment
  (see dapr.web.fragments). That is what lets a background scan's findings reach
  an open page while the page itself stores nothing — and why the digests are
  narrow: a region that digested the whole state map would re-render every time
  any unrelated counter moved.

  Pure: a digest is a function of the state map and the table's view parameters."
  (:require [dapr.ui.format :as fmt]))

(def ^:private region-inputs
  "region -> (fn [state view]) returning the values that region renders from. Only
  these decide whether a poll re-renders, so anything a region shows must appear
  here — and nothing it doesn't."
  {:status   (fn [s _] (fmt/tasks s))
   :jobs     (fn [s _] (fmt/tasks s))
   :log      (fn [s _] (:log-appends s))
   ;; Deliberately NOT the source/sink selection. Those change only by the user
   ;; picking one, and that response already re-renders the workspace this bar
   ;; sits in — pushing as well made the bar replace itself a moment later, which
   ;; is a `<select>` being torn out from under whoever is using it. What must
   ;; still be pushed is what changes on its own: a library appearing, or a
   ;; device turning out to be unreachable.
   :sync-bar (fn [s _] [(mapv (juxt :id :name) (:libraries s))
                        (:library-availability s)])
   :capacity (fn [s _] [(:capacity s) (fmt/library-name (:libraries s) (:sink-id s))])
   :artists  (fn [s _] [(:catalog-version s)
                        (get-in s [:filter-search :artist]) (get-in s [:filter :artist])])
   :albums   (fn [s _] [(:catalog-version s)
                        (get-in s [:filter-search :album]) (:filter s)])
   ;; The selection is hashed rather than carried whole: it can hold a key per
   ;; track, and a persistent set caches its own hash.
   :table    (fn [s v] [(:catalog-version s) (hash (:selected s)) (:filter s) (:capacity s)
                        (get-in s [:settings :sink-only-handling]) v])
   :controls (fn [s _] [(:status s) (get-in s [:plan :summary])
                        (:source-id s) (:sink-id s) (:error s)])
   :browser  (fn [s _] (:browser s))})

(defn regions
  "The regions that can be polled."
  []
  (set (keys region-inputs)))

(defn digest
  "Digest of what `region` renders from, as a string for a URL. nil for a region
  with no digest function, which the fragment endpoint treats as always-changed."
  [state view region]
  (when-let [f (region-inputs region)]
    (str (hash (f state view)))))
