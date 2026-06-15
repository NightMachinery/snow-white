export function normalizePlayerName(name: string): string {
	return name.trim();
}

export function canConnectToRoom(savedName: string): boolean {
	return normalizePlayerName(savedName).length > 0;
}
