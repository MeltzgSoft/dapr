(ns dapr.web.assets
  "Static scripts served from the classpath.

  htmx and its SSE extension are not vendored into this repository: they arrive
  as `org.webjars.npm` dependencies, so they are resolved, cached and
  version-pinned like any other library, and `clojure -T:build uber` copies them
  into the jar along with everything else on the classpath.

  Only the *path* inside a WebJar carries its version, so each is read back from
  that WebJar's own pom.properties rather than repeated here and left to drift."
  (:require [clojure.java.io :as io]
            [ring.util.response :as resp])
  (:import (java.util Properties)))

(def ^:private script-sources
  "The scripts the page loads: url name -> which WebJar it comes from and where
  inside that WebJar's versioned directory it sits.

  The SSE extension comes from its own package, *not* from htmx.org's bundled
  `dist/ext/sse.js` — that copy is the htmx 1.x extension, which under htmx 2
  opens the event stream but never fires the `sse:` triggers, leaving every
  region to its fallback timer."
  {"htmx.js"     {:webjar "htmx.org" :path "dist/htmx.min.js"}
   "htmx-sse.js" {:webjar "htmx-ext-sse" :path "dist/sse.min.js"}
   ;; The -ext build: idiomorph plus the htmx extension that registers `morph`
   ;; as a swap style. The bare idiomorph.min.js is the library alone and
   ;; registers nothing, which would leave every morph swap silently doing
   ;; nothing at all.
   "idiomorph.js" {:webjar "idiomorph" :path "dist/idiomorph-ext.min.js"}})

(defn- webjar-version
  "Version of WebJar `artifact` on the classpath, or nil when it isn't there."
  [artifact]
  (when-let [url (io/resource (format "META-INF/maven/org.webjars.npm/%s/pom.properties" artifact))]
    (with-open [in (io/input-stream url)]
      (.getProperty (doto (Properties.) (.load in)) "version"))))

(defn- resolve-script
  [url-name {:keys [webjar path]}]
  (let [version (or (webjar-version webjar)
                    (throw (ex-info (str webjar " is missing from the classpath — check deps.edn")
                                    {:webjar webjar :asset url-name})))
        resource (format "META-INF/resources/webjars/%s/%s/%s" webjar version path)]
    (when-not (io/resource resource)
      (throw (ex-info "the WebJar is on the classpath but is missing its script"
                      {:webjar webjar :version version :resource resource})))
    [url-name {:version  version
               :resource resource
               :src      (str "/assets/" url-name "?v=" version)}]))

(def assets
  "url name -> {:version :resource :src}, resolved once. Each :src carries its
  version, so an upgraded jar can never be served a cached copy of the old
  script."
  (delay (into {} (map (fn [[url-name source]] (resolve-script url-name source))) script-sources)))

(defn htmx-src
  "URL the page should load htmx from."
  []
  (get-in @assets ["htmx.js" :src]))

(defn htmx-sse-src
  "URL the page should load the htmx SSE extension from."
  []
  (get-in @assets ["htmx-sse.js" :src]))

(defn idiomorph-src
  "URL of the idiomorph htmx extension, versioned for caching."
  []
  (get-in @assets ["idiomorph.js" :src]))

(defn handler
  "Serve one of the WebJar scripts. Immutable: the URL carries the version, so a
  browser may hold it as long as it likes."
  [{:keys [path-params]}]
  (if-let [{:keys [resource]} (@assets (:name path-params))]
    (-> (resp/resource-response resource)
        (resp/content-type "application/javascript")
        (resp/header "Cache-Control" "public, max-age=31536000, immutable"))
    (resp/not-found "no such asset")))
