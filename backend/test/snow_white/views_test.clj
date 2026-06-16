(ns snow-white.views-test
  (:require [clojure.test :refer [deftest is testing]]
            [snow-white.game :as g]
            [snow-white.views :as views]))

(defn- lobby-with-players
  [n]
  (reduce (fn [l i] (g/join l (keyword (str "p" i)) (str "Player" i)))
          (g/new-lobby :p0 "test")
          (range n)))

(defn- revealing-lobby
  []
  (let [l (-> (lobby-with-players 5) g/start-game)
        mayor (:mayor l)
        word (first (:words l))
        l (g/mayor-pick l mayor word)
        villager (first (for [[auth p] (:players l)
                              :when (and (not= auth mayor)
                                         (not= (:role p) :seer)
                                         (not= (:role p) :werewolf))]
                          auth))]
    [(assoc l :chosen-word "apple") villager]))

(deftest vote-stages-reveal-word-but-not-hidden-roles
  (let [[l recipient] (revealing-lobby)]
    (doseq [state [:word-guessed :out-of-time :out-of-tokens]]
      (testing state
        (let [view (views/lobby-view (assoc l :game-state state) recipient)]
          (is (= "apple" (:chosen-word view)))
          (is (nil? (:seer view)))
          (is (empty? (:werewolves view)))
          (is (empty? (:wolf-votes view)))
          (is (nil? (some (fn [[auth p]]
                            (when (and (not= auth recipient) (:role p)) [auth (:role p)]))
                          (:players view)))))))))

(deftest public-late-villager-role-is-visible-before-end-game
  (let [[l recipient] (revealing-lobby)
        l (-> l
              (g/join :late "Late Player")
              (g/mod-seat :late))]
    (is (= :villager (get-in l [:players :late :role])))
    (is (true? (get-in l [:players :late :public-role])))
    (is (= :villager (get-in (views/lobby-view l recipient) [:players :late :role])))))

(deftest migration-tokens-are-redacted-by-recipient
  (let [l (-> (g/new-lobby :owner "t")
              (g/join :owner "Owner")
              (g/join :p1 "Alice")
              (g/join :p2 "Bob")
              (g/promote-mod :owner :p1))
        owner-view (views/lobby-view l :owner)
        mod-view (views/lobby-view l :p1)
        p2-view (views/lobby-view l :p2)]
    (is (= (get-in l [:auth->migration :owner]) (get-in owner-view [:you :migration-token])))
    (is (= (get-in l [:auth->migration :p2]) (get-in mod-view [:players :p2 :migration-token])))
    (is (nil? (get-in p2-view [:players :p1 :migration-token])))))
