<script lang="ts">
	import { untrack } from 'svelte';
	import type { GameMode, Lobby } from '$lib/types';
	import { conn } from '$lib/ws.svelte';
	import { seatedPlayers, bystanders } from '$lib/game';
	import Settings2 from '@lucide/svelte/icons/settings-2';
	import ChevronDown from '@lucide/svelte/icons/chevron-down';
	import UserMinus from '@lucide/svelte/icons/user-minus';
	import UserPlus from '@lucide/svelte/icons/user-plus';
	import ShieldPlus from '@lucide/svelte/icons/shield-plus';
	import ShieldMinus from '@lucide/svelte/icons/shield-minus';
	import MonitorSmartphone from '@lucide/svelte/icons/monitor-smartphone';
	import Check from '@lucide/svelte/icons/check';

	// `compact` (mid-game) renders the panel collapsed behind a disclosure button;
	// the lobby renders it always-open. The same controls are used in both places.
	let { lobby, compact = false }: { lobby: Lobby; compact?: boolean } = $props();

	const canMod = $derived(lobby.you['can-moderate']);
	const elig = $derived(lobby['mayor-eligibility']);
	const inGame = $derived(lobby['game-state'] !== 'lobby');

	// Lobby: open by default. Mid-game (compact): collapsed behind a disclosure.
	// `compact` is fixed per mount, so read it untracked to set the initial state.
	let open = $state(untrack(() => !compact));
	let copiedMigrationFor = $state<string | null>(null);
	let copiedMigrationTimer: ReturnType<typeof setTimeout> | null = null;

	function setTimer(minutes: number) {
		conn.send({ type: 'settings/timer', minutes });
	}
	function setGameMode(mode: GameMode) {
		conn.send({ type: 'settings/game-mode', mode });
	}
	function setPick(n: number) {
		conn.send({ type: 'settings/pick-count', 'pick-count': n });
	}
	function setCustomWordMode(enabled: boolean) {
		conn.send({ type: 'settings/custom-word-mode', enabled });
	}
	function toggleRole(role: 'villager' | 'seer' | 'werewolf') {
		conn.send({ type: 'settings/eligibility', roles: { ...elig, [role]: !elig[role] } });
	}
	function setBudget(patch: { tokens?: number; 'maybe-tokens'?: number; 'discard-tokens'?: number }) {
		conn.send({ type: 'settings/budget', budget: patch });
	}
	function setRule(key: string, value: boolean) {
		conn.send({ type: 'settings/rules', rules: { [key]: value } });
	}
	function toggleWordpack(id: string) {
		const selected = lobby['selected-wordpacks'];
		const next = selected.includes(id) ? selected.filter((pack) => pack !== id) : [...selected, id];
		if (next.length === 0) return;
		conn.send({ type: 'settings/wordpacks', wordpacks: next });
	}
	function unseat(target: string) {
		conn.send({ type: 'mod/unseat', target });
	}
	function seat(target: string) {
		conn.send({ type: 'mod/seat', target });
	}
	function promote(target: string) {
		conn.send({ type: 'mod/promote', target });
	}
	function demote(target: string) {
		conn.send({ type: 'mod/demote', target });
	}
	function legacyCopy(text: string): boolean {
		const textarea = document.createElement('textarea');
		textarea.value = text;
		textarea.setAttribute('readonly', '');
		textarea.style.position = 'fixed';
		textarea.style.left = '-9999px';
		document.body.appendChild(textarea);
		textarea.select();
		const ok = document.execCommand('copy');
		document.body.removeChild(textarea);
		return ok;
	}
	async function copyMigration(authId: string, token: string | null | undefined) {
		if (!token) return;
		const url = `${location.origin}/room/${encodeURIComponent(lobby.name)}?migrate=${encodeURIComponent(token)}`;
		try {
			if (navigator.clipboard?.writeText) {
				await navigator.clipboard.writeText(url);
			} else if (!legacyCopy(url)) {
				throw new Error('copy failed');
			}
			copiedMigrationFor = authId;
			if (copiedMigrationTimer) clearTimeout(copiedMigrationTimer);
			copiedMigrationTimer = setTimeout(() => (copiedMigrationFor = null), 1500);
		} catch {
			prompt('Copy migrate-device link', url);
		}
	}

	const seated = $derived(seatedPlayers(lobby));
	const benched = $derived(bystanders(lobby));
	const selectedWordpacks = $derived(new Set(lobby['selected-wordpacks']));
	const customWordMode = $derived(lobby['custom-word-mode']);
	const classicMode = $derived(lobby['game-mode'] === 'classic');
