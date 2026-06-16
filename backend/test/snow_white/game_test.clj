(ns snow-white.game-test
  (:require [clojure.test :refer [deftest is testing]]
            [snow-white.game :as g]
            [snow-white.words :as words]))

(defn- lobby-with-players
  "Build a lobby with n joined+seated players :p0..:pn-1, owned by :p0."
  [n]
  (reduce (fn [l i] (g/join l (keyword (str "p" i)) (str "Player" i)))
          (g/new-lobby :p0 "test")
          (range n)))

(deftest joining-and-seating
  (let [l (lobby-with-players 4)]
    (is (= 4 (count (:players l))))
    (testing "players auto-seat in lobby phase and become active"
      (is (= 4 (g/active-count l)))
      (is (every? :seat (vals (:players l)))))))

(deftest duplicate-names-get-numbers
  (let [l (-> (g/new-lobby :a "t")
              (g/join :a "Sam")
              (g/join :b "Sam"))]
    (is (= "Sam"   (get-in l [:players :a :display-name])))
    (is (= "Sam 2" (get-in l [:players :b :display-name])))))

(deftest spectate-frees-seat
  (let [l (-> (lobby-with-players 4) (g/spectate :p1))]
    (is (= 3 (g/active-count l)))
    (is (nil? (get-in l [:players :p1 :seat])))))

(deftest start-requires-four
  (is (= :lobby (:game-state (g/start-game (lobby-with-players 3)))))
  (is (= :mayor-pick (:game-state (g/start-game (lobby-with-players 4))))))

(deftest game-mode-defaults-and-normalizes
  (let [l (g/new-lobby :p0 "test")]
    (is (= :werewords (:game-mode l)))
    (is (= :classic (:game-mode (g/set-game-mode l "classic"))))
    (is (= :werewords (:game-mode (g/set-game-mode l :werewords))))
    (is (= :werewords (:game-mode (g/set-game-mode l "bogus"))))))

(deftest game-mode-is-lobby-only-setting
  (let [l (-> (lobby-with-players 4)
              (g/set-game-mode :classic)
              g/start-game)]
    (is (= :classic (:game-mode l)))
    (is (= :classic (:game-mode (g/set-game-mode l :werewords))))))

