<script lang="ts">
	import '../app.css';
	import favicon from '$lib/assets/favicon.svg';
	import { theme } from '$lib/theme.svelte';

	let { children } = $props();

	// Apply the persisted theme once on mount (effects don't run on the server).
	$effect(() => {
		theme.apply();
	});
</script>

<svelte:head>
	<link rel="icon" href={favicon} />
</svelte:head>

<div class="min-h-dvh">
	{@render children()}
</div>
