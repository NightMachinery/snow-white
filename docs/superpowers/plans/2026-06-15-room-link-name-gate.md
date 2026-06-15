# Room Link Name Gate Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make direct `/room/<lobby>` invite links ask first-time players for a name before opening the WebSocket and joining the room.

**Architecture:** Add a tiny plain TypeScript helper for name normalization and room-connection gating, covered by Node's built-in test runner. Update the Svelte room page so the WebSocket connection effect is gated by a saved, trimmed name and the pre-join UI renders an inline form when `snow:name` is missing. Update frontend docs to teach the direct-link identity flow.

**Tech Stack:** SvelteKit 2, Svelte 5 runes, TypeScript, Tailwind CSS, Node `node:test`, existing `identity.svelte.ts` and `ws.svelte.ts` modules.

---

## File Structure

- Create `frontend/src/lib/name-gate.ts`
  - Responsibility: pure string helpers for display-name normalization and deciding whether the room page may connect.
  - Reason: Node tests can cover behavior without needing a Svelte component test runner.
- Create `frontend/src/lib/name-gate.test.ts`
  - Responsibility: TDD coverage for trimming and connection gating.
- Modify `frontend/src/routes/room/[lobby]/+page.svelte`
  - Responsibility: render the direct-link name form when needed and connect only after a valid saved name exists.
- Modify `docs/frontend-svelte.md`
  - Responsibility: explain the direct room-link name gate in the teaching documentation.

Atomic commit groups:

1. `test/feat`: helper plus room page behavior.
2. `docs`: frontend Svelte documentation update.

---

### Task 1: Add tested name-gate helpers

**Files:**
- Create: `frontend/src/lib/name-gate.ts`
- Create: `frontend/src/lib/name-gate.test.ts`

- [ ] **Step 1: Write the failing test**

Create `frontend/src/lib/name-gate.test.ts` with this exact content:

```ts
import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import { canConnectToRoom, normalizePlayerName } from './name-gate.ts';

describe('room link name gate', () => {
	it('normalizes player names by trimming surrounding whitespace', () => {
		assert.equal(normalizePlayerName('  Briar Rose  '), 'Briar Rose');
	});

	it('does not allow room connection without a non-empty saved name', () => {
		assert.equal(canConnectToRoom(''), false);
		assert.equal(canConnectToRoom('   '), false);
	});

	it('allows room connection when a saved name has visible characters', () => {
		assert.equal(canConnectToRoom('Hunter'), true);
		assert.equal(canConnectToRoom('  Gretel  '), true);
	});
});
```

- [ ] **Step 2: Run the test to verify it fails**

Run:

```bash
cd frontend && node --test --experimental-strip-types src/lib/name-gate.test.ts
```

Expected result: FAIL because `./name-gate.ts` does not exist. The useful failure should include text like:

```text
Error [ERR_MODULE_NOT_FOUND]: Cannot find module
```

- [ ] **Step 3: Add the minimal helper implementation**

Create `frontend/src/lib/name-gate.ts` with this exact content:

```ts
export function normalizePlayerName(name: string): string {
	return name.trim();
}

export function canConnectToRoom(savedName: string): boolean {
	return normalizePlayerName(savedName).length > 0;
}
```

- [ ] **Step 4: Run the focused test to verify it passes**

Run:

```bash
cd frontend && node --test --experimental-strip-types src/lib/name-gate.test.ts
```

Expected result: PASS with output ending in `# fail 0`.

- [ ] **Step 5: Run all frontend unit tests**

Run:

```bash
cd frontend && npm test
```

Expected result: PASS with output ending in `# fail 0`.

---

### Task 2: Gate the room page WebSocket connection and render the name form

**Files:**
- Modify: `frontend/src/routes/room/[lobby]/+page.svelte`
- Test: `frontend/src/lib/name-gate.test.ts`

- [ ] **Step 1: Re-run the existing failing-behavior test before editing production UI**

Run:

```bash
cd frontend && node --test --experimental-strip-types src/lib/name-gate.test.ts
```

Expected result: PASS. This confirms the pure gate behavior is already specified before the Svelte page is wired to it.

- [ ] **Step 2: Modify imports and state in the room page**

