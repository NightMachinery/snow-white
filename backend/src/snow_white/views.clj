(ns snow-white.views
  "Per-client redaction: turn the full lobby state into the *view* a particular
  recipient is allowed to see. This is a correctness improvement over the
  original game, which broadcast secret roles and the chosen word to everyone.

  Who may know what:
  - The chosen word: the Mayor, the Seer, and the Werewolves (they 'know the
    magic word'). Everyone learns it once voting begins.
  - A player's role: only that player — until the game ends, when all roles and
    the seer/wolves are revealed for the post-mortem. Late mod-seated Villagers
    are marked public and shown to everyone.
  - Werewolves know each other; the Seer does not know the wolves.

  The view is still a plain map (just with secret fields removed/masked), so the
  Svelte client remains a pure function of whatever it receives."
  (:require [snow-white.game :as game]))

(defn- knows-word?
  [lobby auth-id]
  (let [role (get-in lobby [:players auth-id :role])]
    (or (= auth-id (:mayor lobby))
        (= role :seer)
        (= role :werewolf))))

(defn- ended? [lobby] (= (:game-state lobby) :end-game))

(defn- word-revealed? [lobby]
  (#{:word-guessed :out-of-time :out-of-tokens :end-game} (:game-state lobby)))

(defn- redact-player
  "Strip another player's hidden role unless the recipient may see it."
  [lobby recipient auth player]
  (let [reveal-role?
        (or (= auth recipient)                ; your own role
            (ended? lobby)                     ; post-game reveal
            (:public-role player)               ; late-seated public Villagers
            ;; werewolves see each other during the game
            (and (= (get-in lobby [:players recipient :role]) :werewolf)
                 (= (:role player) :werewolf)))]
    (cond-> (game/decorate-player lobby auth player)
      (not reveal-role?) (assoc :role nil))))

(defn lobby-view
  "Build the redacted lobby map to send to `recipient` (an auth-id)."
  [lobby recipient]
  (let [players (reduce-kv
                 (fn [m auth p] (assoc m auth (redact-player lobby recipient auth p)))
                 {} (:players lobby))
        reveal-secrets? (ended? lobby)
        show-word? (or (word-revealed? lobby) (knows-word? lobby recipient))]
    (-> lobby
        (assoc :players players)
        ;; mask the chosen word unless entitled
        (assoc :chosen-word (when show-word? (:chosen-word lobby)))
        ;; seer identity is end-game only. Wolves know their pack during play;
        ;; everyone sees wolves after the reveal.
        (assoc :seer (when reveal-secrets? (:seer lobby)))
        (assoc :werewolves (if (or reveal-secrets?
                                   (= :werewolf (get-in lobby [:players recipient :role])))
                             (:werewolves lobby)
                             #{}))
        ;; wolf votes are secret until end-game.
        (assoc :wolf-votes (if reveal-secrets? (:wolf-votes lobby) []))
        ;; tell the recipient their own private facts explicitly for convenience
        (assoc :you {:auth-id recipient
                     :role (get-in lobby [:players recipient :role])
                     :is-mayor (= recipient (:mayor lobby))
                     :can-moderate (game/can-moderate? lobby recipient)
                     :knows-word (knows-word? lobby recipient)}))))
