// The WebSocket client: the single source of live game state.
//
// Design: the server broadcasts a full (redacted-for-you) lobby snapshot after
// every command. So the client doesn't track deltas — it just *replaces* its
// `lobby` snapshot wholesale on each push, and the whole UI is `$derived` from
// it. This mirrors the backend's "broadcast snapshot" model and keeps the client
// a pure function of server state. See docs/frontend-svelte.md.
//
// We use `$state` for the snapshot. Because we always reassign it (never mutate
// in place), the deep-proxy cost of `$state` is irrelevant — but we keep it as
// `$state` (not `$state.raw`) so nested reads stay reactive across components.

import { browser } from '$app/environment';
import { decode, encode } from './transit';
import { identity } from './identity.svelte';
import type { Lobby, ServerMessage } from './types';

export type Status = 'idle' | 'connecting' | 'open' | 'closed' | 'error';

class Connection {
	lobby = $state<Lobby | null>(null);
	status = $state<Status>('idle');
	error = $state<string | null>(null);

	#ws: WebSocket | null = null;
	#room = '';
	#name = '';
	#retries = 0;
	#closedByUs = false;
	#reconnectTimer: ReturnType<typeof setTimeout> | null = null;

	/** Open (or re-open) a connection and join `room` as `name`. */
	connect(room: string, name: string) {
		if (!browser) return;
		this.#room = room;
		this.#name = name;
		this.#closedByUs = false;
		this.#open();
	}

	#open() {
		this.status = 'connecting';
		const proto = location.protocol === 'https:' ? 'wss' : 'ws';
		const ws = new WebSocket(`${proto}://${location.host}/ws`);
		this.#ws = ws;

		ws.onopen = () => {
			this.status = 'open';
			this.#retries = 0;
			// Handshake: identify ourselves and join the room.
			ws.send(
				encode({
					type: 'hello',
					'auth-id': identity.authId,
					lobby: this.#room,
					name: this.#name
				})
			);
		};

		ws.onmessage = (ev) => {
			const msg = decode(ev.data as string) as ServerMessage;
			if (msg.type === 'lobby/state') {
				this.lobby = msg.lobby;
				this.error = null;
			} else if (msg.type === 'hello/ok') {
				identity.setAuthId(msg['auth-id']);
			} else if (msg.type === 'error') {
				this.error = msg.msg;
			}
		};

		ws.onerror = () => {
			this.status = 'error';
		};

		ws.onclose = () => {
			this.status = 'closed';
			if (!this.#closedByUs) this.#scheduleReconnect();
		};
	}

	#scheduleReconnect() {
		// Exponential backoff with jitter, capped at 10s.
		const delay = Math.min(10_000, 500 * 2 ** this.#retries) * (0.5 + Math.random());
		this.#retries += 1;
		this.#reconnectTimer = setTimeout(() => this.#open(), delay);
	}

	/** Send a command map to the server (no-op if not open). */
	send(cmd: Record<string, unknown>) {
		if (this.#ws && this.#ws.readyState === WebSocket.OPEN) {
			this.#ws.send(encode(cmd));
		}
	}

	disconnect() {
		this.#closedByUs = true;
		if (this.#reconnectTimer) clearTimeout(this.#reconnectTimer);
		this.#ws?.close();
		this.#ws = null;
		this.lobby = null;
		this.status = 'idle';
	}
}

export const conn = new Connection();
