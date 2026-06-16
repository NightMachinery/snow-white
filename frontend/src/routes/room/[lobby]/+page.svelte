<script lang="ts">
	import { page } from '$app/state';
	import { goto } from '$app/navigation';
	import { conn } from '$lib/ws.svelte';
	import { identity } from '$lib/identity.svelte';
	import { canConnectToRoom, normalizePlayerName } from '$lib/name-gate';
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
	const migrationToken = $derived(page.url.searchParams.get('migrate'));
	let pendingName = $state(identity.name);
	let nameError = $state('');

	const savedName = $derived(normalizePlayerName(identity.name));
	const canConnect = $derived(Boolean(migrationToken) || canConnectToRoom(identity.name));
	const pendingNameOk = $derived(normalizePlayerName(pendingName).length > 0);

	// Connect only after we have a real saved name. Direct invite links should not
	// join the server as the generic "Player" fallback.
	$effect(() => {
		if (!canConnect) return;
		conn.connect(room, savedName || 'Player', migrationToken);
		return () => conn.disconnect();
	});

	function joinWithName() {
		const normalized = normalizePlayerName(pendingName);
		if (!normalized) {
			nameError = 'Enter your name to join this room';
			return;
		}

		nameError = '';
		identity.setName(normalized);
	}

	const lobby = $derived(conn.lobby);
	const gameState = $derived(lobby?.['game-state']);
</script>

<svelte:head><title>Snow White · {room}</title></svelte:head>

<main class="mx-auto flex min-h-dvh max-w-5xl flex-col px-4 py-4 sm:px-6">
	{#if !canConnect}
		<div class="absolute right-5 top-5"><ThemeToggle /></div>

		<div class="flex flex-1 flex-col items-center justify-center gap-6 text-center">
			<header class="max-w-md">
				<p class="text-sm font-medium uppercase tracking-[0.24em] text-apple-500">Snow White</p>
				<h1 class="mt-3 font-display text-4xl font-semibold tracking-tight">Join “{room}”</h1>
				<p class="mt-3 text-mist">Choose the name your friends will see in this game.</p>
			</header>

			<form
				onsubmit={(event) => {
					event.preventDefault();
					joinWithName();
				}}
				class="flex w-full max-w-sm flex-col gap-3 rounded-card bg-white/70 p-5 text-left shadow-xl ring-1 ring-frost dark:bg-white/5 dark:ring-white/10"
			>
				<label class="flex flex-col gap-1">
					<span class="text-sm font-medium text-mist">Your name</span>
					<input
						bind:value={pendingName}
						placeholder="e.g. Briar Rose"
						maxlength="24"
						dir="auto"
						aria-invalid={nameError ? 'true' : undefined}
						aria-describedby={nameError ? 'room-name-error' : undefined}
						class="rounded-xl border border-frost bg-snow px-4 py-2.5 outline-none focus:ring-2 focus:ring-apple-400 dark:border-white/10 dark:bg-white/5"
					/>
				</label>

				{#if nameError}
					<p id="room-name-error" class="text-sm text-red-500">{nameError}</p>
				{/if}

				<button
					type="submit"
					disabled={!pendingNameOk}
					class="rounded-xl bg-apple-500 px-4 py-2.5 font-medium text-white shadow-sm transition hover:bg-apple-600 disabled:opacity-40"
				>
					Join room
				</button>
			</form>
		</div>
	{:else if conn.error && !lobby}
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
			{#if gameState === 'lobby'}
				<LobbyScreen {lobby} />
			{:else if gameState === 'mayor-pick'}
				<MayorPick {lobby} />
			{:else if gameState === 'question-round' || gameState === 'word-guessed'}
				<QuestionRound {lobby} />
			{:else if gameState === 'out-of-time' || gameState === 'out-of-tokens'}
				<VoteScreen {lobby} mode="village" />
			{:else if gameState === 'end-game'}
				<EndScreen {lobby} />
			{/if}

			<!-- Wolves vote for the seer once the word is guessed in Werewords mode. -->
			{#if gameState === 'word-guessed' && lobby['game-mode'] === 'werewords'}
				<VoteScreen {lobby} mode="wolf" />
			{/if}
		</div>
	{/if}
</main>
