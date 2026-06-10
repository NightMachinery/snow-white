# Snow White — Docs & Architecture Tour

These docs do double duty: they explain **how Snow White works**, and they teach
**Svelte + Clojure web development** using this codebase as the worked example.
If you're learning, read in this order:

1. **This file** — the big picture and the data flow.
2. [`game-rules.md`](game-rules.md) — what the game actually is.
3. [`backend-clojure.md`](backend-clojure.md) — the Clojure server, REPL-first.
4. [`protocol.md`](protocol.md) — the WebSocket message contract (the seam).
5. [`frontend-svelte.md`](frontend-svelte.md) — the SvelteKit 5 client.

For standalone concept drills (atoms, runes, transducers, reactivity), see
[`../learn/`](../learn).

## The one big idea

> **The server owns the truth. After every action it broadcasts a fresh,
> per-player-redacted snapshot of the whole lobby. The client is a pure function
> of the snapshot it last received.**

Everything else follows from this:

```
            ┌─────────────────────────────────────────────┐
            │           Clojure backend (:38931)           │
 browser    │                                              │
 ───────────┤  registry: {lobby-name -> atom(lobby-map)}   │
  WebSocket │       │            ▲                          │
   (transit)│       │ swap! pure │ game fns                 │
            │       ▼            │                          │
            │   game.clj (pure: lobby -> lobby)            │
            │       │                                       │
            │       ▼  after each command:                  │
            │   views/lobby-view  ── redact per recipient ──┼──► push snapshot
            └─────────────────────────────────────────────┘     to every client
                                                                       │
   ┌───────────────────────────────────────────────────────────────────┘
   ▼
 SvelteKit client: conn.lobby = $state(snapshot)
   → every component is $derived from conn.lobby
   → user actions call conn.send({type: ...})  ───► back to the server
```

There are **no client-side game rules** and **no deltas**. The client never
computes "is the game over" — it reads `lobby.game-state`. This keeps the two
languages from drifting and makes the whole thing easy to reason about.

## Why these technology choices

| Choice | Reason |
| --- | --- |
| **One atom per lobby** | Lobbies are independent — no rule touches two. Per-lobby atoms mean room A's writes never contend with room B's, so the server scales across cores naturally. A tiny registry atom maps name→atom and only changes on create/destroy. |
| **Pure game functions** | `(f lobby & args) -> lobby` with zero I/O can be unit-tested and explored in the REPL with no server running. Mutation (`swap!`) lives only at the edge. |
| **http-kit + raw WebSocket** | http-kit is a tiny Clojure server with first-class WebSocket support. Browsers speak WebSocket natively, so the Svelte client needs no socket library. |
| **transit-json wire** | Losslessly carries Clojure data (keywords, sets, vectors) over JSON. The client converts keywords↔strings at one boundary (`transit.ts`). |
| **Per-recipient redaction** | The word and hidden roles are secret. The server sends each client only what they may see (`views.clj`) — a correctness fix over the original game, which leaked secrets to everyone. |
| **SvelteKit 5 runes** | `$state`/`$derived` model "UI = f(state)" directly. The single `conn.lobby` snapshot is the state; everything derives from it. |

## Running it

```bash
# terminal 1 — backend (REPL-driven)
cd backend && clj -M:dev          # then at the prompt: (go)
# or one-shot:  clj -M:run

# terminal 2 — frontend
cd frontend && pnpm install && pnpm dev
```

Vite proxies `/api` and `/ws` to the backend (see `frontend/vite.config.ts`), so
the browser only talks to the Vite origin in dev — no CORS, and the WebSocket
upgrade is forwarded transparently.

## Where things live

```
backend/src/snow_white/
  game.clj      ← pure rules (the part to read first)
  roles.clj     ← role dealing, mayor pick, win resolution (pure)
  words.clj     ← the word bank
  ids.clj       ← random auth ids + migration tokens
  registry.clj  ← per-lobby atoms + connection tracking (mutation lives here)
  views.clj     ← per-recipient redaction
  server.clj    ← http-kit, transit, command dispatch, broadcast (the edge)
  core.clj      ← production entrypoint
backend/dev/user.clj   ← REPL workbench: (go) (seed! 5) (sim! "name")

frontend/src/lib/
  ws.svelte.ts   ← the WebSocket client; holds conn.lobby = $state(snapshot)
  transit.ts     ← keyword/map bridge
  types.ts       ← client half of the protocol contract
  game.ts        ← pure view helpers (seatedPlayers, activeCount, …)
  identity / theme.svelte.ts  ← localStorage-backed state
  components/*.svelte          ← one per screen + shared pieces
frontend/src/routes/
  +page.svelte                 ← home (create/join)
  room/[lobby]/+page.svelte    ← renders one screen per game-state
```
