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
	<link rel="preconnect" href="https://fonts.googleapis.com" />
	<link
		href="https://fonts.googleapis.com/css2?family=Fraunces:opsz,wght@9..144,400..700&family=Inter:wght@400..700&display=swap"
		rel="stylesheet"
	/>
</svelte:head>

<div class="min-h-dvh">
	{@render children()}
</div>
