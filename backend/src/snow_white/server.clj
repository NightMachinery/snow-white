(ns snow-white.server
  "The impure edge: http-kit HTTP + WebSocket, transit on the wire, command
  dispatch into the registry, and snapshot broadcast.

  Message shapes (see docs/protocol.md for the full contract):
    client -> server : {:type :game/answer :answer :yes ...}
    server -> client : {:type :lobby/state :lobby {...}}   ; redacted per recipient
                       {:type :error :msg \"...\"}

  Each client receives its own *redacted* view (views/lobby-view), so secrets
  never leak. After any command we re-broadcast a fresh snapshot to everyone in
  the room."
  (:require [clojure.string :as str]
            [cognitect.transit :as transit]
            [org.httpkit.server :as http]
            [snow-white.game :as game]
            [snow-white.ids :as ids]
            [snow-white.registry :as reg]
            [snow-white.views :as views])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream]))

;; ---------------------------------------------------------------------------
;; Transit encode / decode (JSON flavor)
;; ---------------------------------------------------------------------------

(defn ->transit ^String [data]
  (let [out (ByteArrayOutputStream.)]
    (transit/write (transit/writer out :json) data)
    (.toString out "UTF-8")))

(defn <-transit [^String s]
  (transit/read (transit/reader (ByteArrayInputStream. (.getBytes s "UTF-8")) :json)))

;; ---------------------------------------------------------------------------
;; Broadcast
;; ---------------------------------------------------------------------------

(defn send-state!
  "Send a recipient their redacted view of `lobby-name`'s current state."
  [ch auth-id lobby-name]
  (when-let [a (reg/get-lobby-atom lobby-name)]
    (http/send! ch (->transit {:type :lobby/state
                               :lobby (views/lobby-view @a auth-id)}))))

(defn broadcast!
  "Push a fresh per-recipient snapshot to every channel in the lobby."
  [lobby-name]
  (doseq [ch (reg/channels-in lobby-name)]
    (when-let [{:keys [auth-id]} (reg/conn-info ch)]
      (send-state! ch auth-id lobby-name))))

(defn send-error! [ch msg]
  (http/send! ch (->transit {:type :error :msg msg})))

;; ---------------------------------------------------------------------------
;; Command dispatch
;; ---------------------------------------------------------------------------

(defn- mod-gate
  "Apply pure `f` only if the requester may moderate; else no-op."
  [lobby requester f & args]
  (if (game/can-moderate? lobby requester)
    (apply f lobby args)
    lobby))

(defn handle
  "Apply one client command to the lobby it belongs to. `ch` is the sending
  channel; `msg` is a decoded transit map. Returns the lobby-name to broadcast
  (or nil)."
  [ch {:keys [type] :as msg}]
  (let [{:keys [auth-id lobby]} (reg/conn-info ch)]
    (when (and auth-id lobby (reg/lobby-exists? lobby))
      (let [me auth-id]
        (case type
          ;; --- seating ---
          ;; Players may self-seat unless a mod has locked seating (mods bypass).
          :seat/take    (reg/update-lobby! lobby
                                           (fn [l]
                                             (if (and (:lock-seating l)
                                                      (not (game/can-moderate? l me)))
                                               l
                                               (game/take-seat l me (:seat msg) (:color msg)))))
          :seat/spectate (reg/update-lobby! lobby
                                            (fn [l]
                                              (if (and (:lock-seating l)
                                                       (not (game/can-moderate? l me)))
                                                l
                                                (game/spectate l me))))

          ;; --- settings (mod-gated) ---
          :settings/timer       (reg/update-lobby! lobby mod-gate me game/set-timer (:minutes msg))
          :settings/pick-count  (reg/update-lobby! lobby mod-gate me game/set-pick-count (:pick-count msg))
          :settings/eligibility (reg/update-lobby! lobby mod-gate me game/set-mayor-eligibility (:roles msg))
          :settings/budget      (reg/update-lobby! lobby mod-gate me game/set-budget (:budget msg))
          :settings/discard-budget (reg/update-lobby! lobby mod-gate me game/set-budget {:discard-tokens (:discard-tokens msg)})
          :settings/rules       (reg/update-lobby! lobby mod-gate me game/set-rules (:rules msg))
          :settings/wordpacks   (reg/update-lobby! lobby mod-gate me game/set-wordpacks (:wordpacks msg))

          ;; --- mod player management (mod-gated) ---
          :mod/seat     (reg/update-lobby! lobby mod-gate me game/mod-seat (:target msg))
          :mod/unseat   (reg/update-lobby! lobby mod-gate me game/mod-unseat (:target msg))
          :mod/mayor    (reg/update-lobby! lobby mod-gate me game/mod-set-preferred-mayor (:target msg))

          ;; --- player identity ---
          :player/rename (reg/update-lobby! lobby game/rename-player me (:name msg))

          ;; --- game flow ---
          :game/start    (reg/update-lobby! lobby mod-gate me game/start-game)
          :game/pick     (reg/update-lobby! lobby game/mayor-pick me (:word msg))
          :game/ask      (reg/update-lobby! lobby game/ask-question me (:text msg))
          :game/edit     (reg/update-lobby! lobby game/edit-question me (:text msg))
          :game/discard-own (reg/update-lobby! lobby game/discard-own-question me)
          :game/answer   (reg/update-lobby! lobby game/answer-question me (:answer msg))
          :game/timeout  (reg/update-lobby! lobby mod-gate me game/timeout)
          :game/vote-village (reg/update-lobby! lobby game/village-vote me (:target msg))
          :game/vote-wolf    (reg/update-lobby! lobby game/wolf-vote me (:target msg))
          :game/finish-vote  (reg/update-lobby! lobby mod-gate me game/finish-vote)
          :game/finalize (reg/update-lobby! lobby game/finalize)
          :game/reset    (reg/update-lobby! lobby
                                            (fn [l] (if (or (game/can-moderate? l me)
                                                            (= me (:mayor l)))
                                                      (game/reset-game l) l)))
          ;; unknown
          (send-error! ch (str "unknown command: " type)))
        ;; if a flow command pushed us into :end-game, compute the winner
        (reg/update-lobby! lobby game/finalize)
        lobby))))

