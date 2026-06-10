<script lang="ts">
	import type { Lobby } from '$lib/types';
	import { conn } from '$lib/ws.svelte';
	import Clock from '@lucide/svelte/icons/clock';

	let { lobby }: { lobby: Lobby } = $props();

	// Deadline is fixed when this component mounts (the question round begins). We
	// can't read the server's start time, so we anchor locally and count down —
	// good enough for a casual timer. Captured once in the mount effect to avoid
	// re-anchoring on every snapshot.
	let deadline = $state(0);
	let now = $state(0);

	$effect(() => {
		deadline = Date.now() + lobby['timer-minutes'] * 60_000;
		now = Date.now();
		const id = setInterval(() => (now = Date.now()), 250);
		return () => clearInterval(id);
	});

	const remaining = $derived(Math.max(0, deadline - now));
	const mm = $derived(Math.floor(remaining / 60_000));
	const ss = $derived(Math.floor((remaining % 60_000) / 1000));
	const low = $derived(remaining < 15_000);

	let fired = false;
	$effect(() => {
		if (remaining === 0 && !fired && lobby.you['can-moderate']) {
			fired = true;
			conn.send({ type: 'game/timeout' });
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
