(ns dapr.device.file.tag-test
  (:require [clojure.test :refer [deftest is testing]]
            [dapr.device.file.tag]
            [dapr.device.tag :as tag])
  (:import (java.nio.file Files OpenOption Path)
           (java.nio.file.attribute FileAttribute)))

(deftest default-dispatch-test
  (testing "schemes without a registered reader (smb://) fall back to path-derived
            tags, tagged :path (mtp:// now has its own reader — see
            dapr.device.mtp.tag-test)"
    (is (= {:artist "Artist" :album "Album" :title "Title" :source :path}
           (tag/tags! {:root "smb://host/share/Artist/Album/Title.mp3"
                       :rel  "Artist/Album/Title.mp3"}
                      nil)))))

(deftest file-fallback-test
  (testing "an unreadable file:// audio file falls back to path-derived tags (:path)"
    (let [^Path p (Files/createTempFile "dapr-tag" ".mp3" (make-array FileAttribute 0))]
      (try
        (Files/write p (.getBytes "not really an mp3") (make-array OpenOption 0))
        (is (= {:artist "Artist" :album "Album" :title "Title" :source :path}
               (tag/tags! {:root (str (.toUri p))
                           :rel  "Artist/Album/Title.mp3"}
                          p)))
        (finally
          (Files/deleteIfExists p))))))

(deftest file-error-falls-back-test
  (testing "an Error from the tag reader (e.g. jaudiotagger StackOverflowError) is
            caught so one bad file can't abort the scan"
    (with-redefs [dapr.device.file.tag/read-tag (fn [_] (throw (StackOverflowError.)))]
      (is (= {:artist "Artist" :album "Album" :title "Title" :source :path}
             (tag/tags! {:root "file:///x/Artist/Album/Title.mp3"
                         :rel  "Artist/Album/Title.mp3"}
                        nil))))))
