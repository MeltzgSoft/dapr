(ns dapr.device.views
  "Device-specific view extension points plus the shared folder-browser markup.

  Device namespaces install `browser-content` methods keyed by
  [device-type phase]; everything a browser does once a device is established
  (breadcrumbs, listing, 'use this folder') is generic and lives here."
  (:require [dapr.device.format :as device-format]
            [dapr.ui.html :as html]))

(defmulti library-menu-item
  "Button that starts a new library of `device-type`, for the settings panel's
  New-library row."
  identity)

(defmulti browser-content
  "Markup for the browser body: a device-specific setup phase (pick an MTP device,
  enter an SMB share) or the generic folder browser once a browse root exists."
  (fn [_allowed browser] [(:device/type browser) (:phase browser)]))

(defmethod browser-content :default [_ _]
  [:p.muted "Unsupported browser state"])

(def ^:private browser-target
  "Browser navigation replaces only the browser panel, never the editor around it —
  the library-name field lives there, and re-rendering it under the user's cursor
  would be its own bug."
  {:hx-target "#browser-panel" :hx-swap "outerHTML"})

(defn menu-item
  "The default New-library button: every device type creates a library the same
  way, differing only in the label its scheme gives it."
  [device-type]
  [:button.btn
   (merge {:hx-post   (html/url "/actions/library/new" {:device-type (name device-type)})
           :hx-target "#overlay"
           :hx-swap   "outerHTML"})
   (device-format/library-menu-label device-type)])

(defn browser-crumbs
  "Generic breadcrumb trail: a device root button followed by descended folders."
  [browser]
  [:nav.crumbs
   [:button.btn (merge browser-target {:hx-post "/actions/browser/places"})
    (device-format/browser-root-label browser)]
   (map-indexed
    (fn [i c]
      [:button.btn (merge browser-target
                          {:hx-post (html/url "/actions/browser/crumb" {:idx i})})
       (str "▸ " (:label c))])
    (:crumbs browser))])

(defn browser-entry-row
  "One child folder: entering it is a POST, so the browser's cwd stays server-side
  and a reload lands where the user left off."
  [entry]
  [:button.btn
   (merge browser-target
          {:hx-post (html/url "/actions/browser/enter"
                              {:uri (:uri entry) :label (or (:label entry) (:name entry))})})
   (str "📁  " (or (:label entry) (:name entry)))])

(defn folder-browser
  "Generic directory browser once a device-specific setup phase has established
  the browse root."
  [{:keys [cwd entries loading?]  :as browser}]
  [:div.stack
   (browser-crumbs browser)
   [:div.entry-list
    (cond
      loading?      [:p.muted "Loading…"]
      (seq entries) (map browser-entry-row entries)
      :else         [:p.muted "(no sub-folders here)"])]
   [:p.muted (device-format/browser-current-location browser)]
   [:button.primary
    {:hx-post   "/actions/browser/select"
     ;; Choosing a folder ends the browse, so the whole overlay is re-rendered:
     ;; what replaces the browser (the library editor, or the settings body when
     ;; the browse was for the log directory) is not the browser's to know.
     :hx-target "#overlay"
     :hx-swap   "outerHTML"
     :disabled  (or (nil? cwd) (not (device-format/selectable-root? cwd)))}
    "Use this folder"]])

(defn browser-polls?
  "True when the browser panel should keep re-fetching itself. Device listings and
  directory listings are loaded on a background thread, so the panel has to come
  back for the result; a connection *form* must not, since re-rendering it under
  the user would wipe what they are typing."
  [browser]
  (and (some? browser) (not= :connect (:phase browser))))
