<script lang="ts">
	import type { Lobby } from '$lib/types';
	import { conn } from '$lib/ws.svelte';
	import { answerLabel } from '$lib/game';
	import RoleCard from './RoleCard.svelte';
	import Timer from './Timer.svelte';
	import TokenBoard from './TokenBoard.svelte';
	import Roster from './Roster.svelte';
	import ModPanel from './ModPanel.svelte';
	import Send from '@lucide/svelte/icons/send';
	import Check from '@lucide/svelte/icons/check';
	import Pencil from '@lucide/svelte/icons/pencil';

	let { lobby }: { lobby: Lobby } = $props();

	const isMayor = $derived(lobby.you['is-mayor']);
	const myId = $derived(lobby.you['auth-id']);
	// The asker's own pending (unanswered) question, if any.
	const myPending = $derived(lobby.questions.find((q) => q['auth-id'] === myId));
	// You may ask only when you're not the Mayor and have no pending question.
	const canAsk = $derived(lobby['game-state'] === 'question-round' && !isMayor && !myPending);

	let draft = $state('');
	let edit = $state('');
	// Seed the edit box from the current pending text whenever it changes.
	$effect(() => {
		edit = myPending?.text ?? '';
	});

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
	function saveEdit() {
		const text = edit.trim();
		if (!text) return;
		conn.send({ type: 'game/edit', text });
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
				The secret word is <span class="font-display text-base" dir="auto">{lobby['chosen-word']}</span>
			</p>
		{/if}

		<!-- Ask box (non-mayor, no pending question yet) -->
		{#if canAsk}
			<div class="flex gap-2">
				<input
					bind:value={draft}
					placeholder="Ask a yes/no question…"
					dir="auto"
					onkeydown={(e) => e.key === 'Enter' && ask()}
					class="flex-1 rounded-xl border border-frost bg-snow px-4 py-2.5 outline-none focus:ring-2 focus:ring-apple-400 dark:border-white/10 dark:bg-white/5"
				/>
				<button
					onclick={ask}
					aria-label="Send question"
					class="grid place-items-center rounded-xl bg-apple-500 px-4 text-white transition hover:bg-apple-600"
				>
					<Send class="size-5" />
				</button>
			</div>
		{:else if myPending && !isMayor}
			<!-- You have a question in the queue: edit it instead of asking a new one -->
			<div class="rounded-xl border border-apple-200 bg-apple-50/60 p-3 dark:border-white/10 dark:bg-white/5">
				<p class="mb-1.5 flex items-center gap-1.5 text-xs text-mist">
					<Pencil class="size-3.5" /> Your question is waiting — you can edit it until it's answered.
				</p>
				<div class="flex gap-2">
					<input
						bind:value={edit}
						dir="auto"
						onkeydown={(e) => e.key === 'Enter' && saveEdit()}
						class="flex-1 rounded-lg border border-frost bg-snow px-3 py-2 outline-none focus:ring-2 focus:ring-apple-400 dark:border-white/10 dark:bg-ink"
					/>
					<button
						onclick={saveEdit}
						disabled={edit.trim() === (myPending?.text ?? '')}
						aria-label="Save edit"
						class="grid place-items-center rounded-lg bg-apple-500 px-3 text-white transition hover:bg-apple-600 disabled:opacity-40"
					>
						<Check class="size-5" />
					</button>
				</div>
			</div>
		{/if}

		<!-- Pending queue — visible to everyone -->
		{#if lobby.questions.length > 0}
			<div class="flex flex-col gap-1.5">
				<h3 class="text-xs font-medium uppercase tracking-wide text-mist">
					Waiting for the Mayor · {lobby.questions.length}
				</h3>
				{#each lobby.questions as q, i (q['auth-id'] + i)}
					<div
						class={[
							'flex items-baseline justify-between gap-3 rounded-xl px-3 py-2 text-sm',
							i === 0
								? 'bg-apple-50 ring-1 ring-apple-100 dark:bg-white/5 dark:ring-white/10'
								: 'bg-white/60 dark:bg-white/5'
						]}
					>
						<span class="min-w-0" dir="auto"><span class="text-mist">{q.name}:</span> {q.text}</span>
						{#if q['auth-id'] === myId}<span class="shrink-0 text-xs text-mist">you</span>{/if}
					</div>
				{/each}
			</div>
		{/if}

		<!-- Answered history (most recent first) -->
		<div class="flex flex-col gap-1.5">
			{#each [...lobby.answered].reverse() as q, i (lobby.answered.length - i)}
				<div class="flex items-baseline justify-between gap-3 rounded-xl bg-white/60 px-3 py-2 text-sm dark:bg-white/5">
					<span class="min-w-0" dir="auto"><span class="text-mist">{q.name}:</span> {q.text}</span>
					<span class={['shrink-0', answerCls[q.answer ?? ''] ?? 'text-mist']}>
						{answerLabel[q.answer ?? ''] ?? q.answer}
					</span>
				</div>
			{:else}
				{#if lobby.questions.length === 0}<p class="text-sm text-mist">No answers yet.</p>{/if}
			{/each}
		</div>
	</section>

	<aside class="flex flex-col gap-4">
		<div class="rounded-card border border-frost p-4 dark:border-white/10">
			<TokenBoard {lobby} />
		</div>
		<Roster {lobby} />
		{#if lobby.you['can-moderate']}
			<ModPanel {lobby} compact />
		{/if}
	</aside>
</div>
