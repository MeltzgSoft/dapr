(ns dapr.cache-test
  (:require [clojure.test :refer [deftest is testing]]
            [dapr.cache :as cache]
            [datascript.core :as d])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn- track [rel size & {:keys [artist album title root mtime source]}]
  {:rel rel :size size :artist artist :album album :title title
   :root (or root "file:///r/") :mtime mtime :source source})

(deftest library-crud-test
  (let [conn (cache/empty-conn)
        a    (cache/upsert-library! conn {:name "A" :roots ["file:///a/"]})
        b    (cache/upsert-library! conn {:name "B" :roots ["file:///b/"]})]
    (testing "upsert assigns distinct entity ids and projects to UI maps"
      (is (not= a b))
      (is (= [{:id a :name "A" :roots ["file:///a/"]}
              {:id b :name "B" :roots ["file:///b/"]}]
             (map #(select-keys % [:id :name :roots]) (cache/libraries (d/db conn))))))

    (testing "upsert with an existing id updates in place"
      (cache/upsert-library! conn {:id a :name "A2" :roots ["file:///a/" "file:///a2/"]})
      (is (= {:id a :name "A2" :roots ["file:///a/" "file:///a2/"]}
             (select-keys (first (cache/libraries (d/db conn))) [:id :name :roots]))))

    (testing "delete removes the library and its presences"
      (cache/replace-library-tracks! conn a [(track "x/y.mp3" 10)])
      (is (seq (cache/track-libraries (d/db conn) "x/y.mp3" 10)))
      (cache/delete-library! conn a)
      (is (= [{:id b :name "B" :roots ["file:///b/"]}]
             (map #(select-keys % [:id :name :roots]) (cache/libraries (d/db conn)))))
      (is (empty? (cache/track-libraries (d/db conn) "x/y.mp3" 10))))))

(deftest default-library-test
  (let [conn (cache/empty-conn)
        a    (cache/upsert-library! conn {:name "A" :roots ["file:///a/"]})
        b    (cache/upsert-library! conn {:name "B" :roots ["file:///b/"]})]
    (testing "no defaults to start"
      (is (nil? (cache/default-library (d/db conn) :source)))
      (is (nil? (cache/default-library (d/db conn) :sink)))
      (is (= [false false] [(:default-source? (first (cache/libraries (d/db conn))))
                            (:default-sink? (first (cache/libraries (d/db conn))))])))

    (testing "source and sink defaults are independent"
      (cache/set-default! conn :source a)
      (cache/set-default! conn :sink b)
      (is (= a (cache/default-library (d/db conn) :source)))
      (is (= b (cache/default-library (d/db conn) :sink)))
      (let [by-id (into {} (map (juxt :id identity)) (cache/libraries (d/db conn)))]
        (is (true? (:default-source? (by-id a))))
        (is (true? (:default-sink? (by-id b))))))

    (testing "setting a role's default moves it off the previous holder"
      (cache/set-default! conn :source b)
      (is (= b (cache/default-library (d/db conn) :source)))
      (is (false? (:default-source? (first (filter #(= a (:id %)) (cache/libraries (d/db conn))))))))

    (testing "re-setting the current default toggles it off"
      (cache/set-default! conn :source b)
      (is (nil? (cache/default-library (d/db conn) :source))))

    (testing "deleting a library clears any default it held"
      (cache/set-default! conn :sink b)
      (cache/delete-library! conn b)
      (is (nil? (cache/default-library (d/db conn) :sink))))))

(deftest app-settings-test
  (let [conn (cache/empty-conn)]
    (testing "unset settings read as nil / default / empty map"
      (is (nil? (cache/app-setting (d/db conn) :theme)))
      (is (= :system (cache/app-setting (d/db conn) :theme :system)))
      (is (= {} (cache/app-settings (d/db conn)))))

    (testing "multiple keys persist on a single singleton entity"
      (cache/set-app-setting! conn :theme :dark)
      (cache/set-app-setting! conn :log-dir "/tmp/logs")
      (is (= :dark (cache/app-setting (d/db conn) :theme)))
      (is (= {:theme :dark :log-dir "/tmp/logs"} (cache/app-settings (d/db conn))))
      (is (= 1 (count (d/q '[:find [?e ...] :where [?e :app/settings]] (d/db conn))))))

    (testing "updating a key overwrites it in place"
      (cache/set-app-setting! conn :theme :light)
      (is (= :light (cache/app-setting (d/db conn) :theme))))

    (testing "a nil value clears just that key"
      (cache/set-app-setting! conn :theme nil)
      (is (nil? (cache/app-setting (d/db conn) :theme)))
      (is (= {:log-dir "/tmp/logs"} (cache/app-settings (d/db conn)))))))

(deftest app-settings-snapshot-roundtrip-test
  (let [^java.nio.file.Path p (Files/createTempFile "dapr-cache" ".edn" (make-array FileAttribute 0))
        path (.toFile p)]
    (try
      (let [conn (cache/empty-conn)]
        (cache/set-app-setting! conn :theme :dark)
        (cache/set-app-setting! conn :log-dir "/var/log/dapr")
        (cache/snapshot! conn path)
        (testing "the settings map survives a snapshot round-trip"
          (is (= {:theme :dark :log-dir "/var/log/dapr"}
                 (cache/app-settings (d/db (cache/load! path)))))))
      (finally
        (Files/deleteIfExists p)))))

(deftest replace-library-tracks-test
  (let [conn (cache/empty-conn)
        a    (cache/upsert-library! conn {:name "A" :roots ["file:///a/"]})]
    (testing "builds a catalog in the planner/table shape"
      (cache/replace-library-tracks!
       conn a [(track "Artist/Album/One.mp3" 10 :artist "Artist" :album "Album" :title "One" :mtime 111)
               (track "Two.flac" 20 :title "Two")])
      (let [cat (cache/library-catalog (d/db conn) a)]
        (is (= #{["Artist/Album/One.mp3" 10] ["Two.flac" 20]} (set (keys cat))))
        (is (= {:key   ["Artist/Album/One.mp3" 10] :rel "Artist/Album/One.mp3" :size 10
                :root  "file:///r/" :mtime 111 :artist "Artist" :album "Album" :title "One"
                :source nil}
               (get cat ["Artist/Album/One.mp3" 10])))
        (is (nil? (:artist (get cat ["Two.flac" 20]))))))

    (testing "re-running replaces the set: gone tracks are retracted, new ones added"
      (cache/replace-library-tracks! conn a [(track "Two.flac" 20 :title "Two")
                                             (track "Three.mp3" 30)])
      (is (= #{["Two.flac" 20] ["Three.mp3" 30]}
             (set (keys (cache/library-catalog (d/db conn) a))))))))

(deftest large-rescan-no-overflow-test
  (testing "re-scanning a large library (every track an upsert) doesn't overflow
            DataScript's per-upsert recursion"
    (let [conn   (cache/empty-conn)
          lib    (cache/upsert-library! conn {:name "L" :roots ["file:///r/"]})
          tracks (mapv (fn [i] (track (str "d/" i ".mp3") (inc i)
                                      :artist (str "Artist" (mod i 100)) :mtime i))
                       (range 4000))]
      ;; First scan populates; the second is all upserts — the path that overflowed
      ;; the stack when transacted as a single tx.
      (cache/replace-library-tracks! conn lib tracks)
      (cache/replace-library-tracks! conn lib tracks)
      (is (= 4000 (count (cache/library-catalog (d/db conn) lib)))))))

(deftest tag-source-preference-test
  (let [conn (cache/empty-conn)
        a    (cache/upsert-library! conn {:name "A" :roots ["file:///a/"]})
        b    (cache/upsert-library! conn {:name "B" :roots ["smb://h/s/"]})]
    ;; Library A holds the track with real embedded tags.
    (cache/replace-library-tracks! conn a [(track "x.mp3" 1 :artist "Real" :source :embedded)])
    ;; Library B (a path-only device) holds the same [rel size] with inferred tags.
    (cache/replace-library-tracks! conn b [(track "x.mp3" 1 :artist "Path" :source :path
                                                  :root "smb://h/s/")])
    (testing "a path-derived scan records its presence but does not downgrade embedded tags"
      (let [ca (get (cache/library-catalog (d/db conn) a) ["x.mp3" 1])]
        (is (= "Real" (:artist ca)))
        (is (= :embedded (:source ca))))
      (is (= #{a b} (set (cache/track-libraries (d/db conn) "x.mp3" 1)))))

    (testing "a later embedded scan still updates the tags"
      (cache/replace-library-tracks! conn a [(track "x.mp3" 1 :artist "Real2" :source :embedded)])
      (is (= "Real2" (:artist (get (cache/library-catalog (d/db conn) a) ["x.mp3" 1])))))

    (testing "path tags still fill in a track that has none yet"
      (cache/replace-library-tracks! conn b [(track "y.mp3" 2 :artist "PathY" :source :path
                                                    :root "smb://h/s/")])
      (is (= "PathY" (:artist (get (cache/library-catalog (d/db conn) b) ["y.mp3" 2])))))))

(deftest incremental-replace-test
  (let [conn (cache/empty-conn)
        lib  (cache/upsert-library! conn {:name "L" :roots ["file:///r/"]})
        base [(track "a.mp3" 1 :artist "A" :mtime 10)
              (track "b.mp3" 2 :artist "B" :mtime 20)]]
    (cache/replace-library-tracks! conn lib base)

    (testing "re-scanning identical tracks transacts nothing"
      (let [n    (atom 0)
            orig d/transact!]
        (with-redefs [d/transact! (fn [& args] (swap! n inc) (apply orig args))]
          (cache/replace-library-tracks! conn lib base))
        (is (zero? @n))))

    (testing "only changed / added / removed tracks are applied"
      (cache/replace-library-tracks!
       conn lib [(track "a.mp3" 1 :artist "A2" :mtime 11)   ; changed tag + mtime
                 (track "c.mp3" 3 :artist "C" :mtime 30)])  ; added (b removed)
      (let [cat (cache/library-catalog (d/db conn) lib)]
        (is (= #{["a.mp3" 1] ["c.mp3" 3]} (set (keys cat))))
        (is (= "A2" (:artist (get cat ["a.mp3" 1]))))))))

(deftest presence-sharing-test
  (let [conn (cache/empty-conn)
        a    (cache/upsert-library! conn {:name "A" :roots ["file:///a/"]})
        b    (cache/upsert-library! conn {:name "B" :roots ["file:///b/"]})]
    (testing "the same [rel size] on two libraries is one track with two presences"
      (cache/replace-library-tracks! conn a [(track "s.mp3" 10 :title "S")])
      (cache/add-presence! conn b (track "s.mp3" 10 :title "S" :root "file:///b/"))
      (is (= #{a b} (set (cache/track-libraries (d/db conn) "s.mp3" 10))))
      (is (= 1 (count (d/q '[:find [?t ...] :where [?t :track/rel "s.mp3"]] (d/db conn))))))

    (testing "removing one presence leaves the other"
      (cache/remove-presence! conn a "s.mp3" 10)
      (is (= #{b} (set (cache/track-libraries (d/db conn) "s.mp3" 10)))))))

(deftest snapshot-roundtrip-test
  (let [^java.nio.file.Path p (Files/createTempFile "dapr-cache" ".edn" (make-array FileAttribute 0))
        path (.toFile p)]
    (try
      (let [conn (cache/empty-conn)
            a    (cache/upsert-library! conn {:name "A" :roots ["file:///a/"]})]
        (cache/replace-library-tracks!
         conn a [(track "Artist/Album/One.mp3" 10 :artist "Artist" :album "Album" :title "One" :mtime 111)])
        (cache/snapshot! conn path)
        (let [conn2 (cache/load! path)]
          (testing "libraries (with their assigned ids) survive a round-trip"
            (is (= (cache/libraries (d/db conn)) (cache/libraries (d/db conn2)))))
          (testing "the catalog and its tuple identity survive a round-trip"
            (is (= (cache/library-catalog (d/db conn) a)
                   (cache/library-catalog (d/db conn2) a))))
          (testing "the restored DB still upserts by [rel size] tuple identity"
            (cache/replace-library-tracks!
             conn2 a [(track "Artist/Album/One.mp3" 10 :artist "Changed" :title "One")])
            (is (= 1 (count (d/q '[:find [?t ...] :where [?t :track/rel "Artist/Album/One.mp3"]]
                                 (d/db conn2))))))))
      (finally
        (Files/deleteIfExists p)))))

(deftest corrupt-snapshot-falls-back-test
  (let [^java.nio.file.Path p (Files/createTempFile "dapr-cache" ".edn" (make-array FileAttribute 0))
        path (.toFile p)]
    (try
      (spit path "this is not valid snapshot data {{{")
      (let [conn (cache/load! path)]
        (testing "an unreadable snapshot yields an empty DB"
          (is (empty? (cache/libraries (d/db conn)))))
        (testing "the bad file is moved aside, not left in place to be overwritten"
          (is (not (.exists path)))
          (is (seq (filter #(.startsWith (.getName %) (.getName path))
                           (.listFiles (.getParentFile path)))))))
      (finally
        (Files/deleteIfExists p)
        (doseq [f (.listFiles (.getParentFile path))
                :when (.contains (.getName f) ".corrupt-")]
          (.delete f))))))

(deftest migrate-from-edn-test
  (let [conn (cache/empty-conn)]
    (cache/migrate-from-edn! conn [{:id "old-uuid-1" :name "A" :roots ["file:///a/"]}
                                   {:id "old-uuid-2" :name "B" :roots ["file:///b/"]}])
    (testing "legacy libraries import with fresh DB-assigned ids"
      (is (= ["A" "B"] (map :name (cache/libraries (d/db conn)))))
      (is (every? integer? (map :id (cache/libraries (d/db conn))))))))
