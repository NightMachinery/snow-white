# The Clojure Backend — a guided tour

This is written for someone who knows a little Clojure and wants to learn how to
build a **real-time web server** with it, REPL-first. We'll go from the pure
core outward to the socket edge.

## 0. The project: `deps.edn`

Clojure CLI projects are configured by `deps.edn` — a map of dependencies and
*aliases* (named extra configurations).

```clojure
{:paths ["src"]                        ; src on the classpath
 :deps  {org.clojure/clojure {:mvn/version "1.12.0"}
         http-kit/http-kit   {:mvn/version "2.8.0"}
         ...}
 :aliases
 {:dev         {:extra-paths ["dev" "test"]   ; nREPL for editor-connected dev
                :extra-deps  {nrepl/nrepl {...} cider/cider-nrepl {...}}
                :main-opts   ["-m" "nrepl.cmdline" ...]}
  :dev-server  {:main-opts ["-m" "snow-white.dev-server"]} ; HTTP + nREPL
  :repl-client {:main-opts ["-m" "nrepl.cmdline" "--connect" ...]}
  :run         {:main-opts ["-m" "snow-white.core"]}        ; clj -M:run
  :test        {:extra-paths ["test"] ...}}}                ; clj -M:test
```

- `clj -M:dev` starts an nREPL server you connect your editor to.
- `clojure -M:dev-server 38931 38933` starts HTTP and nREPL in one JVM.
- `clojure -M:repl-client --port 38933` starts the tmux nREPL client.
- `clj -M:run` runs `-main` (production).
- `clj -M:test` runs the test runner.

> **Learning note — `-M` vs `-X` vs `-T`.** `-M` runs with the *main* options
> (a `-main`-style entrypoint or `clojure.main`). There are also `-X` (call one
> function with a data map) and `-T` (tools). For app dev you mostly use `-M`.

## 1. Pure core: data + functions, no I/O

The most important Clojure idea in this codebase: **model state as a plain
immutable map, and write rules as pure functions that take a map and return a new
map.** Look at `game.clj`:

```clojure
(defn answer-question [lobby auth-id answer]
  ;; ... returns a NEW lobby map; never mutates the argument
  )
```

A *lobby* is just a map (`new-lobby` builds it). A player is a map. Roles are
keywords (`:villager`, `:seer`, `:werewolf`). The game state is a keyword
(`:lobby`, `:mayor-pick`, …). Nothing here knows about sockets, atoms, or JSON.

Why this matters:

- **Testable.** `test/snow_white/game_test.clj` exercises the whole game with no
  server running — just `(g/start-game (lobby-with-players 5))` and assert on the
  returned map.
- **REPL-explorable.** You can call any rule at the prompt and inspect the result.
- **Reasoning.** Given the same inputs you always get the same output. There's no
  hidden state to chase.

> **Learning note — updating nested immutable data.** Clojure gives you
> `assoc`, `update`, `assoc-in`, `update-in`, and `merge` to produce *modified
> copies*. e.g. in `seat-player`:
>
> ```clojure
> (-> lobby
>     (assoc-in [:seats seat] auth-id)
>     (update-in [:players auth-id] merge {:seat seat :spectator false}))
> ```
>
> The `->` ("thread-first") macro pipes `lobby` through each form. Structural
> sharing makes these copies cheap.

### Wordpacks

`words.clj` is deliberately small and testable. It loads `../wordpacks/*.txt`
when the backend process starts (or `wordpacks/*.txt` if the JVM cwd is the repo
root). Each file is parsed as one pack: trim lines, ignore blanks and `#`
comments, use a leading comment as the display name, and keep a `:word-count` for
the settings UI. Rooms store only selected pack ids. At `start-game`, the backend
normalizes those ids and draws from the de-duplicated union, so invalid or stale
ids fall back to `English_Snow_White_1`.

## 2. Holding state: **one atom per lobby**

Pure functions compute *new* values, but a server must *remember* the current
one between requests. Clojure's tool for shared, synchronous mutable state is the
**atom**.

```clojure
(def a (atom {:n 0}))
@a                 ; deref → {:n 0}
(swap! a update :n inc)   ; atomically set a to (update @a :n inc)
@a                 ; → {:n 1}
```

`swap!` takes a **function** and applies it to the current value, retrying if
another thread changed it in between. Because our game functions are pure, this
is safe and trivial:

