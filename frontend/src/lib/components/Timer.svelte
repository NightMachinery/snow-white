<script lang="ts">
	import type { Lobby } from '$lib/types';
	import { conn } from '$lib/ws.svelte';
	import Clock from '@lucide/svelte/icons/clock';

	let {
		lobby,
		deadlineMs = undefined,
		expireCommand = 'game/timeout'
	}: { lobby: Lobby; deadlineMs?: number | null; expireCommand?: 'game/timeout' | 'game/finish-vote' } = $props();

	// The server anchors the deadline when the Mayor picks a word. Snapshots after
	// answers reuse the same deadline, so the countdown does not reset.
	let now = $state(Date.now());

	$effect(() => {
		const id = setInterval(() => (now = Date.now()), 250);
		return () => clearInterval(id);
	});

	const deadline = $derived(deadlineMs ?? lobby['round-deadline-ms'] ?? Date.now() + lobby['timer-minutes'] * 60_000);
	const remaining = $derived(Math.max(0, deadline - now));
	const mm = $derived(Math.floor(remaining / 60_000));
	const ss = $derived(Math.floor((remaining % 60_000) / 1000));
	const low = $derived(remaining < 15_000);

	let fired = $state(false);
	let lastDeadline = $state<number | null>(null);
	$effect(() => {
		if (lastDeadline !== deadline) {
			lastDeadline = deadline;
			fired = false;
		}
		if (remaining === 0 && !fired && lobby.you['can-moderate']) {
			fired = true;
			conn.send({ type: expireCommand });
		}
	});
</script>

<div
	class={[
		'flex items-center gap-1.5 rounded-full px-3 py-1 font-mono text-sm tabular-nums transition-colors',
		low
			? 'bg-apple-100 text-apple-600 ring-1 ring-apple-400 dark:bg-apple-500/15 dark:text-apple-400'
			: 'bg-frost text-ink dark:bg-white/10 dark:text-snow'
	]}
	style={low ? 'animation: timer-pulse 1s var(--ease-soft) infinite' : ''}
>
	<Clock class={['size-4', low && 'text-apple-500']} />
	{mm}:{ss.toString().padStart(2, '0')}
</div>
