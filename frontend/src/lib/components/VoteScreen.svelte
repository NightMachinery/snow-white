<script lang="ts">
	import type { Lobby } from '$lib/types';
	import { conn } from '$lib/ws.svelte';
	import { seatedPlayers, me } from '$lib/game';
	import PlayerSeat from './PlayerSeat.svelte';
	import QuestionLog from './QuestionLog.svelte';

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

	const votesCast = $derived(mode === 'wolf' ? lobby['wolf-votes'].length : lobby['village-votes'].length);
	const votesExpected = $derived(mode === 'wolf' ? lobby.werewolves.length : seatedPlayers(lobby).length);
	const wolfNames = $derived(lobby.werewolves.map((a) => lobby.players[a]?.['display-name']).filter(Boolean));

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
	function finishVote() {
		conn.send({ type: 'game/finish-vote' });
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
		{#if lobby['chosen-word']}
			<p class="mt-2 rounded-xl bg-frost/60 px-3 py-1.5 text-sm dark:bg-white/5">Word: <span class="font-display" dir="auto">{lobby['chosen-word']}</span></p>
		{/if}
		<p class="mt-2 text-xs text-mist">Votes in: {votesCast} / {votesExpected}</p>
		{#if mode === 'wolf' && wolfNames.length > 0}
			<p class="mt-1 text-xs text-mist">Wolves: <span dir="auto">{wolfNames.join(', ')}</span></p>
		{/if}
		{#if lobby['question-log'].length > 0}
		<div class="mt-2 rounded-2xl border border-frost p-3 text-left dark:border-white/10">
			<QuestionLog questions={lobby['question-log']} />
		</div>
	{/if}
</div>

	{#if lobby.you['can-moderate']}
		<button onclick={finishVote} class="rounded-xl border border-frost px-4 py-2 text-sm text-mist transition hover:bg-frost dark:border-white/10 dark:hover:bg-white/10">
			Finish voting with current votes
		</button>
	{/if}

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
