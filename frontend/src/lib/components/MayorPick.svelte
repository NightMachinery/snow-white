<script lang="ts">
	import type { Lobby } from '$lib/types';
	import { conn } from '$lib/ws.svelte';
	import RoleCard from './RoleCard.svelte';

	let { lobby }: { lobby: Lobby } = $props();
	const isMayor = $derived(lobby.you['is-mayor']);
	const mayorName = $derived(lobby.mayor ? lobby.players[lobby.mayor]?.['display-name'] : '');
	let customWord = $state('');

	function submitCustomWord() {
		const word = customWord.trim();
		if (!word) return;
		conn.send({ type: 'game/pick', word });
	}
</script>

<div class="mx-auto flex max-w-lg flex-col gap-5">
	<RoleCard {lobby} />

	{#if isMayor}
		<div class="text-center">
			<h2 class="font-display text-2xl">{lobby['custom-word-mode'] ? 'Write the secret word' : 'Choose the secret word'}</h2>
			<p class="text-mist">Only the Seer and Wolves will know it too.</p>
		</div>
		{#if lobby['custom-word-mode']}
			<div class="rounded-card border border-frost bg-white/70 p-5 shadow-sm dark:border-white/10 dark:bg-white/5">
				<label class="flex flex-col gap-2">
					<span class="text-sm font-medium text-mist">Secret word or phrase</span>
					<input
						bind:value={customWord}
						dir="auto"
						maxlength="80"
						onkeydown={(e) => e.key === 'Enter' && submitCustomWord()}
						class="rounded-xl border border-frost bg-snow px-4 py-3 font-display text-2xl outline-none focus:ring-2 focus:ring-apple-400 dark:border-white/10 dark:bg-ink"
					/>
				</label>
				<button
					onclick={submitCustomWord}
					disabled={!customWord.trim()}
					class="mt-4 w-full rounded-xl bg-apple-500 px-4 py-3 font-medium text-white transition hover:bg-apple-600 disabled:opacity-40"
				>
					Use this word
				</button>
			</div>
		{:else}
			<div class="grid gap-3">
				{#each lobby.words as word (word)}
					<button
						onclick={() => conn.send({ type: 'game/pick', word })}
						dir="auto"
						class="rounded-card border border-frost bg-white/70 px-5 py-5 font-display text-2xl shadow-sm transition hover:border-apple-500 hover:ring-2 hover:ring-apple-400 dark:border-white/10 dark:bg-white/5"
					>
						{word}
					</button>
				{/each}
			</div>
		{/if}
	{:else}
		<div class="flex flex-col items-center gap-2 py-10 text-center">
			<p class="animate-pulse font-display text-2xl">
				<span dir="auto">{mayorName}</span> is {lobby['custom-word-mode'] ? 'writing' : 'choosing'} the word…
			</p>
			<p class="text-mist">Get your questions ready.</p>
		</div>
	{/if}
</div>
