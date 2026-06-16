(ns snow-white.roles
  "Pure functions for dealing roles, picking the Mayor, and resolving the winner.

  These are deliberately free of any lobby/atom/server concerns so they can be
  unit-tested and explored in the REPL in isolation. They take and return plain
  data.

  Teams:
  - :villager and :seer are on the Village team.
  - :werewolf is on the Wolf team.
  The Seer knows the word; the Wolves know the word and each other.")

(defn deal-roles
  "Given a player count, return a shuffled vector of role keywords.

  Mirrors the original: base deal is two villagers + one seer + one werewolf,
  a second werewolf is added when there are more than 6 players, and the rest
  are villagers."
  [player-count]
  (let [base   [:villager :villager :seer :werewolf]
        base   (if (> player-count 6) (conj base :werewolf) base)
        fill   (max 0 (- player-count (count base)))
        roles  (into base (repeat fill :villager))]
    (vec (shuffle (take player-count roles)))))

(defn assign-roles
  "Assign dealt roles to the given seq of player auth-ids.
  Returns a map of auth-id -> role keyword."
  [auth-ids]
  (zipmap auth-ids (deal-roles (count auth-ids))))

(defn choose-mayor
  "Pick a Mayor auth-id at random among players whose role is eligible.

  `eligibility` is a map like {:villager true :seer false :werewolf false}.
  Falls back to villagers, then to anyone, so a Mayor is always chosen
  (matching the original's fallbacks)."
  [roles-by-auth eligibility]
  (let [eligible (fn [pred]
                   (->> roles-by-auth
                        (filter (fn [[_ role]] (pred role)))
                        (map key)
                        seq))
        pool (or (eligible #(get eligibility % false))
                 (eligible #(= % :villager))
                 (keys roles-by-auth))]
    (when (seq pool)
      (rand-nth (vec pool)))))

(defn vote-result
  "Summarize a vote by selecting uniformly among tied top vote-getters.

  Returns nil when there are no votes yet; otherwise returns counts, tied leaders,
  the selected leader, and whether random tie resolution was needed. Keeping this
  as data lets the client show the exact post-game resolution instead of
  re-computing game logic."
  [mode votes]
  (let [counts (frequencies votes)]
    (when (seq counts)
      (let [top (apply max (vals counts))
            leaders (->> counts (filter #(= top (val %))) (map key) vec)
            selected (if (= 1 (count leaders)) (first leaders) (rand-nth leaders))]
        {:mode mode
         :counts counts
         :leaders leaders
         :selected selected
         :randomized? (< 1 (count leaders))}))))

(defn resolve-winner
  "Decide the winning team at the end of a game.

  Tied vote leaders are resolved by a uniform random sample among the tied top
  targets. The sampled target is then used exactly as a unique plurality target
  would be used."
  [{:keys [guessed? seer-auth werewolf-auths wolf-votes village-votes]}]
  (let [werewolf-auths (set werewolf-auths)]
    (if guessed?
      (let [r (vote-result :wolf wolf-votes)]
        (if (= (:selected r) seer-auth) :wolves :village))
      (let [r (vote-result :village village-votes)]
        (if (and r (werewolf-auths (:selected r))) :village :wolves)))))
