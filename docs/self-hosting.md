# Self-hosting

Snow White is two processes: the Clojure backend (HTTP + WebSocket) and the
SvelteKit frontend (a Node server, via `adapter-node`). For a small group on a
LAN or a single VPS, run both behind one reverse proxy.

## Build

```bash
# Backend: run from source with the Clojure CLI (no build step needed)
cd backend
clj -M:run            # listens on $PORT or 3000

# Frontend: build the Node server bundle
cd frontend
pnpm install
pnpm build            # outputs build/ (adapter-node)
SNOW_BACKEND=http://localhost:3000 node build   # serves on $PORT or 3000
```

> The frontend's dev proxy (`vite.config.ts`) only applies to `pnpm dev`. In
> production the reverse proxy is responsible for routing `/api` and `/ws` to the
> backend; everything else goes to the SvelteKit Node server.

## Reverse proxy (Caddy example)

```
snow.example.com {
    # WebSocket + HTTP API → Clojure backend
    @backend path /ws /api/*
    reverse_proxy @backend localhost:3000

    # Everything else → SvelteKit Node server
    reverse_proxy localhost:5173
}
```

Run the SvelteKit server on a port distinct from the backend (set `PORT` for
each). Caddy upgrades `/ws` to a WebSocket automatically.

## Notes

- **Ephemeral by design:** lobbies live in memory and vanish when the last player
  disconnects. There is no database. Restarting the backend clears all rooms.
- **HTTPS:** when served over `https`, the client uses `wss://` automatically
  (see `ws.svelte.ts`), so the proxy must terminate TLS and forward the upgrade.
- **Scaling:** per-lobby atoms scale across cores on one machine. Cross-machine
  scaling would need to pin a lobby to a node (lobbies share nothing, so this is
  feasible later) — not needed for LAN/party use.
