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
  - **Background** work (dapr.refresh) uses `with-device-background!`, and polls
    `queued?` at directory boundaries to release as soon as a *foreground* op is
    waiting, checkpointing its walk so it can resume where it left off.

  The two entry points differ only in whether they count as a waiter, and that
  distinction is what makes preemption safe under a *pool* of refresh workers.
  `queued?` used to be `ReentrantLock.hasQueuedThreads`, which is true when anyone
  at all waits — so two background walks on one device would preempt *each other*:
  B blocks, A check-points and releases, B acquires and immediately sees A waiting,
  so B check-points too, a check-point per directory and strictly worse than
  serial. Counting only foreground waiters means background contention degrades to
  ordinary blocking instead of thrash.

  dapr.refresh separately leases a device to one walk at a time, so in practice
  background walks do not contend at all. The count is what keeps that a *local*
  property rather than an invariant the whole system has to preserve: a background
  device query added later blocks, but cannot make a walk throw away its frontier.

  Which devices need arbitrating is a property of the device *type*, answered by
  the `dapr.device.format/arbitrate-access?` multimethod alongside the rest of the
  per-device metadata — so a new device type declares its own access model rather
  than this namespace keeping a list. Local file:// libraries opt out (and all share
  one device key), so they are never locked.

  Locks are process-global (memoized per key) because device access is reached
  through the java.nio provider SPI, which carries no component context — the same
  reason dapr.system's :dapr/devices component exists (see its docstring)."
  (:require [dapr.device.format :as device-format]
            [dapr.device.fs :as device-fs]
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

(defonce ^:private foreground-waiters*
  ;; device key -> how many foreground threads are currently blocked on that
  ;; device's lock. Maintained alongside the lock rather than read off it because
  ;; ReentrantLock cannot say *who* is waiting, and only a foreground waiter should
  ;; cost a background walk its place (see the ns docstring).
  (atom {}))

(defn- lock-for
  "The fair lock for device key `k`, creating it on first use."
  ^ReentrantLock [k]
  (or (get @locks* k)
      (get (swap! locks* (fn [m] (cond-> m (not (get m k)) (assoc k (ReentrantLock. true)))))
           k)))

(defn queued?
  "True when a *foreground* thread is waiting to acquire `device` — the background
  refresher's signal to check-point its walk and release it (see dapr.refresh).
  Always false for a device that needs no arbitration.

  Deliberately blind to background waiters: see the ns docstring for the
  check-point thrash that counting them causes."
  [device]
  (boolean (when (coordinated? device)
             (pos? (get @foreground-waiters* (:key device) 0)))))

(defn- with-lock!
  "Run `f` holding `device`'s lock, blocking until it is free. `foreground?` says
  whether the wait should be visible to `queued?` — i.e. whether a background
  holder should give the device up for it. Once acquired, the device-specific
  access wrapper owns any connection/session for exactly the duration of `f`."
  [device foreground? f]
  (if-not (coordinated? device)
    (device-fs/with-access! device f)
    (let [k                (:key device)
          ^ReentrantLock l (lock-for k)]
      (if-not foreground?
        (.lock l)
        ;; Counted from *before* the block until the moment it is acquired, so a
        ;; holder polling queued? sees the waiter for the whole of its wait. The
        ;; decrement is in a finally so a throw from .lock cannot strand a count
        ;; and make a device look permanently wanted.
        (do (swap! foreground-waiters* update k (fnil inc 0))
            (try (.lock l)
                 (finally (swap! foreground-waiters* update k dec)))))
      (try (device-fs/with-access! device f)
           (finally (.unlock l))))))

(defn with-device!
  "Run `f` holding `device`'s lock as a **foreground** operation: a background walk
  of the same device check-points and yields it. A device that needs no arbitration
  (see coordinated?) runs `f` directly. Reentrant: nesting the same device on one
  thread is safe. Returns f's value."
  [device f]
  (with-lock! device true f))

(defn with-device-background!
  "Run `f` holding `device`'s lock as **background** work (dapr.refresh's walk).
  Identical to `with-device!` except that waiting here does not preempt a
  background holder — it simply blocks, since two walks trading a device a
  directory at a time is worse than one finishing first."
  [device f]
  (with-lock! device false f))

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
  "Drop every memoized lock and waiter count. For the :dapr/coordinator component's
  halt (and tests) — a fresh system starts with no device held and none wanted."
  []
  (reset! locks* {})
  (reset! foreground-waiters* {}))
