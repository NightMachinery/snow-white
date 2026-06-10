(ns snow-white.game-test
  (:require [clojure.test :refer [deftest is testing]]
            [snow-white.game :as g]))

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
