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
                 :running?    (atom true)}}))

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
        (is (nil? (get-in @state-atom [:refresh :active])))
        (is (= [lib-id] (vec queue))))

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
        (is (= :error (state/refresh-status @state-atom lib-id)))
        (is (nil? (get-in @state-atom [:refresh :active]))))
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
