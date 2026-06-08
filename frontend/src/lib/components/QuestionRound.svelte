<script lang="ts">
	import type { Lobby } from '$lib/types';
	import { conn } from '$lib/ws.svelte';
	import { answerLabel } from '$lib/game';
	import RoleCard from './RoleCard.svelte';
	import Timer from './Timer.svelte';
	import TokenBoard from './TokenBoard.svelte';
	import Send from '@lucide/svelte/icons/send';

	let { lobby }: { lobby: Lobby } = $props();

	let draft = $state('');
	const canAsk = $derived(lobby['game-state'] === 'question-round' && !lobby.you['is-mayor']);

	const answerCls: Record<string, string> = {
		yes: 'text-forest',
		no: 'text-dusk',
		maybe: 'text-amber-500',
		'so-close': 'text-apple-400',
		'way-off': 'text-mist',
		correct: 'text-apple-600 font-semibold'
	};

	function ask() {
		const text = draft.trim();
		if (!text) return;
		conn.send({ type: 'game/ask', text });
		draft = '';
	}
</script>

<div class="grid gap-5 lg:grid-cols-[1fr_360px]">
	<section class="flex flex-col gap-4">
		<div class="flex items-center justify-between">
			<h2 class="font-display text-2xl">Question round</h2>
			<Timer {lobby} />
		</div>

		<RoleCard {lobby} />

		{#if lobby.you['knows-word'] && lobby['chosen-word']}
			<p class="rounded-xl bg-frost/60 px-4 py-2 text-sm dark:bg-white/5">
				The secret word is <span class="font-display text-base">{lobby['chosen-word']}</span>
			</p>
		{/if}

		{#if canAsk}
			<div class="flex gap-2">
				<input
					bind:value={draft}
					placeholder="Ask a yes/no question…"
					onkeydown={(e) => e.key === 'Enter' && ask()}
					class="flex-1 rounded-xl border border-frost bg-snow px-4 py-2.5 outline-none focus:ring-2 focus:ring-apple-400 dark:border-white/10 dark:bg-white/5"
				/>
				<button
					onclick={ask}
					class="grid place-items-center rounded-xl bg-apple-500 px-4 text-white transition hover:bg-apple-600"
				>
					<Send class="size-5" />
				</button>
			</div>
		{/if}

		<!-- Answered history (most recent first) -->
		<div class="flex flex-col gap-1.5">
			{#each [...lobby.answered].reverse() as q, i (lobby.answered.length - i)}
				<div class="flex items-baseline justify-between gap-3 rounded-xl bg-white/60 px-3 py-2 text-sm dark:bg-white/5">
					<span><span class="text-mist">{q.name}:</span> {q.text}</span>
					<span class={['shrink-0', answerCls[q.answer ?? ''] ?? 'text-mist']}>
						{answerLabel[q.answer ?? ''] ?? q.answer}
					</span>
				</div>
			{:else}
				<p class="text-sm text-mist">No answers yet.</p>
			{/each}
		</div>
	</section>

	<aside class="rounded-card border border-frost p-4 dark:border-white/10">
		<TokenBoard {lobby} />
	</aside>
</div>
