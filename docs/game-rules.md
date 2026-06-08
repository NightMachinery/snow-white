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
eligible; villager-only by default). The Mayor answers the questions.

## Flow

1. **Lobby** — players take seats (up to 20 active; others spectate). The host
   configures the timer, how many candidate words the Mayor sees, and which
   roles can be Mayor, then starts.
2. **Mayor picks** — the Mayor is shown N random candidate words and chooses one
   secret word. Only the Mayor, Seer, and Wolves will know it.
3. **Question round** — players ask yes/no questions. The Mayor answers each
   with **Yes / No / Maybe / So close / Way off / Correct**, or discards it.
   - Each Yes/No spends one of the shared **36 tokens**; each Maybe spends one of
     **12 maybe-tokens**. The round ends when the word is guessed (**Correct**),
     the tokens run out, or the timer expires.
4. **Voting**
   - If the word **was guessed**: the **Wolves** secretly vote for who they think
     the **Seer** is.
   - If the word was **not guessed**: **everyone** votes for who they think a
     **Wolf** is.
5. **Resolution** (see win conditions below).

## Win conditions

| Situation | Vote | Wolves win if… | Village wins if… |
| --- | --- | --- | --- |
| Word **guessed** | Wolves → Seer | a wolf vote hits the Seer | no wolf vote hits the Seer |
| Word **not guessed** | All → a Wolf | the unique top-voted player is **not** a wolf, or there's a tie | the unique top-voted player **is** a wolf |

In other words: guessing the word is good for the Village *unless* the Wolves
then correctly out the Seer; failing to guess is good for the Wolves *unless* the
Village then correctly outs a Wolf with a clear plurality.

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
| Mayor eligibility + pick | `roles/choose-mayor`, `game/mayor-pick` |
| Token economy | `game/answer-question` |
| End-state transitions | `game/answer-question`, `game/timeout` |
| Win resolution | `roles/resolve-winner` (pure, fully unit-tested) |
