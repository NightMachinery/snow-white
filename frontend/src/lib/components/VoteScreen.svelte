<script lang="ts">
	import type { Lobby } from '$lib/types';
	import { conn } from '$lib/ws.svelte';
	import { seatedPlayers, me } from '$lib/game';
	import PlayerSeat from './PlayerSeat.svelte';

	let { lobby, mode }: { lobby: Lobby; mode: 'village' | 'wolf' } = $props();

	let picked = $state<string | null>(null);
	let voted = $state(false);

	const myself = $derived(me(lobby));
	const isWolf = $derived(lobby.you.role === 'werewolf');
	// Village vote: everyone seated votes for a suspected wolf, even if offline.
	// Wolf vote: only wolves vote, for the suspected seer.
	const eligible = $derived(mode === 'wolf' ? isWolf : Boolean(myself?.seat));

	const candidates = $derived(
		seatedPlayers(lobby).filter((p) => p['auth-id'] !== lobby.you['auth-id'])
	);

	const title = $derived(
		mode === 'wolf' ? 'Wolves: who is the Seer?' : 'Village: who is the Wolf?'
	);
	const blurb = $derived(
		mode === 'wolf'
			? 'The word was guessed. Identify the Seer to steal the win.'
			: 'Time is up. Vote for the player you believe is a Wolf.'
	);

	function castVote() {
		if (!picked || voted) return;
		conn.send({
			type: mode === 'wolf' ? 'game/vote-wolf' : 'game/vote-village',
			target: picked
		});
		voted = true;
	}
</script>

<div
	class={[
		'mx-auto flex max-w-lg flex-col gap-4',
		mode === 'wolf' && 'rounded-card bg-dusk/5 p-5 ring-1 ring-dusk/20'
	]}
>
	<div class="text-center">
		<h2 class="font-display text-2xl">{title}</h2>
		<p class="text-mist">{blurb}</p>
	</div>

	{#if !eligible}
		<p class="rounded-2xl bg-frost/60 p-4 text-center text-sm text-mist dark:bg-white/5">
			{mode === 'wolf' ? 'Only the wolves vote now…' : 'Waiting for the votes…'}
		</p>
	{:else if voted}
		<p class="rounded-2xl bg-forest/10 p-4 text-center text-sm text-forest">
			Your vote is in. Waiting for the others…
		</p>
	{:else}
		<div class="grid grid-cols-1 gap-2 sm:grid-cols-2">
			{#each candidates as p (p['auth-id'])}
				<PlayerSeat
					player={p}
					{lobby}
					selectable
					selected={picked === p['auth-id']}
					onpick={(id) => (picked = id)}
				/>
			{/each}
		</div>
		<button
			onclick={castVote}
			disabled={!picked}
			class="rounded-xl bg-apple-500 px-4 py-3 font-medium text-white transition hover:bg-apple-600 disabled:opacity-40"
		>
			Cast vote
		</button>
	{/if}
</div>
