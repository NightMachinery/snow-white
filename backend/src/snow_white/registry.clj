(ns snow-white.registry
  "Holds the live lobbies and the WebSocket connections, and applies pure game
  transitions to lobby state.

  Design: lobbies are *embarrassingly parallel* — no game rule ever touches two
  lobbies — so each lobby gets its **own atom**. A thin top-level `registry` atom
  maps lobby-name -> lobby-atom and is only touched on create/destroy. Gameplay
  `swap!`s a single lobby-atom and therefore never contends with other lobbies.

  This namespace is the boundary between pure (`game.clj`) and impure (sockets):
  it owns mutation (`swap!`) but delegates *what* to compute to pure functions.

  Learning note: `swap!` takes a function and applies it to the current value
  atomically: `(swap! a f x y)` sets the atom to `(f @a x y)`, retrying on
  contention. Because `f` is pure, this is safe and easy to reason about."
  (:require [snow-white.game :as game]))

;; lobby-name (string) -> atom holding a lobby map
(defonce registry (atom {}))

;; channel -> {:auth-id .. :lobby ..}    (who is on each socket)
(defonce conns (atom {}))

;; lobby-name -> epoch-ms when it last went empty (everyone offline). A lobby
;; that is currently occupied has no entry here. The reaper (below) deletes
;; lobbies that have stayed empty longer than `empty-ttl-ms`. This lets a room
;; survive everyone briefly disconnecting (page refresh, flaky wifi, a pause
;; between rounds) and even sit idle overnight, instead of vanishing the instant
;; the last socket closes.
(defonce emptied-at (atom {}))

(def empty-ttl-ms
  "How long a lobby with nobody online is kept before it is reaped."
  (* 14 24 60 60 1000)) ; 14 days

(def ^:private reap-period-ms
  "How often the reaper wakes up to look for expired lobbies."
  (* 60 60 1000)) ; hourly

;; ---------------------------------------------------------------------------
;; Lobby lifecycle
;; ---------------------------------------------------------------------------

(defn get-lobby-atom [name] (get @registry name))

(defn lobby-exists? [name] (contains? @registry name))

(defn create-lobby!
  "Create a new lobby owned by `owner-id`. Returns the lobby atom, or nil if the
  name is already taken / invalid."
  [owner-id name]
  (when (and owner-id (seq name) (not (lobby-exists? name)))
    (let [a (atom (game/new-lobby owner-id name))]
      (swap! registry assoc name a)
      ;; Start the retention clock immediately: a lobby created via HTTP but never
      ;; joined still gets reaped after the TTL. The first connect cancels it via
      ;; `mark-occupied!`.
      (swap! emptied-at assoc name (System/currentTimeMillis))
      a)))

(defn destroy-lobby! [name]
  (swap! registry dissoc name)
  (swap! emptied-at dissoc name))

;; ---------------------------------------------------------------------------
;; Empty-lobby retention (TTL) + reaper
;; ---------------------------------------------------------------------------

(defn mark-empty!
  "Record that `name` has just become empty (nobody online), starting its TTL
  clock. Idempotent: keeps the *earliest* empty time if already marked."
  [name now-ms]
  (swap! emptied-at update name (fn [t] (or t now-ms))))

(defn mark-occupied!
  "Cancel `name`'s TTL clock because someone is connected again."
  [name]
  (swap! emptied-at dissoc name))

(defn expired-lobbies
  "Names of lobbies that have been empty for longer than `empty-ttl-ms` as of
  `now-ms`. Pure read over the `emptied-at` snapshot."
  [now-ms]
  (->> @emptied-at
       (filter (fn [[_ t]] (>= (- now-ms t) empty-ttl-ms)))
       (map key)))

(defn reap-expired!
  "Destroy every lobby whose empty TTL has elapsed. Returns the names reaped."
  [now-ms]
  (let [stale (expired-lobbies now-ms)]
    (doseq [name stale] (destroy-lobby! name))
    stale))

(defonce ^:private reaper (atom nil))

(defn start-reaper!
  "Launch a background thread that periodically reaps expired empty lobbies.
  Safe to call repeatedly (no-op if already running). Returns the thread."
  []
  (or @reaper
      (let [t (Thread.
               (fn []
                 (try
                   (loop []
                     (Thread/sleep (long reap-period-ms))
                     (reap-expired! (System/currentTimeMillis))
                     (recur))
                   (catch InterruptedException _ nil)))
               "snow-white-lobby-reaper")]
        (.setDaemon t true)
        (.start t)
        (reset! reaper t)
        t)))

(defn stop-reaper! []
  (when-let [t @reaper]
    (.interrupt t)
    (reset! reaper nil)))

(defn update-lobby!
  "Apply pure `f` to the named lobby's state: `(swap! lobby-atom f args...)`.
  Returns the new lobby value, or nil if the lobby is gone."
  [name f & args]
  (when-let [a (get-lobby-atom name)]
    (apply swap! a f args)))

;; ---------------------------------------------------------------------------
;; Connection tracking
;; ---------------------------------------------------------------------------

(defn register-conn! [ch auth-id lobby]
  (swap! conns assoc ch {:auth-id auth-id :lobby lobby}))

(defn conn-info [ch] (get @conns ch))

(defn channels-in
  "All currently-open channels attached to `lobby`."
  [lobby]
  (->> @conns (filter (fn [[_ v]] (= lobby (:lobby v)))) (map key)))

(defn forget-conn!
  "Remove a channel. Returns its prior {:auth-id :lobby} info (or nil)."
  [ch]
  (let [info (get @conns ch)]
    (swap! conns dissoc ch)
    info))

(defn auth-still-online?
  "Is `auth-id` still connected on any *other* channel in `lobby`?"
  [lobby auth-id]
  (boolean
   (some (fn [[_ v]] (and (= lobby (:lobby v)) (= auth-id (:auth-id v))))
         @conns)))
