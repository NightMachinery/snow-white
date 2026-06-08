# Handoff: Snow White — Thorough Visual Polish Pass

> **You are a fresh session with no prior context.** This document is complete and
> self-contained. Read it top to bottom, then execute. All paths are absolute.
> The repo is **Snow White**, a social-deduction + 20-questions party game with a
> **Clojure** backend and a **SvelteKit 5** frontend. The game is already
> **functionally complete and verified end-to-end**. Your job is the *visual
> polish*: make it polished, tasteful, cohesive, and delightful on mobile and
> desktop — without breaking the working game.

---

## 0. TL;DR of what to do

1. Bring up backend (`:3000`), frontend (`:5173`), confirm Chrome DevTools MCP is connected.
2. Capture **baseline screenshots** of every screen at mobile + desktop (both light & dark).
3. Critique against the rubric in §6. Make targeted, tasteful improvements.
4. Re-screenshot, compare, iterate until it clears the rubric.
5. Re-run the functional regression (§7) to prove you didn't break the game.
6. Commit in small atomic commits; push; update `docs/` if you change visual conventions.

Work on the `main` branch (the user works directly on main here). Commit at natural
endpoints. End commit messages with the Co-Authored-By trailer (see §8).

---

## 1. Repo & architecture orientation (so your changes fit)

- Repo root: `/Users/evar/code/games/snow-white`
- GitHub: `git@github.com:NightMachinery/snow-white.git` (branch `main`)
- **Read these first** (they explain conventions you must follow):
  - `/Users/evar/code/games/snow-white/AGENTS.md` — the dual mandate: this is BOTH
    a real game AND a learning project. Keep `docs/` current; Svelte must stay
    **runes-only** (no `export let`, no `$:`, no stores, no `on:`, no `<slot>`).
  - `/Users/evar/code/games/snow-white/docs/frontend-svelte.md` — Svelte 5 + Tailwind v4 conventions.
  - `/Users/evar/code/games/snow-white/docs/README.md` — the data-flow big picture.

