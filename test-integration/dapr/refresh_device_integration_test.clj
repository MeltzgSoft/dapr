(ns dapr.refresh-device-integration-test
  "The background refresh against **real devices** — the half of features 10 and 11
  that neither the unit tests nor the file:// integration tests can reach.

  What is hardware-dependent, and therefore lives here:

  - a walk that pauses and resumes across a real MTP device's directory tree,
    proving the checkpoint frontier is honoured rather than the tree re-listed —
    the whole reason resume exists, since a listing is a blocking native round
    trip;
  - a foreground operation taking a device out from under an in-flight walk, on
    the real driver rather than a stubbed one;
  - two *different* devices being walked at the same moment (feature 11's payoff),
    and two libraries on *one* device never being, which is what keeps dapr inside
    melt-jfs's per-device serialization.

  What is **not** here, deliberately: the refresher's queue and lease *dispatch*.
  That is pure bookkeeping, it is covered deterministically in dapr.refresh-test,
  and driving it through a slow device would only make it flaky. These tests drive
  nio/scan-roots! and the coordinator directly and gate every hand-off on a
  promise, so they assert on ordering rather than on timing.

  Fixture: a uniquely named temp directory on the device's first storage, holding
  a few small files across a few subdirectories, removed in the :once fixture's
  teardown. The device's own (potentially huge) library is never walked — same
  rule as dapr.device.mtp.fs-integration-test.

  Skips when no MTP device is attached; device-present-test turns that skip into a
  failure under DAPR_REQUIRE_DEVICE (set on the device runners, where an all-skip
  would be a green run proving nothing). The two-device test additionally needs
  the runner's SMB share (TEST_SMB_GUEST_URL)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [dapr.device.coordinator :as coord]
            [dapr.device.fs :as device-fs]
            [dapr.device.mtp.fs :as mtp]
            [dapr.device.mtp.require-device :as require-device]
            [dapr.fs.nio :as nio])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(def ^:private timeout-ms
  "Upper bound on any hand-off. Generous — these wait on real device round trips —
  but finite, so a regression fails rather than hanging the suite."
  60000)

(def ^:private tree
  "The fixture laid down on each device: three directories so a walk has interior
  boundaries to check-point at, and enough files that a paused walk has really
  seen only part of the tree."
  {"a" ["1.mp3" "2.mp3" "3.mp3"]
   "b" ["1.mp3" "2.mp3" "3.mp3"]
   "c" ["1.mp3" "2.mp3" "3.mp3"]})

(def ^:private file-count (count (mapcat val tree)))

(def ^:private content "dapr-refresh-device-integration")

(def ^:private devices
  "Attached MTP devices, phantoms dropped — same discovery as
  dapr.device.mtp.fs-integration-test (see its docstring for the Windows WPD
  device-interface GUIDs this filters out)."
  (try
    (seq (filter #(try (device-fs/dir-children! (:uri %)) true
                       (catch Throwable _ false))
                 (mtp/devices!)))
    (catch Throwable _ nil)))

(def ^:private smb-share
  "The runner's native SMB share, when this is a device runner. nil elsewhere, and
  the two-device test skips — deliberately not defaulted to a local guess, since a
  silently absent share would make that test assert nothing."
  (System/getenv "TEST_SMB_GUEST_URL"))

(def ^:private fixture
  "{:mtp-url ... :smb-url ...} once the :once fixture has laid the tree down, or
  nil when there was no device to lay it on."
  (atom nil))

;; --- fixture -----------------------------------------------------------------

(defn- rels
  "The tree's relative paths under `dir`."
  [dir]
  (for [[sub files] tree file files] (str dir "/" sub "/" file)))

(defn- seed-local!
  "A local temp directory holding the whole tree under `dir`. The copy source:
  every backend is written through nio/copy-file! from a real file:// root."
  [dir]
  (let [root (Files/createTempDirectory "dapr-refresh-it" (make-array FileAttribute 0))]
    (doseq [rel (rels dir)]
      (let [f (.resolve root ^String rel)]
        (Files/createDirectories (.getParent f) (make-array FileAttribute 0))
        (spit (str f) content)))
    (device-fs/root-path! (str (.toUri root)))))

