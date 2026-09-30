const { test } = require('node:test');
const assert = require('node:assert/strict');
const corpus = require('./fixtures/offline-conversation-corpus.json');

const categories = new Set(['hotel', 'dining', 'transit', 'directions', 'money', 'time', 'negation', 'dietary', 'names']);
const slots = new Set(['allergy', 'amount', 'day', 'direction', 'exclusion', 'floor', 'name', 'negation', 'payment', 'place', 'platform', 'preparation', 'rate', 'room', 'route', 'stop', 'ticket', 'time']);

test('offline conversation fixture has 30 distinct synthetic turns per source language', () => {
  assert.equal(corpus.version, 1);
  assert.equal(corpus.synthetic, true);
  assert.equal(corpus.entries.length, 60);
  const ids = new Set(corpus.entries.map(entry => entry.id));
  assert.equal(ids.size, 60);
  for (const source of ['en', 'it']) {
    const group = corpus.entries.filter(entry => entry.source === source);
    assert.equal(group.length, 30);
    for (let number = 1; number <= 30; number += 1) {
      assert.ok(ids.has(`${source}-${String(number).padStart(2, '0')}`));
    }
    for (const category of categories) {
      assert.ok(group.filter(entry => entry.category === category).length >= 3, `${source} lacks ${category} coverage`);
    }
  }
});

test('every turn is short, scorable by meaning, and has explicit critical fields', () => {
  const phrases = new Set();
  for (const entry of corpus.entries) {
    assert.match(entry.id, /^(en|it)-\d{2}$/);
    assert.ok(['en', 'it'].includes(entry.source));
    assert.equal(entry.id.slice(0, 2), entry.source);
    assert.ok(categories.has(entry.category), `${entry.id}: unknown category`);
    assert.equal(typeof entry.text, 'string');
    assert.ok(entry.text.trim().length > 0 && entry.text.length <= 2000, `${entry.id}: text bounds`);
    assert.equal(entry.text, entry.text.trim());
    assert.ok(!phrases.has(`${entry.source}:${entry.text}`), `${entry.id}: duplicate phrase`);
    phrases.add(`${entry.source}:${entry.text}`);
    assert.equal(typeof entry.expectedMeaningEnglish, 'string');
    assert.ok(entry.expectedMeaningEnglish.trim().length >= 12, `${entry.id}: missing expected meaning`);
    assert.ok(Array.isArray(entry.criticalSlots), `${entry.id}: missing slots`);
    assert.equal(new Set(entry.criticalSlots).size, entry.criticalSlots.length, `${entry.id}: duplicate slots`);
    for (const slot of entry.criticalSlots) assert.ok(slots.has(slot), `${entry.id}: unknown slot ${slot}`);
    assert.equal(entry.critical, entry.criticalSlots.length > 0, `${entry.id}: critical flag mismatch`);
  }
});

test('both languages include critical negation, allergy, quantities, names, places and times', () => {
  for (const source of ['en', 'it']) {
    const group = corpus.entries.filter(entry => entry.source === source);
    for (const slot of ['negation', 'allergy', 'amount', 'name', 'place', 'time']) {
      assert.ok(group.some(entry => entry.criticalSlots.includes(slot)), `${source}: missing ${slot}`);
    }
    assert.ok(group.some(entry => !entry.critical), `${source}: missing routine noncritical cases`);
  }
});
