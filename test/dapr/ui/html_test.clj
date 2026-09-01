(ns dapr.ui.html-test
  (:require [clojure.test :refer [deftest is testing]]
            [dapr.state :as state]
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
           (html/fragment-url :table "99" {:sort "title" :start nil})))))

(deftest poll-test
  (let [state (state/set-ui state/initial-state {:fallback-seconds 15})
        attrs (html/poll state :status "7")]
    (is (= "/fragments/status?d=7" (:hx-get attrs)))
    (testing "the server's notification is the trigger; the timer is the safety net"
      (is (= "region-status from:body, every 15s" (:hx-trigger attrs))))
    (testing "a morph swap, so a re-render keeps what the DOM owns — a running
              spinner animation, a scroll position — rather than replacing it"
      (is (= "outerMorph" (:hx-swap attrs))))
    (testing "the fallback interval is configured, not compiled in"
      (is (= "region-status from:body, every 3s"
             (:hx-trigger (html/poll (state/set-ui state/initial-state {:fallback-seconds 3})
                                     :status "7")))))
    (testing "config.edn saying nothing falls back to the default"
      (is (= (format "region-status from:body, every %ds" (:fallback-seconds state/default-ui))
             (:hx-trigger (html/poll (state/set-ui state/initial-state nil) :status "7")))))
    (testing "view parameters ride along, so a re-fetch keeps sort and window"
      (is (= "/fragments/table?sort=title&start=200&d=7"
             (:hx-get (html/poll state :table "7" {:sort "title" :start 200})))))))

(deftest classes-test
  (is (= "job failed" (html/classes "job" "failed")))
  (is (= "job" (html/classes "job" nil false ""))))
