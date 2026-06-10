# chrome-cdp — drive Chrome directly, when the MCP won't

A dependency-free way to **screenshot and inspect the running app** by talking to
a headful Chrome over the Chrome DevTools Protocol (CDP) on `:9222`, using Node's
built-in `WebSocket` (Node ≥ 22). Use it for visual verification, the screenshot
matrix, and "does this reflow / is there horizontal scroll / what text is on
screen" checks.

## Why not just use the chrome-devtools MCP?

The repo ships a `chrome-devtools` MCP server (`.mcp.json`). When it works, prefer
it — its `navigate_page` / `take_screenshot` / `take_snapshot` / `click` are
ergonomic. **But in practice it is flaky in long sessions:** individual tools
intermittently disappear from the toolset (`No such tool available: …`) even
though `claude mcp list` shows `✓ Connected`, and after a `/mcp` reconnect the
server can sit in `⏸ Pending approval`. Recovering means asking the user to `/mcp`
reconnect (sometimes twice) — which stalls the work.

This skill bypasses all of that: it opens a raw WebSocket to the same Chrome and
speaks CDP. The browser is the same one the MCP attaches to, so nothing else
changes. A prior session confirmed raw CDP `Page.captureScreenshot` works fine
here (the MCP's value was quirk-handling, not capability).

## Prerequisites

- Chrome listening for remote debugging:
  `curl -s --noproxy '*' http://127.0.0.1:9222/json/version` returns JSON with a
  `"Browser"` field. (The user launches Chrome with `--remote-debugging-port=9222`;
  don't kill their browser.)
- The frontend dev server up on `:38932` and backend on `:38931` (see repo `AGENTS.md`
  / `docs/README.md` for how to start them; remember `NO_PROXY=127.0.0.1,localhost`).
- Node ≥ 22 (built-in `WebSocket`). This repo's Node is fine (`node -v`).

## The screenshot/probe tool

[`shot.mjs`](./shot.mjs). Run it from anywhere:

```bash
# Home page, dark theme, mobile viewport:
node skills/chrome-cdp/shot.mjs --vp=mobile --theme=dark --out=/tmp/home.png

# A room from a specific seated player's perspective, with a DOM probe:
node skills/chrome-cdp/shot.mjs --room=darkroom --auth=v2 --name=Gretel \
  --theme=dark --vp=desktop --probe --out=/tmp/qround-dark.png
```

Flags: `--room` (omit for home), `--auth`/`--name` (identity written to
localStorage before navigating), `--theme=light|dark`, `--vp=desktop|mobile`,
`--out=PATH` (PNG), `--probe` (print JSON). Env overrides: `SNOW_ORIGIN`
(default `http://localhost:38932`), `CHROME_CDP` (default `http://127.0.0.1:9222`).

`--probe` prints, e.g.:

```json
{"dirAuto":5,"htmlDir":"ltr","hscroll":false,"iw":1280,"sw":1280,"body":"Snow White\n…"}
```

- `hscroll:false` ⇒ no horizontal scroll at this viewport (a rubric requirement).
- `htmlDir` / `dirAuto` ⇒ RTL handling (UI stays `ltr`; user content uses `dir="auto"`).
- `body` ⇒ the first 400 chars of visible text, so you can assert state without pixels.

## Key know-how (the things that bite you)

1. **Viewport: override metrics, don't resize the window.** A headful Chrome
   window may be small, so resizing it clamps `innerWidth` (we saw 500px when
   asking for 1280). `Emulation.setDeviceMetricsOverride` (what `shot.mjs` does,
   and the MCP `emulate` tool) overrides via CDP regardless of window size.

2. **Identity lives in `localStorage`, which is shared across same-origin tabs.**
   Keys: `snow:auth-id`, `snow:name`, `snow:theme`. `shot.mjs` loads the origin
   first, sets these, then navigates to the room — so a single tab can impersonate
   any player in sequence. (With the *MCP* and multiple simultaneous tabs you must
   give each tab a distinct `isolatedContext`, or the last localStorage write wins
   for all of them — see repo `AGENTS.md`. With this script you screenshot one
   perspective at a time, so that pitfall doesn't apply.)

3. **Drive players from the REPL, not from N browser tabs.** Start the backend
   with a socket REPL and seat/play everyone with pure calls; open a browser only
   for the perspective you want to capture. To get a socket REPL on the *running*
   server, start it as:

   ```bash
   clj -J-Dclojure.server.repl='{:port 5555 :accept clojure.core.server/repl}' -M:run
   ```

   Then pipe forms to it (each `<<…>>` marker is echoed back so you can grep it):

   ```bash
   { printf '(in-ns (quote snow-white.registry))\n(require (quote [snow-white.game :as game]))\n'; \
     cat <<'EOF'; printf '\n'; } | nc -w 4 localhost 5555 | tr '\r' '\n' | grep -oE '<<.*>>'
   (when (lobby-exists? "darkroom") (destroy-lobby! "darkroom"))
   (create-lobby! "h" "darkroom")
   (doseq [[id nm] [["h" "Briar Rose"] ["w" "Hunter"] ["v1" "Grimm"] ["s" "Rapunzel"] ["v2" "Gretel"]]]
     (update-lobby! "darkroom" game/join id nm))
   (update-lobby! "darkroom" game/set-timer 5)            ; long timer: won't expire mid-shoot
   (update-lobby! "darkroom" game/start-game)
   (let [l @(get-lobby-atom "darkroom") m (:mayor l)]
     (update-lobby! "darkroom" game/mayor-pick m (first (:words l)))
     (update-lobby! "darkroom" game/ask-question (first (remove #{m} (keys (:players l)))) "Is it alive?")
     (update-lobby! "darkroom" game/answer-question m :yes))
   (println (str "<<state=" (:game-state @(get-lobby-atom "darkroom")) ">>"))
   EOF
   ```

   The REPL mutates the *same in-memory state the live server serves*, so the
   browser reflects it immediately. (A *separate* `clj` process has its own state
   and won't affect the running server — drive the process that is serving.)

4. **Empty lobbies persist 14 days** (a reaper in `registry.clj`), so a room won't
   vanish just because no socket is connected while you set up shots.

5. **Set a long timer (`game/set-timer 5`)** before driving into the question
   round, or the 1-minute default may flip the game to the vote screen mid-shoot.

6. **Reach deep states by REPL, then screenshot the role that makes them
   interesting:** mayor → sees the TokenBoard; seer/wolf → "you know the word"
   hint and the wolf vote; any seated player → the village vote. Use `--auth` to
   pick whose redacted view you capture.

## Minimal CDP, if you need more than screenshots

`shot.mjs` is ~80 lines; copy its `send()` helper to issue any CDP method
(`DOM.*`, `Input.dispatchMouseEvent` to click, `Runtime.evaluate` for arbitrary
JS, `Page.printToPDF`, etc.). The protocol is just `{id, method, params}` in and
`{id, result}` out over the target's `webSocketDebuggerUrl`.