```clojure
(swap! lobby-atom game/answer-question auth-id :yes)
;; ≡ (reset! lobby-atom (game/answer-question @lobby-atom auth-id :yes)) but atomic
```

### Why *per-lobby* atoms (the key design call)

A naive design puts *all* lobbies in one atom: `(atom {lobby-name -> lobby-map})`.
That works, but every action in every room serializes through one
compare-and-swap — a global bottleneck for something that is inherently parallel.

Lobbies never interact, so `registry.clj` instead keeps:

```clojure
(defonce registry (atom {}))   ; lobby-name -> (atom lobby-map)   [rarely changes]
```

Each lobby is its **own** atom. A `swap!` on room A contends only with other
writers of room A. The registry atom itself only changes when a lobby is created
or destroyed. This scales across cores for free and isolates failures.

> **Learning note — `defonce`.** Like `def`, but only binds if the var is
> unbound. This means re-evaluating the namespace in the REPL won't wipe your
> live state — essential for REPL-driven development.

## 3. The impure edge: http-kit + WebSocket

`server.clj` is where I/O lives. http-kit upgrades a request to a WebSocket with
`as-channel` and gives you callbacks:

```clojure
(defn ws-handler [req]
  (http/as-channel req
    {:on-receive (fn [ch raw] ...)     ; a text frame arrived
     :on-close   (fn [ch status] ...)}))  ; socket closed
(http/send! ch text)                    ; push a frame to one client
```

The shape of the system:

1. Client connects, sends `{:type :hello :auth-id … :lobby … :name …}`.
2. We record the connection (`registry/register-conn!`), `join` the player into
   the lobby atom, reply `:hello/ok`, and **broadcast** the new snapshot.
3. Each later message is a command; `handle` dispatches it with `case` to a pure
   game function applied via `swap!`, then we broadcast again.

```clojure
(case type
  :game/answer (reg/update-lobby! lobby game/answer-question me (:answer msg))
  :game/start  (reg/update-lobby! lobby mod-gate me game/start-game)
  ...)
```

`mod-gate` is a tiny higher-order wrapper that only applies the inner function if
the requester may moderate — authorization stays declarative.

### Broadcast = re-send a snapshot to everyone

```clojure
(defn broadcast! [lobby-name]
  (doseq [ch (reg/channels-in lobby-name)]
    (let [{:keys [auth-id]} (reg/conn-info ch)]
      (http/send! ch (->transit {:type :lobby/state
                                 :lobby (views/lobby-view @atom auth-id)})))))
```

Note `views/lobby-view` runs **per recipient** — the snapshot Alice receives has
the secret word stripped if she isn't entitled to it. See `protocol.md`.

## 4. The wire: transit

`transit` serializes Clojure data over JSON without losing types (keywords stay
keywords, sets stay sets):

```clojure
(defn ->transit [data]
  (let [out (ByteArrayOutputStream.)]
    (transit/write (transit/writer out :json) data)
    (.toString out "UTF-8")))
```

The client mirrors this in `transit.ts`, converting keywords to/from strings at
that single boundary.

## 5. REPL-driven development (the workflow you asked for)

`dev/user.clj` is loaded automatically by `clj -M:dev`. It gives you:

```clojure
(go)              ; start the server on :38931
(def L (seed! 5)) ; create a lobby "dev-5" with 5 fake seated players
(sim! L)          ; drive a whole game, printing the state at each step
(show L)          ; deref and inspect the current lobby map
(halt)            ; stop the server
```

The loop you'll actually use:

1. Edit a function in `game.clj`.
2. Re-evaluate that form (or the namespace) in your editor's REPL.
3. Call it directly on a sample lobby and inspect the result — *no server, no
   browser, no clicking.*
4. When happy, `(sim! ...)` end-to-end, then run `clj -M:test`.

This is the payoff of keeping the core pure: the feedback loop is milliseconds.

## 6. Connection lifecycle & cleanup

`on-close` (in `server.clj`) marks a player offline only if no *other* socket
holds their identity (multi-tab safe). All of this is just more `swap!`s over
pure functions (`game/mark-offline`, `game/any-online?`).

### Lobby retention (the 14-day TTL + reaper)

Originally a lobby was destroyed the instant the last socket closed. That made
rooms *too* ephemeral: a single refresh, a flaky connection, or a pause between
rounds could evaporate a game. Now rooms **linger for 14 days after going empty**,
then a background reaper collects them.

The mechanism lives in `registry.clj` and is deliberately tiny:

