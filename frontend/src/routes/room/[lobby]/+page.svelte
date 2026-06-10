<script lang="ts">
	import { page } from '$app/state';
	import { goto } from '$app/navigation';
	import { conn } from '$lib/ws.svelte';
	import { identity } from '$lib/identity.svelte';
	import ThemeToggle from '$lib/components/ThemeToggle.svelte';
	import ErrorNotice from '$lib/components/ErrorNotice.svelte';
	import RoomHeader from '$lib/components/RoomHeader.svelte';
	import LobbyScreen from '$lib/components/LobbyScreen.svelte';
	import MayorPick from '$lib/components/MayorPick.svelte';
	import QuestionRound from '$lib/components/QuestionRound.svelte';
	import VoteScreen from '$lib/components/VoteScreen.svelte';
	import EndScreen from '$lib/components/EndScreen.svelte';
	import Rules from '$lib/components/Rules.svelte';

	const room = $derived(decodeURIComponent(page.params.lobby ?? ''));

	// Connect on mount; reconnect if the room param changes. Disconnect on leave.
	$effect(() => {
		const name = identity.name || 'Player';
		conn.connect(room, name);
		return () => conn.disconnect();
	});

	const lobby = $derived(conn.lobby);
	const state = $derived(lobby?.['game-state']);
</script>

<svelte:head><title>Snow White · {room}</title></svelte:head>

<main class="mx-auto flex min-h-dvh max-w-5xl flex-col px-4 py-4 sm:px-6">
	{#if conn.error && !lobby}
		<div class="flex flex-1 flex-col items-center justify-center gap-4 text-center">
			<div class="flex flex-col items-center gap-2">
				<p class="font-display text-2xl">Hmm</p>
				<ErrorNotice message={conn.error} detail={conn.errorDetail} centered />
			</div>
			<button
				onclick={() => goto('/')}
				class="rounded-xl bg-apple-500 px-5 py-2.5 font-medium text-white">Back home</button
			>
		</div>
	{:else if !lobby}
		<div class="flex flex-1 items-center justify-center text-mist">
			<p class="animate-pulse">Connecting to “{room}”…</p>
		</div>
	{:else}
		<RoomHeader {lobby} {room}>
			{#snippet right()}
				<Rules />
				<ThemeToggle />
			{/snippet}
		</RoomHeader>

		<div class="flex-1 py-4">
			{#if state === 'lobby'}
				<LobbyScreen {lobby} />
			{:else if state === 'mayor-pick'}
				<MayorPick {lobby} />
			{:else if state === 'question-round' || state === 'word-guessed'}
				<QuestionRound {lobby} />
			{:else if state === 'out-of-time' || state === 'out-of-tokens'}
				<VoteScreen {lobby} mode="village" />
			{:else if state === 'end-game'}
				<EndScreen {lobby} />
			{/if}

			<!-- Wolves vote for the seer once the word is guessed -->
			{#if state === 'word-guessed'}
				<VoteScreen {lobby} mode="wolf" />
			{/if}
		</div>
	{/if}
</main>
