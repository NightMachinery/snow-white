(ns snow-white.words-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [snow-white.words :as words]))

(defn- temp-dir []
  (.toFile (java.nio.file.Files/createTempDirectory "snow-white-wordpacks" (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- write-pack! [dir filename text]
  (spit (io/file dir filename) text))

(deftest load-wordpacks-parses-newline-files
  (let [dir (temp-dir)]
    (write-pack! dir "Alpha_Pack.txt" "# Alpha Pack\n apple \n\nbanana\n# ignored\napple\n")
    (write-pack! dir "beta-pack.txt" "# Beta Title\npear\n banana \n")
    (write-pack! dir "notes.md" "not a pack")
    (is (= [{:id "Alpha_Pack"
             :name "Alpha Pack"
             :word-count 2
             :words ["apple" "banana"]}
            {:id "beta-pack"
             :name "Beta Title"
             :word-count 2
             :words ["pear" "banana"]}]
           (words/load-wordpacks dir)))))

(deftest selected-words-are-a-deduped-union-in-pack-order
  (let [packs [{:id "a" :name "A" :word-count 3 :words ["apple" "banana" "pear"]}
               {:id "b" :name "B" :word-count 3 :words ["banana" "fig" "apple"]}]]
    (is (= ["apple" "banana" "pear" "fig"]
           (words/selected-words packs ["a" "b"])))))

(deftest invalid-or-empty-selection-falls-back-to-default
  (let [packs [{:id words/default-wordpack-id :name "Default" :word-count 1 :words ["snow"]}
               {:id "other" :name "Other" :word-count 1 :words ["wolf"]}]]
    (is (= [words/default-wordpack-id]
           (words/normalize-selection packs [])))
    (is (= [words/default-wordpack-id]
           (words/normalize-selection packs ["missing"])))
    (is (= ["other"]
           (words/normalize-selection packs ["missing" "other" "other"])))))