- An `emptied-at` atom maps `lobby-name -> epoch-ms when it last went empty`. A
  lobby that is currently occupied has **no** entry; an empty one is timestamped.
- `mark-empty!` (called from `on-close` when `not (any-online? ...)`) starts the
  clock, keeping the *earliest* empty time if already set. `mark-occupied!`
  (called from `on-connect`) simply `dissoc`s the entry — rejoining cancels the
  countdown. Creation also stamps `emptied-at`, so a room made via HTTP but never
  joined is still eventually reaped.
- A daemon thread (`start-reaper!`, launched in `server/start!`) wakes hourly and
  calls `reap-expired!`, which `destroy-lobby!`s every lobby whose empty span has
  exceeded `empty-ttl-ms` (14 days).

> **Learning note — pure core, scheduled edge.** Notice the split: *deciding* what
> is expired (`expired-lobbies`, a pure read over a snapshot) is separate from the
> *effect* of deleting it and from the *thread* that drives the clock. The reaper
> is a daemon thread so it never blocks JVM shutdown, and `start-reaper!` is
> idempotent (returns the existing thread if already running) so reloading the
> namespace in the REPL doesn't spawn a second one. This is the same
> "pure-functions-with-mutation-only-at-the-edge" shape as the game rules, applied
> to housekeeping.

---

**Next:** [`protocol.md`](protocol.md) for the exact message contract, then
[`frontend-svelte.md`](frontend-svelte.md) for the other side of the socket.

### Gameplay polish notes: history, timers, and random tiebreaks

Recent rule changes keep three pieces of state as explicit lobby data rather than
asking the Svelte client to infer them:

- `:question-log` is an append-only, chronological history of answered questions
  and discards. The old per-player token buckets are still useful for role reads,
  but the log is the replayable story shown during voting and at end-game.
- `:round-started-at-ms` and `:round-deadline-ms` are set exactly once when the
  Mayor picks the word. Every later snapshot reuses the same deadline, so a UI
  re-render or Mayor answer cannot accidentally restart the timer.
- `:vote-result` stores the vote counts, tied leaders, selected target, and
  whether a random tiebreak happened. This is important because random choices
  should happen once on the server; clients only display the recorded result.

Preferred Mayor selection is handled during `game/start-game` with rejection
sampling: if a mod picked an active player, the backend retries the random role
deal until that player has a role allowed by `:mayor-eligibility` (for example,
Villager or Werewolf by default). If the preferred player is not active, normal
Mayor selection is used.

Late spectators normally remain outside the active round. When a mod seats a
no-role spectator after the game has started, `game/mod-seat` assigns
`:role :villager` and `:public-role true`. The view layer uses that marker to
show the Villager badge to everyone without revealing hidden dealt roles.

The word reveal has a separate rule from role reveal: `views/lobby-view` reveals
`:chosen-word` to everyone during vote states (`:word-guessed`, `:out-of-time`,
`:out-of-tokens`) while keeping hidden roles redacted until `:end-game`.

### Auth, migration tokens, and moderation

A browser stores a real `auth-id` in localStorage, but migrate-device links use a
room-scoped token from `:auth->migration` / `:migration->auth`. During `:hello`,
`server/on-connect` resolves `:migration-token` against that lobby before
registering the socket, so the URL never contains the real auth id. Views expose
the recipient's own migration token under `:you`; only moderator views receive
other players' migration tokens.

Moderation has three layers:

- `:owner-id` is immutable and is always a real mod.
- `:mods` are promoted real mods. Real mods can promote others; only the owner or
  the recorded promoter in `:mod-promoters` can demote a promoted mod.
- `:temp-mods` are remembered fallback moderators. After five minutes with no
  real mod online, `refresh-temp-mods` chooses an active temp mod, preferring
  remembered temp mods. While no real mod is online and `:active-temp-mod` is set,
  all temp mods have full mod powers. When a real mod returns, `:active-temp-mod`
  clears but `:temp-mods` remains for the next outage.

Custom-word mode is a lobby setting (`:custom-word-mode`). In normal mode,
`start-game` samples `:words` from the selected wordpacks and `mayor-pick` only
accepts one of those candidates. In custom mode, `start-game` leaves `:words`
empty and `mayor-pick` accepts any non-blank trimmed string from the Mayor. This
keeps the state machine unchanged (`:mayor-pick` still gates the transition into
`:question-round`) while swapping only the word source.
