(ns dapr.ui.format-test
  (:require [clojure.test :refer [deftest is testing]]
            [dapr.ui.format :as fmt]))

(deftest human-bytes-test
  (testing "scales by unit"
    (is (= "0 B" (fmt/human-bytes 0)))
    (is (= "512 B" (fmt/human-bytes 512)))
    (is (= "1.0 KB" (fmt/human-bytes 1024)))
    (is (= "1.0 MB" (fmt/human-bytes (* 1024 1024))))
    (is (= "1.00 GB" (fmt/human-bytes (* 1024 1024 1024)))))
  (testing "nil is treated as zero"
    (is (= "0 B" (fmt/human-bytes nil)))))

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

(deftest library-unavailable?-test
  (testing "true only when probed and explicitly unavailable"
    (is (true? (fmt/library-unavailable? {1 false} 1)))
    (is (false? (fmt/library-unavailable? {1 true} 1))))
  (testing "unprobed libraries (absent from the map) are treated as available"
    (is (false? (fmt/library-unavailable? {} 1)))
    (is (false? (fmt/library-unavailable? nil 1)))))
