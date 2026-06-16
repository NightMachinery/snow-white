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

A device-migration link may send `:migration-token` too:

```clojure
{:type :hello, :auth-id "local-browser-id", :migration-token "opaque-room-token", :lobby "frost-owl-734", :name "Briar"}
```

If the migration token is valid for that room, the server uses the migrated
identity and returns its real auth id in `:hello/ok`. The token is room-scoped and
never exposes the real auth id in the URL.

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
  has reached a reveal state (`:word-guessed`, `:out-of-time`, `:out-of-tokens`) or `:end-game`.
  In Classic mode there are no Seers/Wolves, so only the Mayor sees the word during play.
- A player's `:role` — `nil` for others until end-game, except Wolves see each
  other and mod-seated late Villagers have a public `:villager` role.
- `:seer`, `:wolf-votes` — empty/`nil` until end-game. `:werewolves` is shown to Wolves during play and to everyone at end-game.
- `:you` — a convenience block of your own private facts:
  `{:auth-id :migration-token :role :is-mayor :can-moderate :knows-word}`.
- `:available-wordpacks`, `:selected-wordpacks`, `:game-mode`, and `:custom-word-mode` are public room settings.
  Wordpack metadata contains `:id`, `:name`, and `:word-count`, never the hidden
  word list itself.

### New gameplay fields

The lobby snapshot also includes:

- `:game-mode` — `:werewords` (default hidden-role mode) or `:classic` (co-op mode with no Seer/Wolves, 2-player start, and no voting stage).
- `:preferred-mayor` — auth-id a mod picked as preferred next Mayor, or `nil`.
- `:custom-word-mode` — when true, Mayor types the word and `:words` is empty for the round.
- `:max-discard-tokens` / `:discard-tokens` — configured and live Mayor discard budget.
- `:round-started-at-ms` / `:round-deadline-ms` — server-anchored timer data; clients count down from the deadline instead of resetting on every snapshot.
- `:question-log` — answered and discarded questions in chronological order. Discard entries use `:answer :discard` and `:discarded-by :mayor|:self`.
- `:vote-result` — end-game vote summary `{:mode :counts :leaders :selected :randomized?}`. Ties are resolved server-side by sampling uniformly among tied leaders.
- Player `:public-role true` marks a role that is intentionally visible before final reveal, currently used for no-role spectators whom mods seat mid-game as public Villagers.
- Player `:migration-token` is included only in moderator views; non-mods see only their own token under `:you`.

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
| `:settings/game-mode` | `:mode` | (mod, lobby only) Set `:werewords` or `:classic`. |
| `:settings/pick-count` | `:pick-count` | (mod) How many candidate words when wordpacks are enabled. |
| `:settings/custom-word-mode` | `:enabled` | (mod, lobby only) Let the Mayor type any non-blank word instead of choosing sampled wordpack candidates. |
| `:settings/eligibility` | `:roles {…}` | (mod) Which roles can be Mayor. |
| `:settings/budget` | `:budget {:tokens? :maybe-tokens? :discard-tokens?}` | (mod) Set answer, maybe, and discard budget sizes. |
| `:settings/rules` | `:rules {…}` | (mod) Toggle `:shared-maybe-pool` `:soft-costs` `:one-at-a-time` `:lock-seating`. |
| `:settings/wordpacks` | `:wordpacks [<id> …]` | (mod, lobby only) Select one or more wordpacks; the next game draws from their union. |
| `:mod/unseat` | `:target` | (mod) Bench a player, freeing their seat and removing them from start/vote participation. |
| `:mod/seat` | `:target` | (mod) Seat a benched player/spectator. |
| `:mod/mayor` | `:target` | (mod) Prefer this active player as next Mayor; roles are dealt so the Mayor receives an eligible role when possible. |
| `:mod/promote` | `:target` | (mod) Promote a player. Real mods create real mods; active temp mods create temp mods. |
| `:mod/demote` | `:target` | (mod) Demote a mod you are allowed to demote. Owner can demote any promoted/temp mod; other mods can demote only people they promoted. |
| `:player/rename` | `:name` | Rename yourself in the room, preserving identity and seat. |
| `:game/start` | — | (mod) Deal the selected mode, pick Mayor, and either draw wordpack candidates or enter custom-word mode. Werewords requires 4 seated players; Classic requires 2. |
| `:game/pick` | `:word` | (Mayor) Commit the secret word; must be sampled in normal mode, any non-blank trimmed word in custom-word mode. |
| `:game/ask` | `:text` | Ask a yes/no question (one pending per player; blocked if `:one-at-a-time` and a question is queued). |
| `:game/edit` | `:text` | Revise the text of *your own* pending question. |
| `:game/discard-own` | — | Withdraw your own unanswered pending question for free; it remains in the question log. |
| `:game/answer` | `:answer` | (Mayor) `:yes :no :maybe :so-close :way-off :correct :discard`. |
| `:game/vote-village` | `:target` | Vote for a suspected Wolf (word not guessed in Werewords). No-op in Classic. |
| `:game/vote-wolf` | `:target` | (Wolf) Vote for the suspected Seer (word guessed in Werewords). No-op in Classic. |
| `:game/finish-vote` | — | (mod) End the current vote stage with the votes already cast. |
| `:game/timeout` | — | (mod) Timer expired. |
| `:game/reset` | — | (mod or Mayor) Back to the lobby. |

`(mod)` = requires moderator rights; the server enforces this via `mod-gate` and
`can-moderate?`, so a forged command from a non-mod is simply ignored. Owner is
immutable. If no owner/promoted real mod is online for five minutes, an online
player becomes a temp mod with full mod powers; temp-mod promotions create temp
mods, and all temp mods lose powers when a real mod returns.

## Invariants the client can rely on

- It never needs to compute game logic; `:game-state` and the redacted fields are
  authoritative.
- Every accepted command results in a fresh `:lobby/state` to all room members,
  so all clients converge on the same view after each action.
