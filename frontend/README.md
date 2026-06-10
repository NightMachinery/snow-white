# Snow White frontend

This is the SvelteKit 5 client for Snow White. It is built as a static app so
Caddy can serve production files directly.

## Development

From the repo root, prefer:

```bash
./self_host.py dev-start
```

That starts the Clojure backend in a REPL tmux session and runs Vite with hot
reload.

For frontend-only checks:

```bash
nvm-load
nvm use 24
pnpm install --frozen-lockfile
pnpm check
pnpm build
```

## Production build

`pnpm build` writes static files to `frontend/build`. In production, Caddy serves
that directory and proxies `/api/*`, `/ws`, and `/health` to the Clojure backend.

Fonts are vendored under `static/fonts`; do not add runtime Google Fonts links.
