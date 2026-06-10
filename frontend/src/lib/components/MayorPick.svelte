<script lang="ts">
	import type { Lobby } from '$lib/types';
	import { conn } from '$lib/ws.svelte';
	import RoleCard from './RoleCard.svelte';

	let { lobby }: { lobby: Lobby } = $props();
	const isMayor = $derived(lobby.you['is-mayor']);
	const mayorName = $derived(lobby.mayor ? lobby.players[lobby.mayor]?.['display-name'] : '');
</script>

<div class="mx-auto flex max-w-lg flex-col gap-5">
	<RoleCard {lobby} />

	{#if isMayor}
		<div class="text-center">
			<h2 class="font-display text-2xl">Choose the secret word</h2>
			<p class="text-mist">Only the Seer and Wolves will know it too.</p>
		</div>
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
	{:else}
		<div class="flex flex-col items-center gap-2 py-10 text-center">
			<p class="animate-pulse font-display text-2xl">
				<span dir="auto">{mayorName}</span> is choosing the word…
			</p>
			<p class="text-mist">Get your questions ready.</p>
		</div>
	{/if}
</div>
