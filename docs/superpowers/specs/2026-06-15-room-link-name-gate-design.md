# Room Link Name Gate Design

## Context

Today, the home page requires a player name before creating or joining a room, then stores it in `localStorage` as `snow:name`. Direct room links skip that step: `frontend/src/routes/room/[lobby]/+page.svelte` connects immediately and sends `identity.name || 'Player'` in the WebSocket `:hello` message. That means a first-time player opening an invite link can enter as the generic name `Player`.

## Goal

When a player opens `/room/<lobby>` and has no saved name, Snow White should ask for their name before joining the live room. Once submitted, the name is saved for future visits and the existing WebSocket join flow continues.

## Chosen Approach

Use an inline gate on the room page.

- If `identity.name.trim()` is empty, render a compact name form in the room page instead of connecting.
- Keep the player on the invite URL so the link still feels direct.
- On submit, trim and save the name through `identity.setName(...)`.
- Let the room page's connection effect open the WebSocket only after a saved non-empty name exists.

This is preferred over redirecting to the home page because it avoids route churn and keeps invite links focused. It is preferred over joining as `Player` and renaming later because the server assigns room-scoped display names at join time, so the cleanest behavior is to collect the intended base name before `:hello`.

## UI Behavior

The pre-join state should show:

- The Snow White title or room context.
- The target room name, so players know the link worked.
- A single text input labelled for the player name.
- A primary action such as `Join room`.
- Existing theme toggle access.

The form should be accessible and mobile-first:

- Use a real `<form>` with `onsubmit`.
- Require a trimmed, non-empty name.
- Preserve `maxlength="24"` to match the home page.
- Use `dir="auto"` for names.
- Avoid opening a WebSocket until validation passes.

## Data Flow

1. Room route loads and derives `room` from `page.params.lobby`.
2. The page derives whether a saved name exists from `identity.name.trim()`.
3. If no saved name exists, render the name gate and do not connect.
4. The user submits a name.
5. `identity.setName(trimmedName)` writes `snow:name` to localStorage.
6. The connection effect sees a valid saved name and calls `conn.connect(room, trimmedName)`.
7. The existing WebSocket client sends `{:type :hello, :auth-id ..., :lobby ..., :name ...}`.

## Error Handling

- Empty or whitespace-only input keeps the player on the name gate.
- Existing WebSocket errors remain handled by the room page after connection starts.
- If `localStorage` is unavailable, `identity.setName` still updates reactive state, so the current page session can connect.

## Tests

Use TDD for the implementation. Add the narrowest automated coverage available in the frontend test setup, likely by extracting a small helper or predicate from the room page if component tests are not already configured. The key behavior to prove is that a room link without a saved name does not attempt to connect until a valid name has been saved.

## Documentation Updates

Update `docs/frontend-svelte.md` to teach the direct room-link flow:

- Home page joins require a name before navigation.
- Direct `/room/<lobby>` visits pause on the room page if `snow:name` is missing.
- The WebSocket is intentionally opened only after identity has a real display name, keeping server-side player records clean.