</script>

<div class="rounded-2xl border border-frost dark:border-white/10">
	<button
		onclick={() => (open = !open)}
		class="flex w-full items-center justify-between gap-2 px-4 py-3 font-medium"
		aria-expanded={open}
	>
		<span class="flex items-center gap-2">
			<Settings2 class="size-4 text-mist" /> Settings{#if !canMod}<span class="text-xs font-normal text-mist"> · view only</span>{/if}
		</span>
		{#if compact}
			<ChevronDown class={['size-4 text-mist transition-transform', open && 'rotate-180']} />
		{/if}
	</button>

	{#if open}
		<div class="flex flex-col gap-3 border-t border-frost px-4 py-3 text-sm dark:border-white/10">
			{#if !inGame}
				<div class="flex flex-col gap-1.5">
					<span class="text-mist">Game mode</span>
					<div class="grid grid-cols-2 rounded-xl bg-frost p-1 dark:bg-white/10" role="group" aria-label="Game mode">
						{#each [
							{ mode: 'werewords', label: 'Werewords' },
							{ mode: 'classic', label: 'Classic' }
						] as option (option.mode)}
							<button
								disabled={!canMod}
								onclick={() => setGameMode(option.mode as GameMode)}
								class={[
									'rounded-lg px-3 py-1.5 text-xs font-medium transition disabled:opacity-60',
									lobby['game-mode'] === option.mode
										? 'bg-white text-apple-600 shadow-sm dark:bg-ink dark:text-apple-400'
										: 'text-mist hover:bg-white/50 dark:hover:bg-white/10'
								]}
								aria-pressed={lobby['game-mode'] === option.mode}
							>
								{option.label}
							</button>
						{/each}
					</div>
					<p class="text-xs text-mist">
						{classicMode ? 'Co-op: no hidden teams, starts with 2 players.' : 'Hidden roles: Seer, Wolves, and final votes.'}
					</p>
				</div>
			{/if}

			<!-- Timer -->
			<label class="flex items-center justify-between gap-2">
				<span class="text-mist">Timer</span>
				<select
					disabled={!canMod}
					value={lobby['timer-minutes']}
					onchange={(e) => setTimer(Number(e.currentTarget.value))}
					class="rounded-lg border border-frost bg-snow px-2 py-1 disabled:opacity-60 dark:border-white/10 dark:bg-white/5"
				>
					{#each [1, 2, 3, 4, 5, 10] as m (m)}<option value={m}>{m} min</option>{/each}
				</select>
			</label>

			<!-- Word source (lobby only — fixed once a game starts) -->
			{#if !inGame}
				<label class="flex items-center justify-between gap-2">
					<span class="text-mist">Mayor writes custom word</span>
					<button
						role="switch"
						aria-checked={customWordMode}
						aria-label="Mayor writes custom word"
						disabled={!canMod}
						onclick={() => setCustomWordMode(!customWordMode)}
						class={[
							'relative h-5 w-9 shrink-0 rounded-full transition disabled:opacity-60',
							customWordMode ? 'bg-apple-500' : 'bg-frost dark:bg-white/15'
						]}
					>
						<span
							class={[
								'absolute top-0.5 size-4 rounded-full bg-white transition-all',
								customWordMode ? 'left-[1.125rem]' : 'left-0.5'
							]}
						></span>
					</button>
				</label>
				<label class={["flex items-center justify-between gap-2", customWordMode && "opacity-50"]}>
					<span class="text-mist">Word choices</span>
					<select
						disabled={!canMod || customWordMode}
						value={lobby['pick-count']}
						onchange={(e) => setPick(Number(e.currentTarget.value))}
						class="rounded-lg border border-frost bg-snow px-2 py-1 disabled:opacity-60 dark:border-white/10 dark:bg-white/5"
					>
						{#each [1, 2, 3, 4] as n (n)}<option value={n}>{n}</option>{/each}
					</select>
				</label>
			{/if}

			<!-- Wordpacks (lobby only — fixed once a game starts) -->
			{#if !inGame}
				<div class={["flex flex-col gap-2 border-t border-frost/70 pt-2 dark:border-white/5", customWordMode && "opacity-50"]}>
					<div class="flex items-baseline justify-between gap-2">
						<span class="text-mist">Wordpacks</span>
						<span class="text-xs text-mist">{lobby['selected-wordpacks'].length} selected</span>
					</div>
					<div class="grid max-h-44 gap-1 overflow-y-auto pr-1">
						{#each lobby['available-wordpacks'] as pack (pack.id)}
							{@const selected = selectedWordpacks.has(pack.id)}
							<label
								class="flex items-center justify-between gap-3 rounded-lg px-2 py-1.5 transition hover:bg-frost/70 dark:hover:bg-white/10"
							>
								<span class="min-w-0">
									<span class="block truncate" dir="auto">{pack.name}</span>
									<span class="text-xs text-mist">{pack['word-count']} words</span>
								</span>
								<input
									type="checkbox"
									checked={selected}
									disabled={!canMod || customWordMode || (selected && lobby['selected-wordpacks'].length === 1)}
									onchange={() => toggleWordpack(pack.id)}
									class="size-4 rounded border-frost text-apple-500 disabled:opacity-60"
								/>
							</label>
						{/each}
					</div>
				</div>
			{/if}

			<!-- Token budget -->
			<div class="flex items-center justify-between gap-2">
				<span class="text-mist">Answer budget</span>
				<div class="flex items-center gap-1.5">
					<input
						type="number"
						min="1"
						disabled={!canMod}
						value={lobby['max-tokens']}
						onchange={(e) => setBudget({ tokens: Number(e.currentTarget.value) })}
						class="w-16 rounded-lg border border-frost bg-snow px-2 py-1 tabular-nums disabled:opacity-60 dark:border-white/10 dark:bg-white/5"
						aria-label="Yes/No budget"
					/>
					{#if !lobby['shared-maybe-pool']}
						<span class="text-mist">·</span>
						<input
							type="number"
							min="0"
							disabled={!canMod}
							value={lobby['max-maybe-tokens']}
							onchange={(e) => setBudget({ 'maybe-tokens': Number(e.currentTarget.value) })}
							class="w-14 rounded-lg border border-frost bg-snow px-2 py-1 tabular-nums disabled:opacity-60 dark:border-white/10 dark:bg-white/5"
							aria-label="Maybe budget"
						/>
						<span class="text-xs text-mist">maybe</span>
					{/if}
				</div>
			</div>

			<!-- Discard budget -->
			<div class="flex items-center justify-between gap-2">
				<span class="text-mist">Discard budget</span>
				<input
					type="number"
					min="0"
					disabled={!canMod}
					value={lobby['max-discard-tokens']}
					onchange={(e) => setBudget({ 'discard-tokens': Number(e.currentTarget.value) })}
					class="w-16 rounded-lg border border-frost bg-snow px-2 py-1 tabular-nums disabled:opacity-60 dark:border-white/10 dark:bg-white/5"
					aria-label="Discard budget"
				/>
			</div>

			<!-- Rule toggles -->
			<div class="flex flex-col gap-2 border-t border-frost/70 pt-2 dark:border-white/5">
				{#each [
					{ k: 'shared-maybe-pool', label: 'Maybes share the main budget' },
					{ k: 'soft-costs', label: '“So close / Way off” cost a token' },
					{ k: 'one-at-a-time', label: 'One question at a time' },
					{ k: 'lock-seating', label: 'Lock seating (mods only)' }
				] as r (r.k)}
					<label class="flex items-center justify-between gap-2">
						<span class="text-mist">{r.label}</span>
						<button
							role="switch"
							aria-checked={lobby[r.k as 'soft-costs']}
							aria-label={r.label}
							disabled={!canMod}
							onclick={() => setRule(r.k, !lobby[r.k as 'soft-costs'])}
							class={[
								'relative h-5 w-9 shrink-0 rounded-full transition disabled:opacity-60',
								lobby[r.k as 'soft-costs'] ? 'bg-apple-500' : 'bg-frost dark:bg-white/15'
							]}
						>
							<span
								class={[
									'absolute top-0.5 size-4 rounded-full bg-white transition-all',
									lobby[r.k as 'soft-costs'] ? 'left-[1.125rem]' : 'left-0.5'
								]}
							></span>
						</button>
					</label>
				{/each}
			</div>

			<!-- Mayor eligibility (lobby only) -->
			{#if !inGame && !classicMode}
				<div class="border-t border-frost/70 pt-2 dark:border-white/5">
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
			{/if}

			<!-- Player management (mods only) -->
			{#if canMod}
				<div class="border-t border-frost/70 pt-2 dark:border-white/5">
					<span class="text-mist">Manage players</span>
					<div class="mt-1.5 flex flex-col gap-1">
						{#each seated as p (p['auth-id'])}
							<div class="flex items-center justify-between gap-2">
								<span class="truncate" dir="auto">{p['display-name']}</span>
								<div class="flex shrink-0 gap-1">
									{#if p['migration-token']}
										<button onclick={() => copyMigration(p['auth-id'], p['migration-token'])} class="rounded-lg px-2 py-1 text-xs text-mist transition hover:bg-frost hover:text-apple-500 dark:hover:bg-white/10" aria-label="Copy this player's migrate-device link" title="Copy this player's migrate-device link">
											{#if copiedMigrationFor === p['auth-id']}<Check class="size-3.5 text-forest" />{:else}<MonitorSmartphone class="size-3.5" />{/if}
										</button>
									{/if}
									{#if !p['is-owner'] && !p['is-mod'] && !p['is-temp-mod']}
										<button onclick={() => promote(p['auth-id'])} class="rounded-lg px-2 py-1 text-xs text-forest transition hover:bg-frost dark:hover:bg-white/10" aria-label="Promote moderator" title="Promote moderator"><ShieldPlus class="size-3.5" /></button>
									{/if}
									{#if !p['is-owner'] && (p['is-mod'] || p['is-temp-mod'])}
										<button onclick={() => demote(p['auth-id'])} class="rounded-lg px-2 py-1 text-xs text-apple-500 transition hover:bg-frost dark:hover:bg-white/10" aria-label="Demote moderator" title="Demote moderator"><ShieldMinus class="size-3.5" /></button>
									{/if}
									<button onclick={() => unseat(p['auth-id'])} class="flex items-center gap-1 rounded-lg px-2 py-1 text-xs text-mist transition hover:bg-frost dark:hover:bg-white/10"><UserMinus class="size-3.5" /> Bench</button>
								</div>
							</div>
						{/each}
						{#each benched as p (p['auth-id'])}
							<div class="flex items-center justify-between gap-2 text-mist">
								<span class="truncate" dir="auto">{p['display-name']}</span>
								<div class="flex shrink-0 gap-1">
									{#if p['migration-token']}
										<button onclick={() => copyMigration(p['auth-id'], p['migration-token'])} class="rounded-lg px-2 py-1 text-xs text-mist transition hover:bg-apple-50 hover:text-apple-500 dark:hover:bg-white/5" aria-label="Copy this player's migrate-device link" title="Copy this player's migrate-device link">
											{#if copiedMigrationFor === p['auth-id']}<Check class="size-3.5 text-forest" />{:else}<MonitorSmartphone class="size-3.5" />{/if}
										</button>
									{/if}
									{#if !p['is-owner'] && !p['is-mod'] && !p['is-temp-mod']}
										<button onclick={() => promote(p['auth-id'])} class="rounded-lg px-2 py-1 text-xs text-forest transition hover:bg-apple-50 dark:hover:bg-white/5" aria-label="Promote moderator" title="Promote moderator"><ShieldPlus class="size-3.5" /></button>
									{/if}
									{#if !p['is-owner'] && (p['is-mod'] || p['is-temp-mod'])}
										<button onclick={() => demote(p['auth-id'])} class="rounded-lg px-2 py-1 text-xs text-apple-500 transition hover:bg-apple-50 dark:hover:bg-white/5" aria-label="Demote moderator" title="Demote moderator"><ShieldMinus class="size-3.5" /></button>
									{/if}
									<button onclick={() => seat(p['auth-id'])} class="flex items-center gap-1 rounded-lg px-2 py-1 text-xs text-apple-500 transition hover:bg-apple-50 dark:hover:bg-white/5"><UserPlus class="size-3.5" /> Seat</button>
								</div>
							</div>
						{/each}
					</div>
				</div>
			{/if}
		</div>
	{/if}
</div>
