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
(def start-discard-tokens 5) ; shared Mayor discard budget
(def temp-mod-delay-ms (* 5 60 1000))

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
   :no-real-mod-since-ms nil
   :name-assignments {}                ; auth-id -> {:base-name :display-name :display-number}
   :used-numbers     {}                ; base-name -> #{numbers}
   :migration->auth  {}               ; migration-token -> auth-id
   :auth->migration  {}               ; auth-id -> migration-token
   :players          {}                ; auth-id -> player
   :seats            (zipmap seat-ids (repeat nil)) ; seat -> auth-id
   :settings         {:minutes 1 :seconds 0}
   :available-wordpacks (words/available-wordpacks)
   :selected-wordpacks (words/normalize-selection [words/default-wordpack-id])
   :mayor-eligibility {:villager true :seer false :werewolf true}
   :timer-minutes    1
   :pick-count       2
   :custom-word-mode false
   ;; --- configurable rules / economy (mod-settable) ---
   :max-tokens       start-tokens       ; configured yes/no budget size
   :max-maybe-tokens start-maybe-tokens ; configured maybe budget size
   :max-discard-tokens start-discard-tokens
   :shared-maybe-pool true              ; maybes draw from the main pool
   :soft-costs       true               ; so-close / way-off each cost 1 token
   :one-at-a-time    false              ; block new questions while one is pending
   :lock-seating     false              ; non-mods cannot seat/unseat themselves
   :game-state       :lobby
   ;; round state
   :mayor            nil               ; auth-id
   :preferred-mayor nil
   :seer             nil               ; auth-id
   :werewolves       #{}              ; set of auth-ids
   :words            []                ; candidate words shown to Mayor
   :chosen-word      nil
   :questions        []                ; pending questions (oldest answered first)
   :answered         []                ; answered/discarded history entries
   :question-log     []
   :so-close         nil
   :way-off          nil
   :correct          nil
   :tokens           start-tokens
   :maybe-tokens     start-maybe-tokens
   :discard-tokens   start-discard-tokens
   :round-started-at-ms nil
   :round-deadline-ms nil
   :village-votes    []                ; auth-ids voted for (find the wolf)
   :wolf-votes       []                ; auth-ids voted for (find the seer)
   :vote-result      nil
   :winner           nil})

;; ---------------------------------------------------------------------------
;; Player / seat helpers (pure)
;; ---------------------------------------------------------------------------

(defn seated?
  "A seated player is a game participant, even when temporarily offline.
  Mods explicitly bench absent players to remove them from participation."
  [player]
  (boolean (and player (:seat player)
                (not (:spectator player)))))

(defn seated-count [lobby]
  (->> lobby :players vals (filter seated?) count))

(defn active?
  "An active player is online, seated, and not spectating."
  [player]
  (boolean (and (seated? player) (:online player))))

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

(defn- release-name
  "Free an auth-id's current room-scoped display number before a rename."
  [lobby auth-id]
  (if-let [{:keys [base-name display-number]} (get-in lobby [:name-assignments auth-id])]
    (-> lobby
        (update-in [:used-numbers base-name] disj display-number)
        (update :used-numbers (fn [m] (if (empty? (get m base-name)) (dissoc m base-name) m)))
        (update :name-assignments dissoc auth-id))
    lobby))

(defn rename-player
  "Rename yourself within a lobby, preserving identity, seat, role, and mod data."
  [lobby auth-id base-name]
  (if (get-in lobby [:players auth-id])
    (let [[lobby a] (assign-name (release-name lobby auth-id) auth-id base-name)]
      (update-in lobby [:players auth-id] merge a))
    lobby))

(defn- ensure-migration
  "Make sure `auth-id` has a migration token (idempotent)."
  [lobby auth-id]
  (if (get-in lobby [:auth->migration auth-id])
    lobby
    (let [tok (ids/token)]
      (-> lobby
          (assoc-in [:auth->migration auth-id] tok)
          (assoc-in [:migration->auth tok] auth-id)))))

