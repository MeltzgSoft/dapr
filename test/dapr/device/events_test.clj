(ns dapr.device.events-test
  "Coverage for the device-generic folder-browser access path."
  (:require [clojure.test :refer [deftest is testing]]
            [dapr.device.coordinator :as coord]
            [dapr.device.events :as device-events]))

(deftest load-browser-entries-coordinates-device-access-test
  (testing "an SMB browser listing uses the same coordinated lifecycle as other device work"
    (let [state-atom (atom {:browser {:device/type :smb
                                      :cwd         "smb://nas/Music/Artist/"
                                      :entries     []
                                      :loading?    true}})
          accessed   (atom nil)
          entries    [{:name "Album" :uri "smb://nas/Music/Artist/Album/" :dir? true}]]
      (with-redefs [coord/with-device! (fn [device work]
                                         (reset! accessed device)
                                         (work))
                    device-events/browser-entries! (fn [_] entries)]
        (is (not= ::timeout
                  (deref (device-events/load-browser-entries! state-atom)
                         5000
                         ::timeout)))
        (is (= {:key "smb://nas/Music" :type :smb} @accessed))
        (is (= entries (get-in @state-atom [:browser :entries])))
        (is (false? (get-in @state-atom [:browser :loading?])))))))
