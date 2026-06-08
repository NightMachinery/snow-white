// Per-browser identity, stored in localStorage.
//
// Mirrors the original game: a hidden random `authId` plus one display name, so
// returning players are not re-prompted. The server may also mint an authId on
// first :hello — we adopt whatever it confirms via :hello/ok.
//
// `.svelte.ts` lets us use runes ($state) in a plain module. See
// docs/frontend-svelte.md for why this is preferred over a writable store.

const AUTH_KEY = 'snow:auth-id';
const NAME_KEY = 'snow:name';

function readLS(key: string): string | null {
	if (typeof localStorage === 'undefined') return null;
	return localStorage.getItem(key);
}

function randomId(): string {
	// 18 bytes of entropy, URL-safe — matches the server's token shape closely enough.
	const bytes = new Uint8Array(18);
	crypto.getRandomValues(bytes);
	return btoa(String.fromCharCode(...bytes))
		.replace(/\+/g, '-')
		.replace(/\//g, '_')
		.replace(/=+$/, '');
}

class Identity {
	authId = $state<string>('');
	name = $state<string>('');

	constructor() {
		this.authId = readLS(AUTH_KEY) ?? randomId();
		this.name = readLS(NAME_KEY) ?? '';
	}

	persist() {
		if (typeof localStorage === 'undefined') return;
		localStorage.setItem(AUTH_KEY, this.authId);
		localStorage.setItem(NAME_KEY, this.name);
	}

	setName(n: string) {
		this.name = n;
		this.persist();
	}

	/** Adopt the server-confirmed auth id (from :hello/ok). */
	setAuthId(id: string) {
		this.authId = id;
		this.persist();
	}
}

export const identity = new Identity();
