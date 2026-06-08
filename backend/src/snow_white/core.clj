(ns snow-white.core
  "Production entrypoint. For development, prefer the REPL (`dev/user.clj`)."
  (:require [snow-white.server :as server])
  (:gen-class))

(defn -main [& args]
  (let [port (Integer/parseInt (or (first args) (System/getenv "PORT") "3000"))]
    (server/start! port)
    @(promise))) ; block forever
