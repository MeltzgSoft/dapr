(ns dapr.web.events
  "Server-pushed change notifications, over Server-Sent Events.

  What is pushed is a *hint*, never markup: `event: region-table` and an empty
  body, meaning \"the data behind the track table moved\". The element re-fetches
  itself from the fragment endpoint it already had, still carrying its own digest
  and its own sort/page. That split is deliberate:

  - the server keeps no per-client state beyond the open channels — it does not
    need to know what any client is showing, which is exactly what pushing HTML
    would force it to know (the table's markup depends on a sort and a page only
    the client has);
  - `/fragments/*` stays the single rendering path, so what the browser gets is
    the same thing the route tests exercise;
  - a client that misses a notification is not stranded — the elements keep a
    slow fallback timer (see dapr.ui.html/fallback-seconds), so a dropped stream
    degrades to the polling this replaced rather than to a frozen page.

  SSE rather than a WebSocket because every message travels one way: user actions
  are ordinary POSTs that answer with their own fragments. A socket would add a
  return channel nothing writes to, plus a ping/reconnect story the browser's own
  EventSource already handles.

  The state atom is written *very* often during a scan — progress lands every 64
  entries — so notifications are coalesced: a write only wakes the publisher,
  which then settles for `coalesce-millis` and sends one notification per region
  that actually changed over the whole burst."
  (:require [dapr.ui.digest :as digest]
            [org.httpkit.server :as http])
  (:import (java.util.concurrent ArrayBlockingQueue TimeUnit)))

(def default-timings
  "Fallback publisher timings, for anything resources/config.edn leaves unset.

  :coalesce-millis is how long the publisher lets a burst of state writes settle
  before digesting — long enough that a scan's progress updates collapse into one
  notification, short enough to stay imperceptible.

  :heartbeat-millis is the idle gap after which a comment line is sent. It keeps
  the connection from being reaped by anything in between, and — because a write
  to a dead channel is how a dead channel is noticed — it is also what prunes
  subscribers whose browser went away without closing cleanly."
  {:coalesce-millis  100
   :heartbeat-millis 25000})

(def ^:private heartbeat ": ping\n\n")

(def sse-headers
  {"Content-Type"      "text/event-stream; charset=utf-8"
   "Cache-Control"     "no-cache, no-transform"
   "Connection"        "keep-alive"
   ;; Belt and braces for any proxy an Electron shell might sit behind: an
   ;; event stream that gets buffered is an event stream that never arrives.
   "X-Accel-Buffering" "no"})

(defn region-digests
  "Every pollable region's digest for `state`, taken with no view parameters. What
  is broadcast is that a region's underlying data moved — not what any particular
  client should now render, which depends on a sort and page only that client
  knows."
  [state]
  (into {} (map (fn [r] [r (digest/digest state nil r)])) (digest/regions)))

(defn changed-regions
  "The regions whose digest differs between two snapshots. A region absent from
  `before` counts as changed."
  [before after]
  (into #{} (keep (fn [[region d]] (when (not= d (get before region)) region))) after))

(defn sse-message
  "One notification: the event name the elements listen for, and no data. The
  content is fetched, not pushed."
  [region]
  (str "event: region-" (name region) "\ndata: \n\n"))

(defn- publish!
  "Write `text` to every subscriber, dropping the ones that report themselves
  closed. A subscriber is a function of the text returning false when it can no
  longer be written to."
  [subscribers text]
  (let [dead (into #{} (remove (fn [send] (send text))) @subscribers)]
    (when (seq dead)
      (swap! subscribers #(reduce disj % dead)))))

(defn subscribe!
  "Register `send` — a function of a string, returning false once its connection
  is gone — and return a function that unregisters it."
  [{:keys [subscribers]} send]
  (swap! subscribers conj send)
  (fn [] (swap! subscribers disj send)))

(defn- publisher
  "The loop that turns state writes into notifications: wait to be woken, let the
  burst settle, then send one notification per region that actually moved. On an
  idle timeout it sends a heartbeat instead."
  [{:keys [state-atom subscribers wake running? coalesce-millis heartbeat-millis]} seen]
  (fn []
    (try
      (while @running?
        (let [woken? (some? (.poll ^ArrayBlockingQueue wake
                                   (long heartbeat-millis) TimeUnit/MILLISECONDS))]
          (when @running?
            (if woken?
              (do
                (Thread/sleep (long coalesce-millis))
                ;; Anything that arrived while settling is already in the state we
                ;; are about to digest, so its wake-up is spent.
                (.clear ^ArrayBlockingQueue wake)
                (let [now     (region-digests @state-atom)
                      changed (changed-regions @seen now)]
                  (reset! seen now)
                  (doseq [region changed]
                    (publish! subscribers (sse-message region)))))
              (publish! subscribers heartbeat)))))
      (catch InterruptedException _ nil))))

(defn start!
  "Watch `state-atom` and publish region notifications to subscribers. Returns the
  hub, which the SSE route subscribes to and stop! shuts down.

  `timings` overrides :coalesce-millis / :heartbeat-millis; the system supplies
  them from resources/config.edn, and anything absent falls back to
  default-timings."
  ([state-atom] (start! state-atom nil))
  ([state-atom timings]
   (let [hub    (assoc (merge default-timings timings)
                       :state-atom  state-atom
                       :subscribers (atom #{})
                       ;; Capacity one, and offer (never put): the queue is a
                       ;; doorbell, not a backlog. A write while one is already
                       ;; pending is dropped, which is the coalescing.
                       :wake        (ArrayBlockingQueue. 1)
                       :running?    (atom true))
         seen   (atom (region-digests @state-atom))
         worker (Thread. ^Runnable (publisher hub seen) "dapr-events")]
     (add-watch state-atom ::events
                (fn [_ _ _ _] (.offer ^ArrayBlockingQueue (:wake hub) :changed)))
     (doto worker (.setDaemon true) (.start))
     (assoc hub :worker worker))))

(defn stop!
  "Stop publishing and drop every subscriber."
  [{:keys [state-atom running? wake ^Thread worker subscribers] :as hub}]
  (when hub
    (remove-watch state-atom ::events)
    (reset! running? false)
    (.offer ^ArrayBlockingQueue wake :stop)
    (when worker (.join worker 1000))
    (reset! subscribers #{})))

(defn handler
  "GET /events: an event stream that stays open for the life of the page."
  [hub]
  (fn [request]
    (http/as-channel
     request
     {:on-open
      (fn [ch]
        ;; The first send opens the streaming response; every later one appends.
        (http/send! ch {:status 200 :headers sse-headers :body ": open\n\n"} false)
        (let [unsubscribe! (subscribe! hub (fn [text] (http/send! ch text false)))]
          (http/on-close ch (fn [_] (unsubscribe!)))))})))
