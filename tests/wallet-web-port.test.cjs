'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { webcrypto } = require('node:crypto');
const { create: createStore } = require('../user-data.js');
const { create } = require('../wallet-web-port.js');
const ctx = { sessionId: 's', documentId: 'd', epoch: '1', requestId: 'r' };
const ok = r => { assert.equal(r.status, 'OK', JSON.stringify(r)); return r.value; };
async function fixture() {
  const map = new Map();
  const storage = { getItem: k => map.get(k) ?? null, setItem: (k, v) => map.set(k, v), removeItem: k => map.delete(k) };
  const store = createStore({ storage, crypto: webcrypto }); await store.load();
  const port = create(store, ctx, () => true); await port.ready;
  const call = (m, ...args) => port.invoke(ctx, m, args);
  return { store, port, call, map };
}
test('adapter gates the real writer and never exposes its recovery fence', async () => {
  const { store, call } = await fixture();
  assert.equal((await store.getSnapshot()).snapshot, null);
  assert.equal(ok(await call('enterRecovery')), null);
  const image = ok(await call('readRecovery'));
  assert.deepEqual(Object.keys(image).sort(), ['active', 'previous', 'transactionId']);
  const raw = ok(await call('captureExact', image.active));
  assert.equal(JSON.parse(raw).revision, image.active.revision);
  assert.equal((await store.mutate(image.active.revision, { type: 'preferences', slow: true })).status, 'FAILED');
});
test('adapter uses actual stage, evidence and paired checkpoint release', async () => {
  const { store, call } = await fixture();
  const prior = ok(await call('readRecovery')).active;
  const raw = ok(await call('prepareCandidate', 'web-mutation', prior, null, JSON.stringify({ type: 'preferences', slow: true })));
  const tx = 'txn_' + 'a'.repeat(32);
  const receipt = ok(await call('stageInactive', tx, 'web-mutation', prior, raw, null, '1'));
  const escrow = ok(await call('exportStage', tx));
  assert.equal(ok(await call('verifyReceipt', receipt, escrow.raw)), true);
  const journal = { transactionId: tx, operation: 'web-mutation', phase: 'PREPARED', web: { prior, candidate: receipt.candidate }, native: { prior: null, candidate: null }, webEscrowHash: escrow.sha256 };
  ok(await call('activateStaged', tx, journal));
  const pair = { web: receipt.candidate, native: null };
  assert.equal(ok(await call('verifyPair', pair)), true);
  ok(await call('releaseReady', { v: 1, sequence: '3', transactionId: tx, pair }, null));
  assert.equal((await store.getSnapshot()).snapshot.preferences.slow, true);
  assert.equal((await call('readRecovery')).code, 'RECOVERY_REQUIRED');
  ok(await call('enterRecovery')); assert.equal((await store.getSnapshot()).snapshot, null);
  ok(await call('discardStage', tx, { v: 1, sequence: '4', transactionId: tx, pair },
    { v: 1, sequence: '3', transactionId: tx, pair }));
  assert.equal(ok(await call('readRecovery')).transactionId, null);
});
test('rejects dynamic dispatch, forged context and caller fences', async () => {
  const { port, call } = await fixture();
  for (const m of ['load', 'mutate', 'constructor', '__proto__']) assert.equal((await call(m)).status, 'FAILED');
  assert.equal((await call('readRecovery', {})).code, 'INVALID_REQUEST');
  assert.equal((await port.invoke({ ...ctx, documentId: 'other' }, 'readRecovery', [])).code, 'STALE_CONTEXT');
});
test('queued operations are rejected after lifecycle invalidation', async () => {
  const { port, call, map } = await fixture(); const before = [...map];
  const pending = call('readRecovery'); port.invalidate();
  assert.equal((await pending).code, 'STALE_CONTEXT');
  assert.equal((await call('enterRecovery')).code, 'STALE_CONTEXT');
  assert.deepEqual([...map], before);
});
test('invalidation after READY immediately hides cached private data', async () => {
  const { store, port, call } = await fixture();
  const web = ok(await call('readRecovery')).active;
  ok(await call('releaseReady', { v: 1, sequence: '1', transactionId: null, pair: { web, native: null } }, null));
  assert.notEqual((await store.getSnapshot()).snapshot, null);
  port.invalidate();
  assert.equal((await store.getSnapshot()).snapshot, null);
});
test('click-time arguments cannot be changed while queued', async () => {
  const { call, port } = await fixture(); const prior = ok(await call('readRecovery')).active;
  const args = ['web-mutation', prior, null, JSON.stringify({ type: 'preferences', slow: true })];
  const pending = port.invoke(ctx, 'prepareCandidate', args);
  args[3] = JSON.stringify({ type: 'preferences', slow: false });
  assert.equal(JSON.parse(ok(await pending)).preferences.slow, true);
});
for (const expired of [false, true]) test(`in-flight release cannot publish READY after ${expired ? 'elapsed deadline without timer callback' : 'lifecycle invalidation'}`, async () => {
  const map = new Map(); let gate = null, entered;
  const reached = new Promise(resolve => { entered = resolve; });
  const crypto = { getRandomValues: a => webcrypto.getRandomValues(a), randomUUID: () => webcrypto.randomUUID(),
    subtle: { digest: async (...args) => { if (gate) { entered(); await gate; } return webcrypto.subtle.digest(...args); } } };
  const store = createStore({ crypto, storage: { getItem: k => map.get(k) ?? null,
    setItem: (k, v) => map.set(k, v), removeItem: k => map.delete(k) } });
  await store.load(); const port = create(store, ctx, () => true); await port.ready;
  const web = ok(await port.invoke(ctx, 'readRecovery', [])).active;
  let resume; gate = new Promise(resolve => { resume = resolve; });
  const events = []; store.subscribe(v => events.push(v));
  let admitted = true;
  const release = port.invoke(ctx, 'releaseReady', [{ v: 1, sequence: '1', transactionId: null, pair: { web, native: null } }, null], () => admitted);
  await reached; if (expired) admitted = false; else port.invalidate(); resume(); await release;
  assert.equal(events.some(v => v.snapshot !== null), false);
  assert.equal((await store.getSnapshot()).snapshot, null);
});
test('coordinated baseline preserves legacy data and stays hidden until paired release', async () => {
  const map = new Map([['itguide.slow', 'true'], ['itguide.tab', '"builder"'], ['itguide.builder', '{ "private": "exact legacy bytes" }']]);
  const store = createStore({ crypto: webcrypto, storage: { getItem: k => map.get(k) ?? null, setItem: (k, v) => map.set(k, v), removeItem: k => map.delete(k) } });
  store.gateStartup();const port = create(store, ctx, () => true);await port.ready;
  const observed = [];store.subscribe(v => observed.push(v));
  const web = ok(await port.invoke(ctx, 'initializeBaseline', []));
  assert.equal(observed.some(v => v.snapshot !== null || v.writable), false);
  const raw = JSON.parse(ok(await port.invoke(ctx, 'captureExact', [web])));
  assert.equal(raw.preferences.slow, true);assert.equal(raw.legacyBuilderRaw, '{ "private": "exact legacy bytes" }');
  const before = [...map];assert.deepEqual(ok(await port.invoke(ctx, 'initializeBaseline', [])), web);assert.deepEqual([...map], before);
  ok(await port.invoke(ctx, 'releaseReady', [{ v: 1, sequence: '1', transactionId: null, pair: { web, native: null } }, null]));
  assert.equal((await store.getSnapshot()).snapshot.preferences.slow, true);
});
test('baseline initialization preserves partial, unsupported and malformed legacy storage', async () => {
  for (const entries of [[['itguide.user.v1.a', '{}']], [['itguide.user.v1.transaction', '{}']], [['itguide.slow', 'invalid']]]) {
    const map = new Map(entries), before = [...map];
    const store = createStore({ crypto: webcrypto, storage: { getItem: k => map.get(k) ?? null, setItem: (k, v) => map.set(k, v), removeItem: k => map.delete(k) } });
    const port = create(store, ctx, () => true);await port.ready;
    assert.notEqual((await port.invoke(ctx, 'initializeBaseline', [])).status, 'OK');assert.deepEqual([...map], before);
    assert.equal((await store.getSnapshot()).snapshot, null);
  }
});
