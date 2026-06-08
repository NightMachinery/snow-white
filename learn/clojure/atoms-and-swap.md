# Drill: Atoms, `swap!`, and pure-function state

A standalone deepening of the single most important pattern in the backend. Open
a REPL (`cd backend && clj`) and type along.

## The mental model

Clojure separates **identity** from **value**. A *value* is immutable
(`{:n 0}` never changes). An **atom** is an *identity*: a mutable reference that,
over time, points at a succession of immutable values.

```clojure
(def a (atom {:n 0}))   ; identity `a` currently points at value {:n 0}
@a                      ; => {:n 0}   (@ is deref)
```

## `swap!` applies a pure function

```clojure
(swap! a update :n inc)   ; a now points at (update {:n 0} :n inc) = {:n 1}
@a                        ; => {:n 1}

(swap! a assoc :name "x") ; => {:n 1 :name "x"}
```

`swap!` is `(swap! the-atom f & args)` → sets the atom to `(apply f @the-atom args)`,
**atomically**. If two threads `swap!` at once, one retries — so `f` may run more
than once and **must be pure** (no side effects). This is exactly why Snow White's
game functions are pure: they're safe to use as the `f` in `swap!`.

Try the retry-safety yourself:

```clojure
(def counter (atom 0))
(def threads (doall (for [_ (range 100)]
                      (future (dotimes [_ 1000] (swap! counter inc))))))
(run! deref threads)
@counter   ; => 100000, exactly. No lost updates, no locks.
```

## `reset!` vs `swap!`

```clojure
(reset! a {:fresh true})  ; set the value directly, ignoring the old one
```

Use `reset!` when the new value doesn't depend on the old; `swap!` when it does.

## `defonce`: survive REPL reloads

```clojure
(defonce registry (atom {}))
```

Re-evaluating this form does **nothing** if `registry` already exists — so your
live state isn't wiped when you reload the namespace during development. Compare
with `def`, which would reset it. This is the backbone of REPL-driven dev.

## The pattern in Snow White

`registry.clj` keeps `(atom {lobby-name -> (atom lobby-map)})`. Gameplay is:

```clojure
(swap! lobby-atom game/answer-question auth-id :yes)
```

`game/answer-question` is pure (`lobby -> lobby`), so the swap is correct and
retry-safe. The registry atom changes only on create/destroy, so per-lobby atoms
never contend with each other — **independent identities for independent state.**

## Exercise

1. Model a tiny bank as `(atom {:alice 100 :bob 0})`. Write a pure
   `transfer` `(fn [accts from to amt] ...)` and move money with
   `(swap! bank transfer :alice :bob 30)`. Confirm it's safe under 100 concurrent
   transfers.
2. Why must `transfer` be pure? What breaks if it prints or writes a file inside?

## See also

- `[[reactivity-runes]]` — the Svelte client uses the same "state is a value you
  replace, the UI derives from it" idea, just with runes instead of atoms.