(defn auth-for-migration
  "Resolve a room-scoped migration token to its auth-id, or nil if invalid."
  [lobby migration-token]
  (when (seq (str migration-token))
    (get-in lobby [:migration->auth migration-token])))

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
             (< (seated-count lobby) max-active-players)
             (first-free-seat lobby))
      (seat-player lobby auth-id)
      lobby)))

;; ---------------------------------------------------------------------------
;; Moderation (pure predicates)
;; ---------------------------------------------------------------------------

(defn online-real-mod?
  "Is the owner or any promoted real mod currently online?"
  [lobby]
  (boolean
   (some (fn [[auth p]]
           (and (:online p)
                (or (= auth (:owner-id lobby)) (contains? (:mods lobby) auth))))
         (:players lobby))))

(defn real-mod?
  "Owner and promoted mods are real mods. Temp mods are intentionally separate."
  [lobby auth-id]
  (boolean (or (= auth-id (:owner-id lobby))
               (contains? (:mods lobby) auth-id))))

(defn active-temp-mod?
  [lobby auth-id]
  (and (not (online-real-mod? lobby))
       (:active-temp-mod lobby)
       (contains? (:temp-mods lobby) auth-id)))

(defn can-moderate?
  "May `auth-id` perform moderator actions? Owner, promoted mods, and the
  currently-active temp mod (only when no real mod is online) all qualify."
  [lobby auth-id]
  (boolean (and auth-id (or (real-mod? lobby auth-id)
                            (active-temp-mod? lobby auth-id)))))

(defn promote-mod
  "Promote `target-auth` according to requester power.

  Owner and promoted real mods create real mods. The active temp mod has full mod
  powers, but anyone they promote is a temp mod, not a real mod."
  [lobby requester target-auth]
  (cond
    (or (nil? target-auth)
        (= target-auth (:owner-id lobby))
        (nil? (get-in lobby [:players target-auth]))
        (not (can-moderate? lobby requester)))
    lobby

    (active-temp-mod? lobby requester)
    (-> lobby
        (update :temp-mods conj target-auth)
        (assoc-in [:mod-promoters target-auth] requester))

    (real-mod? lobby requester)
    (-> lobby
        (update :mods conj target-auth)
        (assoc-in [:mod-promoters target-auth] requester))

    :else lobby))

