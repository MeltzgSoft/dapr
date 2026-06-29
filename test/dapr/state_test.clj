(ns dapr.state-test
  (:require [clojure.test :refer [deftest is testing]]
            [dapr.state :as state]))

(def lib-a {:id "a" :name "A" :roots ["file:///a"]})
(def lib-b {:id "b" :name "B" :roots ["file:///b"]})

(deftest select-invalidates-plan-test
  (testing "changing the source drops a plan built for the previous pair"
    (let [s (-> state/initial-state
                (assoc :plan {:actions [] :summary {}} :status :planned)
                (state/select-source "x"))]
      (is (nil? (:plan s)))
      (is (= :idle (:status s)))))
  (testing "changing the sink drops a plan built for the previous pair"
    (let [s (-> state/initial-state
                (assoc :plan {:actions [] :summary {}} :status :planned)
                (state/select-sink "y"))]
      (is (nil? (:plan s)))
      (is (= :idle (:status s))))))

(deftest filter-test
  (testing "selecting a source clears the column-browser filter"
    (let [s (-> state/initial-state
                (assoc :filter {:artist "X" :album "Y"})
                (state/select-source "id"))]
      (is (= {:artist nil :album nil} (:filter s)))))
  (testing "setting an artist clears the album (its albums change)"
    (let [s (-> state/initial-state
                (assoc-in [:filter :album] "Old")
                (state/set-filter-artist "A"))]
      (is (= {:artist "A" :album nil} (:filter s)))))
  (testing "setting an album keeps the artist"
    (let [s (-> state/initial-state (state/set-filter-artist "A") (state/set-filter-album "B"))]
      (is (= {:artist "A" :album "B"} (:filter s)))))
  (testing "set-filter-search sets a column's search text"
    (let [s (-> state/initial-state
                (state/set-filter-search :artist "be")
                (state/set-filter-search :album "ok"))]
      (is (= {:artist "be" :album "ok"} (:filter-search s)))))
  (testing "selecting a source also clears the facet searches"
    (let [s (-> state/initial-state
                (state/set-filter-search :artist "be")
                (state/select-source "id"))]
      (is (= {:artist "" :album ""} (:filter-search s))))))

(deftest libraries-test
  (testing "set, upsert (insert then replace), and lookup"
    (let [s (state/set-libraries state/initial-state [lib-a])]
      (is (= [lib-a] (:libraries s)))
      (is (= lib-a (state/library-by-id s "a")))
      (let [s2 (state/upsert-library s lib-b)]
        (is (= [lib-a lib-b] (:libraries s2)))
        (let [s3 (state/upsert-library s2 (assoc lib-a :name "A2"))]
          (is (= "A2" (:name (state/library-by-id s3 "a"))))
          (is (= 2 (count (:libraries s3)))))))))

(deftest delete-library-test
  (testing "removes the library and clears it from source/sink when selected"
    (let [s (-> state/initial-state
                (state/set-libraries [lib-a lib-b])
                (state/select-source "a")
                (state/select-sink "b")
                (state/delete-library "a"))]
      (is (= [lib-b] (:libraries s)))
      (is (nil? (:source-id s)))
      (is (= "b" (:sink-id s))))))

(deftest library-availability-test
  (testing "set-library-availability records the id->bool map (nil -> {})"
    (is (= {1 true 2 false}
           (:library-availability (state/set-library-availability state/initial-state {1 true 2 false}))))
    (is (= {} (:library-availability (state/set-library-availability state/initial-state nil)))))
  (testing "clear-unavailable-selection drops only explicitly-unavailable selections"
    (let [s (assoc state/initial-state
                   :source-id 1 :sink-id 2
                   :plan {:actions []} :status :planned
                   :filter {:artist "A" :album "B"})]
      (testing "an available source + unavailable sink clears just the sink and the plan"
        (let [s2 (state/clear-unavailable-selection s {1 true 2 false})]
          (is (= 1 (:source-id s2)))
          (is (nil? (:sink-id s2)))
          (is (nil? (:plan s2)))
          (is (= :idle (:status s2)))))
      (testing "an unavailable source is cleared and its column-browser filter reset"
        (let [s2 (state/clear-unavailable-selection s {1 false 2 true})]
          (is (nil? (:source-id s2)))
          (is (= {:artist nil :album nil} (:filter s2)))))
      (testing "unprobed (absent) selections are left intact, plan untouched"
        (let [s2 (state/clear-unavailable-selection s {})]
          (is (= 1 (:source-id s2)))
          (is (= 2 (:sink-id s2)))
          (is (= {:actions []} (:plan s2))))))))

