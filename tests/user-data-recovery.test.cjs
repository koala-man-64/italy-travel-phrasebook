'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { webcrypto, createHash } = require('node:crypto');
const { create } = require('../user-data.js');
const K = { a: 'itguide.user.v1.a', b: 'itguide.user.v1.b', m: 'itguide.user.v1.manifest', s: 'itguide.user.v1.transaction' };
const tx = 'txn_' + 'a'.repeat(32), hash = v => createHash('sha256').update(v).digest('hex');
const context = { sessionId: 's', documentId: 'd', epoch: '1', requestId: 'r' };
function ok(r) { assert.equal(r.status, 'OK', JSON.stringify(r)); return r.value; }
async function setup() {
  const map = new Map(), events = [];
  const storage = { getItem: k => map.get(k) ?? null, setItem: (k, v) => map.set(k, v), removeItem: k => map.delete(k) };
  const options = { storage, crypto: webcrypto, now: () => new Date('2030-06-10T12:00:00Z') };
  const store = create(options); await store.load();
  await store.mutate('0', { type: 'preferences', slow: true }); // Preserve two populated slots.
  store.subscribe(e => events.push(e));
  const before = new Map(map), f = ok(await store.enterRecovery(context));
  const prior = ok(await store.readRecovery(f)).active;
  const raw = ok(await store.prepareMutation(f, prior, { type: 'preferences', tab: 'itinerary' }, null));
  return { store, storage, options, map, events, before, f, prior, raw };
}
async function stage(s) {
  const receipt = ok(await s.store.stageInactive(s.f, tx, 'web-mutation', s.prior, s.raw, null, '1'));
  const escrow = ok(await s.store.exportStage(s.f, tx));
  const prepared = { transactionId: tx, operation: 'web-mutation', phase: 'PREPARED', web: { prior: s.prior, candidate: receipt.candidate }, native: { prior: null, candidate: null }, webEscrowHash: escrow.sha256 };
  return { receipt, escrow, prepared };
}
test('real writer stages only shadow; activation remains invisible until exact paired checkpoint', async () => {
  const s = await setup(), { receipt, escrow, prepared } = await stage(s);
  for (const [k, v] of s.before) assert.equal(s.map.get(k), v);
  assert.equal(hash(escrow.raw), escrow.sha256);
  assert.equal((await s.store.getSnapshot()).snapshot, null);
  ok(await s.store.activateStaged(s.f, tx, prepared));
  assert.equal((await s.store.getSnapshot()).snapshot, null);
  ok(await s.store.releaseReady(s.f, { v: 1, sequence: '3', transactionId: tx, pair: { web: receipt.candidate, native: null } }, null));
  assert.equal((await s.store.getSnapshot()).snapshot.preferences.tab, 'itinerary');
  assert.equal(s.events.filter(e => e.snapshot !== null).length, 1);
  assert.equal((await s.store.mutate('2', { type: 'preferences', slow: false })).code, 'RECOVERY_REQUIRED');
});
test('full rollback restores overwritten inactive slot and original exact manifest', async () => {
  const s = await setup(), { prepared } = await stage(s);
  ok(await s.store.activateStaged(s.f, tx, prepared));
  ok(await s.store.restorePrevious(s.f, tx, { transactionId: tx, decision: 'ROLLBACK', pair: { web: s.prior, native: null } }));
  for (const [k, v] of s.before) assert.equal(s.map.get(k), v);
  assert.equal((await s.store.getSnapshot()).snapshot, null);
});
test('queued pre-fence intents never rebase or execute after recovery', async () => {
  const s = await setup();
  ok(await s.store.releaseReady(s.f, { v: 1, sequence: '1', pair: { web: s.prior, native: null } }, null));
  let called = 0; s.options.coordinator = { mutate: () => { called++; } };
  const pending = s.store.mutate('1', { type: 'preferences', slow: false });
  const recovery = s.store.enterRecovery(context);
  assert.equal((await pending).code, 'RESTORE_CONFLICT'); ok(await recovery); assert.equal(called, 0);
});
test('coordinator delegate runs outside UserData queue and uses actual fenced writer', async () => {
  const s = await setup(); ok(await s.store.releaseReady(s.f, { v: 1, sequence: '1', pair: { web: s.prior, native: null } }, null));
  s.options.coordinator = { mutate: async (expected, change) => {
    s.f = ok(await s.store.enterRecovery(context));
    s.raw = ok(await s.store.prepareMutation(s.f, s.prior, change, null)); const r = await stage(s);
    ok(await s.store.activateStaged(s.f, tx, r.prepared));
    ok(await s.store.releaseReady(s.f, { v: 1, sequence: '3', transactionId: tx, pair: { web: r.receipt.candidate, native: null } }, null));
    return { status: 'SAVED' };
  } };
  assert.equal((await s.store.mutate('1', { type: 'review', phraseId: 'phrase_test', reviewedAtUtc: '2030-06-10T12:00:00Z' })).status, 'SAVED');
  assert.equal((await s.store.getSnapshot()).snapshot.progress[0].reviewCount, 1);
});
for (const boundary of ['shadow-before', 'shadow-after', 'slot-before', 'slot-after', 'manifest-before', 'manifest-after']) test('storage fault ' + boundary + ' preserves recovery material and never exposes candidate', async () => {
  const s = await setup(), original = s.storage.setItem; let armed = true;
  s.storage.setItem = (k, v) => {
    const match = boundary.startsWith('shadow') ? k === K.s : boundary.startsWith('manifest') ? k === K.m : k === K.a;
    if (match && armed) { armed = false; if (boundary.endsWith('after')) original(k, v); throw new Error('quota or lost acknowledgement'); }
    return original(k, v);
  };
  const result = await s.store.stageInactive(s.f, tx, 'web-mutation', s.prior, s.raw, null, '1');
  if (!boundary.startsWith('shadow')) {
    ok(result); const escrow = ok(await s.store.exportStage(s.f, tx));
    assert.equal((await s.store.activateStaged(s.f, tx, { transactionId: tx, operation: 'web-mutation', phase: 'PREPARED', web: { prior: s.prior, candidate: result.value.candidate }, native: { candidate: null }, webEscrowHash: escrow.sha256 })).status, 'FAILED');
    const shadow = JSON.parse(s.map.get(K.s)); for (const [key, field] of [[K.a, 'a'], [K.b, 'b'], [K.m, 'manifest']]) assert.equal(shadow.before[field], s.before.get(key));
  } else assert.equal(result.status, 'FAILED');
  assert.equal((await s.store.getSnapshot()).snapshot, null);
  const restarted = create(s.options); assert.equal((await restarted.load()).snapshot, s.map.has(K.s) ? null : (await restarted.getSnapshot()).snapshot);
});
test('corrupt or unknown shadow gates restart; changed retries and foreign fence fail closed', async () => {
  const s = await setup(); await stage(s);
  assert.equal((await s.store.stageInactive(s.f, tx, 'delete', s.prior, s.raw, null, '1')).code, 'TRANSACTION_CONFLICT');
  assert.equal((await s.store.readRecovery({})).code, 'STALE_CONTEXT');
  const raw = JSON.parse(s.map.get(K.s)); raw.v = 2; s.map.set(K.s, JSON.stringify(raw));
  const restarted = create(s.options); assert.equal((await restarted.load()).snapshot, null);
  const f = ok(await restarted.enterRecovery(context)); assert.equal((await restarted.readRecovery(f)).code, 'UNSUPPORTED_VERSION');
});
test('native escrow can restore missing shadow; hash mismatch cannot write it', async () => {
  const s = await setup(), { escrow } = await stage(s); s.map.delete(K.s);
  assert.equal((await s.store.restoreEscrow(s.f, escrow.raw, '0'.repeat(64))).code, 'HASH_MISMATCH'); assert.equal(s.map.has(K.s), false);
  ok(await s.store.restoreEscrow(s.f, escrow.raw, escrow.sha256)); assert.equal(s.map.get(K.s), escrow.raw);
});
test('cleanup rejects initial checkpoint and requires subsequent verified active pair', async () => {
  const s = await setup(), { receipt, prepared } = await stage(s); ok(await s.store.activateStaged(s.f, tx, prepared));
  assert.equal((await s.store.discardStage(s.f, tx, { v: 1, sequence: '2', transactionId: tx, pair: { web: receipt.candidate, native: null } })).status, 'FAILED');
  const initial = { v: 1, sequence: '2', transactionId: tx, pair: { web: receipt.candidate, native: null } };
  ok(await s.store.discardStage(s.f, tx, { ...initial, sequence: '4' }, initial)); assert.equal(s.map.has(K.s), false);
});
test('serialized shadow accounts for JSON escaping and rejects over 2MiB before any overwrite', async () => {
  const s = await setup();
  const candidate = JSON.parse(s.raw);
  candidate.saved = Array.from({ length: 120 }, (_, i) => ({ id: 'sav_' + i.toString(16).padStart(32, '0'), sourcePhraseId: null, sourceContentVersion: null,
    snapshot: { it: '"'.repeat(900), en: '\\'.repeat(900) }, createdAtUtc: '2030-06-10T12:00:00Z' }));
  function encoded(revision, digit) { return JSON.stringify({ ...candidate, revision, generationId: 'gen_' + digit.repeat(32) }); }
  const a = encoded('0', 'c'), b = encoded('1', 'd'); assert.ok(Buffer.byteLength(a) < 524288);
  const p = (raw, slot) => { const value = JSON.parse(raw); return { slot, revision: value.revision, generationId: value.generationId, sha256: hash(raw) }; };
  s.map.set(K.a, a); s.map.set(K.b, b); s.map.set(K.m, JSON.stringify({ format: 'itguide-user-manifest', schemaVersion: 1, active: p(b, 'b'), previous: p(a, 'a') }));
  const restarted = create(s.options); await restarted.load(); const f = ok(await restarted.enterRecovery(context));
  const prior = ok(await restarted.readRecovery(f)).active;
  const raw = ok(await restarted.prepareMutation(f, prior, { type: 'preferences', slow: false }, null));
  const before = new Map(s.map); assert.equal((await restarted.stageInactive(f, tx, 'web-mutation', prior, raw, null, '1')).code, 'STORAGE_LIMIT');
  assert.deepEqual(s.map, before); assert.equal(s.map.has(K.s), false);
});
