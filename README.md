# Snow White 🍎

A modern, self-hostable, browser-based party game — a blend of **social deduction**
(werewolf) and **20 questions**. Players take seats, get hidden roles (Villager, Seer,
Werewolf), a randomly-chosen **Mayor** picks a secret word, and everyone asks yes/no
questions the Mayor answers — racing to guess the word before the tokens or timer run out,
then voting to unmask the hidden roles.

> Snow White is a ground-up rewrite of the older "Were Words" / WordWolf game, rebuilt with a
> **Clojure** backend and a **SvelteKit 5** frontend. It is also a learning project — see
> [`AGENTS.md`](AGENTS.md) and [`docs/`](docs/).

## Stack

| Layer       | Tech                                                        |
| ----------- | ---------------------------------------------------------- |
| Backend     | Clojure · `http-kit` (HTTP + WebSocket) · per-lobby atoms · transit-json |
| Frontend    | SvelteKit 5 (runes) · Vite · TypeScript · Tailwind CSS     |
| Realtime    | Native WebSocket, full redacted-snapshot broadcast         |

## Repo layout

```
backend/     Clojure game server (deps.edn, REPL-driven)
frontend/    SvelteKit 5 app
docs/        Teaching-level docs: how this project works (Svelte + Clojure)
learn/       Standalone skill-deepening notes & exercises
.agents/     Reusable agent skills
```

## Quick start

Backend (REPL-driven):

```bash
cd backend
clj -M:dev          # starts an nREPL; then (go) to start the server
```

Frontend:

```bash
cd frontend
pnpm install
pnpm dev
```

Open the printed URL, create a room, and share the room link.

For Caddy/tmux self-hosting, see [`docs/self-hosting.md`](docs/self-hosting.md):

```bash
./self_host.py setup https://snow-white.pinky.lilf.ir
./self_host.py redeploy
./self_host.py dev-start
```

## Learning the codebase

Start with [`docs/README.md`](docs/README.md) for the architecture tour, then
[`docs/backend-clojure.md`](docs/backend-clojure.md) and
[`docs/frontend-svelte.md`](docs/frontend-svelte.md). The WebSocket contract lives in
[`docs/protocol.md`](docs/protocol.md); the rules in [`docs/game-rules.md`](docs/game-rules.md).
