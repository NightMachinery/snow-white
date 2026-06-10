# The SvelteKit Frontend — a guided tour

For someone new to Svelte. We use **Svelte 5 (runes)** and **SvelteKit 2**, the
current idioms. The guiding principle matches the backend: **UI = f(state)**,
where the state is the lobby snapshot the server last sent.

## 0. Project shape (SvelteKit)

```
frontend/
  svelte.config.js   ← static adapter + forces runes mode
  vite.config.ts     ← Tailwind plugin + dev proxy for /api and /ws
  src/
    app.css          ← Tailwind v4 entry + design tokens (@theme)
    app.html         ← page shell
    routes/          ← file-based routing
      +layout.svelte           ← wraps every page (theme, global CSS)
      +layout.ts               ← static SPA route config
      +page.svelte             ← "/" home
      room/[lobby]/+page.svelte ← "/room/:lobby" the game
      room/[lobby]/+page.ts     ← route config: client-only dynamic rooms
    lib/             ← importable as $lib/...
      *.svelte.ts    ← modules that USE runes (note the extension!)
      components/*.svelte
```

> **Learning note — file-based routing.** A folder under `routes/` is a URL
> segment; `[lobby]` is a dynamic param read via `page.params.lobby`.
> `+page.svelte` is the page; `+layout.svelte` wraps all child pages;
> `+page.ts` configures a page (e.g. turn off server-side rendering).

## 1. Runes: the reactivity model

Svelte 5 replaces the old `let x` / `$:` magic with explicit **runes**. The four
you need here:

### `$state` — a reactive value

```ts
let name = $state('');     // reassigning name re-renders anything that reads it
```

Used in components *and*, crucially, in **`.svelte.ts` modules** — that file
extension is what lets you use runes outside a component. See
`identity.svelte.ts`, `theme.svelte.ts`, and the big one, `ws.svelte.ts`:

```ts
class Connection {
  lobby = $state<Lobby | null>(null);   // the entire game state lives here
  ...
}
export const conn = new Connection();
```

> **Learning note — classes with `$state` fields.** The modern way to share
> reactive state between components is *not* a writable store — it's a class with
> `$state` fields, exported as a singleton. Components import `conn` and read
> `conn.lobby`; when the WebSocket reassigns `conn.lobby`, every reader updates.

### `$derived` — computed state

```ts
const nameOk = $derived(name.trim().length > 0);
const state  = $derived(lobby?.['game-state']);
```

Prefer `$derived` over `$effect` for anything you *compute*. It's lazy, cached,
and re-evaluates only when its inputs change. Use `$derived.by(() => {...})` when
the expression needs a function body.

### `$effect` — synchronize with the outside world (sparingly)

Effects are an escape hatch for *side effects*, not for computing values. We use
them for exactly three things:

- Opening/closing the WebSocket when the room page mounts/unmounts:
  ```ts
  $effect(() => {
    conn.connect(room, identity.name || 'Player');
    return () => conn.disconnect();   // cleanup on unmount / room change
  });
  ```
- The countdown timer's `setInterval` (with a cleanup return).
- Applying the persisted theme class on mount.

> **Anti-pattern to avoid:** don't write to `$state` inside an `$effect` to
> "compute" something — use `$derived`. Effects that set state cause extra
> renders and are hard to reason about.

### `$props` — component inputs

```ts
let { lobby, mode = 'village' }: { lobby: Lobby; mode?: 'village' | 'wolf' } = $props();
```

Replaces the old `export let`. Treat props as changeable: derive from them with
`$derived`, don't snapshot them into plain `let`.

## 2. The data flow in practice

`ws.svelte.ts` is the heart:

1. `connect(room, name)` opens a `WebSocket`, sends `:hello`.
2. `onmessage` decodes a transit frame. On `:lobby/state` it does
   `this.lobby = msg.lobby` — a **wholesale replacement**, not a mutation.
3. Components read `conn.lobby` through `$derived`; Svelte re-renders the parts
   that changed.
4. User actions call `conn.send({type: 'game/answer', answer: 'yes'})`.

Because the server always sends a *complete* snapshot, the client carries no
game logic and never drifts from the server. The room page is a pure switch over
`game-state`:

```svelte
{#if state === 'lobby'}        <LobbyScreen {lobby} />
{:else if state === 'mayor-pick'} <MayorPick {lobby} />
{:else if state === 'question-round' || state === 'word-guessed'}
                               <QuestionRound {lobby} />
{:else if state === 'out-of-time' || state === 'out-of-tokens'}
                               <VoteScreen {lobby} mode="village" />
{:else if state === 'end-game'} <EndScreen {lobby} />
{/if}
```

## 3. Templates: blocks, events, snippets

- **Keyed each** (always key by a stable id, never the index):
  ```svelte
  {#each seated as p (p['auth-id'])}
    <PlayerSeat player={p} {lobby} />
  {/each}
  ```
- **Events are attributes**: `onclick={() => conn.send(...)}` — not `on:click`.
- **Snippets** replace slots. The room header takes a `right` snippet:
  ```svelte
  <RoomHeader {lobby} {room}>
    {#snippet right()}<Rules /><ThemeToggle />{/snippet}
  </RoomHeader>
  ```
  and renders it with `{@render right?.()}`.

## 4. Styling: Tailwind v4, mobile-first

Tailwind v4 is configured **in CSS**, not a JS config file. `app.css`:

```css
@import 'tailwindcss';
@theme {
  --color-apple-500: oklch(0.6 0.21 24);
  --font-display: 'Fraunces', serif;
  --radius-card: 1.25rem;
}
@custom-variant dark (&:where(.dark, .dark *));
```

These tokens become utilities: `bg-apple-500`, `font-display`, `rounded-card`,
and the `dark:` variant keys off a `.dark` class on `<html>` (toggled in
`theme.svelte.ts`).

The Google font families are embedded in the repo rather than loaded from
Google at runtime. `frontend/static/fonts` contains Inter and Fraunces files,
and `app.css` defines local `@font-face` rules before the Tailwind theme tokens.
That keeps the same typography in public and intranet deployments.

**Mobile-first**: base classes target phones; `sm:` / `lg:` add larger-screen
overrides. e.g. the home buttons stack on mobile and sit side-by-side on `sm+`:

```svelte
<div class="flex flex-col gap-2 sm:flex-row"> … </div>
```

The room uses a single column on phones and a sidebar on desktop:
`grid gap-6 lg:grid-cols-[1fr_320px]`.

> **Learning note — icons.** We import Lucide icons **per-icon**
> (`import Apple from '@lucide/svelte/icons/apple'`) rather than from the package
> root. Barrel imports force Vite to parse the whole icon set at build time and
> slow `vite build`; per-icon imports keep builds fast.

## 5. Static hosting and client-only routes

`conn` is a module-level singleton. On the server, all SSR requests would share
that one instance — a state-leak hazard, and there's no live socket server-side
anyway. `+layout.ts` sets `ssr = false` for the app, and the SvelteKit static
adapter writes an `index.html` fallback. Caddy serves that fallback for direct
visits like `/room/frost-owl-734`, then the browser-side router reads
`page.params.lobby` and opens the WebSocket.

## 6. Type safety as documentation

`types.ts` is the client's half of the protocol. It uses the exact server field
names (`'display-name'`, `'game-state'`, `'can-moderate'`) so the snapshot maps
directly with no renaming layer. When the protocol changes, update `types.ts` and
`docs/protocol.md` together.

---

For deeper standalone drills on runes and reactivity pitfalls, see
[`../learn/svelte/`](../learn/svelte).
