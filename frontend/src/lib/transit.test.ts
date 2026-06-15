import test from 'node:test';
import assert from 'node:assert/strict';

import { encode } from './transit.ts';

test('encode keywordizes nested settings rule keys', () => {
	const encoded = encode({
		type: 'settings/rules',
		rules: { 'shared-maybe-pool': false, 'soft-costs': true }
	});

	assert.match(encoded, /~:rules/);
	assert.match(encoded, /~:shared-maybe-pool/);
	assert.match(encoded, /~:soft-costs/);
	assert.doesNotMatch(encoded, /"shared-maybe-pool"/);
	assert.doesNotMatch(encoded, /"soft-costs"/);
});

test('encode keywordizes nested settings budget keys', () => {
	const encoded = encode({
		type: 'settings/budget',
		budget: { tokens: 7, 'maybe-tokens': 2 }
	});

	assert.match(encoded, /~:budget/);
	assert.match(encoded, /~:tokens/);
	assert.match(encoded, /~:maybe-tokens/);
	assert.doesNotMatch(encoded, /"tokens"/);
	assert.doesNotMatch(encoded, /"maybe-tokens"/);
});

test('encode keeps ordinary command string values as strings', () => {
	const encoded = encode({ type: 'game/ask', text: 'Is it snow?' });

	assert.match(encoded, /~:game\/ask/);
	assert.match(encoded, /"Is it snow\?"/);
	assert.doesNotMatch(encoded, /~:Is it snow\?/);
});

test('encode keeps wordpack ids as strings in settings command', () => {
	const encoded = encode({ type: 'settings/wordpacks', wordpacks: ['English_Snow_White_1', 'Persian_1'] });

	assert.match(encoded, /~:settings\/wordpacks/);
	assert.match(encoded, /~:wordpacks/);
	assert.match(encoded, /"English_Snow_White_1"/);
	assert.match(encoded, /"Persian_1"/);
	assert.doesNotMatch(encoded, /~:English_Snow_White_1/);
});
