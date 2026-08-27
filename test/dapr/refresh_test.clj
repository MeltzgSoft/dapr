(ns dapr.refresh-test
  "Unit coverage for the background refresher's bookkeeping: what it writes to the
  cache while a walk is in flight, what it does when a walk yields the device, and
  how it projects itself into the app state. nio/scan-roots! is stubbed, so no
  filesystem is touched — the real walk (including pause/resume) is covered by
  dapr.fs.nio-test."
  (:require [clojure.test :refer [deftest is testing]]
            [dapr.db.cache :as cache]
            [dapr.fs.nio :as nio]
            [dapr.refresh :as refresh]
            [dapr.state :as state]
            [datascript.core :as d])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)
           (java.util.concurrent LinkedBlockingDeque)))

(def ^:private refresh-library! #'refresh/refresh-library!)
(def ^:private claim! #'refresh/claim!)
(def ^:private release! #'refresh/release!)

(defn- track [rel size]
  {:rel rel :size size :root "file:///r/" :artist "A" :mtime 1})

(defn- fixture!
  "A refresher wired to a fresh cache holding one library with one cached track,
  and a state atom that knows about it. No worker thread: the tests drive one turn
  of work at a time. Returns {:refresher :conn :lib-id :state-atom :queue :path}."
  []
  (let [conn   (cache/empty-conn)
        lib-id (cache/upsert-library! conn {:name "L" :roots ["file:///r/"]})
        path   (.toFile (Files/createTempFile "dapr-refresh" ".edn" (make-array FileAttribute 0)))
        queue  (LinkedBlockingDeque.)
        st     (atom (-> state/initial-state
                         (state/set-libraries [{:id lib-id :name "L" :roots ["file:///r/"]}])))]
    ;; Pre-existing cache content: stale.mp3 is gone from the device but must not
    ;; be retracted until a walk completes.
    (cache/replace-library-tracks! conn lib-id [(track "stale.mp3" 9)])
    {:conn conn :lib-id lib-id :state-atom st :queue queue :path path
     :refresher {:state-atom  st
                 :cache       {:conn conn :path path}
                 :queue       queue
                 :checkpoints (atom {})
                 :leases      (atom #{})
                 :running?    (atom true)}}))

(defn- await-status!
  "Block until `lib-id` reaches refresh status `want`, up to 5s. Returns true if it
  did — the tests that drive real worker threads need a bounded wait so a
  regression fails rather than hangs."
  [state-atom lib-id want]
  (let [deadline (+ (System/currentTimeMillis) 5000)]
    (loop []
      (cond
        (= want (state/refresh-status @state-atom lib-id)) true
        (> (System/currentTimeMillis) deadline)            false
        :else                                              (do (Thread/sleep 5) (recur))))))

(defn- catalog-files
  "Physical file identities [rel size] on `lib-id` — what a presence is keyed by,
  and what a completed walk reconciles against. (library-catalog itself is keyed by
  the tag-derived domain key, see dapr.domain.library/track-key.)"
  [conn lib-id]
  (into #{} (map (juxt :rel :size)) (vals (cache/library-catalog (d/db conn) lib-id))))

(deftest paused-walk-checkpoints-and-requeues-test
  (let [{:keys [refresher conn lib-id state-atom queue path]} (fixture!)
        seen-opts (atom nil)]
    (try
      (with-redefs [nio/scan-roots!
                    (fn [_roots opts]
                      (reset! seen-opts opts)
                      ((:on-batch opts) [(track "found.mp3" 1)])
                      {:status :paused :checkpoint {:roots ["file:///r/"] :stack [:dir] :seen #{["found.mp3" 1]}}})]
        (refresh-library! refresher lib-id))

      (testing "tracks found so far are already in the cache"
        (is (contains? (catalog-files conn lib-id) ["found.mp3" 1])))

      (testing "nothing is retracted — a partial walk knows nothing about absence"
        (is (contains? (catalog-files conn lib-id) ["stale.mp3" 9])))

      (testing "the library is marked paused and re-queued behind the others"
        (is (= :paused (state/refresh-status @state-atom lib-id)))
        (is (false? (state/library-complete? @state-atom lib-id)))
        (is (= [lib-id] (vec queue))))

      (testing "its counters survive the pause — the status-bar row keeps showing them"
        (is (some? (state/refresh-progress @state-atom lib-id))))

      (testing "the next turn resumes from the saved checkpoint"
        (with-redefs [nio/scan-roots!
                      (fn [_roots opts]
                        (reset! seen-opts opts)
                        {:status :complete :seen #{["found.mp3" 1]}})]
          (refresh-library! refresher lib-id))
        (is (= {:roots ["file:///r/"] :stack [:dir] :seen #{["found.mp3" 1]}}
               (:checkpoint @seen-opts))))

      (testing "completion reconciles away what the walk did not find, and persists"
        (is (= #{["found.mp3" 1]} (catalog-files conn lib-id)))
        (is (= :complete (state/refresh-status @state-atom lib-id)))
        (is (true? (state/library-complete? @state-atom lib-id)))
        (is (nil? (state/refresh-progress @state-atom lib-id))
            "a finished library keeps no counters — it has no status-bar row")
        (is (pos? (.length path)) "the cache was snapshotted"))

      (testing "the checkpoint is dropped once the walk completes"
        (with-redefs [nio/scan-roots! (fn [_roots opts]
                                        (reset! seen-opts opts)
                                        {:status :complete :seen #{["found.mp3" 1]}})]
          (refresh-library! refresher lib-id))
        (is (nil? (:checkpoint @seen-opts))))
      (finally
        (.delete path)))))

(deftest failed-walk-is-recorded-test
  (let [{:keys [refresher lib-id state-atom path]} (fixture!)]
    (try
      (with-redefs [nio/scan-roots! (fn [_roots _opts] (throw (ex-info "device gone" {})))]
        (refresh-library! refresher lib-id))
      (testing "the library is marked :error and the refresher moves on"
        (is (= :error (state/refresh-status @state-atom lib-id))))
      (testing "the reason is recorded, so the UI can say more than 'it failed'"
        (is (= {lib-id "device gone"} (state/refresh-errors @state-atom))))
      (testing "the app-wide status is untouched — a background failure is not a
                foreground one"
        (is (= :idle (:status @state-atom)))
        (is (nil? (:error @state-atom))))
      (finally
        (.delete path)))))

(deftest deleted-library-is-forgotten-test
  (let [{:keys [refresher state-atom path]} (fixture!)]
    (try
      (swap! state-atom state/set-refresh-status :gone :pending)
      (with-redefs [nio/scan-roots! (fn [_roots _opts] (throw (AssertionError. "must not scan")))]
        (refresh-library! refresher :gone))
      (testing "a library deleted while queued is dropped, not scanned"
        (is (nil? (state/refresh-status @state-atom :gone))))
      (finally
        (.delete path)))))

(deftest queue-priorities-test
  (let [{:keys [refresher state-atom queue path]} (fixture!)]
    (try
      (swap! state-atom state/set-libraries [{:id 1 :name "A" :roots ["file:///a/"]}
                                             {:id 2 :name "B" :roots ["file:///b/"]}
                                             {:id 3 :name "C" :roots ["file:///c/"]}])
      (swap! state-atom #(assoc % :source-id 3 :sink-id 2))

      (testing "only the chosen libraries are queued — not every configured one"
        (refresh/refresh! refresher [3 2])
        (is (= [3 2] (vec queue)))
        (is (= {2 :pending 3 :pending} (get-in @state-atom [:refresh :status]))
            "the library nobody selected is never even marked pending"))

      (testing "queueing an already-queued library does not duplicate it"
        (refresh/refresh! refresher [2])
        (is (= [2 3] (vec queue))))

      (testing "a newly chosen library goes to the front"
        (refresh/refresh! refresher [1])
        (is (= [1 2 3] (vec queue))))

      (testing "choosing a library that already completed re-queues it anyway —
                the device may have changed since"
        (swap! state-atom state/set-refresh-status 2 :complete)
        (.remove queue 2)
        (refresh/refresh! refresher [2])
        (is (= [2 1 3] (vec queue)))
        (is (= :pending (state/refresh-status @state-atom 2))))

      (testing "the library being walked right now is left to finish"
        (swap! state-atom state/set-refresh-status 3 :scanning)
        (.remove queue 3)
        (refresh/refresh! refresher [3])
        (is (= [2 1] (vec queue)))
        (is (= :scanning (state/refresh-status @state-atom 3))))

      (testing "a library the probe found unreachable is not queued at all —
                its device isn't attached, so a walk could only fail"
        (swap! state-atom state/set-library-availability {1 false 2 true})
        (.clear queue)
        (refresh/refresh! refresher [1 2])
        (is (= [2] (vec queue))))

      (testing "an unprobed library is queued — the walk itself will say"
        (swap! state-atom state/set-refresh-status 3 :complete) ; no longer :scanning
        (.clear queue)
        (is (nil? (get-in @state-atom [:library-availability 3])) "3 was never probed")
        (refresh/refresh! refresher [3])
        (is (= [3] (vec queue))))

      (testing "nils (no source or no sink chosen) are skipped"
        (.clear queue)
        (refresh/refresh! refresher [nil nil])
        (is (= [] (vec queue))))
      (finally
        (.delete path)))))

(deftest device-leases-test
  ;; What lets the pool be a pool: a coordinated device is handed to one worker at
  ;; a time, but libraries on *different* devices go out at once. A worker rotates
  ;; past a leased device rather than waiting on it — waiting would park a pool
  ;; thread for the length of a whole walk, since a background waiter does not
  ;; preempt a background holder (see dapr.device.coordinator).
  (let [{:keys [refresher state-atom queue path]} (fixture!)
        leases (:leases refresher)]
    (try
      (swap! state-atom state/set-libraries
             [{:id 1 :name "DAP music" :roots ["mtp://1:2:a/SD/Music"]}
              {:id 2 :name "DAP audio" :roots ["mtp://1:2:a/SD/Audiobooks"]}
              {:id 3 :name "NAS"       :roots ["smb://nas/Music/"]}
              {:id 4 :name "Local"     :roots ["file:///m1/"]}
              {:id 5 :name "Local too" :roots ["file:///m2/"]}])

      (testing "the first library on a device takes its lease"
        (.addLast queue 1)
        (.addLast queue 2)
        (is (= {:lib-id 1 :lease "mtp://1:2:a"} (claim! refresher))))

      (testing "a second library on that same device is not handed out"
        (is (= :leased (claim! refresher))
            "nothing runnable — 2 shares the device 1 is being walked on")
        (is (= [2] (vec queue))
            "and it stays queued rather than being dropped or walked twice"))

      (testing "a library on another device runs at once — the whole point"
        (.addLast queue 3)
        (is (= {:lib-id 3 :lease "smb://nas/Music"} (claim! refresher)))
        (is (= [2] (vec queue)) "the leased one was rotated past, not consumed"))

      (testing "releasing a device lets the library waiting on it through"
        (release! leases "mtp://1:2:a")
        (is (= {:lib-id 2 :lease "mtp://1:2:a"} (claim! refresher))))

      (testing "local libraries take no lease, so they run as wide as the pool"
        (.clear queue)
        (reset! leases #{})
        (.addLast queue 4)
        (.addLast queue 5)
        (is (= {:lib-id 4 :lease nil} (claim! refresher)))
        (is (= {:lib-id 5 :lease nil} (claim! refresher))
            "every file:// library shares one device key, so leasing it would
             serialize unrelated local libraries for no gain")
        (is (empty? @leases)))

      (testing "an empty queue says so, so the worker blocks instead of spinning"
        (.clear queue)
        (is (= :empty (claim! refresher))))

      (testing "a library deleted while queued still claims, and is dropped downstream"
        (.clear queue)
        (.addLast queue :gone)
        (is (= {:lib-id :gone :lease nil} (claim! refresher))))
      (finally
        (.delete path)))))

(deftest worker-pool-test
  (let [conn (cache/empty-conn)
        path (.toFile (Files/createTempFile "dapr-pool" ".edn" (make-array FileAttribute 0)))
        st   (atom state/initial-state)]
    (try
      (testing "the pool defaults to more than one worker"
        (let [r (refresh/start! {:state-atom st :cache {:conn conn :path path}})]
          (is (< 1 (count (:threads r))))
          (refresh/stop! r)))

      (testing "start! honours the configured size and stop! joins every worker"
        (let [r (refresh/start! {:state-atom st :cache {:conn conn :path path} :workers 3})]
          (is (= 3 (count (:threads r))))
          (is (every? (fn [^Thread t] (.isAlive t)) (:threads r)))
          (refresh/stop! r)
          (is (not-any? (fn [^Thread t] (.isAlive t)) (:threads r))
              "a worker left running would keep scanning a device the session is closing")))

      (testing "a nonsensical size still yields a usable refresher"
        (let [r (refresh/start! {:state-atom st :cache {:conn conn :path path} :workers 0})]
          (is (= 1 (count (:threads r))))
          (refresh/stop! r)))
      (finally
        (.delete path)))))

(deftest distinct-devices-refresh-concurrently-test
  ;; The user-visible payoff: a DAP and a NAS are scanned at the same time instead
  ;; of back to back. Driven through the real worker threads, since that is the
  ;; part that changed.
  (let [conn    (cache/empty-conn)
        path    (.toFile (Files/createTempFile "dapr-parallel" ".edn" (make-array FileAttribute 0)))
        dap     (cache/upsert-library! conn {:name "DAP" :roots ["mtp://1:2:a/SD/Music"]})
        nas     (cache/upsert-library! conn {:name "NAS" :roots ["smb://nas/Music/"]})
        st      (atom (-> state/initial-state
                          (state/set-libraries
                           [{:id dap :name "DAP" :roots ["mtp://1:2:a/SD/Music"]}
                            {:id nas :name "NAS" :roots ["smb://nas/Music/"]}])))
        walking (atom #{})
        both    (promise)
        release (promise)]
    (try
      (with-redefs [nio/scan-roots!
                    (fn [roots _opts]
                      (when (= 2 (count (swap! walking conj (first roots))))
                        (deliver both true))
                      ;; Hold the "device" until the test has seen both walks, so
                      ;; the overlap is proven rather than inferred from timing.
                      (deref release 5000 ::timeout)
                      {:status :complete :seen #{}})]
        (let [r (refresh/start! {:state-atom st :cache {:conn conn :path path} :workers 2})]
          (try
            (refresh/refresh! r [dap nas])
            (is (= true (deref both 5000 ::timeout))
                "both devices were being walked at the same moment")
            (deliver release true)
            (is (await-status! st dap :complete))
            (is (await-status! st nas :complete))
            (finally
              (deliver release true)
              (refresh/stop! r)))))
      (finally
        (.delete path)))))

(deftest one-device-refreshes-serially-test
  ;; The other half: two libraries on one device are never walked at once, whatever
  ;; the pool size. Belt and braces — the lease keeps the second worker out of the
  ;; queue for that device, and the coordinator's lock would still exclude it if the
  ;; lease were wrong. This asserts the invariant a user could observe (melt-jfs
  ;; serializes MTP calls anyway); device-leases-test covers the lease itself.
  (let [conn    (cache/empty-conn)
        path    (.toFile (Files/createTempFile "dapr-serial" ".edn" (make-array FileAttribute 0)))
        music   (cache/upsert-library! conn {:name "Music" :roots ["mtp://1:2:a/SD/Music"]})
        books   (cache/upsert-library! conn {:name "Books" :roots ["mtp://1:2:a/SD/Books"]})
        st      (atom (-> state/initial-state
                          (state/set-libraries
                           [{:id music :name "Music" :roots ["mtp://1:2:a/SD/Music"]}
                            {:id books :name "Books" :roots ["mtp://1:2:a/SD/Books"]}])))
        walking (atom #{})
        overlap (atom false)
        release (promise)]
    (try
      (with-redefs [nio/scan-roots!
                    (fn [roots _opts]
                      (when (< 1 (count (swap! walking conj (first roots))))
                        (reset! overlap true))
                      (deref release 2000 ::timeout)
                      (swap! walking disj (first roots))
                      {:status :complete :seen #{}})]
        (let [r (refresh/start! {:state-atom st :cache {:conn conn :path path} :workers 4})]
          (try
            (refresh/refresh! r [music books])
            (deliver release true)
            (is (await-status! st music :complete))
            (is (await-status! st books :complete))
            (is (false? @overlap)
                "one device was walked by two workers at once")
            (finally
              (deliver release true)
              (refresh/stop! r)))))
      (finally
        (.delete path)))))