In `frontend/src/routes/room/[lobby]/+page.svelte`, add the helper import next to the existing imports:

```svelte
<script lang="ts">
	import { page } from '$app/state';
	import { goto } from '$app/navigation';
	import { conn } from '$lib/ws.svelte';
	import { identity } from '$lib/identity.svelte';
	import { canConnectToRoom, normalizePlayerName } from '$lib/name-gate';
	import ThemeToggle from '$lib/components/ThemeToggle.svelte';
	import ErrorNotice from '$lib/components/ErrorNotice.svelte';
	import RoomHeader from '$lib/components/RoomHeader.svelte';
	import LobbyScreen from '$lib/components/LobbyScreen.svelte';
	import MayorPick from '$lib/components/MayorPick.svelte';
	import QuestionRound from '$lib/components/QuestionRound.svelte';
	import VoteScreen from '$lib/components/VoteScreen.svelte';
	import EndScreen from '$lib/components/EndScreen.svelte';
	import Rules from '$lib/components/Rules.svelte';

	const room = $derived(decodeURIComponent(page.params.lobby ?? ''));
	let pendingName = $state(identity.name);
	let nameError = $state('');

	const savedName = $derived(normalizePlayerName(identity.name));
	const canConnect = $derived(canConnectToRoom(identity.name));
	const pendingNameOk = $derived(normalizePlayerName(pendingName).length > 0);
```

Do not remove the existing screen imports.

- [ ] **Step 3: Replace the connection effect**

In the same `<script>`, replace the existing connection effect:

```ts
	// Connect on mount; reconnect if the room param changes. Disconnect on leave.
	$effect(() => {
		const name = identity.name || 'Player';
		conn.connect(room, name);
		return () => conn.disconnect();
	});
```

with this exact gated effect:

```ts
	// Connect only after we have a real saved name. Direct invite links should not
	// join the server as the generic "Player" fallback.
	$effect(() => {
		if (!canConnect) return;
		conn.connect(room, savedName);
		return () => conn.disconnect();
	});
```

- [ ] **Step 4: Add the submit handler**

Still inside the `<script>` and before the final derived `lobby`/`state` declarations, add:

```ts
	function joinWithName() {
		const normalized = normalizePlayerName(pendingName);
		if (!normalized) {
			nameError = 'Enter your name to join this room';
			return;
		}

		nameError = '';
		identity.setName(normalized);
	}
```

Keep these existing declarations after the new handler:

```ts
	const lobby = $derived(conn.lobby);
	const state = $derived(lobby?.['game-state']);
```

- [ ] **Step 5: Add the pre-join branch to the template**

In the `<main>` block, insert a new first branch before the current `{#if conn.error && !lobby}` branch. The resulting beginning of `<main>` should be:

```svelte
<main class="mx-auto flex min-h-dvh max-w-5xl flex-col px-4 py-4 sm:px-6">
	{#if !canConnect}
		<div class="absolute right-5 top-5"><ThemeToggle /></div>

		<div class="flex flex-1 flex-col items-center justify-center gap-6 text-center">
			<header class="max-w-md">
				<p class="text-sm font-medium uppercase tracking-[0.24em] text-apple-500">Snow White</p>
				<h1 class="mt-3 font-display text-4xl font-semibold tracking-tight">Join “{room}”</h1>
				<p class="mt-3 text-mist">Choose the name your friends will see in this game.</p>
			</header>

			<form
				onsubmit={(event) => {
					event.preventDefault();
					joinWithName();
				}}
				class="flex w-full max-w-sm flex-col gap-3 rounded-card bg-white/70 p-5 text-left shadow-xl ring-1 ring-frost dark:bg-white/5 dark:ring-white/10"
			>
				<label class="flex flex-col gap-1">
					<span class="text-sm font-medium text-mist">Your name</span>
					<input
						bind:value={pendingName}
						placeholder="e.g. Briar Rose"
						maxlength="24"
						dir="auto"
						autofocus
						aria-invalid={nameError ? 'true' : undefined}
						aria-describedby={nameError ? 'room-name-error' : undefined}
						class="rounded-xl border border-frost bg-snow px-4 py-2.5 outline-none focus:ring-2 focus:ring-apple-400 dark:border-white/10 dark:bg-white/5"
					/>
				</label>

				{#if nameError}
					<p id="room-name-error" class="text-sm text-red-500">{nameError}</p>
				{/if}

				<button
					type="submit"
					disabled={!pendingNameOk}
					class="rounded-xl bg-apple-500 px-4 py-2.5 font-medium text-white shadow-sm transition hover:bg-apple-600 disabled:opacity-40"
				>
					Join room
				</button>
			</form>
		</div>
	{:else if conn.error && !lobby}
```

