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

(defn- wolves-public? [lobby]
  (#{:word-guessed :end-game} (:game-state lobby)))

(defn- vote-targets [votes]
  (vec (if (map? votes) (vals votes) votes)))

(defn- seated-auths [lobby]
  (->> (:players lobby)
       (filter (fn [[_ p]] (game/seated? p)))
       (map key)))

(defn- seated-wolves [lobby]
  (filter #(game/seated? (get-in lobby [:players %])) (:werewolves lobby)))

(defn- village-vote-expected [lobby]
  (count (remove (set (:werewolves lobby)) (seated-auths lobby))))

(defn- wolf-vote-expected [lobby]
  (count (seated-wolves lobby)))

(defn- redact-player
  "Strip hidden role/migration facts unless the recipient may see them."
  [lobby recipient auth player]
  (let [reveal-role?
        (or (= auth recipient)                ; your own role
            (ended? lobby)                    ; post-game reveal
            (:public-role player)             ; late-seated public Villagers
            ;; Werewolves become public during the Seer-finding vote.
            (and (wolves-public? lobby) (= (:role player) :werewolf))
            ;; werewolves see each other during the game
            (and (= (get-in lobby [:players recipient :role]) :werewolf)
                 (= (:role player) :werewolf)))
        can-see-migration? (game/can-moderate? lobby recipient)]
    (cond-> (game/decorate-player lobby auth player)
      (not reveal-role?) (assoc :role nil)
      can-see-migration? (assoc :migration-token (get-in lobby [:auth->migration auth])))))

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
                                   (wolves-public? lobby)
                                   (= :werewolf (get-in lobby [:players recipient :role])))
                             (:werewolves lobby)
                             #{}))
        (assoc :village-votes (vote-targets (:village-votes lobby)))
        (assoc :village-vote-expected (village-vote-expected lobby))
        (assoc :wolf-vote-expected (wolf-vote-expected lobby))
        ;; wolf votes are secret until end-game.
        (assoc :wolf-votes (if reveal-secrets? (vote-targets (:wolf-votes lobby)) []))
        ;; tell the recipient their own private facts explicitly for convenience
        (assoc :you {:auth-id recipient
                     :migration-token (get-in lobby [:auth->migration recipient])
                     :role (get-in lobby [:players recipient :role])
                     :is-mayor (= recipient (:mayor lobby))
                     :can-moderate (game/can-moderate? lobby recipient)
                     :knows-word (knows-word? lobby recipient)
                     :village-vote (get-in lobby [:village-votes recipient])
                     :wolf-vote (get-in lobby [:wolf-votes recipient])}))))
