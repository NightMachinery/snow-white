<script lang="ts">
	import { theme } from '$lib/theme.svelte';
	import Sun from '@lucide/svelte/icons/sun';
	import Moon from '@lucide/svelte/icons/moon';
</script>

<button
	onclick={() => theme.toggle()}
	aria-label="Toggle dark mode"
	class="grid size-9 place-items-center rounded-full text-mist transition hover:bg-frost dark:hover:bg-white/10"
>
	{#if theme.dark}
		<Sun class="size-5" />
	{:else}
		<Moon class="size-5" />
	{/if}
</button>