(defn- upload!
  "Copy the tree into `dir` under `base-url` (a storage or share root). Returns a
  zero-arg teardown removing exactly what was written — files, then their
  subdirectories, then `dir` itself. Best effort: a device left holding a stray
  temp dir beats a teardown throw masking the assertion that failed first."
  [base-url dir src-root]
  (let [dst-root (device-fs/root-path! base-url)
        drop!    (fn [rel] (try (nio/delete-file! dst-root rel) (catch Throwable _ nil)))]
    (doseq [rel (rels dir)]
      (nio/copy-file! src-root dst-root rel))
    (fn []
      (run! drop! (rels dir))
      (run! #(drop! (str dir "/" %)) (keys tree))
      (drop! dir))))

(defn- with-device-fixture
  "Lay the tree down on the attached MTP device (and on the runner's SMB share when
  there is one), run the tests, then remove it."
  [run-tests]
  (let [storage (when devices (first (device-fs/dir-children! (:uri (first devices)))))]
    (if-not storage
      (run-tests)
      (let [dir      (str "dapr-refresh-it-" (System/currentTimeMillis))
            src      (seed-local! dir)
            teardown (atom [])]
        (try
          (swap! teardown conj (upload! (str (:uri storage)) dir src))
          (when smb-share
            (swap! teardown conj (upload! smb-share dir src)))
          (reset! fixture {:mtp-url (str (:uri storage) dir "/")
                           :smb-url (when smb-share (str smb-share dir "/"))})
          (run-tests)
          (finally
            (reset! fixture nil)
            (doseq [f @teardown] (try (f) (catch Throwable _ nil)))))))))

(use-fixtures :once with-device-fixture)

;; --- helpers -----------------------------------------------------------------

(defn- ready?
  "True when the tree is in place, i.e. these tests have something to walk."
  []
  (some? @fixture))

(defn- files-of
  "Physical [rel size] identities from a collection of scanned tracks."
  [tracks]
  (into #{} (map (juxt :rel :size)) tracks))

(defn- await!
  "Block until `pred` holds, up to timeout-ms. Returns true if it held."
  [pred]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (cond
        (pred)                                  true
        (> (System/currentTimeMillis) deadline) false
        :else                                   (do (Thread/sleep 10) (recur))))))

(defn- walk!
  "Run scan-roots! over `url`, collecting the tracks streamed to :on-batch and
  counting directory listings. Returns {:result :tracks :listings}. `opts` are
  scan-roots!'s; any :on-scan is called after the listing count is kept."
  [url opts]
  (let [tracks   (volatile! [])
        listings (volatile! 0)
        on-scan  (get opts :on-scan (fn [_]))
        result   (nio/scan-roots!
                  [url]
                  (assoc opts
                         :on-batch (fn [batch] (vswap! tracks into batch))
                         :on-scan  (fn [ev]
                                     (when (= :listing (:type ev)) (vswap! listings inc))
                                     (on-scan ev))))]
    {:result result :tracks @tracks :listings @listings}))

;; --- tests -------------------------------------------------------------------

(deftest device-present-test
  ;; The gate: a skip normally, a failure under DAPR_REQUIRE_DEVICE. Kept in its
  ;; own deftest because skip-or-fail's failure only registers inside one — from
  ;; the :once fixture it would print and vanish.
  (if (ready?)
    (is (some? (:mtp-url @fixture)))
    (require-device/skip-or-fail
     "refresh device integration test"
     (if devices "device exposes no storage to write to" "no MTP device attached"))))

