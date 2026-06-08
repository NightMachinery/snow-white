// Small pure helpers for reading a lobby snapshot in components.

import type { Lobby, Player } from './types';

/** Players in stable seat order (seat-1 .. seat-20), seated only. */
export function seatedPlayers(lobby: Lobby): Player[] {
	if (!lobby?.seats) return [];
	return Object.entries(lobby.seats)
		.filter(([, auth]) => auth)
		.sort(([a], [b]) => seatNum(a) - seatNum(b))
		.map(([, auth]) => lobby.players[auth as string])
		.filter(Boolean);
}

export function seatNum(seat: string): number {
	return Number(seat.replace('seat-', ''));
}

/** Spectators / observers (no active seat). */
export function bystanders(lobby: Lobby): Player[] {
	if (!lobby?.players) return [];
	return Object.values(lobby.players).filter((p) => !p.seat || p.spectator || p.observer);
}

export function activeCount(lobby: Lobby): number {
	if (!lobby?.players) return 0;
	return Object.values(lobby.players).filter(
		(p) => p.online && p.seat && !p.spectator && !p.observer
	).length;
}

export function me(lobby: Lobby): Player | undefined {
	return lobby.players[lobby.you['auth-id']];
}

export const roleLabel: Record<string, string> = {
	villager: 'Villager',
	seer: 'Seer',
	werewolf: 'Werewolf'
};

export const answerLabel: Record<string, string> = {
	yes: 'Yes',
	no: 'No',
	maybe: 'Maybe',
	'so-close': 'So close',
	'way-off': 'Way off',
	correct: 'Correct!',
	discard: 'Discard'
};
