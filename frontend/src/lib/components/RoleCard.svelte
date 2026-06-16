<script lang="ts">
	import type { Lobby } from '$lib/types';
	import { roleLabel } from '$lib/game';
	import Eye from '@lucide/svelte/icons/eye';
	import Moon from '@lucide/svelte/icons/moon';
	import Home from '@lucide/svelte/icons/house';

	let { lobby }: { lobby: Lobby } = $props();
	const role = $derived(lobby.you.role);
	const isMayor = $derived(lobby.you['is-mayor']);
	const classicMode = $derived(lobby['game-mode'] === 'classic');
	const knowsWord = $derived(lobby.you['knows-word']);
	const wolfNames = $derived(lobby.werewolves.map((a) => lobby.players[a]?.['display-name']).filter(Boolean));

	// The blurb must respect *what you actually know*, not just your role. The Mayor
	// always knows the word (they chose it) and answers questions rather than asking
	// them — so a villager-Mayor must not be told "you don't know the word".
	const blurb = $derived.by(() => {
		if (classicMode) {
			return isMayor
				? 'You know the word and answer the table. Everyone wins together if they guess it.'
				: 'Work together to ask sharp yes/no questions and guess the word before time runs out.';
		}
		if (isMayor) {
			if (role === 'werewolf')
				return 'You picked the word and you run the table — answer to mislead, and blend in.';
			if (role === 'seer')
				return 'You picked the word and you run the table — nudge the village without exposing yourself.';
			return 'You picked the word and you run the table — answer the questions and keep things fair.';
		}
		switch (role) {
			case 'seer':
				return 'You know the word. Help your village — but stay hidden from the wolves.';
			case 'werewolf':
				return 'You know the word and your fellow wolves. Mislead — but blend in.';
			default:
				return "You don't know the word. Ask sharp questions and watch who misleads.";
		}
	});
</script>

{#if role}
	<div
		class={[
			'flex items-center gap-3 rounded-2xl p-4 ring-1',
			role === 'werewolf'
				? 'bg-dusk/10 ring-dusk/30'
				: role === 'seer'
					? 'bg-apple-50 ring-apple-100 dark:bg-apple-500/10'
					: 'bg-forest/10 ring-forest/30'
		]}
	>
		<span class="grid size-11 place-items-center rounded-full bg-white/70 dark:bg-white/10">
			{#if role === 'seer'}<Eye class="size-6 text-apple-500" />
			{:else if role === 'werewolf'}<Moon class="size-6 text-dusk" />
			{:else}<Home class="size-6 text-forest" />{/if}
		</span>
		<div>
			<p class="font-display text-lg leading-tight">
				{#if classicMode}
					Classic mode{#if isMayor}<span class="text-apple-500"> · Mayor</span>{/if}
				{:else}
					You are the {roleLabel[role]}{#if isMayor}<span class="text-apple-500"> · Mayor</span>{/if}
				{/if}
			</p>
			<p class="text-sm text-mist">{blurb}</p>
			{#if role === 'werewolf' && wolfNames.length > 1}
				<p class="mt-1 text-xs text-mist">Your pack: <span dir="auto">{wolfNames.join(', ')}</span></p>
			{/if}
		</div>
	</div>
{/if}
