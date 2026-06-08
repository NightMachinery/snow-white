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
      a)))

(defn destroy-lobby! [name]
  (swap! registry dissoc name))

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
