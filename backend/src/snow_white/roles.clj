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

(defn resolve-winner
  "Decide the winning team at the end of a game.

  Arguments are plain data so this is trivially testable:
  - `guessed?`      did the village guess the word?
  - `seer-auth`     auth-id of the seer
  - `werewolf-auths` set of werewolf auth-ids
  - `wolf-votes`    seq of auth-ids the wolves voted for (only when guessed?)
  - `village-votes` seq of auth-ids everyone voted for (only when not guessed?)

  Returns :village or :wolves.

  Rules (faithful to the original):
  - If the word WAS guessed: wolves try to identify the seer. If any wolf vote
    targets the seer, wolves win; otherwise village wins.
  - If the word was NOT guessed: everyone votes for a suspected wolf. Take the
    plurality (top vote count). If there is a unique top-voted player and they
    are a wolf, village wins; otherwise wolves win. (A tie at the top, or a
    non-wolf winner, means the village failed.)"
  [{:keys [guessed? seer-auth werewolf-auths wolf-votes village-votes]}]
  (let [werewolf-auths (set werewolf-auths)]
    (if guessed?
      (if (some #(= % seer-auth) wolf-votes) :wolves :village)
      (let [counts (frequencies village-votes)
            top    (when (seq counts) (apply max (vals counts)))
            leaders (when top (->> counts (filter #(= top (val %))) (map key)))]
        (if (and leaders
                 (= 1 (count leaders))
                 (werewolf-auths (first leaders)))
          :village
          :wolves)))))
