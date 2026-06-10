(ns snow-white.dev-server
  "Self-host development entrypoint.

  Starts the live HTTP server and a localhost nREPL server in the same JVM. The
  tmux `repl` window connects to this nREPL, so forms typed there operate on
  the same in-memory lobby registry as the served game."
  (:require [cider.nrepl :refer [cider-middleware]]
            [nrepl.server :as nrepl]
            [snow-white.server :as server]
            [user])
  (:import [java.nio.file Files Path StandardOpenOption]))

(defn- parse-port [raw fallback]
  (Integer/parseInt (or raw fallback)))

(defn- write-nrepl-port! [port]
  (Files/writeString
   (Path/of ".nrepl-port" (make-array String 0))
   (str port "\n")
   (into-array StandardOpenOption [StandardOpenOption/CREATE
                                   StandardOpenOption/TRUNCATE_EXISTING
                                   StandardOpenOption/WRITE])))

(defn -main [& args]
  (let [backend-port (parse-port (first args) (or (System/getenv "PORT") "38931"))
        nrepl-port (parse-port (second args) "38933")]
    (nrepl/start-server :bind "127.0.0.1"
                        :port nrepl-port
                        :handler (apply nrepl/default-handler cider-middleware))
    (write-nrepl-port! nrepl-port)
    (println (str "nREPL server on nrepl://127.0.0.1:" nrepl-port))
    (server/start! backend-port)
    @(promise)))
