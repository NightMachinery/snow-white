<script lang="ts">
	import type { Lobby } from '$lib/types';
	import { conn } from '$lib/ws.svelte';
	import Settings2 from '@lucide/svelte/icons/settings-2';

	let { lobby }: { lobby: Lobby } = $props();
	const canMod = $derived(lobby.you['can-moderate']);
	const elig = $derived(lobby['mayor-eligibility']);

	function setTimer(minutes: number) {
		conn.send({ type: 'settings/timer', minutes });
	}
	function setPick(n: number) {
		conn.send({ type: 'settings/pick-count', 'pick-count': n });
	}
	function toggleRole(role: 'villager' | 'seer' | 'werewolf') {
		conn.send({ type: 'settings/eligibility', roles: { ...elig, [role]: !elig[role] } });
	}
</script>

<div class="rounded-2xl border border-frost p-4 dark:border-white/10">
	<h3 class="mb-3 flex items-center gap-2 font-medium">
		<Settings2 class="size-4 text-mist" /> Settings
	</h3>

	<div class="flex flex-col gap-3 text-sm">
		<label class="flex items-center justify-between gap-2">
			<span class="text-mist">Timer</span>
			<select
				disabled={!canMod}
				value={lobby['timer-minutes']}
				onchange={(e) => setTimer(Number(e.currentTarget.value))}
				class="rounded-lg border border-frost bg-snow px-2 py-1 disabled:opacity-60 dark:border-white/10 dark:bg-white/5"
			>
				{#each [1, 2, 3, 4, 5] as m (m)}<option value={m}>{m} min</option>{/each}
			</select>
		</label>

		<label class="flex items-center justify-between gap-2">
			<span class="text-mist">Word choices</span>
			<select
				disabled={!canMod}
				value={lobby['pick-count']}
				onchange={(e) => setPick(Number(e.currentTarget.value))}
				class="rounded-lg border border-frost bg-snow px-2 py-1 disabled:opacity-60 dark:border-white/10 dark:bg-white/5"
			>
				{#each [1, 2, 3, 4] as n (n)}<option value={n}>{n}</option>{/each}
			</select>
		</label>

		<div>
			<span class="text-mist">Mayor can be</span>
			<div class="mt-1.5 flex flex-wrap gap-1.5">
				{#each ['villager', 'seer', 'werewolf'] as role (role)}
					<button
						disabled={!canMod}
						onclick={() => toggleRole(role as 'villager')}
						class={[
							'rounded-full px-3 py-1 text-xs capitalize transition disabled:opacity-60',
							elig[role as 'villager']
								? 'bg-apple-500 text-white'
								: 'bg-frost text-mist dark:bg-white/10'
						]}
					>
						{role}
					</button>
				{/each}
			</div>
		</div>
	</div>
</div>
