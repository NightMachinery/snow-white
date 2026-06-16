// TypeScript mirror of the lobby snapshot the Clojure backend sends.
//
// This file is the *client-side half of the protocol contract* (the server half
// lives in docs/protocol.md and snow-white.views). Transit decodes Clojure
// keywords to JS keyword objects; our transit helper converts them to plain
// strings, so e.g. the role `:werewolf` arrives here as the string "werewolf".

export type GameState =
	| 'lobby'
	| 'mayor-pick'
	| 'question-round'
	| 'word-guessed'
	| 'out-of-tokens'
	| 'out-of-time'
	| 'end-game';

export type Role = 'villager' | 'seer' | 'werewolf' | null;

export type Answer =
	| 'yes'
	| 'no'
	| 'maybe'
	| 'so-close'
	| 'way-off'
	| 'correct'
	| 'discard';

export interface Question {
	'auth-id': string;
	name: string;
	text: string;
	answer?: Answer;
	'discarded-by'?: 'mayor' | 'self';
	auto?: boolean;
}

export interface Wordpack {
	id: string;
	name: string;
	'word-count': number;
}

export interface Player {
	'auth-id': string;
	'base-name': string;
	'display-name': string;
	'display-number': number;
	online: boolean;
	spectator: boolean;
	seat: string | null; // e.g. "seat-3"
	color: string | null;
	role: Role; // redacted to null unless you may see it
	mayor: boolean;
	'is-owner': boolean;
	'is-mod': boolean;
	'is-temp-mod': boolean;
	'can-moderate': boolean;
	tokens: Record<string, Question[]>;
}

/** The private facts the server tells *you* about yourself. */
export interface You {
	'auth-id': string;
	role: Role;
	'is-mayor': boolean;
	'can-moderate': boolean;
	'knows-word': boolean;
}

/** A full (already redacted-for-you) lobby snapshot. */
export interface VoteResult {
	mode: 'village' | 'wolf';
	counts: Record<string, number>;
	leaders: string[];
	selected: string | null;
	'randomized?': boolean;
}

export interface Lobby {
	name: string;
	'owner-id': string;
	players: Record<string, Player>;
	seats: Record<string, string | null>;
	settings: { minutes: number; seconds: number };
	'available-wordpacks': Wordpack[];
	'selected-wordpacks': string[];
	'mayor-eligibility': { villager: boolean; seer: boolean; werewolf: boolean };
	'timer-minutes': number;
	'pick-count': number;
	// configurable economy / flow rules (mod-settable)
	'max-tokens': number;
	'max-maybe-tokens': number;
	'max-discard-tokens': number;
	'shared-maybe-pool': boolean;
	'soft-costs': boolean;
	'one-at-a-time': boolean;
	'lock-seating': boolean;
	'game-state': GameState;
	mayor: string | null;
	'preferred-mayor': string | null;
	seer: string | null; // only revealed at end-game
	werewolves: string[]; // revealed to Wolves during play and to everyone at end-game
	words: string[]; // candidate words (mayor only sees during pick)
	'chosen-word': string | null; // masked unless you know the word
	questions: Question[];
	answered: Question[];
	'question-log': Question[];
	'so-close': Question | null;
	'way-off': Question | null;
	correct: Question | null;
	tokens: number;
	'maybe-tokens': number;
	'discard-tokens': number;
	'round-started-at-ms': number | null;
	'round-deadline-ms': number | null;
	'village-votes': string[];
	'wolf-votes': string[];
	'vote-result': VoteResult | null;
	winner: 'village' | 'wolves' | null;
	you: You;
}

/** Messages the server pushes to us. */
export type ServerMessage =
	| { type: 'lobby/state'; lobby: Lobby }
	| { type: 'hello/ok'; 'auth-id': string }
	| { type: 'error'; msg: string };
