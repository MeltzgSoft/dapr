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
          (is (= {:actions []} (:plan s2)))))))
  (testing "library-unreachable? is true only for a library probed and found absent"
    (let [s (state/set-library-availability state/initial-state {1 true 2 false})]
      (is (false? (state/library-unreachable? s 1)))
      (is (true? (state/library-unreachable? s 2)))
      (is (false? (state/library-unreachable? s 3)) "never probed is not known-absent"))))

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

(deftest track-locked-test
  (let [source {["a" 10] {:size 10 :key ["a" 10]}}
        sink   {["a" 10] {:size 10 :key ["a" 10]}
                ["s" 5]  {:size 5 :key ["s" 5]}}   ; s is sink-only
        base   (state/set-catalogs state/initial-state source sink 100)]
    (testing "a sink-only track is locked under :keep (the default handling)"
      (is (true? (state/track-locked? base ["s" 5])))
      (is (false? (state/track-locked? base ["a" 10]))))   ; present in source
    (testing "not locked once handling is :delete"
      (let [d (state/set-setting base :sink-only-handling :delete)]
        (is (false? (state/track-locked? d ["s" 5])))))))

(deftest toggle-keys-test
  (let [source (into {} (for [i (range 4)] [["t" i] {:size 10 :key ["t" i]}]))
        base   (state/set-catalogs state/initial-state source {} 100)] ; budget 100
    (testing "selects all matching keys when none/some are selected"
      (let [s (state/toggle-keys base [["t" 0] ["t" 1] ["t" 2]])]
        (is (= #{["t" 0] ["t" 1] ["t" 2]} (:selected s)))
        (is (= 30 (get-in s [:capacity :used])))))
    (testing "deselects all when every matching key is already selected"
      (let [on  (state/toggle-keys base [["t" 0] ["t" 1]])
            off (state/toggle-keys on [["t" 0] ["t" 1]])]
        (is (= #{} (:selected off)))))
    (testing "skips keys that don't fit once the budget is exhausted"
      (let [tight (state/set-catalogs state/initial-state source {} 25)] ; fits two 10s
        (is (= 2 (count (:selected (state/toggle-keys
                                    tight [["t" 0] ["t" 1] ["t" 2]])))))))
    (testing "leaves a locked sink-only track untouched (not in the toggle group)"
      (let [src  {["a" 10] {:size 10 :key ["a" 10]}}   ; source-only, off by default
            snk  {["s" 5]  {:size 5 :key ["s" 5]}}      ; sink-only, locked-on under :keep
            b    (state/set-catalogs state/initial-state src snk 100) ; s pre-selected
            s    (state/toggle-keys b [["a" 10] ["s" 5]])]
        ;; "a" is off and "s" is locked (dropped from the group), so this selects
        ;; "a"; the locked "s" stays selected regardless.
        (is (contains? (:selected s) ["a" 10]))
        (is (contains? (:selected s) ["s" 5]))))))

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
        (is (= n (:log-appends s))))
      (testing "the trimmed log is a fresh vector, not a SubVector that retains
                (and keeps growing) the whole append history on the heap"
        (is (not (instance? clojure.lang.APersistentVector$SubVector (:log s))))))))

(deftest log-window-test
  (testing "open-log/close-log toggle the live log window flag"
    (is (true? (:log-open? (state/open-log state/initial-state))))
    (is (false? (:log-open? (-> state/initial-state state/open-log state/close-log)))))
  (testing "open-log re-engages tail-following so it opens at the newest line"
    (is (true? (:log-follow? (-> state/initial-state
                                 (assoc :log-follow? false)
                                 state/open-log)))))
  (testing "set-log-file records the active log path"
    (is (= "/tmp/dapr.0.log" (:log-file (state/set-log-file state/initial-state "/tmp/dapr.0.log")))))
  (testing "the jobs sidebar starts expanded and remembers being collapsed"
    (is (true? (:jobs-open? state/initial-state)))
    (is (false? (:jobs-open? (state/set-jobs-open state/initial-state false))))
    (is (true? (:jobs-open? (-> state/initial-state
                                (state/set-jobs-open false)
                                (state/set-jobs-open true)))))
    (testing "coercing whatever the property listener hands over"
      (is (false? (:jobs-open? (state/set-jobs-open state/initial-state nil)))))))

(deftest log-follow-test
  (let [following (assoc state/initial-state :log-follow? true :log-scroll 1.0)]
    (testing "scrolling up while following disengages follow and freezes at the position"
      (let [s (state/log-scrolled following 0.6)]
        (is (false? (:log-follow? s)))
        (is (= 0.6 (:log-scroll s)))))
    (testing "the programmatic pin (scrollbar value increasing) keeps following"
      (let [s (state/log-scrolled (assoc following :log-scroll 0.5) 0.9)]
        (is (true? (:log-follow? s)))
        (is (= 0.9 (:log-scroll s)))))
    (testing "sub-epsilon jitter around the pin is ignored"
      (is (true? (:log-follow? (state/log-scrolled following 0.995)))))
    (testing "while not following, scrolling never spuriously re-engages"
      (is (false? (:log-follow? (state/log-scrolled
                                 (assoc following :log-follow? false) 0.3)))))
    (testing "follow-log re-engages tail-following"
      (is (true? (:log-follow? (state/follow-log
                                (assoc following :log-follow? false))))))))

;; --- background refresh ------------------------------------------------------

(def ^:private src-cat
  {["a.mp3" 1] {:key ["a.mp3" 1] :rel "a.mp3" :size 1}
   ["b.mp3" 2] {:key ["b.mp3" 2] :rel "b.mp3" :size 2}})

(deftest update-catalogs-test
  (let [base (-> state/initial-state
                 (state/set-catalogs src-cat {} 1000)
                 (state/toggle-track ["a.mp3" 1]))]
    (testing "set-catalogs pre-selects the sink's tracks (a fresh source/sink choice)"
      (is (= #{["b.mp3" 2]}
             (:selected (state/set-catalogs state/initial-state src-cat
                                            {["b.mp3" 2] (src-cat ["b.mp3" 2])} 1000)))))

    (testing "a background repaint keeps the user's selection"
      (is (= #{["a.mp3" 1]} (:selected base)))
      (is (= #{["a.mp3" 1]} (:selected (state/update-catalogs base src-cat {} 1000)))))

    (testing "a selected track that has vanished from both catalogs is dropped"
      (let [s (state/update-catalogs base (dissoc src-cat ["a.mp3" 1]) {} 1000)]
        (is (= #{} (:selected s)))))

    (testing "capacity is recomputed against the new catalogs"
      (let [s (state/update-catalogs base src-cat {} 500)]
        (is (= 500 (:free-bytes s)))
        (is (= 1 (get-in s [:capacity :used])))))))

(deftest refresh-status-test
  (let [s (-> state/initial-state
              (state/set-libraries [lib-a lib-b])
              (assoc :source-id "a" :sink-id "b")
              (state/set-refresh-status "a" :scanning)
              (state/set-refresh-progress "a" {:done 3 :total 9}))]
    (testing "status and progress are projected per library"
      (is (= :scanning (state/refresh-status s "a")))
      (is (nil? (state/refresh-status s "b")))
      (is (= {:done 3 :total 9} (state/refresh-progress s "a")))
      (is (nil? (state/refresh-progress s "b"))))

    (testing "only a completed walk counts as complete"
      (is (false? (state/library-complete? s "a")))
      (is (true? (state/library-complete? (state/set-refresh-status s "a" :complete) "a"))))

    (testing "a second library's progress is tracked beside the first, not instead"
      (let [s2 (state/set-refresh-progress s "b" {:done 1 :total 4})]
        (is (= {:done 3 :total 9} (state/refresh-progress s2 "a")))
        (is (= {:done 1 :total 4} (state/refresh-progress s2 "b"))))
      (testing "and nil clears just that library's"
        (let [s2 (state/set-refresh-progress s "a" nil)]
          (is (nil? (state/refresh-progress s2 "a")))
          (is (= :scanning (state/refresh-status s2 "a"))))))

    (testing "the sync gate lists the chosen libraries that haven't completed"
      (is (= ["A" "B"] (mapv :name (state/sync-incomplete-libraries s))))
      (let [done (-> s
                     (state/set-refresh-status "a" :complete)
                     (state/set-refresh-status "b" :complete))]
        (is (empty? (state/sync-incomplete-libraries done))))
      (testing "a source and sink on one library is listed once"
        (is (= 1 (count (state/sync-incomplete-libraries (assoc s :sink-id "a")))))))

    (testing "a deleted library is forgotten, progress and all"
      (let [s2 (state/forget-refresh s "a")]
        (is (nil? (state/refresh-status s2 "a")))
        (is (nil? (state/refresh-progress s2 "a")))))))

(deftest refresh-error-test
  (let [failed (-> state/initial-state
                   (state/set-libraries [lib-a lib-b])
                   (state/set-refresh-error "a" "device gone"))]
    (testing "a failed refresh records its reason, for the UI to surface"
      (is (= :error (state/refresh-status failed "a")))
      (is (= {"a" "device gone"} (state/refresh-errors failed))))

    (testing "a background failure leaves the foreground status and plan alone"
      (let [s (-> state/initial-state
                  (state/set-plan [] {:add 1})
                  (state/set-refresh-error "a" "device gone"))]
        (is (= :planned (:status s)))
        (is (some? (:plan s)))
        (is (nil? (:error s)))))

    (testing "re-running that library clears the stale message"
      (is (empty? (state/refresh-errors (state/set-refresh-status failed "a" :pending))))
      (is (empty? (state/refresh-errors (state/set-refresh-status failed "a" :complete)))))

    (testing "an error keeps the library out of :complete, so sync still confirms"
      (is (false? (state/library-complete? failed "a")))
      (is (= ["A"] (mapv :name (state/sync-incomplete-libraries
                                (assoc failed :source-id "a"))))))

    (testing "deleting the library drops its error too"
      (is (empty? (state/refresh-errors (state/forget-refresh failed "a")))))))

(deftest confirm-test
  (testing "open-confirm holds the dialog description; close-confirm clears it"
    (let [c {:kind :sync :message "sure?"}
          s (state/open-confirm state/initial-state c)]
      (is (= c (:confirm s)))
      (is (nil? (:confirm (state/close-confirm s)))))))

(deftest set-error-test
  (testing "records the message and moves to :error"
    (let [s (state/set-error state/initial-state "boom")]
      (is (= "boom" (:error s)))
      (is (= :error (:status s))))))
