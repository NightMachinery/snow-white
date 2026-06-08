# learn/ — standalone skill drills

Focused, self-contained notes and exercises to deepen Svelte & Clojure skills,
beyond the project-specific docs in [`../docs/`](../docs). Each file is a drill
you can work through at the REPL or the Svelte playground.

## Clojure
- [`clojure/atoms-and-swap.md`](clojure/atoms-and-swap.md) — atoms, `swap!`,
  `defonce`, and why pure-function state is the backbone of the server.

## Svelte
- [`svelte/reactivity-runes.md`](svelte/reactivity-runes.md) — `$state`,
  `$derived`, `$effect`, `$props`, snippets, and the store→class shift.

## How docs/ and learn/ differ
- **docs/** explains *this codebase* — read it to understand Snow White.
- **learn/** explains *the concepts* — read it to get better at the tools, with
  exercises that stand on their own.

More drills get added here as the project surfaces new concepts (transducers,
`core.async`, SvelteKit load functions, transitions/animations, …).
