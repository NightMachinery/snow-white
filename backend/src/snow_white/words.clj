(ns snow-white.words
  "The word bank the Mayor draws secret words from.

  Learning note: we read a JSON resource once at namespace load and keep it in a
  `def`. `io/resource` finds files on the classpath — here `resources/` is on the
  classpath via :paths in deps.edn, so `wordList.json` is found by name."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]))

(def words
  "Vector of ~2300 candidate words/phrases, loaded once from the classpath."
  (with-open [r (io/reader (io/resource "wordList.json"))]
    (vec (json/read r))))

(defn random-words
  "Return `n` distinct random words for the Mayor to choose from.
  Falls back to allowing repeats only if `n` exceeds the bank size."
  [n]
  (if (<= n (count words))
    (vec (take n (shuffle words)))
    (vec (repeatedly n #(rand-nth words)))))