(deftest classic-starts-with-two-players-and-no-hidden-teams
  (let [l (-> (lobby-with-players 2)
              (g/set-game-mode :classic)
              g/start-game)]
    (is (= :mayor-pick (:game-state l)))
    (is (= :classic (:game-mode l)))
    (is (some? (:mayor l)))
    (is (nil? (:seer l)))
    (is (empty? (:werewolves l)))
    (is (every? #(= :villager (:role %)) (vals (:players l))))))

(deftest offline-seated-players-count-for-starting
  (let [l (-> (lobby-with-players 4)
              (g/mark-offline :p3)
              g/start-game)]
    (is (= :mayor-pick (:game-state l)))
    (is (some? (get-in l [:players :p3 :role]))
        "offline seated players are dealt in until a mod benches them")))

(deftest start-deals-roles-and-mayor
  (let [l (g/start-game (lobby-with-players 5))]
    (is (= :mayor-pick (:game-state l)))
    (is (some? (:mayor l)))
    (is (some? (:seer l)))
    (is (= 1 (count (:werewolves l))))
    (is (= 2 (count (:words l)))))) ; pick-count default is 2

(deftest full-game-word-guessed
  (let [l (g/start-game (lobby-with-players 5))
        mayor (:mayor l)
        word (first (:words l))
        ;; an asker who is not the mayor
        asker (first (remove #{mayor} (keys (:players l))))
        l (g/mayor-pick l mayor word)
        _ (is (= :question-round (:game-state l)))
        l (g/ask-question l asker "Is it alive?")
        l (g/answer-question l mayor :yes)
        _ (is (= (dec g/start-tokens) (:tokens l)))
        l (g/ask-question l asker "Is it the word?")
        l (g/answer-question l mayor :correct)]
    (is (= :word-guessed (:game-state l)))
    (is (some? (:correct l)))))

(deftest classic-correct-guess-ends-with-players-win
  (let [l (-> (lobby-with-players 2)
              (g/set-game-mode :classic)
              g/start-game)
        mayor (:mayor l)
        word (first (:words l))
        asker (first (remove #{mayor} (keys (:players l))))
        l (-> l
              (g/mayor-pick mayor word)
              (g/ask-question asker "Is this correct?")
              (g/answer-question mayor :correct)
              g/finalize)]
    (is (= :end-game (:game-state l)))
    (is (= :players (:winner l)))
    (is (nil? (:vote-result l)))))

(deftest classic-timeout-and-token-exhaustion-end-with-word-win
  (let [timed-out (let [l (-> (lobby-with-players 2)
                              (g/set-game-mode :classic)
                              g/start-game)]
                    (-> l
                        (g/mayor-pick (:mayor l) (first (:words l)))
                        g/timeout
                        g/finalize))
        out-of-tokens (let [l (-> (lobby-with-players 2)
                                  (g/set-game-mode :classic)
                                  (g/set-budget {:tokens 1})
                                  g/start-game)
                            mayor (:mayor l)
                            word (first (:words l))
                            asker (first (remove #{mayor} (keys (:players l))))]
                        (-> l
                            (g/mayor-pick mayor word)
                            (g/ask-question asker "No?")
                            (g/answer-question mayor :no)
                            g/finalize))]
    (is (= :end-game (:game-state timed-out)))
    (is (= :word (:winner timed-out)))
    (is (= :end-game (:game-state out-of-tokens)))
    (is (= :word (:winner out-of-tokens)))))

(deftest classic-votes-are-no-ops
  (let [l (-> (lobby-with-players 2)
              (g/set-game-mode :classic)
              g/start-game
              (assoc :game-state :word-guessed))
        timed-out (assoc l :game-state :out-of-time)]
    (is (= l (g/wolf-vote l :p0 :p1)))
    (is (= timed-out (g/village-vote timed-out :p0 :p1)))
    (is (= timed-out (g/finish-vote timed-out :village)))))

(deftest wolf-vote-resolves
  (let [l (-> (lobby-with-players 5) g/start-game)
        l (assoc l :game-state :word-guessed) ; force into guessed state
        wolf (first (:werewolves l))
        l (g/wolf-vote l wolf (:seer l))
        l (g/finalize l)]
    (is (= :end-game (:game-state l)))
    (is (= :wolves (:winner l)))))

(deftest village-vote-waits-for-offline-seated-players
  (let [l (-> (lobby-with-players 4)
              (g/mark-offline :p3)
              g/start-game
              (assoc :game-state :out-of-time))
        voters (take 3 (remove #{:p3} (keys (:players l))))
        l (reduce #(g/village-vote %1 %2 :p0) l voters)]
    (is (= :out-of-time (:game-state l))
        "offline seated players remain in the vote quorum")
    (is (= :end-game (:game-state (g/village-vote l :p3 :p0))))))

(deftest wolf-vote-waits-for-offline-seated-wolves
  (let [l (-> (lobby-with-players 8) g/start-game)
        offline-wolf (first (:werewolves l))
        online-wolf (first (remove #{offline-wolf} (:werewolves l)))
        l (-> l
              (g/mark-offline offline-wolf)
              (assoc :game-state :word-guessed)
              (g/wolf-vote online-wolf (:seer l)))]
    (is (= :word-guessed (:game-state l))
        "offline seated wolves remain in the wolf vote quorum")
    (is (= :end-game (:game-state (g/wolf-vote l offline-wolf (:seer l)))))))

(deftest reset-clears-round
  (let [l (-> (lobby-with-players 5) g/start-game (g/reset-game))]
    (is (= :lobby (:game-state l)))
    (is (nil? (:mayor l)))
    (is (empty? (:werewolves l)))
    (is (every? (complement :public-role) (vals (:players l))))
    (is (= 5 (count (filter :seat (vals (:players l)))))))) ; seats preserved

;; --- question-round rules ---------------------------------------------------

(defn- into-question-round
  "Start a 5-player game and pick the first word. Returns [lobby mayor askers]."
  []
  (let [l (g/start-game (lobby-with-players 5))
        mayor (:mayor l)
        l (g/mayor-pick l mayor (first (:words l)))
        askers (vec (remove #{mayor} (keys (:players l))))]
    [l mayor askers]))

(deftest one-pending-question-per-player
  (let [[l _ askers] (into-question-round)
        a (first askers)
        l (-> l (g/ask-question a "First?") (g/ask-question a "Second?"))]
    (is (= 1 (count (:questions l))) "a second pending question is rejected")
    (is (= "First?" (:text (first (:questions l)))))))

(deftest mayor-cannot-ask
  (let [[l mayor _] (into-question-round)
        l (g/ask-question l mayor "May I?")]
    (is (empty? (:questions l)))))

(deftest edit-own-pending-question
  (let [[l _ askers] (into-question-round)
        a (first askers)
        l (-> l (g/ask-question a "Typo?") (g/edit-question a "Fixed?"))]
    (is (= "Fixed?" (:text (first (:questions l)))))))

(deftest one-at-a-time-gate
  (let [[l _ askers] (into-question-round)
        l (g/set-rules l {:one-at-a-time true})
        l (-> l (g/ask-question (first askers) "A?")
                (g/ask-question (second askers) "B?"))]
    (is (= 1 (count (:questions l))) "no new question while one is pending")))

;; --- token economy ----------------------------------------------------------

(deftest soft-costs-default-on
  (let [[l mayor askers] (into-question-round)
        l (-> l (g/ask-question (first askers) "Close?")
                (g/answer-question mayor :so-close))]
    (is (= (dec g/start-tokens) (:tokens l)) "so-close spends a main token by default")))

(deftest soft-costs-off
  (let [[l mayor askers] (into-question-round)
        l (g/set-rules l {:soft-costs false})
        l (-> l (g/ask-question (first askers) "Close?")
                (g/answer-question mayor :way-off))]
    (is (= g/start-tokens (:tokens l)) "way-off is free when soft-costs off")))

(deftest shared-maybe-pool-default-on
  (let [[l mayor askers] (into-question-round)
        l (-> l (g/ask-question (first askers) "Maybe?")
                (g/answer-question mayor :maybe))]
    (is (= (dec g/start-tokens) (:tokens l)) "maybe spends the main pool by default")
    (is (= g/start-maybe-tokens (:maybe-tokens l)) "separate maybe pool untouched")))

(deftest separate-maybe-pool
  (let [[l mayor askers] (into-question-round)
        l (g/set-rules l {:shared-maybe-pool false})
        l (-> l (g/ask-question (first askers) "Maybe?")
                (g/answer-question mayor :maybe))]
    (is (= g/start-tokens (:tokens l)) "main pool untouched")
    (is (= (dec g/start-maybe-tokens) (:maybe-tokens l)) "maybe pool spent")))

(deftest configurable-budget-applied-at-start
  (let [l (-> (lobby-with-players 5)
              (g/set-budget {:tokens 5})
              g/start-game)]
    (is (= 5 (:tokens l)) "start-game loads the configured budget")))


(deftest nested-transit-string-keys-work-for-settings
  (let [l (-> (g/new-lobby :p0 "t")
              (g/set-mayor-eligibility {"villager" false "seer" false "werewolf" true})
              (g/set-budget {"tokens" 7 "maybe-tokens" 2 "discard-tokens" 4})
              (g/set-rules {"one-at-a-time" true}))]
    (is (= {:villager false :seer false :werewolf true} (:mayor-eligibility l)))
    (is (= 7 (:max-tokens l)))
    (is (= 2 (:max-maybe-tokens l)))
    (is (= 4 (:max-discard-tokens l)))
    (is (true? (:one-at-a-time l)))))

;; --- mod player management ---------------------------------------------------

(deftest mod-unseat-and-seat
  (let [l (lobby-with-players 5)
        l (g/mod-unseat l :p1)]
    (is (= 4 (g/active-count l)))
    (is (:spectator (get-in l [:players :p1])))
    (is (nil? (get-in l [:players :p1 :seat])))
    (let [l (g/mod-seat l :p1)]
      (is (= 5 (g/active-count l)))
      (is (some? (get-in l [:players :p1 :seat]))))))

(deftest mod-unseat-removes-offline-player-from-vote-quorum
  (let [l (-> (lobby-with-players 4)
              (g/mark-offline :p3)
              g/start-game
              (assoc :game-state :out-of-time)
              (g/mod-unseat :p3))
        voters (take 3 (remove #{:p3} (keys (:players l))))
        l (reduce #(g/village-vote %1 %2 :p0) l voters)]
    (is (= :end-game (:game-state l)))))


(deftest custom-word-mode-uses-mayor-entered-word
  (let [l (g/new-lobby :p0 "test")]
    (is (false? (:custom-word-mode l))))
  (let [l (-> (lobby-with-players 4)
              (g/set-custom-word-mode true)
              g/start-game)
        mayor (:mayor l)]
    (is (true? (:custom-word-mode l)))
    (is (= [] (:words l)) "custom mode does not sample wordpacks")
    (is (= :mayor-pick (:game-state l)))
    (let [picked (g/mayor-pick l mayor "  A Secret Phrase  " 1000)]
      (is (= :question-round (:game-state picked)))
      (is (= "A Secret Phrase" (:chosen-word picked))))
    (is (= :mayor-pick (:game-state (g/mayor-pick l mayor "   " 1000)))
        "blank custom words are rejected")))

(deftest normal-wordpack-mode-still-requires-candidate-word
  (let [l (-> (lobby-with-players 4) g/start-game)
        mayor (:mayor l)]
    (is (= :mayor-pick (:game-state (g/mayor-pick l mayor "not in the list" 1000))))))

(deftest custom-word-mode-is-lobby-only-setting
  (let [l (-> (lobby-with-players 4) g/start-game)]
    (is (false? (:custom-word-mode (g/set-custom-word-mode l true))))))

;; --- wordpacks --------------------------------------------------------------

(deftest new-lobby-defaults-to-snow-white-wordpack
  (let [l (g/new-lobby :p0 "test")]
    (is (= ["English_Snow_White_1"] (:selected-wordpacks l)))
    (is (seq (:available-wordpacks l)))))

(deftest set-wordpacks-normalizes-selection-in-lobby-only
  (let [packs [{:id "English_Snow_White_1" :name "Default" :word-count 1 :words ["snow"]}
               {:id "other" :name "Other" :word-count 1 :words ["wolf"]}]]
    (with-redefs [words/wordpacks packs]
      (let [l (g/new-lobby :p0 "test")]
        (is (= ["other" "English_Snow_White_1"]
               (:selected-wordpacks (g/set-wordpacks l ["other" "missing" "English_Snow_White_1" "other"]))))
        (is (= ["English_Snow_White_1"]
               (:selected-wordpacks (g/set-wordpacks l []))))
        (is (= (:selected-wordpacks l)
               (:selected-wordpacks (g/set-wordpacks (assoc l :game-state :mayor-pick) ["other"]))))))))

(deftest start-game-draws-from-selected-wordpack-union
  (let [packs [{:id "English_Snow_White_1" :name "Default" :word-count 2 :words ["snow" "apple"]}
               {:id "other" :name "Other" :word-count 2 :words ["wolf" "apple"]}]]
    (with-redefs [words/wordpacks packs
                  words/random-words (fn [n selected]
                                       (vec (take n (words/selected-words packs selected))))]
      (let [l (-> (lobby-with-players 4)
                  (g/set-pick-count 3)
                  (g/set-wordpacks ["other" "English_Snow_White_1"])
                  g/start-game)]
        (is (= ["wolf" "apple" "snow"] (:words l)))))))

(deftest start-game-normalizes-invalid-wordpack-selection
  (let [packs [{:id "English_Snow_White_1" :name "Default" :word-count 1 :words ["snow"]}]]
    (with-redefs [words/wordpacks packs
                  words/random-words (fn [n selected]
                                       (is (= ["English_Snow_White_1"] selected))
                                       (vec (repeat n "snow")))]
      (let [l (-> (lobby-with-players 4)
                  (assoc :selected-wordpacks ["missing"])
                  g/start-game)]
        (is (= ["English_Snow_White_1"] (:selected-wordpacks l)))
        (is (= ["snow" "snow"] (:words l)))))))

(deftest reset-preserves-wordpack-selection
  (let [packs [{:id "English_Snow_White_1" :name "Default" :word-count 1 :words ["snow"]}
               {:id "other" :name "Other" :word-count 1 :words ["wolf"]}]]
    (with-redefs [words/wordpacks packs]
      (let [l (-> (lobby-with-players 4)
                  (g/set-wordpacks ["other"])
                  g/start-game
                  g/reset-game)]
        (is (= ["other"] (:selected-wordpacks l)))))))

;; --- gameplay polish plan regressions ---------------------------------------

(deftest mayor-defaults-include-wolves-not-seer
  (is (= {:villager true :seer false :werewolf true}
         (:mayor-eligibility (g/new-lobby :owner "t")))))

(deftest rename-self-preserves-seat-and-resolves-collisions
  (let [l (-> (g/new-lobby :a "t")
              (g/join :a "Sam")
              (g/join :b "Pat")
              (g/rename-player :b "Sam"))]
    (is (= "Sam" (get-in l [:players :a :display-name])))
    (is (= "Sam 2" (get-in l [:players :b :display-name])))
    (is (some? (get-in l [:players :b :seat])))))

(deftest preferred-mayor-is-used-when-active-and-eligible
  (with-redefs [snow-white.roles/assign-roles (fn [_] {:p0 :seer :p1 :villager :p2 :villager :p3 :werewolf :p4 :villager})
                snow-white.words/random-words (fn ([_] ["apple" "pear"]) ([_ _] ["apple" "pear"]))]
    (let [l (-> (lobby-with-players 5)
                (g/set-mayor-eligibility {:villager true :seer false :werewolf true})
                (g/mod-set-preferred-mayor :p3)
                g/start-game)]
      (is (= :p3 (:mayor l)))
      (is (= :werewolf (get-in l [:players :p3 :role]))))))

(deftest preferred-mayor-falls-back-when-not-active
  (let [l (-> (lobby-with-players 5)
              (g/mod-set-preferred-mayor :ghost)
              g/start-game)]
    (is (some? (:mayor l)))
    (is (not= :ghost (:mayor l)))))

(deftest question-queue-is-fifo
  (let [[l mayor askers] (into-question-round)
        l (-> l
              (g/ask-question (first askers) "Older?")
              (g/ask-question (second askers) "Newer?")
              (g/answer-question mayor :yes))]
    (is (= "Older?" (:text (first (:answered l)))))
    (is (= ["Newer?"] (mapv :text (:questions l))))))

(deftest mayor-discard-is-fifo
  (let [[l mayor askers] (into-question-round)
        l (-> l
              (g/ask-question (first askers) "Older?")
              (g/ask-question (second askers) "Newer?")
              (g/answer-question mayor :discard))]
    (is (= "Older?" (:text (last (:question-log l)))))
    (is (= ["Newer?"] (mapv :text (:questions l))))))

(deftest mod-seating-mid-game-newcomer-makes-public-villager
  (let [[l _ _] (into-question-round)
        l (-> l
              (g/join :late "Late Player")
              (g/mod-seat :late))]
    (is (= :villager (get-in l [:players :late :role])))
    (is (true? (get-in l [:players :late :public-role])))
    (is (g/seated? (get-in l [:players :late])))))

(deftest mayor-discard-spends-discard-budget-and-logs-question
  (let [[l mayor askers] (into-question-round)
        l (-> l
              (assoc :discard-tokens 1)
              (g/ask-question (first askers) "Noise?")
              (g/answer-question mayor :discard))]
    (is (= 0 (:discard-tokens l)))
    (is (empty? (:questions l)))
    (is (= :discard (:answer (last (:question-log l)))))
    (is (= :mayor (:discarded-by (last (:question-log l)))))))

(deftest mayor-discard-is-blocked-when-budget-empty
  (let [[l mayor askers] (into-question-round)
        l (-> l
              (assoc :discard-tokens 0)
              (g/ask-question (first askers) "Keep me"))
        l2 (g/answer-question l mayor :discard)]
    (is (= (:questions l) (:questions l2)))
    (is (empty? (:question-log l2)))))

(deftest own-discard-is-free-and-logged
  (let [[l _ askers] (into-question-round)
        asker (first askers)
        l (-> l
              (assoc :discard-tokens 0)
              (g/ask-question asker "Never mind")
              (g/discard-own-question asker))]
    (is (empty? (:questions l)))
    (is (= 0 (:discard-tokens l)))
    (is (= :discard (:answer (last (:question-log l)))))
    (is (= :self (:discarded-by (last (:question-log l)))))))

(deftest exact-match-question-auto-corrects
  (let [[l _ askers] (into-question-round)
        l (assoc l :chosen-word "Snow White")
        l (g/ask-question l (first askers) " snow white? ")]
    (is (= :word-guessed (:game-state l)))
    (is (= :correct (:answer (last (:question-log l)))))
    (is (= g/start-tokens (:tokens l)))
    (is (empty? (:questions l)))))

(deftest mayor-pick-sets-stable-round-deadline
  (let [l (g/start-game (lobby-with-players 5))
        mayor (:mayor l)
        l (g/mayor-pick l mayor (first (:words l)) 1000)
        deadline (:round-deadline-ms l)
        asker (first (remove #{mayor} (keys (:players l))))
        l2 (-> l (g/ask-question asker "A?") (g/answer-question mayor :yes))]
    (is (= 61000 deadline))
    (is (= deadline (:round-deadline-ms l2)))))

(deftest finish-voting-uses-current-votes-and-records-random-tie-result
  (with-redefs [rand-nth first]
    (let [l (-> (lobby-with-players 5)
                g/start-game
                (assoc :game-state :out-of-time
                       :werewolves #{:p1}
                       :village-votes [:p1 :p2]))
          l (g/finish-vote l :village)]
      (is (= :end-game (:game-state l)))
      (is (= :p1 (get-in l [:vote-result :selected])))
      (is (= #{:p1 :p2} (set (get-in l [:vote-result :leaders]))))
      (is (true? (get-in l [:vote-result :randomized?]))))))

;; --- auth / moderation spec completion --------------------------------------

(deftest migration-tokens-are-room-scoped-and-resolve-auth
  (let [l (-> (g/new-lobby :owner "t")
              (g/join :owner "Owner")
              (g/join :p1 "Alice"))
        token (get-in l [:auth->migration :p1])]
    (is (string? token))
    (is (not= token (name :p1)) "migration token must not be the auth-id")
    (is (= :p1 (g/auth-for-migration l token)))
    (is (nil? (g/auth-for-migration (g/new-lobby :other "other") token))
        "tokens are scoped to the lobby that issued them")))

(deftest owner-and-mod-promotion-rules
  (let [l (-> (g/new-lobby :owner "t")
              (g/join :owner "Owner")
              (g/join :a "Alice")
              (g/join :b "Bob")
              (g/join :c "Cara"))
        l (g/promote-mod l :owner :a)]
    (is (contains? (:mods l) :a))
    (is (= :owner (get-in l [:mod-promoters :a])))
    (testing "mods can promote other real mods"
      (let [l2 (g/promote-mod l :a :b)]
        (is (contains? (:mods l2) :b))
        (is (= :a (get-in l2 [:mod-promoters :b])))))
    (testing "mods can demote only mods they promoted"
      (let [l2 (-> l (g/promote-mod :a :b) (g/demote-mod :a :b))]
        (is (not (contains? (:mods l2) :b))))
      (let [l2 (-> l (g/promote-mod :a :b) (g/demote-mod :b :a))]
        (is (contains? (:mods l2) :a))))
    (testing "owner can demote any mod, but owner cannot be demoted"
      (let [l2 (-> l (g/promote-mod :a :b) (g/demote-mod :owner :b))]
        (is (not (contains? (:mods l2) :b))))
      (let [l2 (-> l (g/promote-mod :a :b) (g/demote-mod :a :owner))]
        (is (= :owner (:owner-id l2)))))))

(deftest temp-mod-election-and-powers
  (let [l (-> (g/new-lobby :owner "t")
              (g/join :owner "Owner")
              (g/join :a "Alice")
              (g/join :b "Bob")
              (g/mark-offline :owner))]
    (let [waiting (g/refresh-temp-mods l 1000)]
      (is (nil? (:active-temp-mod waiting)))
      (is (= 1000 (:no-real-mod-since-ms waiting))))
    (let [l (-> l
                (g/refresh-temp-mods 1000)
                (g/refresh-temp-mods (+ 1000 g/temp-mod-delay-ms)))]
      (is (some? (:active-temp-mod l)))
      (is (contains? (:temp-mods l) (:active-temp-mod l)))
      (is (g/can-moderate? l (:active-temp-mod l)))
      (testing "temp mod promotions create temp mods with powers, not real mods"
        (let [temp (:active-temp-mod l)
              target (first (remove #{temp :owner} (keys (:players l))))
              l2 (g/promote-mod l temp target)]
          (is (contains? (:temp-mods l2) target))
          (is (not (contains? (:mods l2) target)))
          (is (g/can-moderate? l2 target))))
      (testing "real mod returning clears active temp but keeps designation"
        (let [l2 (-> l (g/join :owner "Owner") (g/refresh-temp-mods 2000000))]
          (is (nil? (:active-temp-mod l2)))
          (is (seq (:temp-mods l2))))))))

(deftest previous-temp-mods-are-preferred
  (let [l (-> (g/new-lobby :owner "t")
              (g/join :owner "Owner")
              (g/join :a "Alice")
              (g/join :b "Bob")
              (g/mark-offline :owner)
              (assoc :temp-mods #{:b}))
        l (-> l
              (g/refresh-temp-mods 1000)
              (g/refresh-temp-mods (+ 1000 g/temp-mod-delay-ms)))]
    (is (= :b (:active-temp-mod l)))))
