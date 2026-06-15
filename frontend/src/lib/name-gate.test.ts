import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import { canConnectToRoom, normalizePlayerName } from './name-gate.ts';

describe('room link name gate', () => {
	it('normalizes player names by trimming surrounding whitespace', () => {
		assert.equal(normalizePlayerName('  Briar Rose  '), 'Briar Rose');
	});

	it('does not allow room connection without a non-empty saved name', () => {
		assert.equal(canConnectToRoom(''), false);
		assert.equal(canConnectToRoom('   '), false);
	});

	it('allows room connection when a saved name has visible characters', () => {
		assert.equal(canConnectToRoom('Hunter'), true);
		assert.equal(canConnectToRoom('  Gretel  '), true);
	});
});
