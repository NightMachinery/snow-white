(ns user
  "REPL workbench. `clj -M:dev` drops you here (nREPL); these helpers let you
  start/stop the server and drive a whole game without a browser.

  Try:
    (go)                         ; start the server on :38931
    (def L (seed! 5))            ; create a lobby with 5 fake players
    (sim! L)                     ; run a full game to a winner, printing states
    (reset)                      ; reload changed namespaces + restart server"
  (:require [snow-white.game :as game]
            [snow-white.registry :as reg]
            [snow-white.server :as server]
            [snow-white.views :as views]))

(defn go
  ([] (go 38931))
  ([port] (server/start! port)))
(defn halt [] (server/stop!))

(defn seed!
  "Create a lobby named `name` with n fake seated players p0..pn-1. Returns name."
  ([n] (seed! n (str "dev-" n)))
  ([n name]
   (reg/create-lobby! "p0" name)
   (dotimes [i n]
     (reg/update-lobby! name game/join (str "p" i) (str "Player" i)))
   name))

(defn show [name] (some-> (reg/get-lobby-atom name) deref))

(defn sim!
  "Drive a full game in `name` to a winner, printing the state at each step.
  Picks the first non-mayor as the asker and answers everything :yes until the
  word is 'guessed', then runs the wolf vote."
  [name]
  (let [p! (fn [label] (println label '=> (:game-state (show name))))]
    (reg/update-lobby! name game/start-game)        (p! :start)
    (let [l (show name)
          mayor (:mayor l)
          word (first (:words l))
          asker (first (remove #{mayor} (keys (:players l))))]
      (reg/update-lobby! name game/mayor-pick mayor word)   (p! :pick)
      (reg/update-lobby! name game/ask-question asker "alive?")
      (reg/update-lobby! name game/answer-question mayor :yes)
      (reg/update-lobby! name game/ask-question asker "the word?")
      (reg/update-lobby! name game/answer-question mayor :correct) (p! :guessed)
      (let [l (show name)
            wolf (first (:werewolves l))]
        (reg/update-lobby! name game/wolf-vote wolf (:seer l))
        (reg/update-lobby! name game/finalize) (p! :end)
        (println :winner (:winner (show name)))))))

(defn reset []
  (halt)
  (go))