- **The frontend is the only thing you'll edit.** It lives in
  `/Users/evar/code/games/snow-white/frontend`.
  - Global styles & design tokens: `frontend/src/app.css` (Tailwind v4 `@theme`).
  - Components: `frontend/src/lib/components/*.svelte`
    - `RoomHeader, PlayerSeat, LobbyScreen, Settings, RoleCard, MayorPick,`
      `Timer, TokenBoard, QuestionRound, VoteScreen, EndScreen, Rules, ThemeToggle`
  - Routes: `frontend/src/routes/+page.svelte` (home), `frontend/src/routes/room/[lobby]/+page.svelte` (game).
  - Pure data helpers (don't change behavior): `frontend/src/lib/game.ts`.
- **Do NOT change** the backend, the WebSocket protocol, `transit.ts`, `ws.svelte.ts`
  message handling, or `types.ts` shapes. This is a *visual* pass. If you believe a
  structural/markup change is needed for layout, that's fine — but preserve all
  behavior, all `conn.send(...)` calls, and all `game-state` rendering branches.

### The design language already in place (extend, don't fight it)
`frontend/src/app.css` defines a fairy-tale palette via OKLCH tokens:
- `--color-snow / frost / ink / mist` (neutrals), `--color-apple-{50,100,400,500,600}`
  (red accent), `--color-forest` (village/green), `--color-dusk` (wolves/purple).
- Fonts: `--font-display: Fraunces` (serif, headings), `--font-sans: Inter` (body).
- `--radius-card: 1.25rem`. Dark mode via `.dark` class on `<html>` (toggled in
  `frontend/src/lib/theme.svelte.ts`); the `dark:` Tailwind variant is wired.
- Reduced-motion is already respected globally — keep any animations behind that.

Icons: **Lucide via per-icon imports only** (`import X from '@lucide/svelte/icons/x'`).
Never barrel-import from `@lucide/svelte` (it slows `vite build`).

---

## 2. Start the environment

Run each in its own background shell. **Set `NO_PROXY` for localhost** (the user
has a global proxy; localhost must bypass it). For any `npm/pnpm/npx` that fetches
from the internet, also export the proxy (see §2.3).

### 2.1 Backend (Clojure, port 3000)
```bash
cd /Users/evar/code/games/snow-white/backend
NO_PROXY=127.0.0.1,localhost no_proxy=127.0.0.1,localhost clj -M:run
```
Confirm: `curl -s -m3 --noproxy '*' http://localhost:3000/health` → `ok`.

### 2.2 Frontend (SvelteKit dev, port 5173)
```bash
cd /Users/evar/code/games/snow-white/frontend
NO_PROXY=127.0.0.1,localhost no_proxy=127.0.0.1,localhost SNOW_BACKEND=http://localhost:3000 \
  pnpm dev --port 5173
```
Confirm: `curl -s -m3 --noproxy '*' -o /dev/null -w '%{http_code}' http://localhost:5173/` → `200`.
If `node_modules` is missing, install first (proxy needed — §2.3):
```bash
cd /Users/evar/code/games/snow-white/frontend
export ALL_PROXY=http://127.0.0.1:2089 all_proxy=http://127.0.0.1:2089 \
  http_proxy=http://127.0.0.1:2089 https_proxy=http://127.0.0.1:2089 \
  HTTP_PROXY=http://127.0.0.1:2089 HTTPS_PROXY=http://127.0.0.1:2089 \
  npm_config_proxy=http://127.0.0.1:2089 npm_config_https_proxy=http://127.0.0.1:2089 \
  NO_PROXY=127.0.0.1,localhost no_proxy=127.0.0.1,localhost
pnpm install
```

### 2.3 The user's proxy (for internet fetches only)
```
export ALL_PROXY=http://127.0.0.1:2089 all_proxy=http://127.0.0.1:2089 http_proxy=http://127.0.0.1:2089 https_proxy=http://127.0.0.1:2089 HTTP_PROXY=http://127.0.0.1:2089 HTTPS_PROXY=http://127.0.0.1:2089 npm_config_proxy=http://127.0.0.1:2089 npm_config_https_proxy=http://127.0.0.1:2089 NO_PROXY=127.0.0.1,localhost no_proxy=127.0.0.1,localhost
```
(Fonts come from Google Fonts at runtime in the browser — fine over the user's network.)

### 2.4 Chrome DevTools MCP
The repo ships `/Users/evar/code/games/snow-white/.mcp.json` configuring a
`chrome-devtools` MCP server that attaches to a Chrome at `http://127.0.0.1:9222`.
- Confirm Chrome is listening: `curl -s -m3 --noproxy '*' http://127.0.0.1:9222/json/version`
  should return JSON with a `"Browser"` field. If not, the user must launch Chrome
  with `--remote-debugging-port=9222` (ask them; do not kill their browser).
- Confirm the MCP tools are available to you: you should have `mcp__chrome-devtools__*`
  tools (navigate, screenshot, snapshot, click, fill, resize/emulate, evaluate, etc.).
  If they are NOT in your toolset, the server is likely **pending approval** — ask the
  user to approve it (`claude mcp list` shows status; approval is interactive). Do not
  proceed to the visual pass until these tools are live.

> Prefer the MCP's own screenshot/navigate/click tools over raw CDP. (A previous
> session found raw CDP `Page.captureScreenshot` unreliable against the user's
> headful Chrome; the MCP handles these quirks. If the MCP screenshot tool also
> struggles, fall back to its DOM `snapshot`/`evaluate` tools to measure layout, and
> note any screenshots you could not capture.)

---

## 3. Seed game states for screenshots

You need to view every screen. The backend is in-memory and ephemeral; create rooms
via the HTTP API, then open them in the browser. Helper facts:
- Create a room: `GET /api/create?authId=<id>&lobby=<name>` (through the frontend
  origin so the proxy applies: `http://localhost:5173/api/create?...`).
- Identity is per-browser in `localStorage` keys `snow:name` and `snow:auth-id`.
  Set them via `evaluate` before navigating to a room so you control who you are.
- To reach later game states you need ≥4 seated players. Two good options:

**Option A — drive the UI with multiple Chrome tabs/pages (closest to real use).**
Open N pages, each with a distinct `snow:auth-id`/`snow:name`, all navigated to
`/room/<name>`. Use the host (the creator's authId) to click **Start game**. Then:
mayor picks a word, a non-mayor asks a question, mayor clicks an answer, etc. This
mirrors exactly how the prior session verified the game (it works).

**Option B — drive state straight from the backend REPL, then screenshot the browser.**
Faster for reaching deep states. In a REPL (`clj` in `backend/`):
```clojure
(require '[snow-white.registry :as reg] '[snow-white.game :as game])
(reg/create-lobby! "alice" "shot")
(doseq [i (range 5)] (reg/update-lobby! "shot" game/join (str "p" i) (str "Player" i)))
(reg/update-lobby! "shot" game/start-game)        ; -> :mayor-pick
;; pick:
(let [l @(reg/get-lobby-atom "shot")] (reg/update-lobby! "shot" game/mayor-pick (:mayor l) (first (:words l))))
;; ask + answer to reach later states, e.g. correct => :word-guessed; or game/timeout => :out-of-time
```
Then open `http://localhost:5173/room/shot` in the browser **as one of the seated
players** (set `localStorage` `snow:auth-id` to e.g. `"p0"` and reload) to see that
player's redacted view. Use a player whose role gives an interesting view (e.g. the
mayor sees the pick screen; the seer/wolf sees the word).

> Tip: to screenshot a *specific role's* perspective, set `snow:auth-id` to that
> player's id. `@(reg/get-lobby-atom "shot")` shows `:mayor`, `:seer`, `:werewolves`.

### Screens to capture (the full matrix)
For **each**: mobile (390×844) and desktop (1280×800), in **light and dark**.
1. **Home** (`/`) — name/room form, both buttons.
2. **Lobby** (`game-state lobby`) — seated players grid, "Watching" chips, Settings
   panel, Start button (capture both a host view *and* a non-host view).
3. **Mayor pick** — the mayor's word-choice screen, AND a non-mayor "waiting" view.
4. **Role card** — capture villager, seer, and werewolf variants (different colors).
5. **Question round** — with several answered questions in the feed; the Mayor's
   **TokenBoard** with the pending question + answer buttons; the Timer (normal and
   the low/<15s red state).
6. **Vote — village** (`out-of-time`/`out-of-tokens`) — candidate grid, selected state.
7. **Vote — wolf** (`word-guessed`) — the dusk-tinted wolf vote panel.
8. **End screen** — both "Village wins" and "Wolves win", with the role reveal grid.
9. **Rules modal** — open overlay (bottom-sheet on mobile, centered on desktop).
10. **Errors / loading** — the "Connecting…" state and the "lobby not found" state
    (open `/room/does-not-exist`).

Save screenshots under `/tmp/snow-shots/<screen>-<viewport>-<theme>.png` and keep a
short index so you can compare before/after.

---

## 4. Viewports & emulation

Use the MCP's resize/emulation tool. Target at least:
- **Mobile**: 390×844 (iPhone 14-ish), `deviceScaleFactor` 2, mobile=true.
- **Small tablet** (spot check): 768×1024.
- **Desktop**: 1280×800.
Verify at each: **no horizontal scroll**, tap targets ≥ ~40px, nothing clipped,
text legible. (Prior session confirmed no h-scroll and button reflow at 390/1280 —
keep that property.)

---

## 5. How to iterate (tight loop)

For each screen: screenshot → critique against §6 → make a small edit in the
relevant `.svelte`/`app.css` → Vite HMR reloads → re-screenshot → compare. Keep
changes surgical and on-theme. After a batch of related changes, run
`pnpm check` (§7) to keep types/runes clean.

---

## 6. Polish rubric (what "tasteful & polished" means here)

Judge every screen against these. Fix what falls short; don't gold-plate.

**Layout & rhythm**
- Consistent spacing scale (Tailwind 4/8/12/16…); even gaps; aligned edges.
- Clear hierarchy: one obvious primary action per screen; secondary actions quieter.
- Comfortable max-widths (home is `max-w-md`; room `max-w-5xl`) and generous padding.
- Mobile: single column, thumb-reachable primary actions; nothing cramped.

**Typography**
- Fraunces for headings, Inter for body; sensible sizes; no orphaned giant text on mobile.
- Line-height and contrast meet readability (and WCAG AA contrast in both themes).

**Color & theming**
- Use the token palette; apple-red for primary, forest=village, dusk=wolves — keep
  this semantic mapping consistent (e.g. wolf-related UI leans dusk, village leans forest).
- Dark mode is first-class: check every screen in dark; no near-invisible borders,
  no pure-black-on-dark, no washed-out text.

**Components & states**
- Buttons: clear hover/active/disabled/focus-visible states; consistent radius/height.
- Inputs/selects: visible focus ring, consistent styling light & dark.
- Seats/cards: legible avatars, role/mayor/mod/offline badges clear at a glance.
- Empty states (no seated players, no answers yet) look intentional, not broken.
- Selected/voted states are unmistakable.

**Motion (subtle, optional, reduced-motion-safe)**
- Gentle transitions on state changes / list insertions (Svelte `transition:`/`animate:`
  are allowed and idiomatic). Keep them quick (≤200ms) and behind reduced-motion.
- A small celebratory touch on the End screen is welcome but must not be gaudy.

**Delight & theme cohesion**
- The "Snow White / fairy-tale" identity should read throughout (apple motif, soft
  snow/frost surfaces) without becoming cluttered or cute-to-a-fault. Tasteful > themed.

**Accessibility**
- `aria-label`s on icon-only buttons (ThemeToggle/Rules already have them — keep it up).
- Focus order sane; modal (Rules) traps/han­dles Escape and is dismissible; keyboard
  users can play (Enter submits the ask box, etc.).

**Polish nits to specifically check**
- The Timer's low-time state is noticeable but not alarming.
- The TokenBoard answer buttons are easy to scan and color-coded sensibly.
- The answered-questions feed is readable with many entries (scroll, ordering).
- The invite-link copy button in `RoomHeader` gives clear feedback.
- Long display names / long words don't break layouts (truncate gracefully).

---

## 7. Regression: prove you didn't break the game

After polishing, run these. All must pass.

**Static checks** (in `frontend/`):
```bash
NO_PROXY=127.0.0.1,localhost no_proxy=127.0.0.1,localhost pnpm check     # 0 errors, 0 warnings
NO_PROXY=127.0.0.1,localhost no_proxy=127.0.0.1,localhost pnpm build     # production build succeeds (adapter-node)
```

**Functional end-to-end** (the prior session's proven flow — reproduce it):
Drive a real 5-player game through the UI (Option A in §3) and confirm:
1. 5 players seat; everyone sees all 5 names; host sees **Start game** enabled.
2. Start → exactly one mayor sees "Choose the secret word"; others see "… is choosing".
3. Mayor picks a word → all 5 transition to **Question round**.
4. A non-mayor asks "Is it alive?" → the **Mayor sees** that question.
5. Mayor clicks **Correct!** → all see the **wolf** vote ("who is the Seer?").
6. Wolves pick + cast → game ends; all see a winner; the word is revealed on the End screen.
7. Host clicks **Play again** → back to **lobby**, seats preserved.
Also spot-check the **timeout path**: from a fresh game, reach `out-of-time` (REPL
`game/timeout`, or short timer) → the **village** vote shows → resolves to a winner.

**Redaction sanity** (don't leak secrets in the UI): as a plain villager during the
question round, confirm the secret word is **not** shown anywhere in the DOM; as the
mayor/seer/wolf, confirm it **is** shown in the "you know the word" hint.

If a polish change broke any of the above, fix it before committing.

---

## 8. Commit, push, document

- Make **small atomic commits** grouped by area (e.g. "Polish lobby & seating",
  "Refine end screen + reveal", "Tune dark-mode surfaces", "Add subtle list/transition
  motion"). Don't lump everything into one commit.
- If you introduce new visual conventions (a new token, a motion pattern, a component
  style), **update `docs/frontend-svelte.md`** (and `learn/svelte/` if it's a teachable
  technique) — this is required by the dual mandate in `AGENTS.md`.
- Commit message trailer (required):
  ```
  Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
  ```
- Push at the end: `git push origin main`.
- Keep before/after screenshots handy and summarize the changes for the user, ideally
  with a few representative after-shots per screen (mobile + desktop, light + dark).

---

## 9. Guardrails (read before editing)

- **Behavioral freeze:** no changes to game rules, protocol, transit, or message
  dispatch. Visual/markup/CSS/motion only. Every `conn.send({...})` and every
  `game-state` branch in `room/[lobby]/+page.svelte` must still be present and reachable.
- **Runes only**, no legacy Svelte. Run `pnpm check` — it must stay at 0 warnings
  (the project treats the runes/idiom warnings as failures).
- **Per-icon Lucide imports only.**
- **Don't touch the user's Chrome process / profile** beyond navigating tabs you open.
- **Don't add heavy dependencies** for polish; prefer Tailwind utilities, CSS, and
  Svelte's built-in `transition:`/`animate:`. If you think a library is truly needed,
  ask the user first.
- If something is ambiguous (e.g. how playful vs. minimal the user wants it), make a
  tasteful default, note it, and show the user — don't stall.

---

## 10. Definition of done

- [ ] Every screen in §3 screenshotted at mobile + desktop, light + dark.
- [ ] Each clears the §6 rubric (or deviations are noted with rationale).
- [ ] No horizontal scroll, no clipping, legible & AA-contrast in both themes.
- [ ] `pnpm check` = 0/0; `pnpm build` succeeds.
- [ ] Full functional regression (§7) passes, including redaction sanity.
- [ ] Small atomic commits pushed to `main`; `docs/` updated if conventions changed.
- [ ] A summary with representative after-screenshots delivered to the user.
