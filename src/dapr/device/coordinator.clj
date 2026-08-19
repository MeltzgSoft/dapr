(ns dapr.device.coordinator
  "Arbitration of device access between the background library refresher and the
  foreground user operations (sync, capacity queries).

  A slow device is a scarce resource: melt-jfs serializes calls per MTP device
  outright, and a share reached over one network connection is saturated by a
  library walk's thousands of round trips. Either way a user's sync ends up behind a
  running scan with no way to jump ahead. This namespace hands such a device to one
  holder at a time, *preemptibly*: one fair ReentrantLock per device key, held for
  the duration of a device operation.

  - **Foreground** work wraps itself in `with-device!` / `with-devices!` and simply
    blocks. The lock is fair, so it is served in arrival order rather than being
    starved by a scan that keeps re-acquiring.
  - **Background** work (dapr.refresh) also holds the lock, but polls `queued?` at
    directory boundaries and releases as soon as anyone is waiting, checkpointing
    its walk so it can resume where it left off.

  Which devices need arbitrating is a property of the device *type*, answered by
  the `dapr.device.format/arbitrate-access?` multimethod alongside the rest of the
  per-device metadata — so a new device type declares its own access model rather
  than this namespace keeping a list. Local file:// libraries opt out (and all share
  one device key), so they are never locked.

  Locks are process-global (memoized per key) because device access is reached
  through the java.nio provider SPI, which carries no component context — the same
  reason dapr.system's :dapr/devices component exists (see its docstring)."
  (:require [dapr.device.format :as device-format]
            [dapr.domain.library :as lib])
  (:import (java.util.concurrent.locks ReentrantLock)))

(defonce ^:private locks*
  ;; device key -> fair ReentrantLock. Never pruned during a run: there is one
  ;; entry per device the user has libraries on, and a lock must outlive any
  ;; thread waiting on it.
  (atom {}))

(defn library-device
  "Descriptor of the device `library` lives on: {:key <device/share key> :type
  <device type>}. A library's roots are constrained to one device (see
  dapr.domain.library), so its first root answers both. The pair travels together
  because the key identifies *which* device to lock while the type says *whether*
  the device needs locking at all. nil-ish for a library with no roots."
  [{:keys [roots]}]
  (when-let [uri (first roots)]
    {:key  (lib/roots-device-key roots)
     :type (device-format/device-type uri)}))

(defn coordinated?
  "True when `device` (a library-device descriptor) must be arbitrated: it names a
  concrete device and its type asks for arbitration (see
  dapr.device.format/arbitrate-access?)."
  [{:keys [key type]}]
  (boolean (and key (device-format/arbitrate-access? type))))

(defn- lock-for
  "The fair lock for device key `k`, creating it on first use."
  ^ReentrantLock [k]
  (or (get @locks* k)
      (get (swap! locks* (fn [m] (cond-> m (not (get m k)) (assoc k (ReentrantLock. true)))))
           k)))

(defn queued?
  "True when another thread is waiting to acquire `device` — the background
  refresher's signal to check-point its walk and release it (see dapr.refresh).
  Always false for a device that needs no arbitration."
  [device]
  (boolean (when (coordinated? device)
             (when-let [^ReentrantLock l (get @locks* (:key device))]
               (.hasQueuedThreads l)))))

(defn with-device!
  "Run `f` holding `device`'s lock, blocking until it is free. A device that needs
  no arbitration (see coordinated?) runs `f` directly. Reentrant: nesting the same
  device on one thread is safe. Returns f's value."
  [device f]
  (if-not (coordinated? device)
    (f)
    (let [^ReentrantLock l (lock-for (:key device))]
      (.lock l)
      (try (f) (finally (.unlock l))))))

(defn with-devices!
  "Run `f` holding every one of `devices` (a sync touches its source *and* its
  sink). De-duplicated by key — source and sink on one device take a single lock —
  and acquired in a fixed key order, so two threads locking overlapping sets can't
  deadlock."
  [devices f]
  ((reduce (fn [thunk device] (fn [] (with-device! device thunk)))
           f
           (->> devices
                (filter coordinated?)
                (distinct)
                (sort-by :key)
                (reverse)))))

(defn reset-locks!
  "Drop every memoized lock. For the :dapr/coordinator component's halt (and tests)
  — a fresh system starts with no device held."
  []
  (reset! locks* {}))