Leave the rest of the existing branches unchanged.

- [ ] **Step 6: Run Svelte type checking**

Run:

```bash
cd frontend && npm run check
```

Expected result: PASS. The output should not contain Svelte errors. Do not run `npx @sveltejs/mcp svelte-autofixer`; project instructions say to skip it.

- [ ] **Step 7: Run all frontend unit tests**

Run:

```bash
cd frontend && npm test
```

Expected result: PASS with output ending in `# fail 0`.

- [ ] **Step 8: Commit helper and UI behavior**

Run:

```bash
git add frontend/src/lib/name-gate.ts frontend/src/lib/name-gate.test.ts frontend/src/routes/room/[lobby]/+page.svelte
git commit -m "feat: ask for name before direct room join"
```

Expected result: one commit containing the helper, tests, and Svelte room page update.

---

### Task 3: Update frontend teaching docs

**Files:**
- Modify: `docs/frontend-svelte.md`

- [ ] **Step 1: Inspect the current room/WebSocket docs section**

Run:

```bash
sed -n '60,125p' docs/frontend-svelte.md
```

Expected result: output includes the current explanation that the room page opens and closes the WebSocket, including the old example `conn.connect(room, identity.name || 'Player')`.

- [ ] **Step 2: Replace the stale connection example with the gated flow**

In `docs/frontend-svelte.md`, replace the old room connection example:

```md
- Opening/closing the WebSocket when the room page mounts/unmounts:

  ```ts
  $effect(() => {
    conn.connect(room, identity.name || 'Player');
    return () => conn.disconnect();   // cleanup on unmount / room change
  });
  ```
```

with this text:

````md
- Opening/closing the WebSocket when the room page has a real saved name:

  ```ts
  $effect(() => {
    if (!canConnect) return;
    conn.connect(room, savedName);
    return () => conn.disconnect();   // cleanup on unmount / room change
  });
  ```

  This gate matters for invite links. The home page already requires a name before
  navigating, but a direct `/room/<lobby>` visit can happen before `snow:name`
  exists in `localStorage`. In that case the room page renders a small name form
  and waits to open the WebSocket until `identity.setName(...)` has saved a
  trimmed display name. The server then creates the player with the intended base
  name instead of the generic `Player` fallback.
````

- [ ] **Step 3: Run frontend checks after the docs-only edit**

Run:

```bash
cd frontend && npm test && npm run check
```

Expected result: both commands PASS. The output should not contain Svelte errors.

- [ ] **Step 4: Commit the docs update**

Run:

```bash
git add docs/frontend-svelte.md
git commit -m "docs: explain room link name gate"
```

Expected result: one docs-only commit.

---

### Task 4: Final verification and push

**Files:**
- Read-only verification across the repository.

- [ ] **Step 1: Check git status**

Run:

```bash
git status --short
```

Expected result: no unstaged implementation or docs changes, except any deliberate plan-file change if this plan is being edited during execution.

- [ ] **Step 2: Run frontend tests**

Run:

```bash
cd frontend && npm test
```

Expected result: PASS with output ending in `# fail 0`.

- [ ] **Step 3: Run frontend Svelte/TypeScript check**

Run:

```bash
cd frontend && npm run check
```

Expected result: PASS. The output should not contain Svelte errors. Do not run `npx @sveltejs/mcp svelte-autofixer`; project instructions say to skip it.

- [ ] **Step 4: Push commits**

Run:

```bash
git push
```

Expected result: local commits are pushed to the configured remote. If there is no configured remote or push is rejected, report the exact error to the user and do not rewrite history unless the user asks.
