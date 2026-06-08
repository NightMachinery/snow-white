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
- **Commits:** atomic and logically grouped; commit at natural endpoints; push at the end.

## Skills

Reusable skills live in `.agents/skills/`. Most relevant here: `svelte-code-writer`,
`svelte-core-bestpractices`, `tailwind-css-patterns`, `websocket-engineer`, `api-designer`,
`architecture-designer`, `code-documenter`, `test-driven-development`,
`verification-before-completion`.
