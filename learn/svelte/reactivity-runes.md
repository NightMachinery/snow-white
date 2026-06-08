# Drill: Svelte 5 runes & reactivity

A standalone deepening of the Svelte reactivity model, beyond how Snow White
happens to use it. Best read with the Svelte playground (svelte.dev/playground)
open to experiment.

## The shift from Svelte 4 → 5

Old Svelte was implicitly reactive: `let count = 0` was reactive *because the
compiler said so*, and `$: doubled = count * 2` was a magic label. Svelte 5 makes
it **explicit** with runes — functions like `$state`, `$derived`, `$effect` that
the compiler recognizes.

| Need | Svelte 4 | Svelte 5 |
| --- | --- | --- |
| reactive var | `let x = 0` | `let x = $state(0)` |
| computed | `$: y = x * 2` | `const y = $derived(x * 2)` |
| side effect | `$: { sideEffect(x) }` | `$effect(() => sideEffect(x))` |
| props | `export let p` | `let { p } = $props()` |
| shared state | a store | a class with `$state` fields |

## `$state`: only what must be reactive

```ts
let count = $state(0);          // reactive
let label = 'static heading';   // NOT reactive — fine, leave it a plain let
```

Objects/arrays passed to `$state` are made **deeply reactive** (mutating a nested
field triggers updates) via a Proxy. That costs a little. If you only ever
*replace* a big object wholesale (like an API response), use `$state.raw`:

```ts
let response = $state.raw(null);  // reassign to update; no deep proxy overhead
response = await fetchBig();
```

> Snow White's `conn.lobby` is plain `$state` (not raw) because components read
> nested fields and we want those reads tracked — but we still *replace* it
> wholesale each server push, so we never rely on deep mutation.

## `$derived`: compute, don't effect

```ts
let n = $state(2);
let square = $derived(n * n);   // lazy + cached; recomputes when n changes
```

`$derived` takes an **expression**. For a block, use `$derived.by`:

```ts
let stats = $derived.by(() => {
  const xs = items;            // reading items registers the dependency
  return { count: xs.length, total: xs.reduce((a, b) => a + b, 0) };
});
```

**Rule of thumb:** if you're tempted to write to a variable inside `$effect`, you
almost always want `$derived` instead.

## `$effect`: the escape hatch

Effects run *after* the DOM updates, and only in the browser. Use them to bridge
to the non-Svelte world: timers, subscriptions, imperative DOM/canvas, logging.
Always return a cleanup function for anything you set up:

```ts
$effect(() => {
  const id = setInterval(tick, 1000);
  return () => clearInterval(id);   // runs on teardown / before re-run
});
```

Pitfalls:
- Don't set `$state` you also read in the same effect → infinite loop.
- Don't wrap effect bodies in `if (browser)` — effects already don't run on the
  server.
- To debug "why did this re-run?", drop `$inspect.trace('label')` as the first
  line of the effect/derived.

## Sharing state without stores

A singleton class with `$state` fields, exported from a `.svelte.ts` module:

```ts
// counter.svelte.ts
class Counter { value = $state(0); inc() { this.value++; } }
export const counter = new Counter();
```

```svelte
<script>import { counter } from './counter.svelte';</script>
<button onclick={() => counter.inc()}>{counter.value}</button>
```

Any component importing `counter` shares the same reactive value. This is how
`conn`, `identity`, and `theme` work in Snow White. (On the server, beware: a
module singleton is shared across requests — which is why the room route is
`ssr = false`.)

## `$props` and snippets

```svelte
<script>
  let { title, children } = $props();
</script>
<h1>{title}</h1>
{@render children?.()}   <!-- render a passed-in snippet -->
```

Snippets (`{#snippet name(args)}…{/snippet}` + `{@render name(args)}`) replace
slots and can be passed as props — see `RoomHeader.svelte`'s `right` snippet.

## Exercise

1. Build a temperature converter: `let c = $state(20)`, derive `f`, and let the
   user edit either. (Hint: deriveds are writable — assigning to them is allowed
   and re-runs the expression on the next read.)
2. Add an `$effect` that logs every change, then add `$inspect(c, f)` and compare
   the developer experience.
3. Convert a small store-based component you find online into a `.svelte.ts`
   class singleton.

## See also

- `[[../clojure/atoms-and-swap]]` — same core idea as the backend: hold a value,
  replace it, let the view derive from it.
