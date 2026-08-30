(ns dapr.web.routes-test
  "The HTTP surface, exercised through the Ring handler rather than a live socket:
  the routing, parameter coercion, digest-driven 204s and out-of-band fragment
  assembly are all in the handler, and none of them need a port to be open."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dapr.db.cache :as cache]
            [dapr.state :as state]
            [dapr.ui.digest :as digest]
            [dapr.ui.views :as views]
            [dapr.web.routes :as routes])
  (:import (java.net URLEncoder)
           (java.nio.charset StandardCharsets)))

;; --- fixtures ----------------------------------------------------------------

(defn- track [artist album title size]
  (let [rel (str artist "/" album "/" title ".mp3")
        k   [artist album title size rel]]
    [k {:key k :artist artist :album album :title title :size size :rel rel
        :disc-number 1 :track-number 1}]))

(def ^:private source-catalog
  (into {} [(track "Alice" "One" "Song A" 10)
            (track "Bob" "Two" "Song B" 20)]))

(def ^:private alice-key (first (first [(track "Alice" "One" "Song A" 10)])))

(def ^:private libraries
  [{:id 1 :name "Laptop" :roots ["file:///music"] :default-source? true :default-sink? false}
   {:id 2 :name "Phone" :roots ["mtp://dev/"] :default-source? false :default-sink? true}])

(defn- test-state []
  (-> state/initial-state
      (assoc :libraries libraries :source-id 1 :sink-id 2
             :library-availability {1 true 2 false})
      (state/set-catalogs source-catalog {} 1000000)))

(defn- temp-cache
  "A real DataScript cache under a temp file, for the handlers that persist."
  []
  (let [f (doto (java.io.File/createTempFile "dapr-routes" ".edn") (.delete) (.deleteOnExit))]
    {:conn (cache/load! f) :path f}))

(defn- app
  ([] (app (atom (test-state)) {}))
  ([state-atom deps]
   (routes/handler state-atom (merge {:cache (temp-cache) :refresher nil :on-quit (fn [])}
                                     deps))))

(defn- enc [v] (URLEncoder/encode (str v) StandardCharsets/UTF_8))

(defn- GET [handler uri & [query]]
  (handler {:request-method :get :uri uri :query-string query}))

(defn- POST [handler uri & [query]]
  (handler {:request-method :post :uri uri :query-string query}))

;; --- the page ----------------------------------------------------------------

