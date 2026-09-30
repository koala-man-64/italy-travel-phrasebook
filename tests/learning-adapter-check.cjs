'use strict';
// Opt-in cross-stream compatibility check. Pass the reviewed user-data.js path;
// no external checkout is required by the normal focused suite.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { createHash, webcrypto } = require('node:crypto');
const Learning = require('../learning.js');
async function main() {
  const adapterPath = path.resolve(process.argv[2]);
  const before = createHash('sha256').update(fs.readFileSync(adapterPath)).digest('hex');
  const Adapter = require(adapterPath);
  const data = new Map(); let failWrites = false;
  const storage = { getItem: key => data.get(key) ?? null,
    setItem(key, value) { if (failWrites) throw Error('fixture full'); data.set(key, value); } };
  const store = Adapter.create({ storage, crypto: webcrypto, now: () => new Date('2026-09-27T12:00:00Z') });
  const loaded = await store.load(); assert.equal(loaded.writable, true);
  const orphan = await store.mutate(loaded.snapshot.revision, { type: 'review', phraseId: 'phrase_orphan', reviewedAtUtc: '2026-09-01T00:00:00Z' });
  assert.equal(orphan.status, 'SAVED');
  let notifications = 0; const unsubscribe = store.subscribe(() => notifications++);
  const result = await store.mutate(orphan.revision, { type: 'review', phraseId: 'phrase_example', reviewedAtUtc: '2026-09-27T23:59:59Z' });
  assert.equal(result.status, 'SAVED'); assert.ok(notifications > 0);
  const snapshot = await store.getSnapshot();
  const phrases = Learning.prepareContent({ phrases: [{ id: 'phrase_example', review: 'reviewed', snapshot: { it: 'Grazie.', en: 'Thank you.' } }] });
  const cards = Learning.schedule(phrases, snapshot.snapshot.progress, new Date('2026-09-28T00:00:00Z'));
  assert.equal(cards.length, 1); assert.equal(cards[0].dueAtUtc, '2026-09-28T00:00:00.000Z'); assert.equal(cards[0].due, true);
  assert.equal(snapshot.snapshot.progress.find(x => x.phraseId === 'phrase_orphan').reviewCount, 1);
  const failure = await store.mutate(orphan.revision, { type: 'review', phraseId: 'phrase_example', reviewedAtUtc: '2026-09-28T00:00:00Z' });
  assert.equal(failure.status, 'FAILED'); assert.equal(failure.code, 'REVISION_CONFLICT');
  failWrites = true;
  const full = await store.mutate(snapshot.snapshot.revision, { type: 'review', phraseId: 'phrase_example', reviewedAtUtc: '2026-09-28T00:00:00Z' });
  assert.notEqual(full.status, 'SAVED');
  assert.equal((await store.getSnapshot()).snapshot.progress.find(x => x.phraseId === 'phrase_example').reviewCount, 1);
  unsubscribe();
  const after = createHash('sha256').update(fs.readFileSync(adapterPath)).digest('hex'); assert.equal(before, after, 'Adapter changed while checking');
  console.log(JSON.stringify({ result: 'PASS', checkedAtUtc: new Date().toISOString(), adapterPath, adapterSha256: before,
    cases: ['initial load', 'review save and subscription', 'learning UTC due date', 'orphan preservation', 'revision conflict', 'storage failure without increment'] }, null, 2));
}
main().catch(error => { console.error(error); process.exitCode = 1; });