;; ---------------------------------------------------------------------------
;; WebSocket lifecycle
;; ---------------------------------------------------------------------------

(defn- on-connect
  "First message on a socket must be {:type :hello :auth-id .. :lobby .. :name ..}.
  We attach the connection, join the player, and send initial state."
  [ch {:keys [auth-id lobby name]}]
  (let [auth-id (or auth-id (ids/auth-id))]
    (if (reg/lobby-exists? lobby)
      (do
        (reg/register-conn! ch auth-id lobby)
        (reg/mark-occupied! lobby)            ; cancel any pending retention TTL
        (reg/update-lobby! lobby game/join auth-id (or name "Player"))
        ;; tell the client its resolved auth-id (in case the server minted one)
        (http/send! ch (->transit {:type :hello/ok :auth-id auth-id}))
        (broadcast! lobby))
      (send-error! ch "lobby not found"))))

(defn ws-handler [req]
  (http/as-channel
   req
   {:on-receive
    (fn [ch raw]
      (let [msg (<-transit raw)]
        (if (= (:type msg) :hello)
          (on-connect ch msg)
          (when-let [lobby (handle ch msg)]
            (broadcast! lobby)))))
    :on-close
    (fn [ch _status]
      (when-let [{:keys [auth-id lobby]} (reg/forget-conn! ch)]
        (when (reg/lobby-exists? lobby)
          ;; mark offline only if no other socket holds this identity
          (when-not (reg/auth-still-online? lobby auth-id)
            (reg/update-lobby! lobby game/mark-offline auth-id))
          ;; An empty lobby is no longer destroyed immediately — instead we start
          ;; its retention clock, so the room survives refreshes, brief drops, and
          ;; idle gaps. The background reaper deletes it only after it has stayed
          ;; empty for `reg/empty-ttl-ms` (14 days).
          (let [a (reg/get-lobby-atom lobby)]
            (when (and a (not (game/any-online? @a)))
              (reg/mark-empty! lobby (System/currentTimeMillis)))
            (broadcast! lobby)))))}))

;; ---------------------------------------------------------------------------
;; HTTP routes
;; ---------------------------------------------------------------------------

(defn create-lobby-http
  "POST/GET create. Returns transit {:ok true} or {:error ..}."
  [{:keys [params] :as _req}]
  (let [auth-id (get params "authId")
        name    (get params "lobby")]
    (cond
      (not auth-id) {:status 400 :body (->transit {:error "missing identity"})}
      (reg/lobby-exists? name) {:status 409 :body (->transit {:error "lobby name already in use"})}
      :else (do (reg/create-lobby! auth-id name)
                {:status 200 :body (->transit {:ok true})}))))

(defn lobby-exists-http [{:keys [params]}]
  {:status 200 :body (->transit {:exists (reg/lobby-exists? (get params "lobby"))})})

(defn router [req]
  (case [(:request-method req) (:uri req)]
    [:get "/health"]      {:status 200 :body "ok"}
    [:get "/api/create"]  (create-lobby-http req)
    [:get "/api/exists"]  (lobby-exists-http req)
    [:get "/ws"]          (ws-handler req)
    {:status 404 :body "not found"}))

(defn- wrap-query-params [handler]
  (fn [req]
    (let [params (->> (or (:query-string req) "")
                      (#(str/split % #"&"))
                      (remove str/blank?)
                      (map #(let [[k v] (str/split % #"=" 2)]
                              [k (java.net.URLDecoder/decode (or v "") "UTF-8")]))
                      (into {}))]
      (handler (assoc req :params params)))))

(defn- wrap-cors [handler]
  (fn [req]
    (let [resp (handler req)]
      (update resp :headers merge
              {"Access-Control-Allow-Origin" "*"
               "Access-Control-Allow-Methods" "GET, POST, OPTIONS"
               "Access-Control-Allow-Headers" "Content-Type"}))))

(def app (-> router wrap-query-params wrap-cors))

;; ---------------------------------------------------------------------------
;; Server lifecycle (REPL-friendly)
;; ---------------------------------------------------------------------------

(defonce server (atom nil))

(defn start!
  ([] (start! 38931))
  ([port]
   (when @server (@server))
   (reset! server (http/run-server app {:port port :legacy-return-value? false}))
   (reg/start-reaper!)                    ; reap lobbies left empty past the TTL
   (println (str "Snow White server on http://localhost:" port))
   @server))

(defn stop! []
  (reg/stop-reaper!)
  (when-let [s @server]
    (http/server-stop! s)
    (reset! server nil)
    (println "stopped")))
