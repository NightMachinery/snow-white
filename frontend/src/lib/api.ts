// Thin HTTP helpers for the non-realtime endpoints (room create / existence).
// These hit the Clojure backend through Vite's /api proxy (see vite.config.ts).

import { decode } from './transit';

const UNEXPECTED_RESPONSE = 'The server returned an unexpected response. Try refreshing.';
const SERVER_UNREACHABLE = 'Could not reach the game server. Check your connection and try again.';

export type ApiPayload = Record<string, unknown> & { error?: string; errorDetail?: string };
export type RoomExistsResult = { exists: boolean } | { error: string; errorDetail?: string };

function isRecord(value: unknown): value is ApiPayload {
	return value !== null && typeof value === 'object' && !Array.isArray(value);
}

function formatValue(value: unknown): string {
	if (value instanceof Error) return `${value.name}: ${value.message}`;
	if (typeof value === 'string') return value;
	try {
		return JSON.stringify(value);
	} catch {
		return String(value);
	}
}

function diagnostic(title: string, fields: Record<string, unknown>): string {
	return [
		title,
		...Object.entries(fields).map(([key, value]) => `${key}: ${formatValue(value)}`)
	].join('\n');
}

async function get(path: string): Promise<ApiPayload> {
	let res: Response;
	try {
		res = await fetch(path);
	} catch (cause) {
		const errorDetail = diagnostic('Snow White API request failed', { path, cause });
		console.error('Snow White API request failed', { path, cause, errorDetail });
		return { error: SERVER_UNREACHABLE, errorDetail };
	}

	const text = await res.text();
	let decoded: unknown;
	try {
		decoded = decode(text);
	} catch (cause) {
		const errorDetail = diagnostic('Snow White API returned an undecodable response', {
			path,
			status: res.status,
			statusText: res.statusText,
			contentType: res.headers.get('content-type'),
			body: text,
			cause
		});
		console.error('Snow White API returned an undecodable response', {
			path,
			status: res.status,
			statusText: res.statusText,
			contentType: res.headers.get('content-type'),
			body: text,
			cause,
			errorDetail
		});
		return { error: UNEXPECTED_RESPONSE, errorDetail };
	}

	if (!isRecord(decoded)) {
		const errorDetail = diagnostic('Snow White API returned a non-object response', {
			path,
			status: res.status,
			statusText: res.statusText,
			value: decoded
		});
		console.error('Snow White API returned a non-object response', {
			path,
			status: res.status,
			statusText: res.statusText,
			value: decoded,
			errorDetail
		});
		return { error: UNEXPECTED_RESPONSE, errorDetail };
	}

	if (!res.ok && typeof decoded.error !== 'string') {
		const errorDetail = diagnostic('Snow White API request failed without a readable error', {
			path,
			status: res.status,
			statusText: res.statusText,
			payload: decoded
		});
		console.error('Snow White API request failed without a readable error', {
			path,
			status: res.status,
			statusText: res.statusText,
			payload: decoded,
			errorDetail
		});
		return { error: res.statusText || `Request failed (${res.status})`, errorDetail };
	}

	if (!res.ok) {
		const errorDetail = diagnostic('Snow White API request failed', {
			path,
			status: res.status,
			statusText: res.statusText,
			payload: decoded
		});
		console.error('Snow White API request failed', {
			path,
			status: res.status,
			statusText: res.statusText,
			payload: decoded,
			errorDetail
		});
		return { ...decoded, errorDetail };
	}

	return decoded;
}

export async function createRoom(authId: string, lobby: string) {
	return get(`/api/create?authId=${encodeURIComponent(authId)}&lobby=${encodeURIComponent(lobby)}`);
}

export async function roomExists(lobby: string): Promise<RoomExistsResult> {
	const r = await get(`/api/exists?lobby=${encodeURIComponent(lobby)}`);
	if (typeof r.error === 'string') return { error: r.error };
	return { exists: Boolean(r.exists) };
}

/** A friendly, readable random room id like "frost-owl-734". */
export function randomRoomName(): string {
	const a = ['frost', 'snow', 'apple', 'amber', 'dusk', 'raven', 'birch', 'ember', 'lark', 'moss'];
	const b = ['owl', 'fox', 'wolf', 'hart', 'crow', 'pine', 'fern', 'wren', 'elm', 'doe'];
	const pick = (xs: string[]) => xs[Math.floor(Math.random() * xs.length)];
	return `${pick(a)}-${pick(b)}-${100 + Math.floor(Math.random() * 900)}`;
}
