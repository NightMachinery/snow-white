<script lang="ts">
	import type { Lobby, Player } from '$lib/types';
	import Crown from '@lucide/svelte/icons/crown';
	import Shield from '@lucide/svelte/icons/shield';
	import WifiOff from '@lucide/svelte/icons/wifi-off';

	let {
		player,
		lobby,
		selectable = false,
		selected = false,
		onpick
	}: {
		player: Player;
		lobby: Lobby;
		selectable?: boolean;
		selected?: boolean;
		onpick?: (authId: string) => void;
	} = $props();

	const isYou = $derived(player['auth-id'] === lobby.you['auth-id']);
	const initial = $derived((player['display-name'] || '?').charAt(0).toUpperCase());
</script>

<button
	type="button"
	disabled={!selectable}
	onclick={() => onpick?.(player['auth-id'])}
	class={[
		'flex w-full items-center gap-3 rounded-2xl border p-2.5 text-left transition',
		selectable && 'cursor-pointer hover:ring-2 hover:ring-apple-400',
		selected ? 'border-apple-500 ring-2 ring-apple-500' : 'border-frost dark:border-white/10',
		!player.online && 'opacity-50'
	]}
>
	<span
		class="grid size-9 shrink-0 place-items-center rounded-full font-display text-sm font-semibold text-white"
		style:background-color={player.color ?? 'var(--color-mist)'}
	>
		{initial}
	</span>
	<span class="min-w-0 flex-1">
		<span class="flex items-center gap-1 truncate font-medium">
			<span class="truncate" dir="auto">{player['display-name']}</span>
			{#if isYou}<span class="shrink-0 text-xs text-mist">(you)</span>{/if}
		</span>
		<span class="flex items-center gap-1.5 text-xs text-mist">
			{#if player.mayor}<Crown class="size-3 text-apple-500" /> Mayor{/if}
			{#if player['can-moderate']}<Shield class="size-3" />{/if}
			{#if !player.online}<WifiOff class="size-3" /> offline{/if}
		</span>
	</span>
</button>
