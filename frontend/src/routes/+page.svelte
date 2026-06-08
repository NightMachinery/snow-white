<script lang="ts">
	import { goto } from '$app/navigation';
	import { createRoom, randomRoomName, roomExists } from '$lib/api';
	import { identity } from '$lib/identity.svelte';
	import ThemeToggle from '$lib/components/ThemeToggle.svelte';
	import Apple from '@lucide/svelte/icons/apple';
	import Sparkles from '@lucide/svelte/icons/sparkles';

	let name = $state(identity.name);
	let room = $state('');
	let busy = $state(false);
	let error = $state('');

	const nameOk = $derived(name.trim().length > 0);

	async function create() {
		if (!nameOk || busy) return;
		busy = true;
		error = '';
		identity.setName(name.trim());
		const r = room.trim() || randomRoomName();
		const res = await createRoom(identity.authId, r);
		busy = false;
		if (res.ok) goto(`/room/${encodeURIComponent(r)}`);
		else error = String(res.error ?? 'Could not create room');
	}

	async function join() {
		if (!nameOk || busy) return;
		const r = room.trim();
		if (!r) {
			error = 'Enter a room name to join';
			return;
		}
		busy = true;
		error = '';
		identity.setName(name.trim());
		const exists = await roomExists(r);
		busy = false;
		if (exists) goto(`/room/${encodeURIComponent(r)}`);
		else error = `Room “${r}” was not found`;
	}
</script>

<main class="mx-auto flex min-h-dvh max-w-md flex-col justify-center gap-8 px-6 py-10">
	<div class="absolute right-5 top-5"><ThemeToggle /></div>

	<header class="text-center">
		<div class="mb-3 flex items-center justify-center gap-2 text-apple-500">
			<Apple class="size-9" strokeWidth={1.75} />
			<Sparkles class="size-5 opacity-70" />
		</div>
		<h1 class="font-display text-5xl font-semibold tracking-tight">Snow White</h1>
		<p class="mt-2 text-mist">
			A party game of secret words &amp; hidden wolves. Ask, deduce, and unmask.
		</p>
	</header>

	<div
		class="flex flex-col gap-4 rounded-card bg-white/70 p-6 shadow-xl ring-1 ring-frost backdrop-blur dark:bg-white/5 dark:ring-white/10"
	>
		<label class="flex flex-col gap-1">
			<span class="text-sm font-medium text-mist">Your name</span>
			<input
				bind:value={name}
				placeholder="e.g. Briar Rose"
				maxlength="24"
				class="rounded-xl border border-frost bg-snow px-4 py-2.5 outline-none focus:ring-2 focus:ring-apple-400 dark:border-white/10 dark:bg-white/5"
			/>
		</label>

		<label class="flex flex-col gap-1">
			<span class="text-sm font-medium text-mist"
				>Room <span class="opacity-60">(optional for new game)</span></span
			>
			<input
				bind:value={room}
				placeholder="leave blank to generate"
				maxlength="40"
				onkeydown={(e) => e.key === 'Enter' && join()}
				class="rounded-xl border border-frost bg-snow px-4 py-2.5 outline-none focus:ring-2 focus:ring-apple-400 dark:border-white/10 dark:bg-white/5"
			/>
		</label>

		{#if error}
			<p class="text-sm text-apple-500">{error}</p>
		{/if}

		<div class="mt-1 flex flex-col gap-2 sm:flex-row">
			<button
				onclick={create}
				disabled={!nameOk || busy}
				class="flex-1 rounded-xl bg-apple-500 px-4 py-2.5 font-medium text-white shadow-sm transition hover:bg-apple-600 disabled:opacity-40"
			>
				New game
			</button>
			<button
				onclick={join}
				disabled={!nameOk || busy}
				class="flex-1 rounded-xl border border-apple-500 px-4 py-2.5 font-medium text-apple-500 transition hover:bg-apple-50 disabled:opacity-40 dark:hover:bg-white/5"
			>
				Join
			</button>
		</div>
	</div>

	<p class="text-center text-xs text-mist">4–20 players · best with 5+ · works on any device</p>
</main>
