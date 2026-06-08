(ns snow-white.roles-test
  (:require [clojure.test :refer [deftest is testing]]
            [snow-white.roles :as roles]))

(deftest deal-roles-counts
  (testing "small games have exactly one wolf and one seer"
    (let [r (roles/deal-roles 5)]
      (is (= 5 (count r)))
      (is (= 1 (count (filter #{:werewolf} r))))
      (is (= 1 (count (filter #{:seer} r))))))
  (testing "games over 6 players add a second wolf"
    (let [r (roles/deal-roles 8)]
      (is (= 8 (count r)))
      (is (= 2 (count (filter #{:werewolf} r))))
      (is (= 1 (count (filter #{:seer} r)))))))

(deftest choose-mayor-eligibility
  (let [roles {:a :villager :b :seer :c :werewolf}]
    (testing "respects eligibility"
      (is (= :a (roles/choose-mayor roles {:villager true :seer false :werewolf false}))))
    (testing "falls back to villager then anyone"
      (is (some? (roles/choose-mayor roles {:villager false :seer false :werewolf false}))))))

(deftest resolve-winner-cases
  (testing "guessed: wolves win only by hitting the seer"
    (is (= :wolves  (roles/resolve-winner {:guessed? true :seer-auth :s :werewolf-auths #{:w} :wolf-votes [:s]})))
    (is (= :village (roles/resolve-winner {:guessed? true :seer-auth :s :werewolf-auths #{:w} :wolf-votes [:x]}))))
  (testing "not guessed: village wins only with a unique top wolf"
    (is (= :village (roles/resolve-winner {:guessed? false :werewolf-auths #{:w} :village-votes [:w :w :a]})))
    (is (= :wolves  (roles/resolve-winner {:guessed? false :werewolf-auths #{:w} :village-votes [:w :a]})))   ; tie
    (is (= :wolves  (roles/resolve-winner {:guessed? false :werewolf-auths #{:w} :village-votes [:a :a :w]}))))) ; non-wolf top
