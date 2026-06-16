<script lang="ts">
	import type { Lobby } from '$lib/types';
	import { seatedPlayers } from '$lib/game';
	import Crown from '@lucide/svelte/icons/crown';
	import { roleLabel } from '$lib/game';

	// A compact roster for in-game screens. The snapshot redacts hidden roles, so
	// any role present here is intentionally public (late Villager or end-game reveal).
	let { lobby }: { lobby: Lobby } = $props();
	const players = $derived(seatedPlayers(lobby));
</script>

<div class="rounded-2xl border border-frost p-3 dark:border-white/10">
	<h3 class="mb-2 text-xs font-medium uppercase tracking-wide text-mist">
		At the table · {players.length}
	</h3>
	<ul class="flex flex-col gap-1">
		{#each players as p (p['auth-id'])}
			<li class="flex items-center gap-2 text-sm" class:opacity-50={!p.online}>
				<span
					class="grid size-6 shrink-0 place-items-center rounded-full text-[0.65rem] font-semibold text-white"
					style="background-color: {p.color ?? 'var(--color-mist)'}"
				>
					{(p['display-name'] || '?').charAt(0).toUpperCase()}
				</span>
				<span class="min-w-0 flex-1 truncate" dir="auto">{p['display-name']}</span>
				{#if p.mayor}
					<span
						class="flex shrink-0 items-center gap-1 rounded-full bg-apple-50 px-1.5 py-0.5 text-[0.65rem] font-medium text-apple-600 dark:bg-apple-500/15 dark:text-apple-400"
					>
						<Crown class="size-3" /> Mayor
					</span>
				{/if}
				{#if p.role}
					<span class="shrink-0 rounded-full bg-forest/10 px-1.5 py-0.5 text-[0.65rem] font-medium text-forest dark:bg-forest/15">
						{roleLabel[p.role]}
					</span>
				{/if}
				{#if !p.online}<span class="shrink-0 text-[0.65rem] text-mist">offline</span>{/if}
			</li>
		{/each}
	</ul>
</div>
