(ns snow-white.words
  "File-backed wordpacks for the Mayor's candidate words.

  Learning note: the backend loads plain text wordpack files once at namespace
  load. Each `.txt` file in repo-root `wordpacks/` is one pack: one word or
  phrase per line, blank lines ignored, and `#` lines treated as comments. A
  leading comment can name the pack for the UI. Keeping this loader pure-ish and
  small makes it easy to test without a running server."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(def default-wordpack-id "English_Snow_White_1")

(defn- existing-wordpack-dir []
  (let [candidates [(io/file "wordpacks")
                    (io/file ".." "wordpacks")]]
    (or (some #(when (.isDirectory %) %) candidates)
        (first candidates))))

(defn- txt-file? [^java.io.File f]
  (and (.isFile f) (str/ends-with? (.getName f) ".txt")))

(defn- pack-id [^java.io.File f]
  (let [name (.getName f)]
    (subs name 0 (- (count name) 4))))

(defn- fallback-name [id]
  (str/replace id #"[_-]+" " "))

(defn- parse-pack [^java.io.File f]
  (let [id (pack-id f)
        lines (str/split-lines (slurp f :encoding "UTF-8"))
        first-line (some-> lines first str/trim)
        title (when (and first-line (str/starts-with? first-line "#"))
                (let [s (str/trim (subs first-line 1))]
                  (when (seq s) s)))
        words (->> lines
                   (map #(str/replace % #"^\uFEFF" ""))
                   (map str/trim)
                   (remove str/blank?)
                   (remove #(str/starts-with? % "#"))
                   distinct
                   vec)]
    {:id id
     :name (or title (fallback-name id))
     :word-count (count words)
     :words words}))

(defn load-wordpacks
  "Load all `.txt` wordpacks from `dir`, sorted by filename."
  ([] (load-wordpacks (existing-wordpack-dir)))
  ([dir]
   (let [d (io/file dir)]
     (if-not (.isDirectory d)
       []
       (->> (.listFiles d)
            (filter txt-file?)
            (sort-by #(.getName ^java.io.File %))
            (mapv parse-pack))))))

(def wordpacks
  "Vector of loaded wordpack maps. Loaded once when the backend starts."
  (load-wordpacks))

(defn available-wordpacks
  "Public metadata for the client; excludes the actual words."
  ([] (available-wordpacks wordpacks))
  ([packs]
   (mapv #(select-keys % [:id :name :word-count]) packs)))

(defn normalize-selection
  "Return a distinct, valid selected pack id vector. Empty/invalid selections
  fall back to the default pack when present, then to the first available pack."
  ([selected] (normalize-selection wordpacks selected))
  ([packs selected]
   (let [ids (set (map :id packs))
         normalized (->> selected
                         (map str)
                         (filter ids)
                         distinct
                         vec)]
     (cond
       (seq normalized) normalized
       (contains? ids default-wordpack-id) [default-wordpack-id]
       (seq packs) [(:id (first packs))]
       :else []))))

(defn selected-words
  "Return the de-duplicated union of selected packs, preserving selected pack
  order and each pack's file order."
  ([selected] (selected-words wordpacks selected))
  ([packs selected]
   (let [by-id (into {} (map (juxt :id identity) packs))]
     (->> (normalize-selection packs selected)
          (mapcat #(get-in by-id [% :words]))
          distinct
          vec))))

(defn random-words
  "Return `n` random candidate words from selected wordpacks. Returns distinct
  words when the union is large enough; allows repeats only when `n` exceeds the
  selected bank size."
  ([n] (random-words n [default-wordpack-id]))
  ([n selected]
   (let [bank (selected-words selected)]
     (cond
       (empty? bank) []
       (<= n (count bank)) (vec (take n (shuffle bank)))
       :else (vec (repeatedly n #(rand-nth bank)))))))
