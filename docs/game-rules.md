# Game Rules

Snow White is a blend of **social deduction** (werewolf) and **20 questions**.
4–20 players; best with 5+.

## Roles

Everyone gets a hidden role when the game starts:

- **Villagers** (the majority) — do **not** know the secret word. They ask
  questions to discover it.
- **Seer** (one) — **knows** the word. Plays for the Village, but secretly:
  helps without revealing that they know it.
- **Werewolves** (one, or two when there are more than 6 players) — **know** the
  word **and each other**. They mislead the Village while blending in.

Teams: Villagers + Seer = **Village**. Werewolves = **Wolves**.

One player is also designated the **Mayor** (configurable which roles are
eligible; Villagers and Werewolves are eligible by default, Seers are not). The Mayor answers the questions.

## Flow

1. **Lobby** — players take seats (up to 20 seated participants; others spectate). Mods
   configure the timer, candidate-word count, selected wordpacks, mayor
   eligibility, the token economy (above), and may **bench** a player or
   **seat** a spectator. With *Lock seating* on, only mods move players between
   seat and bench. The same settings panel is available (collapsed) during the
   game, so mods can adjust mid-round. Offline seated players still count for
   starting, roles, and vote quorum; mods explicitly bench anyone who is not
   coming back.
   - The creator/owner never transfers. The owner can promote/demote mods; mods
     can promote others and can demote only people they promoted.
   - If no owner/promoted mod is online for five minutes, the room lazily elects
     a temp mod from online players, preferring previous temp mods. Temp mods have
     full mod powers while no real mod is online. Mods promoted by temp mods are
     temp mods too.
   - Players can copy a migrate-device link for themselves; mods can copy one for
     any player. The link contains a room-scoped token, not the real auth id.
2. **Mayor picks** — the Mayor is shown N random candidate words from the room's
   selected wordpacks and chooses one secret word. Only the Mayor, Seer, and
   Wolves will know it.
3. **Question round** — players ask yes/no questions. The Mayor answers each
   with **Yes / No / Maybe / So close / Way off / Correct**, or discards it.
   - Each player may have **one pending (unanswered) question at a time** and can
     **edit** it until the Mayor answers; they can't queue a second. Everyone sees
     the pending queue and a live roster of who's at the table.
   - **Token economy (configurable by mods).** A shared **answer budget** (default
     **36**) limits how many questions get answered. By default **Yes / No / Maybe /
     So close / Way off** each spend **1** token; **Correct** ends the round. The
     Mayor also has a separate **discard budget** (default **5**) for rejecting
     noisy questions. Mods can change the budget sizes and toggle:
     - *Maybes share the main budget* (default **on**) — when off, Maybe draws from a
       separate maybe pool (default **12**).
     - *“So close / Way off” cost a token* (default **on**) — when off, those are free.
     - *One question at a time* (default **off**) — when on, no new question may be
       queued while one is still unanswered.
   - The pending queue is **FIFO**: first asked, first answered. If a player asks
     the exact target word (case-insensitive, ignoring trailing punctuation), it
     is recorded immediately as **Correct** without spending a token. A player
     may withdraw their own unanswered question for free; both Mayor-discarded
     and self-withdrawn questions remain visible in the question log.
   - Players who join mid-round start as spectators. If a mod seats a no-role
     spectator during the game, they join as a **public Villager** and everyone
     sees that Villager badge.
   - The round ends when the word is guessed (**Correct**), the budget runs out, or
     the timer expires.
4. **Voting** — the target word is revealed to everyone as soon as voting
   begins, but hidden roles stay secret until final resolution.
   - If the word **was guessed**: the **Wolves** secretly vote for who they think
     the **Seer** is.
   - If the word was **not guessed**: **everyone** votes for who they think a
     **Wolf** is.
5. **Resolution** (see win conditions below).

## Win conditions

| Situation | Vote | Wolves win if… | Village wins if… |
| --- | --- | --- | --- |
| Word **guessed** | Wolves → Seer | the resolved vote target is the Seer | the resolved vote target is not the Seer |
| Word **not guessed** | All → a Wolf | the resolved vote target is **not** a wolf | the resolved vote target is a wolf |

If a vote has tied top targets, the server picks uniformly at random among those
leaders and records the selected target so the end screen can show the tiebreak.
In other words: guessing the word is good for the Village *unless* the Wolves
then correctly out the Seer; failing to guess is good for the Wolves *unless* the
Village then outs a Wolf after any tiebreak.

## Strategy notes (from the original)

- Start broad: "Does it live?", "Can you touch it?" — narrow the category.
- Track *who* asks helpful vs. misleading questions; that's your read at the vote.
- As Seer: help, but don't be so obviously useful that the Wolves out you.
- As Wolf: mislead, but stay plausible. Questions whose "helpful" answer is
  technically wrong are gold.

## How this maps to the code

| Rule | Code |
| --- | --- |
| Role deal (+2nd wolf >6) | `roles/deal-roles` |
| Mayor eligibility + pick | `roles/choose-mayor`, preferred Mayor rejection sampling in `game/start-game`, `game/mayor-pick` |
| Token economy | `game/answer-question` |
| End-state transitions | `game/answer-question`, `game/timeout` |
| Win resolution | `roles/resolve-winner` (pure, fully unit-tested) |
