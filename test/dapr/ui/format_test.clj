(ns dapr.ui.format-test
  (:require [clojure.test :refer [deftest is testing]]
            [dapr.ui.format :as fmt]))

(deftest compare-field-test
  (testing "strings sort case-insensitively"
    (is (neg? (fmt/compare-field "apple" "Banana")))
    (is (pos? (fmt/compare-field "Banana" "apple")))
    (is (= ["apple" "Banana" "cherry"]
           (sort fmt/compare-field ["Banana" "cherry" "apple"]))))
  (testing "case-only differences order deterministically (lower- vs upper-case)"
    (is (not (zero? (fmt/compare-field "abc" "ABC"))))
    (is (= (fmt/compare-field "abc" "ABC")
           (- (fmt/compare-field "ABC" "abc")))))
  (testing "numbers keep numeric order and nil sorts first"
    (is (neg? (fmt/compare-field 2 10)))
    (is (neg? (fmt/compare-field nil "a")))
    (is (zero? (fmt/compare-field nil nil)))))

(deftest human-bytes-test
  (testing "scales by unit"
    (is (= "0 B" (fmt/human-bytes 0)))
    (is (= "512 B" (fmt/human-bytes 512)))
    (is (= "1.0 KB" (fmt/human-bytes 1024)))
    (is (= "1.0 MB" (fmt/human-bytes (* 1024 1024))))
    (is (= "1.00 GB" (fmt/human-bytes (* 1024 1024 1024)))))
  (testing "nil is treated as zero"
    (is (= "0 B" (fmt/human-bytes nil)))))

(deftest duration-mmss-test
  (testing "millis format as m:ss with zero-padded seconds"
    (is (= "3:30" (fmt/duration-mmss 210000)))
    (is (= "0:05" (fmt/duration-mmss 5000)))
    (is (= "0:00" (fmt/duration-mmss 0)))
    (is (= "4:01" (fmt/duration-mmss 241000))))
  (testing "seconds truncate (no rounding) and minutes are uncapped"
    (is (= "0:03" (fmt/duration-mmss 3999)))
    (is (= "72:14" (fmt/duration-mmss (+ (* 72 60 1000) 14000)))))
  (testing "nil (unknown duration) is blank"
    (is (= "" (fmt/duration-mmss nil)))))

(deftest status-text-test
  (testing "maps known statuses"
    (is (= "Idle" (fmt/status-text :idle)))
    (is (= "Syncing…" (fmt/status-text :syncing))))
  (testing "falls back to the raw value"
    (is (= ":weird" (fmt/status-text :weird)))))

(deftest busy?-test
  (testing "true while scanning or syncing"
    (is (true? (fmt/busy? :scanning)))
    (is (true? (fmt/busy? :syncing))))
  (testing "false otherwise"
    (is (false? (fmt/busy? :idle)))
    (is (false? (fmt/busy? :planned)))))

(deftest capacity-test
  (testing "capacity-text renders used / budget"
    (is (= "1.0 KB / 2.0 KB" (fmt/capacity-text {:used 1024 :budget 2048}))))
  (testing "capacity-fraction is used/budget, capped at 1.0"
    (is (= 0.5 (fmt/capacity-fraction {:used 50 :budget 100})))
    (is (= 1.0 (fmt/capacity-fraction {:used 150 :budget 100})))
    (is (= 0.0 (fmt/capacity-fraction {:used 0 :budget 0}))))
  (testing "over-capacity? when used exceeds budget"
    (is (true? (fmt/over-capacity? {:used 101 :budget 100})))
    (is (false? (fmt/over-capacity? {:used 100 :budget 100})))))

