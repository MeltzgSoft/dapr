(ns dapr.track-window.transforms-test
  (:require [clojure.test :refer [deftest is testing]]
            [dapr.track-window.transforms :as transforms]))

(defn- track [title number]
  (let [key [title number]]
    [key {:key key :title title :artist "Artist" :album "Album" :size 1
          :disc-number 1 :track-number number :rel (str title ".mp3")}]))

(defn- state-with [& tracks]
  {:catalog-version 7
   :filter          {:artist nil :album nil}
   :source-catalog  (into {} tracks)
   :sink-catalog    {}
   :selected        #{}
   :capacity        {:free 1000}
   :settings        {}})

(deftest index-key-test
  (let [state (state-with (track "b" 2))
        view  {:sort :title :dir :asc :start 100}]
    (testing "catalog, filter and ordering identify an index"
      (is (= [7 {:artist nil :album nil} :title :asc]
             (transforms/index-key state view))))
    (testing "scroll offset and dynamic row state do not invalidate ordering"
      (is (= (transforms/index-key state view)
             (transforms/index-key (assoc state :selected #{["b" 2]})
                                   (assoc view :start 500)))))))

(deftest ordered-keys-test
  (let [state (state-with (track "b" 2) (track "A" 1))]
    (testing "sorts using the requested track-table order"
      (is (= [["A" 1] ["b" 2]]
             (transforms/ordered-keys state {:sort :title :dir :asc}))))
    (testing "reverses the requested order"
      (is (= [["b" 2] ["A" 1]]
             (transforms/ordered-keys state {:sort :title :dir :desc}))))))

(deftest normalize-start-test
  (testing "keeps an in-range offset"
    (is (= 20 (transforms/normalize-start 500 20))))
  (testing "clamps to a full final window"
    (is (= 300 (transforms/normalize-start 500 999))))
  (testing "nil, negative and short catalogs begin at zero"
    (is (= 0 (transforms/normalize-start 500 nil)))
    (is (= 0 (transforms/normalize-start 500 -4)))
    (is (= 0 (transforms/normalize-start 20 10)))))

(deftest window-test
  (let [result (transforms/window (vec (range 500)) 100)]
    (testing "returns one bounded slice and its absolute bounds"
      (is (= {:start 100 :end 300 :total 500}
             (select-keys result [:start :end :total])))
      (is (= (vec (range 100 300)) (:keys result))))
    (testing "spacers account for every omitted row"
      (is (= (* 100 transforms/row-height) (:top-height result)))
      (is (= (* 200 transforms/row-height) (:bottom-height result)))))
  (testing "an empty index produces an empty zero-height window"
    (is (= {:start 0 :end 0 :total 0 :keys [] :top-height 0 :bottom-height 0}
           (transforms/window [] 12)))))
