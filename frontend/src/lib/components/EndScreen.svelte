<script lang="ts">
	import type { Lobby } from '$lib/types';
	import { conn } from '$lib/ws.svelte';
	import { roleLabel } from '$lib/game';
	import QuestionLog from './QuestionLog.svelte';
	import Trophy from '@lucide/svelte/icons/trophy';
	import RotateCcw from '@lucide/svelte/icons/rotate-ccw';
	import Sparkles from '@lucide/svelte/icons/sparkles';

	let { lobby }: { lobby: Lobby } = $props();

	const winner = $derived(lobby.winner);
	const classicMode = $derived(lobby['game-mode'] === 'classic');
	const wolves = $derived(lobby.werewolves.map((a) => lobby.players[a]?.['display-name']).filter(Boolean));
	const seerName = $derived(lobby.seer ? lobby.players[lobby.seer]?.['display-name'] : '');
	const villageWon = $derived(winner === 'village' || winner === 'players');
	const resultTitle = $derived.by(() => {
		if (winner === 'players') return 'Everyone wins!';
		if (winner === 'word') return 'The word wins!';
		return villageWon ? 'The Village wins!' : 'The Wolves win!';
	});
	const resultBlurb = $derived.by(() => {
		if (winner === 'players') return 'The table guessed the word together.';
		if (winner === 'word') return 'The table ran out before guessing it.';
		return null;
	});
	const voteResult = $derived(lobby['vote-result']);
	const selectedName = $derived(voteResult?.selected ? lobby.players[voteResult.selected]?.['display-name'] : '');
	const voteCounts = $derived(voteResult ? Object.entries(voteResult.counts) : []);

	// A small, tasteful celebratory flourish: a handful of sparkles drift up behind
	// the result banner. Purely decorative, and the global reduced-motion rule
	// freezes the animation for users who prefer less motion.
	const sparks = [
		{ left: '8%', delay: '0s', size: 'size-4', d: '2.6s' },
		{ left: '24%', delay: '0.5s', size: 'size-3', d: '3s' },
		{ left: '46%', delay: '0.15s', size: 'size-5', d: '2.4s' },
		{ left: '68%', delay: '0.7s', size: 'size-3', d: '3.1s' },
		{ left: '88%', delay: '0.35s', size: 'size-4', d: '2.8s' }
	];
</script>

<div class="mx-auto flex max-w-lg flex-col items-center gap-5 text-center" style="animation: pop-in 0.4s var(--ease-soft) both">
	<div
		class={[
			'relative flex w-full flex-col items-center gap-2 overflow-hidden rounded-card px-8 py-6 ring-1',
			villageWon ? 'bg-forest/10 ring-forest/30' : 'bg-dusk/10 ring-dusk/30'
		]}
	>
		<!-- Drifting sparkles (decorative) -->
		<div class="pointer-events-none absolute inset-0" aria-hidden="true">
			{#each sparks as s, i (i)}
				<Sparkles
					class={['absolute bottom-0', s.size, villageWon ? 'text-forest/50' : 'text-dusk/50']}
					style="left:{s.left}; animation: float-up {s.d} var(--ease-soft) {s.delay} infinite;"
				/>
			{/each}
		</div>

		<Trophy class={['size-10', villageWon ? 'text-forest' : 'text-dusk']} />
		<h2 class="font-display text-3xl">
			{resultTitle}
		</h2>
		{#if resultBlurb}
			<p class="text-mist">{resultBlurb}</p>
		{/if}
		<p class="text-mist">
			The word was <span class="font-display text-ink dark:text-snow" dir="auto">{lobby['chosen-word']}</span>
		</p>
	</div>

	{#if !classicMode}
		<div class="grid w-full gap-2 text-left text-sm">
			<div class="flex items-center justify-between rounded-xl bg-dusk/10 px-4 py-2">
				<span class="text-mist">{wolves.length > 1 ? 'Wolves' : 'Wolf'}</span>
				<span class="font-medium" dir="auto">{wolves.join(', ')}</span>
			</div>
			<div class="flex items-center justify-between rounded-xl bg-apple-50 px-4 py-2 dark:bg-white/5">
				<span class="text-mist">Seer</span>
				<span class="font-medium" dir="auto">{seerName}</span>
			</div>
		</div>
	{/if}

	{#if voteResult}
		<div class="w-full rounded-2xl border border-frost p-4 text-left text-sm dark:border-white/10">
			<h3 class="mb-2 text-xs font-medium uppercase tracking-wide text-mist">Vote result</h3>
			<div class="flex flex-wrap gap-2">
				{#each voteCounts as [auth, count] (auth)}
					<span class="rounded-full bg-frost px-2.5 py-1 text-xs dark:bg-white/10">
						<span dir="auto">{lobby.players[auth]?.['display-name'] ?? auth}</span>: {count}
					</span>
				{/each}
			</div>
			{#if selectedName}
				<p class="mt-2 text-mist">
					Resolved target: <span class="font-medium text-ink dark:text-snow" dir="auto">{selectedName}</span>{#if voteResult['randomized?']} · random tiebreak{/if}
				</p>
			{/if}
		</div>
	{/if}

	{#if lobby['question-log'].length > 0}
		<div class="w-full rounded-2xl border border-frost p-4 text-left dark:border-white/10">
			<QuestionLog questions={lobby['question-log']} />
		</div>
	{/if}

	<!-- Full role reveal -->
	{#if !classicMode}
		<div class="grid w-full grid-cols-2 gap-1.5 text-left text-xs sm:grid-cols-3">
			{#each Object.values(lobby.players).filter((p) => p.role) as p (p['auth-id'])}
				<div class="flex items-center justify-between rounded-lg bg-white/60 px-2.5 py-1.5 dark:bg-white/5">
					<span class="truncate" dir="auto">{p['display-name']}</span>
					<span class="ml-1 shrink-0 text-mist">{roleLabel[p.role ?? '']}</span>
				</div>
			{/each}
		</div>
	{/if}

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
