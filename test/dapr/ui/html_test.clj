(ns dapr.ui.html-test
  (:require [clojure.test :refer [deftest is testing]]
            [dapr.ui.html :as html]))

(deftest qs-test
  (testing "nothing to say, nothing appended"
    (is (= "" (html/qs {})))
    (is (= "" (html/qs {:a nil}))))
  (testing "values are percent-encoded, so an EDN track key survives the round trip"
    (is (= "?key=%5B%22A%2FB%22+1%5D" (html/qs {:key (pr-str ["A/B" 1])})))
    (is (= "?q=a%26b%3Dc" (html/qs {:q "a&b=c"}))))
  (testing "a blank value is kept — only nil is dropped"
    (is (= "?q=" (html/qs {:q ""})))))

(deftest url-test
  (is (= "/actions/x" (html/url "/actions/x")))
  (is (= "/actions/x?id=3" (html/url "/actions/x" {:id 3}))))

(deftest fragment-url-test
  (testing "a poll carries the digest it is checking against, plus the view it renders"
    (is (= "/fragments/table?sort=title&d=99"
           (html/fragment-url :table "99" {:sort "title" :page nil})))))

(deftest poll-test
  (let [attrs (html/poll :status "7")]
    (is (= "/fragments/status?d=7" (:hx-get attrs)))
    (testing "the server's notification is the trigger; the timer is the safety net"
      (is (= (format "sse:region-status, every %ds" html/fallback-seconds)
             (:hx-trigger attrs))))
    (is (= "outerHTML" (:hx-swap attrs))))
  (testing "view parameters ride along, so a re-fetch keeps the sort and page"
    (is (= "/fragments/table?sort=title&page=2&d=7"
           (:hx-get (html/poll :table "7" {:sort "title" :page 2}))))))

(deftest classes-test
  (is (= "job failed" (html/classes "job" "failed")))
  (is (= "job" (html/classes "job" nil false ""))))