(deftest column-browser-facets-test
  (let [cat {["a" 1] {:key ["a" 1] :artist "Alice" :album "One"   :title "x"}
             ["b" 2] {:key ["b" 2] :artist "Alice" :album "Two"   :title "y"}
             ["c" 3] {:key ["c" 3] :artist "Bob"   :album "Three" :title "z"}
             ["d" 4] {:key ["d" 4] :artist nil     :album nil     :title "n"}}]
    (testing "artists are distinct and sorted, with nil omitted"
      (is (= ["Alice" "Bob"] (fmt/artists cat))))
    (testing "albums span the catalog when artist is nil, else scope to the artist"
      (is (= ["One" "Three" "Two"] (fmt/albums cat nil)))
      (is (= ["One" "Two"] (fmt/albums cat "Alice")))
      (is (= ["Three"] (fmt/albums cat "Bob"))))
    (testing "search-filter narrows facet values case-insensitively; blank keeps all"
      (is (= ["Alice" "Bob"] (fmt/search-filter (fmt/artists cat) "")))
      (is (= ["Alice" "Bob"] (fmt/search-filter (fmt/artists cat) "   ")))
      (is (= ["Alice"] (fmt/search-filter (fmt/artists cat) "ali")))
      (is (= ["Bob"] (fmt/search-filter (fmt/artists cat) "B")))
      (is (= [] (fmt/search-filter (fmt/artists cat) "zzz"))))
    (testing "filter-catalog constrains by artist and album; a nil field is unconstrained"
      (is (= 4 (count (fmt/filter-catalog cat {:artist nil :album nil}))))
      (is (= #{["a" 1] ["b" 2]} (set (keys (fmt/filter-catalog cat {:artist "Alice" :album nil})))))
      (is (= #{["a" 1]} (set (keys (fmt/filter-catalog cat {:artist "Alice" :album "One"}))))))))

(deftest plan-summary-text-test
  (testing "renders a populated summary"
    (is (= "Add 2 (2.0 KB) · Delete 1 (1.0 KB) · Skip 3"
           (fmt/plan-summary-text {:add 2 :bytes-added 2048
                                   :delete 1 :bytes-freed 1024 :skip 3 :blocked 0}))))
  (testing "appends a to-source count when present"
    (is (= "Add 0 (0 B) · Delete 0 (0 B) · Skip 0 · To source 2 (1.0 KB)"
           (fmt/plan-summary-text {:add 0 :bytes-added 0 :delete 0 :bytes-freed 0 :skip 0
                                   :add-to-source 2 :bytes-to-source 1024}))))
  (testing "appends a blocked count when present"
    (is (= "Add 0 (0 B) · Delete 0 (0 B) · Skip 0 · Blocked 2"
           (fmt/plan-summary-text {:add 0 :bytes-added 0 :delete 0
                                   :bytes-freed 0 :skip 0 :blocked 2}))))
  (testing "nil summary has a placeholder"
    (is (= "No plan yet." (fmt/plan-summary-text nil)))))

(deftest can-preview?-test
  (testing "true when distinct source and sink chosen and not busy"
    (is (true? (fmt/can-preview? {:source-id "a" :sink-id "b" :status :idle}))))
  (testing "false when a library is missing, identical, or busy"
    (is (false? (fmt/can-preview? {:source-id nil :sink-id "b" :status :idle})))
    (is (false? (fmt/can-preview? {:source-id "a" :sink-id "a" :status :idle})))
    (is (false? (fmt/can-preview? {:source-id "a" :sink-id "b" :status :syncing})))))

(deftest can-sync?-test
  (testing "true when a plan with work is ready"
    (is (true? (fmt/can-sync? {:status :planned :plan {:summary {:add 1 :move 0 :delete 0}}}))))
  (testing "true for an add-to-source-only plan"
    (is (true? (fmt/can-sync? {:status :planned
                               :plan {:summary {:add 0 :move 0 :delete 0 :add-to-source 1}}}))))
  (testing "false when the plan is a no-op"
    (is (false? (fmt/can-sync? {:status :planned :plan {:summary {:add 0 :move 0 :delete 0}}}))))
  (testing "false when not yet planned"
    (is (false? (fmt/can-sync? {:status :idle :plan nil})))))

(deftest active-theme-test
  (testing "explicit :dark/:light win regardless of the OS scheme"
    (is (= :dark (fmt/active-theme :dark :light)))
    (is (= :light (fmt/active-theme :light :dark))))
  (testing ":system (or nil) follows the OS scheme"
    (is (= :dark (fmt/active-theme :system :dark)))
    (is (= :light (fmt/active-theme :system :light)))
    (is (= :dark (fmt/active-theme nil :dark))))
  (testing ":system falls back to :light when the OS scheme is unknown"
    (is (= :light (fmt/active-theme :system nil)))
    (is (= :light (fmt/active-theme nil nil)))))

(deftest name-list-test
  (testing "quotes and joins library names readably"
    (is (= "" (fmt/name-list [])))
    (is (= "'A'" (fmt/name-list ["A"])))
    (is (= "'A' and 'B'" (fmt/name-list ["A" "B"])))
    (is (= "'A', 'B' and 'C'" (fmt/name-list ["A" "B" "C"])))))

(deftest progress-fraction-test
  (testing "fills proportionally, and not past full"
    (is (= 0.25 (fmt/progress-fraction {:done 1 :total 4})))
    (is (= 0.0 (fmt/progress-fraction {:done 0 :total 4})))
    (is (= 1.0 (fmt/progress-fraction {:done 9 :total 4}))))
  (testing "nil until a total is known — a walk learns its total as it descends"
    (is (nil? (fmt/progress-fraction {:done 3 :total 0})))
    (is (nil? (fmt/progress-fraction {:done 3})))
    (is (nil? (fmt/progress-fraction nil)))))

(def ^:private task-libs
  [{:id 1 :name "Phone"} {:id 2 :name "NAS"} {:id 3 :name "SD card"}])

(defn- task-lines
  "The status bar's rows as [label detail] pairs, for terse assertions."
  [state]
  (mapv (juxt :label :detail) (fmt/tasks (assoc state :libraries task-libs))))

(deftest tasks-test
  (testing "nothing running, no rows — the bar disappears rather than saying 'Idle'"
    (is (= [] (task-lines {:status :idle})))
    (is (= [] (task-lines {:status :planned})) "a ready plan is reported by its summary")
    (is (= [] (task-lines {:status :done})) "a finished op is reported by the log")
    (is (= [] (task-lines {:status :idle :refresh {:status {1 :complete 2 :complete}}}))
        "a library that is up to date is not a running job"))

  (testing "a running foreground op shows its counts and fills its bar"
    (let [[row] (fmt/tasks {:status :syncing :progress {:done 3 :total 12}})]
      (is (= "Syncing… 3 / 12" (:detail row)))
      (is (= 0.25 (:progress row)))))

  (testing "a failed foreground op keeps its row, with the reason nothing else shows"
    (is (= [["Status" "Sync failed: device gone"]]
           (task-lines {:status :error :error "Sync failed: device gone"})))
    (is (true? (:error? (first (fmt/tasks {:status :error :error "boom"})))))
    (testing "and falls back to the bare status when there is no message"
      (is (= [["Status" "Error"]] (task-lines {:status :error})))))

  (testing "a row each for what is running or stuck, most urgent first; the merely
            queued are only counted"
    (is (= [["Phone" "boom"]
            ["NAS" "Scanning… 30 / 120"]
            ["Queued" "1 library"]]
           (task-lines {:status :idle
                        :refresh {:status   {1 :error 2 :scanning 3 :pending}
                                  :errors   {1 "boom"}
                                  :progress {2 {:done 30 :total 120}}}}))))

  (testing "a paused library keeps the counts it had reached"
    (is (= [["NAS" "Paused 30 / 120"]]
           (task-lines {:status :idle
                        :refresh {:status {2 :paused} :progress {2 {:done 30 :total 120}}}}))))

  (testing "a bar is drawn only where a fraction means something"
    (let [progress-of (fn [state] (mapv :progress (fmt/tasks (assoc state :libraries task-libs))))]
      (is (= [nil] (progress-of {:refresh {:status {2 :scanning} :progress {2 {:done 7 :total 0}}}}))
          "a walk that has not yet listed anything has no total to fill toward")
      (is (= [nil] (progress-of {:refresh {:status {1 :error} :progress {1 {:done 7 :total 9}}}}))
          "how far a failed walk got is not progress toward anything")
      (is (= [nil] (progress-of {:refresh {:status {1 :pending}}})) "nor is waiting")))

  (testing "a failure carries its reason, and is flagged for the UI to colour"
    (let [[row] (fmt/tasks {:status  :idle
                            :libraries task-libs
                            :refresh {:status {1 :error} :errors {1 "device gone"}}})]
      (is (= "device gone" (:detail row)))
      (is (true? (:error? row)))))

  (testing "a queue of libraries is one row, however long it is"
    (is (= [["Queued" "8 libraries"]]
           (task-lines {:status :idle :refresh {:status (zipmap (range 1 9) (repeat :pending))}}))))

  (testing "rows past the cap are counted too, so the bar can't eat the window"
    (let [rows (fmt/tasks {:status    :idle
                           :libraries task-libs
                           :refresh   {:status (zipmap (range 1 9) (repeat :paused))}})]
      (is (= 5 (count rows)) "four libraries and the count")
      (is (= "4 libraries" (:detail (last rows))))))

  (testing "rows have stable identities, so cljfx can diff them"
    (is (= [:foreground [:refresh 2] :queued]
           (mapv :id (fmt/tasks {:status :syncing :libraries task-libs
                                 :refresh {:status {2 :scanning 3 :pending}}}))))))

(deftest status-summary-test
  (let [summary (fn [state] (fmt/status-summary (assoc state :libraries task-libs)))]
    (testing "nothing running, no summary — the strip isn't drawn"
      (is (nil? (summary {:status :idle})))
      (is (nil? (summary {:status :done :refresh {:status {1 :complete}}}))))

    (testing "one job speaks for itself"
      (is (= {:text "Syncing… 3 / 12" :running? true :error? false}
             (summary {:status :syncing :progress {:done 3 :total 12}})))
      (is (= {:text "NAS — Scanning… 30 / 120" :running? true :error? false}
             (summary {:refresh {:status {2 :scanning} :progress {2 {:done 30 :total 120}}}}))))

    (testing "the rest are counted, leading with the same row the sidebar leads with"
      (is (= "Phone — boom · 2 more"
             (:text (summary {:status  :syncing
                              :refresh {:status {1 :error 2 :scanning} :errors {1 "boom"}}})))))

    (testing "the spinner turns only while something is actually moving"
      (is (false? (:running? (summary {:refresh {:status {1 :error} :errors {1 "boom"}}})))
          "a failed scan is not work in progress")
      (is (false? (:running? (summary {:refresh {:status {1 :pending 2 :paused}}})))
          "nor is a queue nobody is serving")
      (is (true? (:running? (summary {:refresh {:status {1 :pending 2 :scanning}}})))))

    (testing "a failure anywhere colours the summary, even behind a running job"
      (is (true? (:error? (summary {:status  :syncing
                                    :refresh {:status {1 :error} :errors {1 "boom"}}}))))
      (is (false? (:error? (summary {:status :syncing})))))))

(deftest library-unavailable?-test
  (testing "true only when probed and explicitly unavailable"
    (is (true? (fmt/library-unavailable? {1 false} 1)))
    (is (false? (fmt/library-unavailable? {1 true} 1))))
  (testing "unprobed libraries (absent from the map) are treated as available"
    (is (false? (fmt/library-unavailable? {} 1)))
    (is (false? (fmt/library-unavailable? nil 1)))))
;; --- track table ------------------------------------------------------------

(defn- track [artist album title size]
  (let [rel (str artist "/" album "/" title ".mp3")]
    {:key [artist album title size rel] :artist artist :album album :title title
     :size size :rel rel :disc-number 1 :track-number 1}))

(def ^:private a1 (track "A" "One" "x" 10))
(def ^:private b1 (track "B" "Two" "y" 20))

(defn- catalog [& tracks] (into {} (map (juxt :key identity)) tracks))

(defn- rows-of [state]
  (fmt/track-rows (merge {:capacity {:free 1000} :settings {}} state)))

(deftest track-rows-test
  (testing "rows the union of both catalogs, flagging what is not on the source"
    (let [rows (rows-of {:source-catalog (catalog a1)
                         :sink-catalog   (catalog a1 b1)
                         :selected       #{(:key a1)}})
          by-key (into {} (map (juxt :key identity)) rows)]
      (is (= 2 (count rows)))
      (is (true? (:in-source? (by-key (:key a1)))))
      (is (false? (:in-source? (by-key (:key b1)))))))

  (testing "a sink-only track is locked on under :keep, and merely selectable under :delete"
    (let [locked (first (filter #(= (:key b1) (:key %))
                                (rows-of {:source-catalog (catalog a1)
                                          :sink-catalog   (catalog b1)
                                          :selected       #{}
                                          :settings       {:sink-only-handling :keep}})))
          free   (first (filter #(= (:key b1) (:key %))
                                (rows-of {:source-catalog (catalog a1)
                                          :sink-catalog   (catalog b1)
                                          :selected       #{}
                                          :settings       {:sink-only-handling :delete}})))]
      (is (and (:on? locked) (:disabled? locked)))
      (is (and (not (:on? free)) (not (:disabled? free))))))

  (testing "a track that would not fit the sink is disabled rather than merely refused"
    (let [[row] (rows-of {:source-catalog (catalog a1)
                          :sink-catalog   {}
                          :selected       #{}
                          :capacity       {:free 5}})]
      (is (true? (:disabled? row)))))

  (testing "only tracks matching the column-browser filter are rowed"
    (is (= [(:key b1)]
           (mapv :key (rows-of {:source-catalog (catalog a1 b1)
                                :selected       #{}
                                :filter         {:artist "B"}}))))))

(deftest sort-rows-test
  (let [rows [{:key ["" "" "" 0 "c"] :title "b" :disc-number 1 :track-number 2 :album "z" :artist "z"}
              {:key ["" "" "" 0 "a"] :title "C" :disc-number 1 :track-number 1 :album "z" :artist "z"}
              {:key ["" "" "" 0 "b"] :title "a" :disc-number 2 :track-number 1 :album "z" :artist "z"}]]
    (testing "with no column, disc then track order"
      (is (= ["C" "b" "a"] (mapv :title (fmt/sort-rows rows nil :asc)))))
    (testing "a column sorts case-insensitively by that field alone"
      (is (= ["a" "b" "C"] (mapv :title (fmt/sort-rows rows :title :asc))))
      (is (= ["C" "b" "a"] (mapv :title (fmt/sort-rows rows :title :desc)))))))

(deftest paging-test
  (testing "an empty table still has one page"
    (is (= 1 (fmt/page-count 0 10))))
  (testing "a partial last page counts"
    (is (= 3 (fmt/page-count 21 10))))
  (testing "pages slice in order"
    (is (= [0 1 2] (fmt/page-rows (vec (range 10)) 0 3)))
    (is (= [9] (fmt/page-rows (vec (range 10)) 3 3))))
  (testing "a page past the end clamps to the last one, rather than showing nothing"
    (is (= [9] (fmt/page-rows (vec (range 10)) 99 3)))
    (is (= [0 1 2] (fmt/page-rows (vec (range 10)) -1 3)))))
