(ns snow-white.game
  "The pure heart of Snow White.

  A *lobby* is a plain immutable map. Every function here has the shape
  `(f lobby & args) -> lobby` (or returns the same lobby unchanged when an action
  is not allowed). There is no atom, socket, or I/O in this namespace — that lives
  at the edges (registry.clj / server.clj). This is what makes the game trivially
  testable and explorable in the REPL.

  Game state machine (`:game-state`):
    :lobby -> :mayor-pick -> :question-round
          -> (:word-guessed | :out-of-tokens | :out-of-time) -> :end-game

  Learning notes are sprinkled throughout; see docs/backend-clojure.md for the
  guided tour."
  (:require [clojure.string :as str]
            [snow-white.roles :as roles]
            [snow-white.words :as words]
            [snow-white.ids :as ids]))

(defn- subvec-rest
  "Drop the first element of a vector, keeping it a vector."
  [v]
  (if (seq v) (subvec (vec v) 1) []))

;; ---------------------------------------------------------------------------
;; Constants
;; ---------------------------------------------------------------------------

(def max-active-players 20)
(def max-inactive-players 200)
(def start-tokens 36)        ; shared yes/no budget
(def start-maybe-tokens 12)  ; shared "maybe" budget

(def seat-ids
  "Stable seat keywords :seat-1 .. :seat-20."
  (mapv #(keyword (str "seat-" (inc %))) (range max-active-players)))

(def seat-colors
  "A seat -> hex color map, reusing the original game's 20-color palette."
  (zipmap seat-ids
          ["#E6474E" "#F18E35" "#F5D74C" "#54B877" "#55BFDB"
           "#164186" "#582C71" "#D564D8" "#71362E" "#333333"
           "#B84A62" "#2F7D8C" "#9B7E2D" "#5B8C2F" "#8B5FBF"
           "#C45A2C" "#2C5AC4" "#6A6A6A" "#A33FA3" "#3FA36B"]))

(def answer-types
  "Valid Mayor answers to a question and how they spend the token economy."
  #{:yes :no :maybe :so-close :way-off :correct :discard})

;; ---------------------------------------------------------------------------
;; Lobby & player construction
;; ---------------------------------------------------------------------------

(defn new-player
  "A fresh player record. Players start as spectators (no seat) and are
  auto-seated on join when the game is in the :lobby phase and room remains."
  [{:keys [auth-id base-name display-name display-number]}]
  {:auth-id        auth-id
   :base-name      base-name
   :display-name   display-name
   :display-number display-number
   :online         true
   :spectator      true
   :seat           nil
   :color          nil
   :role           nil
   :mayor          false
   ;; per-answer history of questions this player asked, bucketed by answer
   :tokens         {:yes [] :no [] :maybe [] :so-close [] :way-off [] :correct []}})

(defn new-lobby
  "Create an empty lobby owned by `owner-id`."
  [owner-id name]
  {:name             name
   :owner-id         owner-id          ; immutable owner
   :mods             #{}               ; promoted moderators (auth-ids)
   :temp-mods        #{}               ; auto-elected fallback mods
   :mod-promoters    {}                ; who promoted whom (for demote rules)
   :active-temp-mod  nil
   :name-assignments {}                ; auth-id -> {:base-name :display-name :display-number}
   :used-numbers     {}                ; base-name -> #{numbers}
   :migration->auth  {}               ; migration-token -> auth-id
   :auth->migration  {}               ; auth-id -> migration-token
   :players          {}                ; auth-id -> player
   :seats            (zipmap seat-ids (repeat nil)) ; seat -> auth-id
   :settings         {:minutes 1 :seconds 0}
   :mayor-eligibility {:villager true :seer false :werewolf false}
   :timer-minutes    1
   :pick-count       2
   ;; --- configurable rules / economy (mod-settable) ---
   :max-tokens       start-tokens       ; configured yes/no budget size
   :max-maybe-tokens start-maybe-tokens ; configured maybe budget size
   :shared-maybe-pool true              ; maybes draw from the main pool
   :soft-costs       true               ; so-close / way-off each cost 1 token
   :one-at-a-time    false              ; block new questions while one is pending
   :lock-seating     false              ; non-mods cannot seat/unseat themselves
   :game-state       :lobby
   ;; round state
   :mayor            nil               ; auth-id
   :seer             nil               ; auth-id
   :werewolves       #{}              ; set of auth-ids
   :words            []                ; candidate words shown to Mayor
   :chosen-word      nil
   :questions        []                ; pending questions (FIFO)
   :answered         []                ; [{:q ... :answer ...}]
   :so-close         nil
   :way-off          nil
   :correct          nil
   :tokens           start-tokens
   :maybe-tokens     start-maybe-tokens
   :village-votes    []                ; auth-ids voted for (find the wolf)
   :wolf-votes       []                ; auth-ids voted for (find the seer)
   :winner           nil})

;; ---------------------------------------------------------------------------
;; Player / seat helpers (pure)
;; ---------------------------------------------------------------------------

(defn active?
  "An active player is online, seated, and not spectating."
  [player]
  (boolean (and player (:online player) (:seat player)
                (not (:spectator player)))))

(defn active-count [lobby]
  (->> lobby :players vals (filter active?) count))

(defn inactive-count [lobby]
  (->> lobby :players vals
       (filter #(or (:spectator %) (not (:seat %))))
       count))

(defn first-free-seat [lobby]
  (some (fn [s] (when (nil? (get-in lobby [:seats s])) s)) seat-ids))

(defn- normalize-name [s]
  (let [t (-> (str (or s "Player")) (str/trim)
              (str/replace #"\s+" " "))]
    (if (str/blank? t) "Player" t)))

(defn- assign-name
  "Resolve a stable room-scoped display name for `auth-id`, appending a number
  when the base name is already taken. Returns [lobby assignment]."
  [lobby auth-id base-name]
  (if-let [a (get-in lobby [:name-assignments auth-id])]
    [lobby a]
    (let [norm (normalize-name base-name)
          used (get-in lobby [:used-numbers norm] #{})
          n    (first (remove used (iterate inc 1)))
          disp (if (= n 1) norm (str norm " " n))
          a    {:base-name norm :display-name disp :display-number n}]
      [(-> lobby
           (update-in [:used-numbers norm] (fnil conj #{}) n)
           (assoc-in [:name-assignments auth-id] a))
       a])))

(defn- ensure-migration
  "Make sure `auth-id` has a migration token (idempotent)."
  [lobby auth-id]
  (if (get-in lobby [:auth->migration auth-id])
    lobby
    (let [tok (ids/token)]
      (-> lobby
          (assoc-in [:auth->migration auth-id] tok)
          (assoc-in [:migration->auth tok] auth-id)))))

(defn seat-player
  "Seat `auth-id` at `seat` (defaulting to first free). Returns lobby unchanged
  if the seat is taken by someone else or no seat is available."
  ([lobby auth-id] (seat-player lobby auth-id (first-free-seat lobby) nil))
  ([lobby auth-id seat color]
   (let [player  (get-in lobby [:players auth-id])
         taken-by (get-in lobby [:seats seat])]
     (if (or (nil? player) (nil? seat)
             (and taken-by (not= taken-by auth-id)))
       lobby
       (let [prev (:seat player)]
         (cond-> lobby
           (and prev (not= prev seat)) (assoc-in [:seats prev] nil)
           true (assoc-in [:seats seat] auth-id)
           true (update-in [:players auth-id] merge
                           {:seat seat
                            :color (or color (seat-colors seat))
                            :spectator false})))))))

(defn auto-seat
  "Auto-seat a player when in lobby phase and capacity remains."
  [lobby auth-id]
  (let [player (get-in lobby [:players auth-id])]
    (if (and (= :lobby (:game-state lobby))
             player (nil? (:seat player))
             (< (active-count lobby) max-active-players)
             (first-free-seat lobby))
      (seat-player lobby auth-id)
      lobby)))

;; ---------------------------------------------------------------------------
;; Moderation (pure predicates)
;; ---------------------------------------------------------------------------

(defn online-real-mod?
  "Is the owner or any promoted mod currently online?"
  [lobby]
  (boolean
   (some (fn [[auth p]]
           (and (:online p)
                (or (= auth (:owner-id lobby)) (contains? (:mods lobby) auth))))
         (:players lobby))))

(defn can-moderate?
  "May `auth-id` perform moderator actions? Owner, promoted mods, and the
  currently-active temp mod (only when no real mod is online) all qualify."
  [lobby auth-id]
  (boolean
   (and auth-id
        (or (= auth-id (:owner-id lobby))
            (contains? (:mods lobby) auth-id)
            (and (not (online-real-mod? lobby))
                 (= auth-id (:active-temp-mod lobby)))))))

(defn decorate-player
  "Add derived moderation flags to a player map for client display."
  [lobby auth player]
  (let [real-mod? (online-real-mod? lobby)]
    (assoc player
           :is-owner     (= auth (:owner-id lobby))
           :is-mod       (contains? (:mods lobby) auth)
           :is-temp-mod  (contains? (:temp-mods lobby) auth)
           :can-moderate (can-moderate? lobby auth))))

;; ---------------------------------------------------------------------------
;; Joining / leaving
;; ---------------------------------------------------------------------------

(defn join
  "Add or refresh a player in the lobby and auto-seat them. Returns lobby
  unchanged when inactive capacity is full for a brand-new player."
  [lobby auth-id base-name]
  (let [existing (get-in lobby [:players auth-id])]
    (if (and (nil? existing) (>= (inactive-count lobby) max-inactive-players))
      lobby
      (let [[lobby a] (assign-name lobby auth-id base-name)
            lobby     (ensure-migration lobby auth-id)
            player    (if existing
                        (merge existing {:online true
                                         :base-name (:base-name a)
                                         :display-name (:display-name a)
                                         :display-number (:display-number a)})
                        (new-player (assoc a :auth-id auth-id)))]
        (-> lobby
            (assoc-in [:players auth-id] player)
            (auto-seat auth-id))))))

(defn mark-offline
  "Mark a player offline (keeps their seat reserved). Returns lobby."
  [lobby auth-id]
  (if (get-in lobby [:players auth-id])
    (assoc-in lobby [:players auth-id :online] false)
    lobby))

(defn any-online?
  [lobby]
  (boolean (some :online (vals (:players lobby)))))

;; ---------------------------------------------------------------------------
;; Seating actions (player-initiated)
;; ---------------------------------------------------------------------------

(defn take-seat
  "Player claims a seat (toggle join). No-op when full. The `:lock-seating`
  policy for non-mods is enforced at the server edge, not here."
  [lobby auth-id seat color]
  (let [player (get-in lobby [:players auth-id])
        target (or seat (first-free-seat lobby))]
    (if (or (nil? player) (nil? target)
            (and (nil? (:seat player)) (>= (active-count lobby) max-active-players)))
      lobby
      (seat-player lobby auth-id target color))))

(defn spectate
  "Player leaves their seat to spectate."
  [lobby auth-id]
  (let [player (get-in lobby [:players auth-id])
        prev   (:seat player)]
    (if (nil? player)
      lobby
      (cond-> lobby
        prev (assoc-in [:seats prev] nil)
        true (update-in [:players auth-id] merge
                        {:spectator true :seat nil :color nil})))))

;; ---------------------------------------------------------------------------
;; Settings (mod-gated at the edge; pure here)
;; ---------------------------------------------------------------------------

(defn set-timer [lobby minutes]
  (assoc lobby :timer-minutes (max 0 (long minutes))
               :settings {:minutes (max 0 (long minutes)) :seconds 0}))

(defn set-pick-count [lobby n]
  (assoc lobby :pick-count (max 1 (long n))))

(defn set-mayor-eligibility [lobby {:keys [villager seer werewolf]}]
  (let [e {:villager (boolean villager)
           :seer (boolean seer)
           :werewolf (boolean werewolf)}
        e (if (some true? (vals e)) e (assoc e :villager true))]
    (assoc lobby :mayor-eligibility e)))

(defn set-budget
  "Set the configured token-budget sizes. Clamped to sane minimums. Applies to
  the next round; if set during the question round, also tops the live counters
  so the change takes effect immediately without exceeding the new maxes."
  [lobby {:keys [tokens maybe-tokens]}]
  (let [mt  (when tokens (max 1 (long tokens)))
        mmt (when maybe-tokens (max 0 (long maybe-tokens)))
        lobby (cond-> lobby
                mt  (assoc :max-tokens mt)
                mmt (assoc :max-maybe-tokens mmt))]
    (if (= (:game-state lobby) :question-round)
      (cond-> lobby
        mt  (update :tokens min mt)
        mmt (update :maybe-tokens min mmt))
      lobby)))

(defn set-rules
  "Toggle the configurable economy/flow rules. Only keys present in `m` change."
  [lobby m]
  (reduce (fn [l k]
            (if (contains? m k) (assoc l k (boolean (get m k))) l))
          lobby
          [:shared-maybe-pool :soft-costs :one-at-a-time :lock-seating]))

;; ---------------------------------------------------------------------------
;; Mod player-management powers (gated at the edge)
;; ---------------------------------------------------------------------------

(defn mod-unseat
  "A mod sidelines a player: free their seat and make them a spectator. They keep
  their identity and can be brought back with `mod-seat` (or re-seat themselves
  unless `:lock-seating` is on)."
  [lobby target-auth]
  (let [player (get-in lobby [:players target-auth])
        prev   (:seat player)]
    (if (nil? player)
      lobby
      (cond-> lobby
        prev (assoc-in [:seats prev] nil)
        true (update-in [:players target-auth] merge
                        {:spectator true :seat nil :color nil})))))

(defn mod-seat
  "A mod seats a player (bypasses `:lock-seating`). No-op if the table is full
  or no seat is free."
  [lobby target-auth]
  (let [player (get-in lobby [:players target-auth])]
    (if (and player
             (nil? (:seat player))
             (< (active-count lobby) max-active-players)
             (first-free-seat lobby))
      (seat-player lobby target-auth)
      lobby)))

;; ---------------------------------------------------------------------------
;; Game flow
;; ---------------------------------------------------------------------------

(defn start-game
  "Deal roles to active players, choose a Mayor, draw candidate words, and move
  to :mayor-pick. Returns lobby unchanged if fewer than 4 active players."
  [lobby]
  (let [active-auths (->> (:players lobby) (filter (comp active? val)) (map key) vec)]
    (if (< (count active-auths) 4)
      lobby
      (let [roles-by-auth (roles/assign-roles active-auths)
            mayor (roles/choose-mayor roles-by-auth (:mayor-eligibility lobby))
            seer  (some (fn [[a r]] (when (= r :seer) a)) roles-by-auth)
            wolves (->> roles-by-auth (filter #(= :werewolf (val %))) (map key) set)
            lobby (reduce (fn [l a]
                            (-> l
                                (assoc-in [:players a :role] (roles-by-auth a))
                                (assoc-in [:players a :mayor] (= a mayor))))
                          lobby active-auths)]
        (assoc lobby
               :game-state :mayor-pick
               :mayor mayor
               :seer seer
               :werewolves wolves
               ;; (re)load the token budget from the configured maxes
               :tokens (:max-tokens lobby start-tokens)
               :maybe-tokens (:max-maybe-tokens lobby start-maybe-tokens)
               :words (words/random-words (:pick-count lobby)))))))

(defn mayor-pick
  "Mayor commits to the secret word and the question round begins."
  [lobby auth-id word]
  (if (and (= (:game-state lobby) :mayor-pick)
           (= auth-id (:mayor lobby))
           (some #{word} (:words lobby)))
    (assoc lobby :chosen-word word :game-state :question-round)
    lobby))

(defn- has-pending?
  "Does `auth-id` already have an unanswered question in the queue?"
  [lobby auth-id]
  (boolean (some #(= auth-id (:auth-id %)) (:questions lobby))))

(defn ask-question
  "Enqueue a yes/no question from a player during the question round.
  Rules: a player may have at most ONE pending (unanswered) question at a time;
  if `:one-at-a-time` is on, no new question may be added while ANY is pending."
  [lobby auth-id text]
  (let [text (str/trim (str text))]
    (if (and (= (:game-state lobby) :question-round)
             (seq text)
             (not= auth-id (:mayor lobby))           ; the Mayor answers, doesn't ask
             (not (has-pending? lobby auth-id))       ; one pending per player
             (not (and (:one-at-a-time lobby)
                       (seq (:questions lobby)))))     ; optional global gate
      (update lobby :questions conj {:auth-id auth-id
                                     :name (get-in lobby [:players auth-id :display-name])
                                     :text text})
      lobby)))

(defn edit-question
  "Let the asker revise the text of their own pending (unanswered) question."
  [lobby auth-id text]
  (let [text (str/trim (str text))]
    (if (and (= (:game-state lobby) :question-round) (seq text))
      (update lobby :questions
              (fn [qs] (mapv (fn [q] (if (= (:auth-id q) auth-id)
                                       (assoc q :text text)
                                       q))
                             qs)))
      lobby)))

(defn- spend-token
  "Decrement the appropriate budget for `answer` given the lobby's economy
  settings. Yes/No always cost 1 from the main pool. Maybe costs 1 — from the
  main pool when `:shared-maybe-pool`, else from the separate maybe pool. So-close
  and Way-off cost 1 from the main pool only when `:soft-costs` is on. Correct and
  Discard are free."
  [lobby answer]
  (let [shared? (:shared-maybe-pool lobby)
        soft?   (:soft-costs lobby)
        main    #(update % :tokens dec)
        maybep  #(update % :maybe-tokens dec)]
    (case answer
      (:yes :no)         (main lobby)
      :maybe             (if shared? (main lobby) (maybep lobby))
      (:so-close :way-off) (if soft? (main lobby) lobby)
      lobby)))

(defn answer-question
  "Mayor answers the head-of-queue question with one of `answer-types`.
  Spends the configurable token economy and advances state as needed."
  [lobby auth-id answer]
  (let [q (first (:questions lobby))]
    (cond
      (or (not= (:game-state lobby) :question-round)
          (not= auth-id (:mayor lobby))
          (not (answer-types answer))
          (nil? q))
      lobby

      (= answer :discard)
      (update lobby :questions subvec-rest)

      :else
      (let [asker (:auth-id q)
            lobby (-> lobby
                      (update-in [:players asker :tokens answer] (fnil conj []) q)
                      (update :answered conj (assoc q :answer answer))
                      (update :questions subvec-rest))
            lobby (case answer
                    :correct  (assoc lobby :correct q :game-state :word-guessed)
                    :so-close (assoc lobby :so-close q)
                    :way-off  (assoc lobby :way-off q)
                    lobby)
            lobby (if (= answer :correct) lobby (spend-token lobby answer))]
        (if (and (not= (:game-state lobby) :word-guessed)
                 (<= (:tokens lobby) 0))
          (assoc lobby :game-state :out-of-tokens)
          lobby)))))

(defn timeout
  "Timer expired during the question round."
  [lobby]
  (if (= (:game-state lobby) :question-round)
    (assoc lobby :game-state :out-of-time)
    lobby))

(defn village-vote
  "An active player votes for a suspected wolf (used when the word was NOT
  guessed). When everyone on the village side has voted, resolve the end."
  [lobby voter-auth target-auth]
  (if (and (#{:out-of-time :out-of-tokens} (:game-state lobby))
           (active? (get-in lobby [:players voter-auth])))
    (let [lobby (update lobby :village-votes conj target-auth)
          ;; everyone active votes in the village round
          expected (active-count lobby)]
      (if (>= (count (:village-votes lobby)) expected)
        (assoc lobby :game-state :end-game)
        lobby))
    lobby))

(defn wolf-vote
  "A werewolf secretly votes for the suspected seer (used when the word WAS
  guessed). When all wolves have voted, resolve the end."
  [lobby voter-auth target-auth]
  (if (and (= (:game-state lobby) :word-guessed)
           (contains? (:werewolves lobby) voter-auth)
           (active? (get-in lobby [:players voter-auth])))
    (let [lobby (update lobby :wolf-votes conj target-auth)]
      (if (>= (count (:wolf-votes lobby)) (count (:werewolves lobby)))
        (assoc lobby :game-state :end-game)
        lobby))
    lobby))

(defn finalize
  "Compute and store the winning team. Idempotent."
  [lobby]
  (if (and (= (:game-state lobby) :end-game) (nil? (:winner lobby)))
    (assoc lobby :winner
           (roles/resolve-winner
            {:guessed? (boolean (:correct lobby))
             :seer-auth (:seer lobby)
             :werewolf-auths (:werewolves lobby)
             :wolf-votes (:wolf-votes lobby)
             :village-votes (:village-votes lobby)}))
    lobby))

(defn reset-game
  "Return everyone to the lobby, clearing round state but keeping seats,
  identities, settings and moderation."
  [lobby]
  (let [players (reduce-kv
                 (fn [m a p]
                   (assoc m a (assoc p
                                     :role nil :mayor false
                                     :tokens {:yes [] :no [] :maybe []
                                              :so-close [] :way-off [] :correct []})))
                 {} (:players lobby))]
    (assoc lobby
           :players players
           :game-state :lobby
           :mayor nil :seer nil :werewolves #{}
           :words [] :chosen-word nil
           :questions [] :answered []
           :so-close nil :way-off nil :correct nil
           :tokens (:max-tokens lobby start-tokens)
           :maybe-tokens (:max-maybe-tokens lobby start-maybe-tokens)
           :village-votes [] :wolf-votes [] :winner nil
           :settings {:minutes (:timer-minutes lobby) :seconds 0})))
