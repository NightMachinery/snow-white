// Transit-JSON bridge between the Clojure backend and the JS client.
//
// The backend speaks transit (keywords, sets, vectors). We want plain JS on this
// side, so:
//   - decode: keywords -> strings (":yes" -> "yes"), sets -> arrays, maps -> objects
//   - encode: outgoing {type, ...} maps get their `type` and known enum-ish string
//     fields turned back into Clojure keywords so dispatch (`case`) matches.
//
// Keeping keywords-as-strings means components write `player.role === 'werewolf'`
// rather than juggling keyword objects — see docs/frontend-svelte.md.

import transit from 'transit-js';

const reader = transit.reader('json');
const writer = transit.writer('json');

/** Recursively convert a decoded transit value into plain JS. */
function toPlain(v: unknown): unknown {
	if (v == null) return v;
	// transit keyword / symbol -> string name
	if (transit.isKeyword(v) || transit.isSymbol(v)) {
		return (v as { name(): string }).name();
	}
	// transit map -> plain object (keys may themselves be keywords)
	if (transit.map && v instanceof transit.map().constructor) {
		const out: Record<string, unknown> = {};
		(v as { forEach(f: (val: unknown, key: unknown) => void): void }).forEach(
			(val, key) => {
				out[String(toPlain(key))] = toPlain(val);
			}
		);
		return out;
	}
	// transit set -> array
	if (transit.isSet?.(v)) {
		const arr: unknown[] = [];
		(v as { forEach(f: (x: unknown) => void): void }).forEach((x) => arr.push(toPlain(x)));
		return arr;
	}
	if (Array.isArray(v)) return v.map(toPlain);
	return v;
}

/** Decode a transit-json string from the server into plain JS. */
export function decode(s: string): unknown {
	return toPlain(reader.read(s));
}

// Fields whose string values should be sent as Clojure keywords so the server's
// `case` dispatch and game logic recognize them.
const KEYWORD_FIELDS = new Set(['type', 'answer']);

/** Encode an outgoing command object to a transit-json string. */
export function encode(cmd: Record<string, unknown>): string {
	const out = transit.map();
	for (const [k, val] of Object.entries(cmd)) {
		const key = transit.keyword(k);
		const v = KEYWORD_FIELDS.has(k) && typeof val === 'string' ? transit.keyword(val) : val;
		out.set(key, v);
	}
	return writer.write(out);
}
