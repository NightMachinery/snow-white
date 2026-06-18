<script lang="ts">
	import type { Lobby } from '$lib/types';
	import { conn } from '$lib/ws.svelte';

	let { lobby }: { lobby: Lobby } = $props();
	const isMayor = $derived(lobby.you['is-mayor']);
	const head = $derived(lobby.questions[0]);
	const canEditLast = $derived(lobby['game-state'] === 'question-round' && lobby.answered.length > 0);

	const buttons = [
		{ a: 'yes', label: 'Yes', cls: 'bg-forest text-white' },
		{ a: 'no', label: 'No', cls: 'bg-dusk text-white' },
		{ a: 'maybe', label: 'Maybe', cls: 'bg-amber-400 text-ink' },
		{ a: 'so-close', label: 'So close', cls: 'bg-apple-400 text-white' },
		{ a: 'way-off', label: 'Way off', cls: 'bg-mist text-white' },
		{ a: 'correct', label: 'Correct!', cls: 'bg-apple-600 text-white' }
	] as const;

	function answer(a: string) {
		conn.send({ type: 'game/answer', answer: a });
	}
	function editLastAnswer() {
		conn.send({ type: 'game/edit-last-answer' });
	}
</script>

<div class="flex flex-col gap-3">
	<div class="flex items-center justify-between text-sm">
		<span class="text-mist">Tokens left</span>
		<span class="font-mono tabular-nums">
			<span class="text-ink dark:text-snow">{lobby.tokens}</span>
			{#if !lobby['shared-maybe-pool']}<span class="text-mist"> · maybe {lobby['maybe-tokens']}</span>{/if}
			<span class="text-mist"> · discard {lobby['discard-tokens']}</span>
		</span>
	</div>

	{#if isMayor}
		{#if head}
			<div class="rounded-2xl border border-apple-200 bg-apple-50 p-3 dark:border-white/10 dark:bg-white/5">
				<p class="text-xs uppercase tracking-wide text-mist"><span dir="auto">{head.name}</span> asks</p>
				<p class="font-display text-lg" dir="auto">{head.text}</p>
			</div>
			<div class="grid grid-cols-2 gap-2 sm:grid-cols-3">
				{#each buttons as b (b.a)}
					<button
						onclick={() => answer(b.a)}
						class={['rounded-xl px-3 py-2.5 text-sm font-medium transition hover:opacity-90', b.cls]}
					>
						{b.label}
					</button>
				{/each}
			</div>
			<button
				onclick={() => answer('discard')}
				disabled={lobby['discard-tokens'] <= 0}
				class="self-start text-xs text-mist underline-offset-2 hover:underline disabled:opacity-40"
			>
				Discard this question
			</button>
		{:else}
			<p class="rounded-2xl border border-dashed border-frost p-4 text-center text-mist dark:border-white/10">
				No questions waiting — players are thinking.
			</p>
		{/if}
		{#if canEditLast}
			<button
				onclick={editLastAnswer}
				class="self-start rounded-lg border border-frost px-3 py-1.5 text-xs font-medium text-mist transition hover:bg-frost dark:border-white/10 dark:hover:bg-white/10"
			>
				Edit last answer
			</button>
		{/if}
	{/if}
</div>