(deftest settings-test
  (testing "set-settings replaces the whole map; nil becomes empty"
    (is (= {:theme :dark} (:settings (state/set-settings state/initial-state {:theme :dark}))))
    (is (= {} (:settings (state/set-settings state/initial-state nil)))))
  (testing "set-setting sets a key; setting reads it, with a default for misses"
    (let [s (-> state/initial-state
                (state/set-setting :theme :dark)
                (state/set-setting :log-dir "/tmp"))]
      (is (= :dark (state/setting s :theme)))
      (is (= "/tmp" (state/setting s :log-dir)))
      (is (= :system (state/setting s :missing :system)))
      (testing "a nil value clears just that key"
        (is (= {:log-dir "/tmp"} (:settings (state/set-setting s :theme nil)))))))
  (testing "set-os-color-scheme records the OS scheme (not a persisted setting)"
    (let [s (state/set-os-color-scheme state/initial-state :dark)]
      (is (= :dark (:os-color-scheme s)))
      (is (= {} (:settings s)))
      (is (nil? (:os-color-scheme (state/set-os-color-scheme s nil)))))))

(deftest set-catalogs-test
  (testing "pre-selects sink tracks and computes capacity"
    (let [source {["a" 10] {:size 10 :key ["a" 10]}
                  ["b" 20] {:size 20 :key ["b" 20]}}
          sink   {["a" 10] {:size 10 :key ["a" 10]}}
          s (state/set-catalogs state/initial-state source sink 100)]
      (is (= #{["a" 10]} (:selected s)))
      ;; budget = 100 free + 10 on-sink = 110; used = selected (a) = 10
      (is (= {:used 10 :budget 110 :free 100} (:capacity s)))))
  (testing "a source chosen with no sink shows tracks but pre-selects nothing and
            has zero capacity (browsing only until a sink is picked)"
    (let [source {["a" 10] {:size 10 :key ["a" 10]}
                  ["b" 20] {:size 20 :key ["b" 20]}}
          s (state/set-catalogs state/initial-state source {} 0)]
      (is (= #{} (:selected s)))
      (is (= {:used 0 :budget 0 :free 0} (:capacity s)))
      (testing "no track fits with no sink, so selecting one is refused"
        (is (= #{} (:selected (state/toggle-track s ["a" 10]))))))))

(deftest toggle-track-test
  (let [source {["a" 10] {:size 10 :key ["a" 10]}
                ["big" 100] {:size 100 :key ["big" 100]}}
        base (state/set-catalogs state/initial-state source {} 50)] ; budget 50
    (testing "selecting a fitting track adds it and updates capacity"
      (let [s (state/toggle-track base ["a" 10])]
        (is (= #{["a" 10]} (:selected s)))
        (is (= 10 (get-in s [:capacity :used])))))
    (testing "selecting an over-budget track is refused"
      (let [s (state/toggle-track base ["big" 100])]
        (is (= #{} (:selected s)))))
    (testing "deselecting always works"
      (let [s (-> base (state/toggle-track ["a" 10]) (state/toggle-track ["a" 10]))]
        (is (= #{} (:selected s)))))))

(deftest editor-test
  (testing "build, edit fields, add/remove roots"
    (let [s (-> state/initial-state
                (state/set-editor {:id "x" :name "" :roots []})
                (state/editor-name "Music")
                (state/editor-add-root "file:///music")
                (state/editor-add-root "file:///more"))]
      (is (= "Music" (get-in s [:editor :name])))
      (is (= ["file:///music" "file:///more"] (get-in s [:editor :roots])))
      (testing "duplicate roots are ignored"
        (is (= ["file:///music" "file:///more"]
               (get-in (state/editor-add-root s "file:///music") [:editor :roots]))))
      (testing "a root on a different device is rejected"
        (is (= ["file:///music" "file:///more"]
               (get-in (state/editor-add-root s "mtp://1:2:a/SD") [:editor :roots]))))
      (testing "remove and cancel"
        (is (= ["file:///more"]
               (get-in (state/editor-remove-root s "file:///music") [:editor :roots])))
        (is (nil? (:editor (state/cancel-editor s))))))))

;; Browser setup is device-specific and side-effecting (it lives in
;; dapr.device.*.events); the pure browser transitions that the common UI drives
;; once a device namespace has opened the browser are exercised here.

(deftest browser-set-entries-test
  (testing "set-entries records entries and clears the loading flag"
    (let [s (-> state/initial-state
                (state/set-browser {:phase :browse :device/type :file :device nil
                                    :cwd nil :crumbs [] :entries [] :loading? true})
                (state/browser-set-entries [{:name "Music" :uri "file:///m" :dir? true}]))]
      (is (false? (get-in s [:browser :loading?])))
      (is (= 1 (count (get-in s [:browser :entries])))))))

(deftest browser-set-devices-test
  (testing "set-devices records the device list and clears the loading flag"
    (let [s (-> state/initial-state
                (state/set-browser {:phase :device :device/type :mtp :devices [] :loading? true})
                (state/browser-set-devices [{:id "1:2:a" :name "Phone" :uri "mtp://1:2:a/"}]))]
      (is (false? (get-in s [:browser :loading?])))
      (is (= 1 (count (get-in s [:browser :devices])))))))

(deftest browser-start-browse-test
  (testing "start-browse enters the browse phase at a chosen device root"
    (let [s (-> state/initial-state
                (state/set-browser {:phase :device :device/type :mtp :loading? false})
                (state/browser-start-browse {:device {:name "Phone" :uri "mtp://1:2:a/"}
                                             :cwd "mtp://1:2:a/"}))]
      (is (= :browse (get-in s [:browser :phase])))
      (is (= {:name "Phone" :uri "mtp://1:2:a/"} (get-in s [:browser :device])))
      (is (= "mtp://1:2:a/" (get-in s [:browser :cwd])))
      (is (= [] (get-in s [:browser :crumbs])))
      (is (true? (get-in s [:browser :loading?]))))))

(deftest browser-navigation-test
  (testing "entering folders pushes crumbs and tracks cwd"
    (let [s (-> state/initial-state
                (state/set-browser {:phase :browse :device/type :mtp
                                    :device {:name "Phone" :uri "mtp://1:2:a/"}
                                    :cwd "mtp://1:2:a/" :crumbs [] :entries [] :loading? false})
                (state/browser-enter {:name "SD" :uri "mtp://1:2:a/SD"})
                (state/browser-enter {:name "Music" :uri "mtp://1:2:a/SD/Music"}))]
      (is (= "mtp://1:2:a/SD/Music" (get-in s [:browser :cwd])))
      (is (= [{:label "SD" :uri "mtp://1:2:a/SD"}
              {:label "Music" :uri "mtp://1:2:a/SD/Music"}]
             (get-in s [:browser :crumbs])))
      (is (true? (get-in s [:browser :loading?])))
      (testing "jumping to a crumb truncates deeper crumbs and resets cwd"
        (let [s (state/browser-to-crumb s 0)]
          (is (= "mtp://1:2:a/SD" (get-in s [:browser :cwd])))
          (is (= [{:label "SD" :uri "mtp://1:2:a/SD"}]
                 (get-in s [:browser :crumbs])))))
      (testing "returning to places resets to the device root when one is set (mtp://)"
        (let [s (state/browser-to-places s)]
          (is (= "mtp://1:2:a/" (get-in s [:browser :cwd])))
          (is (= [] (get-in s [:browser :crumbs])))))
      (testing "close removes the browser entirely"
        (is (nil? (:browser (state/browser-close s)))))))
  (testing "returning to places clears cwd when there is no device root (file://)"
    (let [s (-> state/initial-state
                (state/set-browser {:phase :browse :device/type :file :device nil
                                    :cwd "file:///m" :crumbs [] :entries [] :loading? false})
                (state/browser-enter {:name "Music" :uri "file:///m/Music"})
                (state/browser-to-places))]
      (is (nil? (get-in s [:browser :cwd])))
      (is (= [] (get-in s [:browser :crumbs]))))))

(deftest set-plan-test
  (testing "records the plan and moves to :planned"
    (let [s (state/set-plan state/initial-state [:action] {:add 1})]
      (is (= {:actions [:action] :summary {:add 1}} (:plan s)))
      (is (= :planned (:status s))))))

(deftest append-log-test
  (testing "appends messages in order"
    (is (= ["a" "b"] (:log (-> state/initial-state
                               (state/append-log "a")
                               (state/append-log "b"))))))
  (testing "caps retained lines at max-log-lines, keeping the most recent"
    (let [n (+ state/max-log-lines 50)
          s (reduce (fn [s i] (state/append-log s (str i)))
                    state/initial-state
                    (range n))]
      (is (= state/max-log-lines (count (:log s))))
      (is (= (str (dec n)) (last (:log s))))
      (is (= (str (- n state/max-log-lines)) (first (:log s))))
      (testing ":log-appends counts every append, not just retained lines"
        (is (= n (:log-appends s)))))))

(deftest log-window-test
  (testing "open-log/close-log toggle the live log window flag"
    (is (true? (:log-open? (state/open-log state/initial-state))))
    (is (false? (:log-open? (-> state/initial-state state/open-log state/close-log)))))
  (testing "set-log-file records the active log path"
    (is (= "/tmp/dapr.0.log" (:log-file (state/set-log-file state/initial-state "/tmp/dapr.0.log"))))))

(deftest set-error-test
  (testing "records the message and moves to :error"
    (let [s (state/set-error state/initial-state "boom")]
      (is (= "boom" (:error s)))
      (is (= :error (:status s))))))
