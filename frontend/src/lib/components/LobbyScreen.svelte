<script lang="ts">
	import type { Lobby } from '$lib/types';
	import { conn } from '$lib/ws.svelte';
	import { seatedPlayers, bystanders, activeCount, me } from '$lib/game';
	import PlayerSeat from './PlayerSeat.svelte';
	import ModPanel from './ModPanel.svelte';
	import Play from '@lucide/svelte/icons/play';
	import Eye from '@lucide/svelte/icons/eye';
	import LogIn from '@lucide/svelte/icons/log-in';

	let { lobby }: { lobby: Lobby } = $props();

	const seated = $derived(seatedPlayers(lobby));
	const others = $derived(bystanders(lobby));
	const myself = $derived(me(lobby));
	const canStart = $derived(lobby.you['can-moderate'] && activeCount(lobby) >= 4);
	const amSeated = $derived(Boolean(myself?.seat) && !myself?.spectator);
</script>

<div class="grid gap-6 lg:grid-cols-[1fr_320px]">
	<section>
		<div class="mb-3 flex items-center justify-between">
			<h2 class="font-display text-2xl">Players at the table</h2>
			<span class="text-sm text-mist">{activeCount(lobby)} / 20</span>
		</div>

		{#if seated.length === 0}
			<p class="rounded-2xl border border-dashed border-frost p-6 text-center text-mist dark:border-white/10">
				No one is seated yet.
			</p>
		{:else}
			<div class="grid grid-cols-1 gap-2 sm:grid-cols-2">
				{#each seated as p (p['auth-id'])}
					<PlayerSeat player={p} {lobby} />
				{/each}
			</div>
		{/if}

		{#if others.length > 0}
			<h3 class="mb-2 mt-5 text-sm font-medium text-mist">Watching ({others.length})</h3>
			<div class="flex flex-wrap gap-2">
				{#each others as p (p['auth-id'])}
					<span class="rounded-full bg-frost px-3 py-1 text-sm dark:bg-white/10" dir="auto">
						{p['display-name']}
					</span>
				{/each}
			</div>
		{/if}

		<div class="mt-5 flex flex-wrap gap-2">
			{#if amSeated}
				<button
					onclick={() => conn.send({ type: 'seat/spectate' })}
					class="flex items-center gap-2 rounded-xl border border-frost px-4 py-2 text-sm transition hover:bg-frost dark:border-white/10 dark:hover:bg-white/5"
				>
					<Eye class="size-4" /> Watch instead
				</button>
			{:else}
				<button
					onclick={() => conn.send({ type: 'seat/take' })}
					disabled={activeCount(lobby) >= 20}
					class="flex items-center gap-2 rounded-xl border border-apple-500 px-4 py-2 text-sm text-apple-500 transition hover:bg-apple-50 disabled:opacity-40 dark:hover:bg-white/5"
				>
					<LogIn class="size-4" /> Take a seat
				</button>
			{/if}
		</div>
	</section>

	<aside class="flex flex-col gap-4">
		<ModPanel {lobby} />

		{#if lobby.you['can-moderate']}
			<button
				onclick={() => conn.send({ type: 'game/start' })}
				disabled={!canStart}
				class="flex items-center justify-center gap-2 rounded-xl bg-apple-500 px-4 py-3 font-medium text-white shadow-sm transition hover:bg-apple-600 disabled:opacity-40"
			>
				<Play class="size-5" /> Start game
			</button>
			{#if activeCount(lobby) < 4}
				<p class="-mt-2 text-center text-xs text-mist">Need at least 4 seated players.</p>
			{/if}
		{:else}
			<p class="rounded-2xl bg-frost/60 p-4 text-center text-sm text-mist dark:bg-white/5">
				Waiting for the host to start…
			</p>
		{/if}
	</aside>
</div>
