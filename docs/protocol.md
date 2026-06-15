# WebSocket Protocol

The contract between the SvelteKit client and the Clojure server. The wire format
is **transit-json**. On the client, transit keywords become strings (so
`:lobby/state` reads as `"lobby/state"`, `:yes` as `"yes"`) — see `transit.ts`.

The server-side source of truth for redaction is `views.clj`; the client-side
type mirror is `frontend/src/lib/types.ts`.

## Transport

- Dev: the browser connects to `ws(s)://<vite-host>/ws`; Vite proxies it to the
  backend (`vite.config.ts`). Prod: connect to the backend's `/ws` directly.
- HTTP side endpoints (through the same proxy):
  - `GET /api/create?authId=<id>&lobby=<name>` → `{:ok true}` | `{:error "..."}`
  - `GET /api/exists?lobby=<name>` → `{:exists true|false}`
  - `GET /health` → `"ok"`

## Handshake

First frame from the client **must** be `:hello`:

```clojure
{:type :hello, :auth-id "abc…", :lobby "frost-owl-734", :name "Briar"}
```

The server replies:

```clojure
{:type :hello/ok, :auth-id "abc…"}        ; echoes/assigns the identity
```

then immediately broadcasts a `:lobby/state` to everyone in the room.

## Server → client messages

| `:type` | Payload | Meaning |
| --- | --- | --- |
| `:lobby/state` | `:lobby <redacted lobby map>` | The full current state, redacted for *you*. Replace your snapshot wholesale. |
| `:hello/ok` | `:auth-id <string>` | Your confirmed identity. |
| `:error` | `:msg <string>` | Something was rejected (e.g. "lobby not found"). |

### What gets redacted (per recipient)

- `:chosen-word` — `nil` unless you are the Mayor / Seer / a Wolf, or the game
  has ended.
- A player's `:role` — `nil` for others until end-game (Wolves see each other).
- `:seer`, `:werewolves`, `:wolf-votes` — empty/`nil` until end-game.
- `:you` — a convenience block of your own private facts:
  `{:auth-id :role :is-mayor :can-moderate :knows-word}`.
- `:available-wordpacks` and `:selected-wordpacks` are public room settings.
  Wordpack metadata contains `:id`, `:name`, and `:word-count`, never the hidden
  word list itself.

## Client → server commands

All include just the fields shown; the server already knows *who* you are from
your socket.

Command map keys are Clojure keywords on the wire, including nested payload maps
such as `:rules`, `:budget`, and `:roles`. Plain string values like question text
remain strings; enum-like values such as `:type` and `:answer` are keywords.

| `:type` | Fields | Effect |
| --- | --- | --- |
| `:seat/take` | `:seat?` `:color?` | Sit down (first free seat if omitted). Blocked for non-mods when `:lock-seating`. |
| `:seat/spectate` | — | Leave your seat to watch. Blocked for non-mods when `:lock-seating`. |
| `:settings/timer` | `:minutes` | (mod) Set round length. |
| `:settings/pick-count` | `:pick-count` | (mod) How many candidate words. |
| `:settings/eligibility` | `:roles {…}` | (mod) Which roles can be Mayor. |
| `:settings/budget` | `:budget {:tokens? :maybe-tokens?}` | (mod) Set the token-budget sizes. |
| `:settings/rules` | `:rules {…}` | (mod) Toggle `:shared-maybe-pool` `:soft-costs` `:one-at-a-time` `:lock-seating`. |
| `:settings/wordpacks` | `:wordpacks [<id> …]` | (mod, lobby only) Select one or more wordpacks; the next game draws from their union. |
| `:mod/unseat` | `:target` | (mod) Bench a player, freeing their seat and removing them from start/vote participation. |
| `:mod/seat` | `:target` | (mod) Seat a benched player/spectator. |
| `:game/start` | — | (mod) Deal roles, pick Mayor, draw words. |
| `:game/pick` | `:word` | (Mayor) Commit the secret word. |
| `:game/ask` | `:text` | Ask a yes/no question (one pending per player; blocked if `:one-at-a-time` and a question is queued). |
| `:game/edit` | `:text` | Revise the text of *your own* pending question. |
| `:game/answer` | `:answer` | (Mayor) `:yes :no :maybe :so-close :way-off :correct :discard`. |
| `:game/vote-village` | `:target` | Vote for a suspected Wolf (word not guessed). |
| `:game/vote-wolf` | `:target` | (Wolf) Vote for the suspected Seer (word guessed). |
| `:game/timeout` | — | (mod) Timer expired. |
| `:game/reset` | — | (mod or Mayor) Back to the lobby. |

`(mod)` = requires moderator rights; the server enforces this via `mod-gate` and
`can-moderate?`, so a forged command from a non-mod is simply ignored.

## Invariants the client can rely on

- It never needs to compute game logic; `:game-state` and the redacted fields are
  authoritative.
- Every accepted command results in a fresh `:lobby/state` to all room members,
  so all clients converge on the same view after each action.
