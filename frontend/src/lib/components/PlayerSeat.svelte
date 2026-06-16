<script lang="ts">
	import type { Lobby, Player } from '$lib/types';
	import Crown from '@lucide/svelte/icons/crown';
	import Shield from '@lucide/svelte/icons/shield';
	import WifiOff from '@lucide/svelte/icons/wifi-off';
	import UserMinus from '@lucide/svelte/icons/user-minus';
	import UserPlus from '@lucide/svelte/icons/user-plus';
	import Star from '@lucide/svelte/icons/star';
	import { roleLabel } from '$lib/game';

	let {
		player,
		lobby,
		selectable = false,
		selected = false,
		onpick,
		onbench,
		onseat,
		onmayor
	}: {
		player: Player;
		lobby: Lobby;
		selectable?: boolean;
		selected?: boolean;
		onpick?: (authId: string) => void;
		onbench?: (authId: string) => void;
		onseat?: (authId: string) => void;
		onmayor?: (authId: string) => void;
	} = $props();

	const isYou = $derived(player['auth-id'] === lobby.you['auth-id']);
	const initial = $derived((player['display-name'] || '?').charAt(0).toUpperCase());
	const hasActions = $derived(Boolean(onbench || onseat || onmayor));
	const cardClass = $derived([
		'flex w-full items-center gap-3 rounded-2xl border p-2.5 text-left transition',
		selectable && 'cursor-pointer hover:ring-2 hover:ring-apple-400',
		selected ? 'border-apple-500 ring-2 ring-apple-500' : 'border-frost dark:border-white/10',
		!player.online && 'opacity-50'
	]);

	function pick() {
		onpick?.(player['auth-id']);
	}
</script>

{#snippet content()}
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
		<span class="flex flex-wrap items-center gap-1.5 text-xs text-mist">
			{#if player.mayor}<Crown class="size-3 text-apple-500" /> Mayor{/if}
			{#if player.role}
				<span class="rounded-full bg-forest/10 px-1.5 py-0.5 text-[0.65rem] font-medium text-forest dark:bg-forest/15">
					{roleLabel[player.role]}
				</span>
			{/if}
			{#if player['can-moderate']}<Shield class="size-3" />{/if}
			{#if !player.online}<WifiOff class="size-3" /> offline{/if}
		</span>
	</span>
	{#if hasActions}
		<span class="flex shrink-0 gap-1" role="group" aria-label="Moderator actions">
			{#if onmayor}
				<button type="button" onclick={() => onmayor?.(player['auth-id'])} class="rounded-lg p-1.5 text-mist transition hover:bg-frost hover:text-apple-500 dark:hover:bg-white/10" aria-label="Choose as Mayor">
					<Star class={['size-4', lobby['preferred-mayor'] === player['auth-id'] && 'fill-apple-500 text-apple-500']} />
				</button>
			{/if}
			{#if onbench && player.seat && !player.spectator}
				<button type="button" onclick={() => onbench?.(player['auth-id'])} class="rounded-lg p-1.5 text-mist transition hover:bg-frost dark:hover:bg-white/10" aria-label="Bench player">
					<UserMinus class="size-4" />
				</button>
			{/if}
			{#if onseat && (!player.seat || player.spectator)}
				<button type="button" onclick={() => onseat?.(player['auth-id'])} class="rounded-lg p-1.5 text-apple-500 transition hover:bg-apple-50 dark:hover:bg-white/5" aria-label="Seat player">
					<UserPlus class="size-4" />
				</button>
			{/if}
		</span>
	{/if}
{/snippet}

{#if selectable && !hasActions}
	<button type="button" onclick={pick} class={cardClass}>
		{@render content()}
	</button>
{:else}
	<div class={cardClass}>
		{@render content()}
	</div>
{/if}
