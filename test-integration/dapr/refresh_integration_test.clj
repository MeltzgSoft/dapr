(ns dapr.refresh-integration-test
  "End-to-end coverage of the background refresher against real directories: the
  worker thread, the resumable walk, the incremental cache writes, and the
  completion reconcile, wired exactly as dapr.system wires them. Uses file://
  temp dirs (real, addressable URIs) — no mocks, matching the rest of the
  integration suite."
  (:require [clojure.test :refer [deftest is testing]]
            [dapr.db.cache :as cache]
            [dapr.refresh :as refresh]
            [dapr.state :as state]
            [dapr.test-fs :as tfs]
            [datascript.core :as d])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(def ^:private timeout-ms 20000)

(defn- await-status!
  "Block until library `lib-id` reaches `status`, up to timeout-ms. Returns true if
  it did."
  [state-atom lib-id status]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (cond
        (= status (state/refresh-status @state-atom lib-id)) true
        (> (System/currentTimeMillis) deadline)              false
        :else (do (Thread/sleep 20) (recur))))))

(defn- catalog-files
  "Physical file identities [rel size] on `lib-id` — what a presence is keyed by,
  and what a completed walk reconciles against. (library-catalog itself is keyed by
  the tag-derived domain key, see dapr.domain.library/track-key.)"
  [conn lib-id]
  (into #{} (map (juxt :rel :size)) (vals (cache/library-catalog (d/db conn) lib-id))))

(deftest refreshes-a-library-in-the-background-test
  (let [dir       (tfs/temp-dir!)
        snapshot  (.toFile (Files/createTempFile "dapr-refresh" ".edn" (make-array FileAttribute 0)))
        conn      (cache/empty-conn)]
    (try
      (tfs/write! (.resolve dir "a.mp3") "aaa")
      (tfs/write! (.resolve dir "Album/b.flac") "bbbb")
      (tfs/write! (.resolve dir "Album/notes.txt") "not audio")
      (let [lib-id     (cache/upsert-library! conn {:name "L" :roots [(tfs/uri-of dir)]})
            state-atom (atom (state/set-libraries state/initial-state
                                                  [{:id lib-id :name "L" :roots [(tfs/uri-of dir)]}]))
            refresher  (refresh/start! {:state-atom state-atom
                                        :cache      {:conn conn :path snapshot}})]
        (try
          (testing "a queued library is walked into the cache and marked complete"
            (refresh/refresh! refresher [lib-id])
            (is (await-status! state-atom lib-id :complete))
            (is (= #{["a.mp3" 3] ["Album/b.flac" 4]} (catalog-files conn lib-id))
                "audio files only, keyed by [rel size]")
            (is (true? (state/library-complete? @state-atom lib-id)))
            (is (nil? (get-in @state-atom [:refresh :active]))))

          (testing "the completed scan is persisted, so the next launch paints from it"
            (is (= (catalog-files conn lib-id)
                   (catalog-files (cache/load! snapshot) lib-id))))

          (testing "a deleted file is retracted by the next completed refresh"
            (Files/delete (.resolve dir "a.mp3"))
            (swap! state-atom state/set-refresh-status lib-id :pending)
            (refresh/refresh! refresher [lib-id])
            (is (await-status! state-atom lib-id :complete))
            (is (= #{["Album/b.flac" 4]} (catalog-files conn lib-id))))

          (testing "a new file is picked up by the next refresh"
            (tfs/write! (.resolve dir "Album/c.mp3") "ccccc")
            (swap! state-atom state/set-refresh-status lib-id :pending)
            (refresh/refresh! refresher [lib-id])
            (is (await-status! state-atom lib-id :complete))
            (is (= #{["Album/b.flac" 4] ["Album/c.mp3" 5]} (catalog-files conn lib-id))))

          (finally
            (refresh/stop! refresher))))

      (testing "stopping the refresher leaves no worker behind"
        (is (empty? (filter #(= "dapr-refresh" (.getName ^Thread %))
                            (filter #(.isAlive ^Thread %) (keys (Thread/getAllStackTraces)))))))
      (finally
        (tfs/delete-tree! dir)
        (.delete snapshot)))))
