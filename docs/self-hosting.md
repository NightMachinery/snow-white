# Self-hosting Snow White

Snow White is self-hosted as one Clojure process plus static frontend files:

- **Caddy** terminates HTTP/HTTPS, serves the built Svelte files, and proxies
  `/api/*`, `/ws`, and `/health` to Clojure.
- **tmux** keeps the backend and development server sessions inspectable.
- **No Docker** is used.

The helper script is:

```bash
./self_host.py [setup|redeploy|start|stop|dev-start|status] [url]
```

If you omit `url`, the script uses the last saved URL from `.self-host/config.json`;
if no config exists yet, it defaults to:

```text
https://snow-white.pinky.lilf.ir
```

Pass only an origin, such as `https://game.example.test` or
`http://192.168.1.20:8080`. Paths like `/room/demo` are rejected because room
links are app routes, not deployment origins.

## Commands

`setup [url]` stops existing Snow White tmux sessions, installs frontend
dependencies, builds the static app, writes the Caddy block, reloads Caddy, and
starts production.

Frontend commands run through `zsh`, call `nvm-load`, and then `nvm use 24`
from `frontend/.nvmrc`. This is deliberate: current `pnpm` requires a modern
Node, and some servers still have an older system `node` first on `PATH`. The
install step also sets `CI=true` so `pnpm` does not stop for an interactive
`node_modules` recreation prompt during redeploys.

`setup [url]` also runs `pnpm dedupe` as a one-time dependency maintenance pass.
Normal redeploys skip it so they do not perform an extra resolver pass against
the package registry.

`redeploy [url]` is the normal "ship latest local changes" command. It stops any
prod or dev sessions, runs `pnpm install --frozen-lockfile`, rebuilds the
frontend, refreshes Caddy, and starts production.

`start [url]` switches Caddy to production mode and starts only the Clojure
backend. Caddy serves `frontend/build` directly, so there is no Node static file
server in production. If `frontend/build` is missing, `start` builds it first.

`dev-start [url]` switches Caddy to development mode. It starts:

- backend tmux session: `clj -M:dev`, then sends `(go <backend-port>)` into
  that REPL
- frontend tmux session: `pnpm dev --host ... --port <frontend-dev-port>`

On macOS the Vite dev server listens on `localhost`, which is right for local
development. On non-macOS it listens on `0.0.0.0`, which is right for a remote
server using the configured domain name even in dev mode.

The helper defaults to uncommon local ports instead of the usual demo ports:

- `38931`: Clojure backend
- `38932`: Vite dev server

If either port is already occupied, the helper scans upward for the next free
port, writes the chosen values to `.self-host/config.json`, and reuses those
saved values on later runs. If a saved port later becomes busy, the helper picks
and saves a replacement before updating Caddy and starting tmux sessions.

`stop` kills only Snow White's tmux sessions.

`status` prints the saved URL/mode, tmux session state, port state, and backend
health.

Commands that start serving (`setup`, `redeploy`, `start`, and `dev-start`)
finish by printing `serving: <url>` so the live URL is visible after the Caddy
reload and tmux sessions succeed.

## Caddy behavior

The script edits only the managed section in `~/Caddyfile`:

```text
# BEGIN snow-white self_host.py
...
# END snow-white self_host.py
```

For an HTTPS deployment, it writes an explicit HTTP-to-HTTPS redirect block:

```caddyfile
http://snow-white.pinky.lilf.ir {
    redir https://snow-white.pinky.lilf.ir{uri} permanent
}
```

For an HTTP deployment, it writes the opposite HTTPS-to-HTTP redirect. This is
intentional for intranet installs where HTTPS may not be available or useful.

In production, the main Caddy site looks conceptually like this:

```caddyfile
https://snow-white.pinky.lilf.ir {
    @backend path /api/* /ws /health
    reverse_proxy @backend localhost:38931

    root * /path/to/repo/frontend/build
    try_files {path} /index.html
    file_server
}
```

The `try_files` line is what makes direct visits to `/room/<name>` work with a
static Svelte build.

In development, the backend routes still go to Clojure, while everything else is
proxied to Vite for hot reload.

## Ports and sessions

The script saves the selected runtime ports in `.self-host/config.json`:

```json
{
  "mode": "dev",
  "ports": {
    "backend": 38931,
    "frontend_dev": 38932
  },
  "url": "https://snow-white.pinky.lilf.ir"
}
```

Manual backend workflows use the same backend default. `(go)` in the Clojure
dev REPL starts `:38931`, and `(go 39000)` starts the same server on an explicit
port. `clj -M:run` defaults to `:38931`, still honors `PORT`, and also accepts a
port argument.

tmux sessions:

- `snow-white-backend`
- `snow-white-dev-backend`
- `snow-white-dev-frontend`

Attach to a session with:

```bash
tmux attach -t snow-white-dev-backend
```

The development backend session is deliberately REPL-driven. It runs `clj -M:dev`
and then `(go)`, so the live server is the same Clojure process you inspect and
drive from the REPL.

## Proxy environment

`self_host.py` does not hardcode proxy values. If proxy environment variables
are already present, it passes them into tmux with hardened `tmux -e
"VARIABLE=value"` arguments. This includes:

```text
ALL_PROXY all_proxy http_proxy https_proxy HTTP_PROXY HTTPS_PROXY
npm_config_proxy npm_config_https_proxy NO_PROXY no_proxy
```

Set proxies in your shell before running the script when your network needs
them.

## Intranet notes

The frontend does not depend on Google at runtime. Inter and Fraunces are
vendored under `frontend/static/fonts` and loaded from local `@font-face` rules
in `frontend/src/app.css`.

There are no captcha checks or Firebase services in this repo. Game state lives
in memory in the Clojure backend.

The WebSocket client chooses `ws://` or `wss://` dynamically from the current
page protocol, so the same build works on both HTTP intranet deployments and
HTTPS public deployments.

The invite-link copy button first tries `navigator.clipboard`; when that API is
unavailable on plain HTTP, it falls back to a legacy copy path and then a manual
prompt.
