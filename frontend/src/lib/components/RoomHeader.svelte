<script lang="ts">
	import type { Lobby } from '$lib/types';
	import { seatedCount } from '$lib/game';
	import Apple from '@lucide/svelte/icons/apple';
	import Users from '@lucide/svelte/icons/users';
	import Link from '@lucide/svelte/icons/link';
	import Check from '@lucide/svelte/icons/check';

	let { lobby, room, right }: { lobby: Lobby; room: string; right?: import('svelte').Snippet } =
		$props();

	let copied = $state(false);

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

	async function copyLink() {
		try {
			if (navigator.clipboard?.writeText) {
				await navigator.clipboard.writeText(location.href);
			} else if (!legacyCopy(location.href)) {
				throw new Error('copy failed');
			}
			copied = true;
			setTimeout(() => (copied = false), 1500);
		} catch {
			location.hash = '';
			prompt('Copy invite link', location.href);
		}
	}
</script>

<header class="flex items-center justify-between gap-3 border-b border-frost pb-3 dark:border-white/10">
	<div class="flex items-center gap-2">
		<Apple class="size-6 text-apple-500" strokeWidth={1.75} />
		<div>
			<a href="/" class="font-display text-xl font-semibold leading-none">Snow White</a>
			<button
				onclick={copyLink}
				class="mt-0.5 flex items-center gap-1 text-xs text-mist hover:text-apple-500"
				title="Copy invite link"
			>
				{#if copied}<Check class="size-3" /> copied!{:else}<Link class="size-3" /> <span dir="auto">{room}</span>{/if}
			</button>
		</div>
	</div>

	<div class="flex items-center gap-2">
		<span class="flex items-center gap-1 text-sm text-mist">
			<Users class="size-4" />{seatedCount(lobby)}
		</span>
		{@render right?.()}
	</div>
</header>