(defn demote-mod
  "Demote a promoted mod/temp mod. Owner can demote anyone except themselves;
  other moderators can only demote people they promoted. Owner never transfers."
  [lobby requester target-auth]
  (cond
    (or (= target-auth (:owner-id lobby))
        (nil? target-auth)
        (not (can-moderate? lobby requester)))
    lobby

    (or (= requester (:owner-id lobby))
        (= requester (get-in lobby [:mod-promoters target-auth])))
    (-> lobby
        (update :mods disj target-auth)
        (update :temp-mods disj target-auth)
        (update :mod-promoters dissoc target-auth)
        (update :active-temp-mod #(when (not= % target-auth) %)))

    :else lobby))

(defn refresh-temp-mods
  "Update temp-mod state for `now-ms`.

  If no real mod is online for five minutes, choose an active temp mod. Prefer a
  previously designated temp mod who is online; otherwise pick a random online
  player and remember them in `:temp-mods`. When a real mod is online, clear the
  active temp mod but keep `:temp-mods` for future outages."
  [lobby now-ms]
  (if (online-real-mod? lobby)
    (assoc lobby :active-temp-mod nil :no-real-mod-since-ms nil)
    (let [since (or (:no-real-mod-since-ms lobby) now-ms)
          lobby (assoc lobby :no-real-mod-since-ms since)]
      (if (and (nil? (:active-temp-mod lobby))
               (<= temp-mod-delay-ms (- now-ms since)))
        (let [online-auths (->> (:players lobby)
                                (filter (comp :online val))
                                (map key)
                                (remove #{(:owner-id lobby)})
                                vec)
              preferred (some (set online-auths) (:temp-mods lobby))
              chosen (or preferred (when (seq online-auths) (rand-nth online-auths)))]
          (cond-> lobby
            chosen (assoc :active-temp-mod chosen)
            chosen (update :temp-mods conj chosen)))
        lobby))))

(defn decorate-player
  "Add derived moderation flags to a player map for client display."
  [lobby auth player]
  (assoc player
         :is-owner     (= auth (:owner-id lobby))
         :is-mod       (contains? (:mods lobby) auth)
         :is-temp-mod  (contains? (:temp-mods lobby) auth)
         :can-moderate (can-moderate? lobby auth)))

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
            (and (nil? (:seat player)) (>= (seated-count lobby) max-active-players)))
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

(defn set-custom-word-mode
  "Toggle whether the Mayor types any word instead of choosing sampled words.
  Lobby-only so an in-progress round keeps its word source fixed."
  [lobby enabled]
  (if (= :lobby (:game-state lobby))
    (assoc lobby :custom-word-mode (boolean enabled))
    lobby))

(defn- get-either
  "Read keyword or string keys from nested Transit maps. The JS client sends
  top-level command keys as keywords, but nested object keys may arrive as
  strings depending on the Transit writer."
  [m k]
  (or (get m k) (get m (name k))))

(defn- contains-either?
  [m k]
  (or (contains? m k) (contains? m (name k))))

(defn set-mayor-eligibility [lobby roles]
  (let [e {:villager (boolean (get-either roles :villager))
           :seer (boolean (get-either roles :seer))
           :werewolf (boolean (get-either roles :werewolf))}
        e (if (some true? (vals e)) e (assoc e :villager true))]
    (assoc lobby :mayor-eligibility e)))

(defn set-budget
  "Set the configured token-budget sizes. Clamped to sane minimums. Applies to
  the next round; if set during the question round, also clamps live counters so
  the change takes effect immediately without exceeding the new maxes."
  [lobby budget]
  (let [tokens (get-either budget :tokens)
        maybe-tokens (get-either budget :maybe-tokens)
        discard-tokens (get-either budget :discard-tokens)
        mt  (when tokens (max 1 (long tokens)))
        mmt (when maybe-tokens (max 0 (long maybe-tokens)))
        mdt (when discard-tokens (max 0 (long discard-tokens)))
        lobby (cond-> lobby
                mt  (assoc :max-tokens mt)
                mmt (assoc :max-maybe-tokens mmt)
                mdt (assoc :max-discard-tokens mdt))]
    (if (= (:game-state lobby) :question-round)
      (cond-> lobby
        mt  (update :tokens min mt)
        mmt (update :maybe-tokens min mmt)
        mdt (update :discard-tokens min mdt))
      lobby)))

(defn set-rules
  "Toggle the configurable economy/flow rules. Only keys present in `m` change."
  [lobby m]
  (reduce (fn [l k]
            (if (contains-either? m k) (assoc l k (boolean (get-either m k))) l))
          lobby
          [:shared-maybe-pool :soft-costs :one-at-a-time :lock-seating]))

(defn set-wordpacks
  "Set the room wordpack selection. Wordpacks are lobby-only settings because a
  running round should keep the candidate bank it started with."
  [lobby selected]
  (if (= :lobby (:game-state lobby))
    (assoc lobby
           :available-wordpacks (words/available-wordpacks)
           :selected-wordpacks (words/normalize-selection selected))
    lobby))

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
  or no seat is free. During a live round, seating a no-role spectator makes them
  a public Villager so everyone understands they joined after the deal."
  [lobby target-auth]
  (let [player (get-in lobby [:players target-auth])]
    (if (and player
             (nil? (:seat player))
             (< (seated-count lobby) max-active-players)
             (first-free-seat lobby))
      (let [lobby (seat-player lobby target-auth)]
        (if (and (not= :lobby (:game-state lobby))
                 (nil? (get-in lobby [:players target-auth :role])))
          (update-in lobby [:players target-auth] merge {:role :villager
                                                         :public-role true})
          lobby))
      lobby)))

(defn mod-set-preferred-mayor
  "Remember a mod's preferred Mayor for the next deal. The preference is honored
  only if that player is active when the game starts."
  [lobby target-auth]
  (assoc lobby :preferred-mayor target-auth))

(defn- eligible-role?
  [eligibility role]
  (boolean (get eligibility role false)))

(defn- preferred-active?
  [lobby auth-id]
  (seated? (get-in lobby [:players auth-id])))

(defn- deal-roles-for-mayor
  "Deal roles, retrying briefly when a preferred active Mayor needs an eligible
  role. Falls back to the last deal if randomness cannot satisfy the preference."
  [active-auths eligibility preferred]
  (let [eligible? #(eligible-role? eligibility %)]
    (loop [tries 200
           last-deal nil]
      (let [roles-by-auth (roles/assign-roles active-auths)
            role (get roles-by-auth preferred)]
        (cond
          (or (nil? preferred) (eligible? role)) roles-by-auth
          (pos? tries) (recur (dec tries) roles-by-auth)
          :else last-deal)))))

;; ---------------------------------------------------------------------------
;; Game flow
;; ---------------------------------------------------------------------------

(defn start-game
  "Deal roles to seated players, choose a Mayor, draw candidate words, and move
  to :mayor-pick. Returns lobby unchanged if fewer than 4 seated players."
  [lobby]
  (let [seated-auths (->> (:players lobby) (filter (comp seated? val)) (map key) vec)]
    (if (< (count seated-auths) 4)
      lobby
      (let [selected-wordpacks (words/normalize-selection (:selected-wordpacks lobby))
            preferred (when (preferred-active? lobby (:preferred-mayor lobby)) (:preferred-mayor lobby))
            roles-by-auth (deal-roles-for-mayor seated-auths (:mayor-eligibility lobby) preferred)
            mayor (if (and preferred (eligible-role? (:mayor-eligibility lobby) (roles-by-auth preferred)))
                    preferred
                    (roles/choose-mayor roles-by-auth (:mayor-eligibility lobby)))
            seer  (some (fn [[a r]] (when (= r :seer) a)) roles-by-auth)
            wolves (->> roles-by-auth (filter #(= :werewolf (val %))) (map key) set)
            lobby (reduce (fn [l a]
                            (-> l
                                (assoc-in [:players a :role] (roles-by-auth a))
                                (assoc-in [:players a :mayor] (= a mayor))))
                          lobby seated-auths)]
        (assoc lobby
               :game-state :mayor-pick
               :mayor mayor
               :seer seer
               :werewolves wolves
               :selected-wordpacks selected-wordpacks
               ;; (re)load budgets from configured maxes
               :tokens (:max-tokens lobby start-tokens)
               :maybe-tokens (:max-maybe-tokens lobby start-maybe-tokens)
               :discard-tokens (:max-discard-tokens lobby start-discard-tokens)
               :question-log []
               :vote-result nil
               :round-started-at-ms nil
               :round-deadline-ms nil
               :words (if (:custom-word-mode lobby)
                        []
                        (words/random-words (:pick-count lobby) selected-wordpacks)))))))

(defn mayor-pick
  "Mayor commits to the secret word and the question round begins. In normal
  mode the word must be one of the sampled candidates; in custom-word mode the
  Mayor may enter any non-blank trimmed word or phrase."
  ([lobby auth-id word]
   (mayor-pick lobby auth-id word (System/currentTimeMillis)))
  ([lobby auth-id word now-ms]
   (let [word (str/trim (str word))]
     (if (and (= (:game-state lobby) :mayor-pick)
              (= auth-id (:mayor lobby))
              (seq word)
              (or (:custom-word-mode lobby)
                  (some #{word} (:words lobby))))
       (assoc lobby
              :chosen-word word
              :game-state :question-round
              :round-started-at-ms now-ms
              :round-deadline-ms (+ now-ms (* (:timer-minutes lobby) 60 1000)))
       lobby))))

(defn- normalize-guess [s]
  (-> (str (or s ""))
      str/trim
      (str/replace #"\s+" " ")
      (str/replace #"[?!\.]+$" "")
      str/lower-case))

(defn- exact-match? [lobby text]
  (and (seq (:chosen-word lobby))
       (= (normalize-guess text) (normalize-guess (:chosen-word lobby)))))

(defn- log-question [lobby q answer extra]
  (let [entry (merge q {:answer answer} extra)]
    (-> lobby
        (update :answered conj entry)
        (update :question-log conj entry))))

(defn- has-pending?
  "Does `auth-id` already have an unanswered question in the queue?"
  [lobby auth-id]
  (boolean (some #(= auth-id (:auth-id %)) (:questions lobby))))

(defn ask-question
  "Enqueue a yes/no question from a player during the question round.
  Rules: a player may have at most ONE pending (unanswered) question at a time;
  if `:one-at-a-time` is on, no new question may be added while ANY is pending.
  A text that exactly matches the chosen word auto-records as Correct."
  [lobby auth-id text]
  (let [text (str/trim (str text))
        q {:auth-id auth-id
           :name (get-in lobby [:players auth-id :display-name])
           :text text}]
    (if (and (= (:game-state lobby) :question-round)
             (seq text)
             (not= auth-id (:mayor lobby))
             (not (has-pending? lobby auth-id))
             (not (and (:one-at-a-time lobby)
                       (seq (:questions lobby)))))
      (if (exact-match? lobby text)
        (-> lobby
            (update-in [:players auth-id :tokens :correct] (fnil conj []) q)
            (log-question q :correct {:auto? true})
            (assoc :correct q :game-state :word-guessed))
        (update lobby :questions conj q))
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

(defn- current-question [lobby]
  (first (:questions lobby)))

(defn- drop-current-question [lobby]
  (update lobby :questions subvec-rest))

(defn answer-question
  "Mayor answers the oldest queued question with one of `answer-types`.
  Spends the configurable token economy and advances state as needed."
  [lobby auth-id answer]
  (let [q (current-question lobby)]
    (cond
      (or (not= (:game-state lobby) :question-round)
          (not= auth-id (:mayor lobby))
          (not (answer-types answer))
          (nil? q))
      lobby

      (= answer :discard)
      (if (pos? (:discard-tokens lobby 0))
        (-> lobby
            (update :discard-tokens dec)
            (log-question q :discard {:discarded-by :mayor})
            drop-current-question)
        lobby)

      :else
      (let [asker (:auth-id q)
            lobby (-> lobby
                      (update-in [:players asker :tokens answer] (fnil conj []) q)
                      (log-question q answer {})
                      drop-current-question)
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

(defn discard-own-question
  "Let a player withdraw their own unanswered question for free, keeping it in the log."
  [lobby auth-id]
  (if (= (:game-state lobby) :question-round)
    (let [q (some #(when (= (:auth-id %) auth-id) %) (:questions lobby))]
      (if q
        (-> lobby
            (update :questions (fn [qs] (vec (remove #(= (:auth-id %) auth-id) qs))))
            (log-question q :discard {:discarded-by :self}))
        lobby))
    lobby))

(defn timeout
  "Timer expired during the question round."
  [lobby]
  (if (= (:game-state lobby) :question-round)
    (assoc lobby :game-state :out-of-time)
    lobby))

(defn- store-vote-result [lobby mode]
  (case mode
    :village (assoc lobby :vote-result (roles/vote-result :village (:village-votes lobby)))
    :wolf    (assoc lobby :vote-result (roles/vote-result :wolf (:wolf-votes lobby)))))

(defn village-vote
  "A seated player votes for a suspected wolf (used when the word was NOT
  guessed). When everyone seated has voted, resolve the end."
  [lobby voter-auth target-auth]
  (if (and (#{:out-of-time :out-of-tokens} (:game-state lobby))
           (seated? (get-in lobby [:players voter-auth])))
    (let [lobby (update lobby :village-votes conj target-auth)
          ;; everyone seated votes in the village round, including offline players
          expected (seated-count lobby)]
      (if (>= (count (:village-votes lobby)) expected)
        (-> lobby (store-vote-result :village) (assoc :game-state :end-game))
        lobby))
    lobby))

(defn wolf-vote
  "A werewolf secretly votes for the suspected seer (used when the word WAS
  guessed). When all wolves have voted, resolve the end."
  [lobby voter-auth target-auth]
  (if (and (= (:game-state lobby) :word-guessed)
           (contains? (:werewolves lobby) voter-auth)
           (seated? (get-in lobby [:players voter-auth])))
    (let [lobby (update lobby :wolf-votes conj target-auth)]
      (if (>= (count (:wolf-votes lobby)) (count (:werewolves lobby)))
        (-> lobby (store-vote-result :wolf) (assoc :game-state :end-game))
        lobby))
    lobby))

(defn finish-vote
  "Moderator shortcut: end the current voting stage using currently cast votes."
  ([lobby]
   (cond
     (#{:out-of-time :out-of-tokens} (:game-state lobby)) (finish-vote lobby :village)
     (= :word-guessed (:game-state lobby)) (finish-vote lobby :wolf)
     :else lobby))
  ([lobby mode]
   (case mode
     :village (if (#{:out-of-time :out-of-tokens} (:game-state lobby))
                (-> lobby (store-vote-result :village) (assoc :game-state :end-game))
                lobby)
     :wolf (if (= :word-guessed (:game-state lobby))
             (-> lobby (store-vote-result :wolf) (assoc :game-state :end-game))
             lobby)
     lobby)))

(defn finalize
  "Compute and store the winning team. Idempotent."
  [lobby]
  (if (and (= (:game-state lobby) :end-game) (nil? (:winner lobby)))
    (let [lobby (cond
                  (and (:correct lobby) (nil? (:vote-result lobby))) (store-vote-result lobby :wolf)
                  (nil? (:vote-result lobby)) (store-vote-result lobby :village)
                  :else lobby)
          selected (get-in lobby [:vote-result :selected])
          winner (if (:correct lobby)
                   (if (= selected (:seer lobby)) :wolves :village)
                   (if ((set (:werewolves lobby)) selected) :village :wolves))]
      (assoc lobby :winner winner))
    lobby))

(defn reset-game
  "Return everyone to the lobby, clearing round state but keeping seats,
  identities, settings and moderation."
  [lobby]
  (let [players (reduce-kv
                 (fn [m a p]
                   (assoc m a (-> p
                                     (assoc :role nil :mayor false
                                            :tokens {:yes [] :no [] :maybe []
                                                     :so-close [] :way-off [] :correct []})
                                     (dissoc :public-role))))
                 {} (:players lobby))]
    (assoc lobby
           :players players
           :game-state :lobby
           :mayor nil :seer nil :werewolves #{}
           :words [] :chosen-word nil
           :questions [] :answered [] :question-log []
           :so-close nil :way-off nil :correct nil
           :tokens (:max-tokens lobby start-tokens)
           :maybe-tokens (:max-maybe-tokens lobby start-maybe-tokens)
           :discard-tokens (:max-discard-tokens lobby start-discard-tokens)
           :round-started-at-ms nil :round-deadline-ms nil
           :village-votes [] :wolf-votes [] :vote-result nil :winner nil
           :settings {:minutes (:timer-minutes lobby) :seconds 0})))
