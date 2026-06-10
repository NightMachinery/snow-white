<script lang="ts">
	import Copy from '@lucide/svelte/icons/copy';
	import Check from '@lucide/svelte/icons/check';

	let {
		message,
		detail = '',
		centered = false
	}: {
		message: string;
		detail?: string | null;
		centered?: boolean;
	} = $props();

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

	async function copyError() {
		const text = detail || message;
		try {
			if (navigator.clipboard?.writeText) {
				await navigator.clipboard.writeText(text);
			} else if (!legacyCopy(text)) {
				throw new Error('copy failed');
			}
			copied = true;
			setTimeout(() => (copied = false), 1500);
		} catch {
			prompt('Copy error', text);
		}
	}
</script>

<div class={`flex flex-col gap-2 ${centered ? 'items-center text-center' : ''}`}>
	<p class="text-sm text-apple-500">{message}</p>
	{#if detail}
		<button
			type="button"
			onclick={copyError}
			class="inline-flex w-fit items-center gap-1 text-xs font-medium text-mist hover:text-apple-500"
			title="Copy error"
		>
			{#if copied}
				<Check class="size-3" /> copied
			{:else}
				<Copy class="size-3" /> Copy error
			{/if}
		</button>
	{/if}
</div>