(deftest resumable-walk-on-device-test
  (when (ready?)
    (let [url (:mtp-url @fixture)]
      (testing "an uninterrupted walk of the fixture finds every file"
        (let [clean    (walk! url {})
              expected (files-of (:tracks clean))]
          (is (= :complete (:status (:result clean))))
          (is (= file-count (count expected)) "the fixture tree should be found whole")
          (is (pos? (:listings clean)))

          (testing "pausing mid-walk and resuming finds the same files, without
                    re-listing a directory the first pass already walked"
            ;; The point of the checkpoint: over MTP a directory listing is a
            ;; blocking native round trip, so a resumed walk that re-listed the
            ;; tree would cost about as much as starting over.
            (let [paused-once (volatile! false)
                  seen-dirs   (volatile! 0)
                  first-pass  (walk! url
                                     {:on-scan (fn [ev]
                                                 (when (= :listing (:type ev))
                                                   (vswap! seen-dirs inc)))
                                      ;; Yield once, at the second boundary — far
                                      ;; enough in to have a real frontier, short
                                      ;; of the end.
                                      :pause?  (fn []
                                                 (when (and (not @paused-once)
                                                            (<= 2 @seen-dirs))
                                                   (vreset! paused-once true)
                                                   true))})
                  checkpoint  (:checkpoint (:result first-pass))]
              (is (= :paused (:status (:result first-pass)))
                  "the walk should have yielded at a directory boundary")
              (is (some? checkpoint))
              (is (< (count (files-of (:tracks first-pass))) (count expected))
                  "a paused walk should have seen only part of the tree")

              (let [second-pass (walk! url {:checkpoint checkpoint})]
                (is (= :complete (:status (:result second-pass))))
                (is (= expected (into (files-of (:tracks first-pass))
                                      (files-of (:tracks second-pass))))
                    "pause + resume should cover exactly what one clean walk does")
                (is (= expected (:seen (:result second-pass)))
                    "the completed walk's `seen` carries the whole tree, so a
                     reconcile after a resumed walk cannot retract live tracks")
                (is (= (:listings clean)
                       (+ (:listings first-pass) (:listings second-pass)))
                    "a directory was listed twice — the resume re-walked instead
                     of continuing from the saved frontier")))))))))

(deftest foreground-preempts-device-walk-test
  (when (ready?)
    (testing "a user operation takes the device from an in-flight walk, which
              check-points and resumes"
      (let [url      (:mtp-url @fixture)
            dev      (coord/library-device {:roots [url]})
            mid      (promise)   ; delivered by the test, to let the walk continue
            underway (promise)   ; delivered by the walk, once it really is walking
            walker   (future
                       ;; Exactly how dapr.refresh drives it: a background
                       ;; acquire, and pause? asking whether a *foreground* op is
                       ;; waiting.
                       (coord/with-device-background! dev
                         (fn []
                           (walk! url
                                  {:on-scan (fn [ev]
                                              (when (= :listing (:type ev))
                                                (deliver underway true)
                                                ;; Hold the device mid-walk so the
                                                ;; hand-off is ordered, not raced.
                                                (deref mid timeout-ms nil)))
                                   :pause?  (fn [] (coord/queued? dev))}))))]
        (is (= true (deref underway timeout-ms ::timeout))
            "the walk should have started and be holding the device")
        (is (false? (coord/queued? dev)) "nobody is waiting yet")

        (let [entered (promise)
              acquire ^Runnable (fn [] (coord/with-device! dev #(deliver entered true)))
              fg      (doto (Thread. acquire "device-it-foreground")
                        (.setDaemon true)
                        (.start))]
          (is (await! #(coord/queued? dev))
              "the foreground op should be visible to the walk as a waiter")
          (deliver mid true)

          (let [paused (deref walker timeout-ms ::timeout)]
            (is (not= ::timeout paused))
            (is (= :paused (:status (:result paused)))
                "the walk should have check-pointed rather than run to completion")
            (is (await! #(realized? entered))
                "the foreground op should have got the device once the walk yielded")
            (.join fg timeout-ms)

            (testing "and the yielded walk resumes to completion afterwards"
              (let [rest-pass (walk! url {:checkpoint (:checkpoint (:result paused))})]
                (is (= :complete (:status (:result rest-pass))))
                (is (= file-count (count (into (files-of (:tracks paused))
                                               (files-of (:tracks rest-pass)))))
                    "nothing was lost across the preemption")))))))))

(deftest one-device-is-walked-serially-test
  (when (ready?)
    (testing "two libraries on one device never walk it at once"
      ;; What keeps dapr inside melt-jfs's per-device serialization: the two roots
      ;; are separate libraries, but they share a device key, so the second walk
      ;; waits rather than issuing concurrent native calls.
      (let [url     (:mtp-url @fixture)
            dev     (coord/library-device {:roots [url]})
            inside  (atom 0)
            overlap (atom false)
            walker  (fn [sub]
                      (future
                        (coord/with-device-background! dev
                          (fn []
                            (when (< 1 (swap! inside inc)) (reset! overlap true))
                            (try (walk! (str url sub "/") {})
                                 (finally (swap! inside dec)))))))
            a       (walker "a")
            b       (walker "b")]
        (is (not= ::timeout (deref a timeout-ms ::timeout)))
        (is (not= ::timeout (deref b timeout-ms ::timeout)))
        (is (false? @overlap)
            "one MTP device was walked by two threads at once")))))

(deftest distinct-devices-walk-concurrently-test
  (when (ready?)
    (if-not (:smb-url @fixture)
      (println "  (skipping two-device walk — no TEST_SMB_GUEST_URL share)")
      (testing "an MTP device and an SMB share are walked at the same moment"
        ;; Feature 11's payoff on real hardware: different device keys mean
        ;; different locks, so neither walk waits for the other.
        (let [both    (promise)
              release (promise)
              inside  (atom #{})
              walker  (fn [url tag]
                        (future
                          (coord/with-device-background!
                            (coord/library-device {:roots [url]})
                            (fn []
                              (walk! url
                                     {:on-scan
                                      (fn [ev]
                                        (when (= :listing (:type ev))
                                          (when (= 2 (count (swap! inside conj tag)))
                                            (deliver both true))
                                          ;; Hold both inside their walks until the
                                          ;; overlap has been observed.
                                          (deref release timeout-ms nil)))})))))
              a       (walker (:mtp-url @fixture) :mtp)
              b       (walker (:smb-url @fixture) :smb)]
          (is (= true (deref both timeout-ms ::timeout))
              "both devices should have been inside a walk simultaneously")
          (deliver release true)
          (is (= :complete (:status (:result (deref a timeout-ms {})))))
          (is (= :complete (:status (:result (deref b timeout-ms {}))))))))))