(deftest page-test
  (let [{:keys [status body headers]} (GET (app) "/")]
    (is (= 200 status))
    (is (str/starts-with? body "<!doctype html>"))
    (is (str/includes? (get headers "Content-Type") "text/html"))
    (testing "every swap target the fragments address is present in the document"
      (doseq [id ["workspace" "sync-bar" "capacity" "facets" "artists" "albums"
                  "track-table" "controls" "status-bar" "overlay" "activity"]]
        (is (str/includes? body (str "id=\"" id "\"")) id)))
    (testing "htmx and its SSE extension are loaded from the app, not a CDN"
      (is (str/includes? body "src=\"/assets/htmx.js"))
      (is (str/includes? body "src=\"/assets/htmx-sse.js"))
      (is (not (str/includes? body "//unpkg.com"))))
    (testing "the page opens one event stream for its regions to listen on"
      (is (str/includes? body "hx-sse:connect=\"/events\"")))
    (testing "a region re-fetches on its own notification, with the timer only
              as a fallback"
      (is (re-find #"hx-trigger=\"region-table from:body, every \d+s\"" body))))

  (testing "an unreachable library can be seen but not chosen"
    (is (re-find #"<option disabled=\"disabled\"[^>]*value=\"2\"" (:body (GET (app) "/"))))))

(deftest static-assets-test
  (let [handler (app)]
    (testing "the stylesheet is served from the classpath"
      (is (= 200 (:status (GET handler "/dapr.css")))))
    (testing "every script the page loads is served out of the WebJar, cached by
              its versioned URL"
      (doseq [asset ["htmx.js" "htmx-sse.js"]]
        (let [{:keys [status headers]} (GET handler (str "/assets/" asset))]
          (is (= 200 status) asset)
          (is (str/includes? (get headers "Content-Type") "javascript"))
          (is (str/includes? (get headers "Cache-Control") "immutable")))))
    (testing "an asset that is not one of ours is a 404, not a classpath read"
      (is (= 404 (:status (GET handler "/assets/config.edn")))))))

(deftest events-route-test
  (testing "with no hub — a handler driven directly, as these tests do — the
            stream says it is unavailable rather than 404ing, since the page
            treats it as optional and falls back to its timer either way"
    (is (= 503 (:status (GET (app) "/events"))))))

;; --- fragments ---------------------------------------------------------------

(deftest fragment-polling-test
  (let [state-atom (atom (test-state))
        handler    (app state-atom {})
        view       {:sort nil :dir :asc :page 0}]
    (testing "a poll carrying the digest it already shows gets nothing back"
      (is (= 204 (:status (GET handler "/fragments/table"
                            (str "d=" (digest/digest @state-atom view :table)))))))
    (testing "once the state moves, the same poll gets the new markup"
      (swap! state-atom state/toggle-track alice-key)
      (let [{:keys [status body]} (GET handler "/fragments/table"
                                    (str "d=" (digest/digest (test-state) view :table)))]
        (is (= 200 status))
        (is (str/includes? body "id=\"track-table\""))))
    (testing "a request with no digest at all always renders — that is a control's
              link, not a poll"
      (is (= 200 (:status (GET handler "/fragments/table")))))
    (testing "an unknown region is a 404, not an arbitrary fragment"
      (is (= 404 (:status (GET handler "/fragments/nonsense")))))))

(deftest table-view-params-test
  (let [handler (app)]
    (testing "sorting is driven entirely by the URL"
      (let [body (:body (GET handler "/fragments/table" "sort=title&dir=desc"))]
        (is (< (str/index-of body "Song B") (str/index-of body "Song A")))))
    (testing "an unrecognized sort field is ignored rather than sorting by nothing"
      (let [body (:body (GET handler "/fragments/table" "sort=drop-tables&dir=asc"))]
        (is (< (str/index-of body "Song A") (str/index-of body "Song B")))))))

;; --- actions -----------------------------------------------------------------

(deftest toggle-track-test
  (let [state-atom (atom (test-state))
        handler    (app state-atom {})
        {:keys [status body]} (POST handler "/actions/toggle-track"
                                (str "key=" (enc (pr-str alice-key))))]
    (is (= 200 status))
    (is (contains? (:selected @state-atom) alice-key))
    (testing "the response carries the table plus the regions the tick changed,
              the extras marked as out-of-band swaps"
      (is (str/includes? body "id=\"track-table\""))
      (is (= 3 (count (re-seq #"hx-swap-oob=\"true\"" body)))))
    (testing "and toggling again clears it"
      (POST handler "/actions/toggle-track" (str "key=" (enc (pr-str alice-key))))
      (is (not (contains? (:selected @state-atom) alice-key))))))

(deftest filter-test
  (let [state-atom (atom (test-state))
        handler    (app state-atom {})]
    (POST handler "/actions/filter" "col=artist&value=Alice")
    (is (= "Alice" (get-in @state-atom [:filter :artist])))
    (testing "the table narrows, while the facet list still offers the others"
      (let [table (:body (GET handler "/fragments/table"))]
        (is (str/includes? table "Song A"))
        (is (not (str/includes? table "Song B"))))
      (is (str/includes? (:body (GET handler "/fragments/artists")) "Bob")))
    (testing "picking All clears it again"
      (POST handler "/actions/filter" "col=artist")
      (is (nil? (get-in @state-atom [:filter :artist]))))))

(deftest paging-test
  (let [many       (into {} (for [i (range (* 2 views/page-size))]
                              (track "Alice" "One" (format "Song %04d" i) 10)))
        state-atom (atom (-> (test-state) (state/set-catalogs many {} 100000000)))
        handler    (app state-atom {})]
    (testing "a long table is paged rather than poured into the page whole"
      (let [body (:body (GET handler "/fragments/table"))]
        (is (str/includes? body (format "Tracks (%d)" (* 2 views/page-size))))
        (is (= views/page-size (count (re-seq #"type=\"checkbox\"" body))))
        (is (str/includes? body "Song 0000"))
        (is (not (str/includes? body (format "Song %04d" views/page-size))))))
    (testing "the page number is a URL parameter, held nowhere on the server"
      (let [body (:body (GET handler "/fragments/table" "page=1"))]
        (is (str/includes? body (format "Song %04d" views/page-size)))
        (is (not (str/includes? body "Song 0000")))))
    (testing "narrowing the table returns to its first page — page 1 of a list
              that no longer exists would just look empty"
      (let [body (:body (POST handler "/actions/filter" "col=album&value=One&page=1"))]
        (is (str/includes? body "Song 0000"))))))

(deftest toggle-facet-test
  (let [state-atom (atom (test-state))
        handler    (app state-atom {})]
    (POST handler "/actions/toggle-facet" "col=artist&value=Alice")
    (is (= #{alice-key} (:selected @state-atom))
        "checks every track under the facet without narrowing the view")
    (is (nil? (get-in @state-atom [:filter :artist])))
    (POST handler "/actions/toggle-facet" "col=artist&value=Alice")
    (is (= #{} (:selected @state-atom)) "and unchecks them")))

(deftest settings-test
  (let [state-atom (atom (test-state))
        handler    (app state-atom {})]
    (testing "opening and closing the settings panel is server state, so a reload
              lands back on it"
      (POST handler "/actions/settings/open")
      (is (true? (:settings-open? @state-atom)))
      (is (str/includes? (:body (GET handler "/")) "Libraries"))
      (POST handler "/actions/settings/close")
      (is (false? (:settings-open? @state-atom))))

    (testing "a theme change asks for a reload — the palette hangs off <html>,
              which no fragment swap can reach"
      (let [{:keys [status headers]} (POST handler "/actions/setting" "key=theme&value=dark")]
        (is (= 204 status))
        (is (= "true" (get headers "HX-Refresh"))))
      (is (= :dark (get-in @state-atom [:settings :theme])))
      (is (str/includes? (:body (GET handler "/")) "data-theme=\"dark\"")))

    (testing "any other setting just re-renders the regions it affects"
      (let [{:keys [status body]} (POST handler "/actions/setting"
                                    "key=sink-only-handling&value=delete")]
        (is (= 200 status))
        (is (str/includes? body "id=\"overlay\""))
        (is (= :delete (get-in @state-atom [:settings :sink-only-handling])))))))

(deftest activity-test
  (let [state-atom (atom (assoc (test-state) :log ["one" "two"]))
        handler    (app state-atom {})]
    (is (not (str/includes? (:body (GET handler "/fragments/activity")) "drawer")))
    (POST handler "/actions/activity/open")
    (let [body (:body (GET handler "/fragments/activity"))]
      (is (str/includes? body "drawer"))
      (testing "the log reads newest-first, which is what pins the box to the tail"
        (is (< (str/index-of body "two") (str/index-of body "one")))))
    (POST handler "/actions/activity/close")
    (is (false? (:log-open? @state-atom)))))

(deftest library-editor-test
  (let [state-atom (atom (test-state))
        handler    (app state-atom {})]
    (POST handler "/actions/library/new" "device-type=file")
    (is (= {:id nil :name "" :roots [] :device/type :file} (:editor @state-atom)))
    (POST handler "/actions/editor/name" "name=New%20library")
    (is (= "New library" (get-in @state-atom [:editor :name])))
    (POST handler "/actions/editor/cancel")
    (is (nil? (:editor @state-atom)))))

(deftest quit-test
  (let [quit? (atom false)
        handler (app (atom (test-state)) {:on-quit (fn [] (reset! quit? true))})
        {:keys [status body]} (POST handler "/actions/quit")]
    (is (= 200 status))
    (is (true? @quit?))
    (is (str/includes? body "Dapr has stopped"))))

(deftest unknown-action-test
  (is (= 404 (:status (POST (app) "/actions/no-such-thing")))))
