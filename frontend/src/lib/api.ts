// Thin HTTP helpers for the non-realtime endpoints (room create / existence).
// These hit the Clojure backend through Vite's /api proxy (see vite.config.ts).

import { decode } from './transit';

async function get(path: string): Promise<Record<string, unknown>> {
	const res = await fetch(path);
	const text = await res.text();
	try {
		return decode(text) as Record<string, unknown>;
	} catch {
		return { error: text || res.statusText };
	}
}

export async function createRoom(authId: string, lobby: string) {
	return get(`/api/create?authId=${encodeURIComponent(authId)}&lobby=${encodeURIComponent(lobby)}`);
}

export async function roomExists(lobby: string): Promise<boolean> {
	const r = await get(`/api/exists?lobby=${encodeURIComponent(lobby)}`);
	return Boolean(r.exists);
}

/** A friendly, readable random room id like "frost-owl-734". */
export function randomRoomName(): string {
	const a = ['frost', 'snow', 'apple', 'amber', 'dusk', 'raven', 'birch', 'ember', 'lark', 'moss'];
	const b = ['owl', 'fox', 'wolf', 'hart', 'crow', 'pine', 'fern', 'wren', 'elm', 'doe'];
	const pick = (xs: string[]) => xs[Math.floor(Math.random() * xs.length)];
	return `${pick(a)}-${pick(b)}-${100 + Math.floor(Math.random() * 900)}`;
}
