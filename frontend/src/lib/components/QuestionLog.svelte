<script lang="ts">
	import type { Question } from '$lib/types';
	import { answerLabel } from '$lib/game';

	let {
		questions,
		title = 'Question log',
		empty = 'No questions logged yet.'
	}: { questions: Question[]; title?: string; empty?: string } = $props();

	const answerCls: Record<string, string> = {
		yes: 'text-forest',
		no: 'text-dusk',
		maybe: 'text-amber-500',
		'so-close': 'text-apple-400',
		'way-off': 'text-mist',
		correct: 'text-apple-600 font-semibold',
		discard: 'text-mist italic'
	};

	function label(q: Question): string {
		if (q.answer === 'discard') {
			return q['discarded-by'] === 'self' ? 'Withdrawn' : 'Discarded';
		}
		return answerLabel[q.answer ?? ''] ?? q.answer ?? '';
	}
</script>

<div class="flex flex-col gap-1.5">
	<h3 class="text-xs font-medium uppercase tracking-wide text-mist">{title}</h3>
	{#each [...questions].reverse() as q, i (questions.length - i)}
		<div class="flex items-baseline justify-between gap-3 rounded-xl bg-white/60 px-3 py-2 text-sm dark:bg-white/5">
			<span class="min-w-0" dir="auto"><span class="text-mist">{q.name}:</span> {q.text}</span>
			<span class={['shrink-0', answerCls[q.answer ?? ''] ?? 'text-mist']}>
				{label(q)}
			</span>
		</div>
	{:else}
		<p class="text-sm text-mist">{empty}</p>
	{/each}
</div>
