<script lang="ts">
	import HelpCircle from '@lucide/svelte/icons/circle-help';
	import X from '@lucide/svelte/icons/x';

	let open = $state(false);
</script>

<button
	onclick={() => (open = true)}
	aria-label="How to play"
	class="grid size-9 place-items-center rounded-full text-mist transition hover:bg-frost dark:hover:bg-white/10"
>
	<HelpCircle class="size-5" />
</button>

{#if open}
	<!-- svelte-ignore a11y_click_events_have_key_events, a11y_no_static_element_interactions -->
	<div
		class="fixed inset-0 z-50 flex items-end justify-center bg-ink/40 p-0 backdrop-blur-sm sm:items-center sm:p-6"
		onclick={() => (open = false)}
	>
		<div
			class="max-h-[85dvh] w-full max-w-lg overflow-y-auto rounded-t-card bg-snow p-6 text-sm shadow-2xl dark:bg-ink sm:rounded-card"
			onclick={(e) => e.stopPropagation()}
		>
			<div class="mb-3 flex items-center justify-between">
				<h2 class="font-display text-2xl">How to play</h2>
				<button onclick={() => (open = false)} class="text-mist hover:text-apple-500">
					<X class="size-5" />
				</button>
			</div>

			<div class="flex flex-col gap-3 text-mist">
				<p>
					Players race to guess a secret <strong class="text-ink dark:text-snow">magic word</strong>
					by asking the Mayor yes/no questions — before the timer or tokens run out.
				</p>
				<div>
					<p class="font-medium text-ink dark:text-snow">Roles</p>
					<ul class="ml-4 list-disc space-y-1">
						<li><strong>Villagers</strong> don't know the word; they ask to discover it.</li>
						<li><strong>Seer</strong> knows the word and helps the village — secretly.</li>
						<li><strong>Wolves</strong> know the word and each other; they mislead.</li>
					</ul>
				</div>
				<div>
					<p class="font-medium text-ink dark:text-snow">Winning</p>
					<ul class="ml-4 list-disc space-y-1">
						<li>
							If the word <strong>is guessed</strong>, the Wolves secretly vote for the Seer. Right →
							Wolves win; wrong → Village wins.
						</li>
						<li>
							If the word <strong>isn't guessed</strong>, everyone votes for a suspected Wolf. Right →
							Village wins; wrong → Wolves win.
						</li>
					</ul>
				</div>
				<p class="text-xs">
					Tip: start broad (“Is it alive?”), track who helps and who muddies the water, and don't
					reveal that you know the word.
				</p>
			</div>
		</div>
	</div>
{/if}
