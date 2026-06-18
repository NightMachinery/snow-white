<script lang="ts">
	import type { Lobby } from '$lib/types';
	import { conn } from '$lib/ws.svelte';
	import { seatedPlayers, me } from '$lib/game';
	import PlayerSeat from './PlayerSeat.svelte';
	import QuestionLog from './QuestionLog.svelte';
	import Timer from './Timer.svelte';

	let { lobby, mode }: { lobby: Lobby; mode: 'village' | 'wolf' } = $props();

	let picked = $state<string | null>(null);

	const myself = $derived(me(lobby));
	const isWolf = $derived(lobby.you.role === 'werewolf');
	// Village vote: seated non-wolves vote for a suspected wolf.
	// Wolf vote: only wolves vote, for the suspected seer.
	const eligible = $derived(mode === 'wolf' ? Boolean(myself?.seat) && isWolf : Boolean(myself?.seat) && !isWolf);
	const serverVote = $derived(mode === 'wolf' ? (lobby.you['wolf-vote'] ?? null) : (lobby.you['village-vote'] ?? null));
	const selectedVote = $derived(picked ?? serverVote);
	const hasServerVote = $derived(Boolean(serverVote));

	const candidates = $derived(
		seatedPlayers(lobby).filter((p) => p['auth-id'] !== lobby.you['auth-id'])
	);

	const votesCast = $derived(mode === 'wolf' ? lobby['wolf-votes'].length : lobby['village-votes'].length);
	const votesExpected = $derived(
		mode === 'wolf'
			? (lobby['wolf-vote-expected'] ?? lobby.werewolves.length)
			: (lobby['village-vote-expected'] ?? seatedPlayers(lobby).length)
	);
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
		if (!selectedVote || selectedVote === serverVote) return;
		conn.send({
			type: mode === 'wolf' ? 'game/vote-wolf' : 'game/vote-village',
			target: selectedVote
		});
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
		<div class="mt-3 flex items-center justify-center gap-3">
			<Timer lobby={lobby} deadlineMs={lobby['vote-deadline-ms']} expireCommand="game/finish-vote" />
			<p class="text-xs text-mist">Votes in: {votesCast} / {votesExpected}</p>
		</div>
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
		<button onclick={finishVote} class="rounded-xl border border-apple-300 bg-apple-50 px-4 py-2 text-sm font-medium text-apple-600 transition hover:bg-apple-100 dark:border-apple-500/40 dark:bg-apple-500/10 dark:text-apple-300 dark:hover:bg-apple-500/20">
			Force end vote now
		</button>
	{/if}

	{#if !eligible}
		<p class="rounded-2xl bg-frost/60 p-4 text-center text-sm text-mist dark:bg-white/5">
			{mode === 'wolf'
				? 'Only the wolves vote now…'
				: isWolf
					? 'Wolves wait this one out while the Village hunts.'
					: 'Waiting for the votes…'}
		</p>
	{:else}
		{#if hasServerVote}
			<p class="rounded-2xl bg-forest/10 p-3 text-center text-sm text-forest">
				Your vote is in. Pick another player and press Change vote if you want to switch before voting ends.
			</p>
		{/if}
		<div class="grid grid-cols-1 gap-2 sm:grid-cols-2">
			{#each candidates as p (p['auth-id'])}
				<PlayerSeat
					player={p}
					{lobby}
					selectable
					hideActions
					selected={selectedVote === p['auth-id']}
					onpick={(id) => (picked = id)}
				/>
			{/each}
		</div>
		<button
			onclick={castVote}
			disabled={!selectedVote || selectedVote === serverVote}
			class="rounded-xl bg-apple-500 px-4 py-3 font-medium text-white transition hover:bg-apple-600 disabled:opacity-40"
		>
			{hasServerVote ? 'Change vote' : 'Cast vote'}
		</button>
	{/if}
</div>
