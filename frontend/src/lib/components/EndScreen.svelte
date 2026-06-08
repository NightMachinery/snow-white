<script lang="ts">
	import type { Lobby } from '$lib/types';
	import { conn } from '$lib/ws.svelte';
	import { roleLabel } from '$lib/game';
	import Trophy from '@lucide/svelte/icons/trophy';
	import RotateCcw from '@lucide/svelte/icons/rotate-ccw';

	let { lobby }: { lobby: Lobby } = $props();

	const winner = $derived(lobby.winner);
	const wolves = $derived(lobby.werewolves.map((a) => lobby.players[a]?.['display-name']).filter(Boolean));
	const seerName = $derived(lobby.seer ? lobby.players[lobby.seer]?.['display-name'] : '');
	const villageWon = $derived(winner === 'village');
</script>

<div class="mx-auto flex max-w-lg flex-col items-center gap-5 text-center">
	<div
		class={[
			'flex flex-col items-center gap-2 rounded-card px-8 py-6 ring-1',
			villageWon ? 'bg-forest/10 ring-forest/30' : 'bg-dusk/10 ring-dusk/30'
		]}
	>
		<Trophy class={['size-10', villageWon ? 'text-forest' : 'text-dusk']} />
		<h2 class="font-display text-3xl">
			{villageWon ? 'The Village wins!' : 'The Wolves win!'}
		</h2>
		<p class="text-mist">
			The word was <span class="font-display text-ink dark:text-snow">{lobby['chosen-word']}</span>
		</p>
	</div>

	<div class="grid w-full gap-2 text-left text-sm">
		<div class="flex items-center justify-between rounded-xl bg-dusk/10 px-4 py-2">
			<span class="text-mist">{wolves.length > 1 ? 'Wolves' : 'Wolf'}</span>
			<span class="font-medium">{wolves.join(', ')}</span>
		</div>
		<div class="flex items-center justify-between rounded-xl bg-apple-50 px-4 py-2 dark:bg-white/5">
			<span class="text-mist">Seer</span>
			<span class="font-medium">{seerName}</span>
		</div>
	</div>

	<!-- Full role reveal -->
	<div class="grid w-full grid-cols-2 gap-1.5 text-left text-xs sm:grid-cols-3">
		{#each Object.values(lobby.players).filter((p) => p.role) as p (p['auth-id'])}
			<div class="flex items-center justify-between rounded-lg bg-white/60 px-2.5 py-1.5 dark:bg-white/5">
				<span class="truncate">{p['display-name']}</span>
				<span class="ml-1 shrink-0 text-mist">{roleLabel[p.role ?? '']}</span>
			</div>
		{/each}
	</div>

	{#if lobby.you['can-moderate']}
		<button
			onclick={() => conn.send({ type: 'game/reset' })}
			class="flex items-center gap-2 rounded-xl bg-apple-500 px-5 py-2.5 font-medium text-white transition hover:bg-apple-600"
		>
			<RotateCcw class="size-4" /> Play again
		</button>
	{:else}
		<p class="text-sm text-mist">Waiting for the host to start a new round…</p>
	{/if}
</div>
