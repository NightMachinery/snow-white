# Snow White — Agent Guide

Snow White is a modern rewrite of the social-deduction + 20-questions party game
"Were Words" (formerly WordWolf). It pairs a **Clojure** backend with a **SvelteKit 5**
frontend over WebSockets.

## Dual mandate (IMPORTANT — do not drop this)

This project has **two equally important goals**:

1. **Ship a great game** the owner can actually play with friends — polished, tasteful,
   responsive (mobile + desktop), and fun.
2. **Be a learning vehicle** for the owner to deepen **Svelte** and **Clojure web dev**.
   The owner knows some Clojure but not much, and is new to Svelte.

Because of goal 2:

- Keep `docs/` continuously updated as you build, written in a **teaching voice** that uses
  this repo's real code as the worked example. `docs/` is for understanding *this project*.
- Use `learn/` for **focused, standalone skill-deepening material** (Clojure and Svelte
  concepts, exercises, mental models) that goes beyond just this codebase.
- Prefer **idiomatic, modern** code (Clojure: pure functions + atoms; Svelte 5: runes only)
  and briefly explain *why* an idiom is preferred when it is non-obvious.
- When you introduce a new concept (a rune, an atom pattern, transit, http-kit), leave a
  short note in the relevant `docs/` or `learn/` file.

## Architecture (one-paragraph orientation)

The backend keeps **one atom per lobby** (lobbies are embarrassingly parallel — no global
lock) plus a thin registry atom mapping `lobby-id -> lobby-atom`. All game rules are **pure
functions** `(f lobby & args) -> lobby`, applied with `swap!` at the edge. The server
(`http-kit`) broadcasts a **redacted full lobby snapshot** to every client in a room after
each command; the Svelte client is a pure function of that snapshot. The wire format is
**transit-json**. See `docs/` for the teaching-level detail and `docs/protocol.md` for the
message contract.

## Conventions

- **Clojure:** develop REPL-first. Pure logic in `game.clj`/`roles.clj` must be testable with
  no server running. Keep side effects (sockets, broadcast) at the edges (`server.clj`).
- **Svelte:** runes only (`$state`, `$derived`, `$effect`) — no `export let`, no `$:`, no
  stores, no `on:` directives, no `<slot>`. Follow the `svelte-core-bestpractices` skill.
- **Styling:** Tailwind, mobile-first, dark mode, respect reduced-motion.
- **Self-hosting/dev:** use `./self_host.py dev-start` for local macOS development unless
  the user explicitly asks for production `start`; `dev-start` keeps the backend in a REPL
  tmux session and switches Caddy to the Vite dev server.
- **Commits:** atomic and logically grouped; commit at natural endpoints; push at the end.

## Driving game states for testing & screenshots

Reaching a mid-game screen (question round, votes, end) needs ≥4 seated players and
several actions. Two mechanisms, **used together**:

- **Drive the bulk of players from the REPL — not from browser tabs.** Run the backend
  REPL-first (`clj -M:dev` → `(go)`, or start the server with a socket REPL:
  `clj -J-Dclojure.server.repl='{:port 5555 :accept clojure.core.server/repl}' -M:run`).
  Then seat and play *all* the players with pure calls — no sockets required:

  ```clojure
  (in-ns 'snow-white.registry)
  (require '[snow-white.game :as game])
  (create-lobby! "host-auth" "shots")
  (doseq [[id nm] [["host-auth" "Briar Rose"] ["p2-auth" "Hunter"]
                   ["p3-auth" "Grimm"] ["p4-auth" "Rapunzel"] ["p5-auth" "Gretel"]]]
    (update-lobby! "shots" game/join id nm))
  (update-lobby! "shots" game/start-game)                  ; -> :mayor-pick
  (let [l @(get-lobby-atom "shots")]
    (update-lobby! "shots" game/mayor-pick (:mayor l) (first (:words l))))
  (update-lobby! "shots" game/ask-question "p2-auth" "Is it a place?")
  (update-lobby! "shots" game/answer-question (:mayor @(get-lobby-atom "shots")) :yes)
  ```

  The REPL holds the *same in-memory state the live server serves*, so a browser opened
  to that room immediately reflects whatever the REPL has done. (A *separate* `clj`
  process would have its own state and would NOT affect the running server — drive the
  process that is actually serving.)

- **Open a browser tab only for each perspective you need to *see* — one tab per role,
  each in its own `isolatedContext`.** Identity lives in `localStorage`
  (`snow:auth-id`, `snow:name`, `snow:theme`), and **`localStorage` is shared across all
  same-origin tabs**. So if you open N plain tabs and set a different `snow:auth-id` in
  each, the last write wins for *all* of them — every tab ends up as the same player and
  only one seat fills. With the Chrome DevTools MCP, give each player tab a **distinct**
  `isolatedContext` (`new_page` with `isolatedContext:"p2"`, `"p3"`, …): pages in the
  same context share storage, different contexts are fully isolated. Set that tab's
  `localStorage` to the auth-id of the role you want to view (e.g. the mayor to see the
  TokenBoard, a villager to see the ask box), then navigate to `/room/<name>`.

  You rarely need more than one or two tabs — drive the rest from the REPL.

> **Lobby retention:** an empty lobby is kept for **14 days** (a background reaper in
> `registry.clj` deletes it only after it has stayed empty that long); rejoining resets
> the clock. So a room won't vanish mid-session just because every socket briefly
> disconnected. Use `emulate` (not `resize_page`) to set the MCP viewport — a headful
> Chrome clamps `resize_page` to its small window, whereas `emulate` overrides via CDP.

> **When the chrome-devtools MCP is flaky** (its tools intermittently vanish from the
> toolset mid-session and need a `/mcp` reconnect), fall back to
> [`skills/chrome-cdp`](skills/chrome-cdp/README.md) — a dependency-free
> screenshot/probe tool (`node skills/chrome-cdp/shot.mjs --room=… --auth=… --theme=… --vp=… --out=…`)
> that talks straight to Chrome on `:9222` via CDP. Its README also documents the full
> REPL-driven, one-tab-per-perspective screenshot workflow.

## Skills

Reusable skills live in `.agents/skills/`. Most relevant here: `svelte-code-writer`,
`svelte-core-bestpractices`, `tailwind-css-patterns`, `websocket-engineer`, `api-designer`,
`architecture-designer`, `code-documenter`, `test-driven-development`,
`verification-before-completion`.
