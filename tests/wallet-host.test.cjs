'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { webcrypto, createHash } = require('node:crypto');
const { create: createStore } = require('../user-data.js');
const { create } = require('../wallet-host.js');
const context = { sessionId: '11111111-1111-1111-1111-111111111111', documentId: '22222222-2222-2222-2222-222222222222', epoch: '1', requestId: '33333333-3333-3333-3333-333333333333' };
async function fixture(options = {}) {
  const map = new Map(), sent = [], events = new Map();
  const store = createStore({ crypto: webcrypto, storage: { getItem: k => map.get(k) ?? null, setItem: (k, v) => map.set(k, v), removeItem: k => map.delete(k) } });
  await store.load();const bridge = { postMessage: raw => sent.push(JSON.parse(raw)) };
  const host = create({ store, bridge, crypto: webcrypto, lifecycle: { addEventListener: (k, f) => events.set(k, f), removeEventListener: k => events.delete(k) }, ...options });
  host.hello();const nonce = sent.pop().nonce;
  return { store, bridge, host, sent, events, nonce };
}
const init = c => JSON.stringify({ v: 1, kind: 'init', context: c });
const ready = f => f.host.deliver(f.nonce, JSON.stringify({ v: 1, kind: 'wallet-ready', context }));
test('picker commands retain independent picker plus transfer deadline',async()=>{
  let time=0,budget;const f=await fixture({now:()=>time,after:ms=>{budget=ms;return()=>{};}});
  f.host.accept(f.nonce,init(context));ready(f);
  const pending=f.host.wallet('archive-preview',{userRevision:'0',walletGenerationId:null,walletRevision:null},{}),id=f.sent.at(-1).id;
  assert.equal(budget,300000);time=180001;
  f.host.deliver(f.nonce,JSON.stringify({v:1,kind:'wallet-result',context,id,status:'OK',value:{}}));
  assert.equal((await pending).status,'OK');f.host.dispose();
});
test('wallet waits for native command readiness and shares mutation admission', async () => {
  const f = await fixture();f.host.accept(f.nonce, init(context));
  const expected = { userRevision: '0', walletGenerationId: null, walletRevision: null };
  assert.equal((await f.host.wallet('snapshot', expected, {})).code, 'BUSY');
  ready(f);const pending = f.host.wallet('snapshot', expected, {}), first = f.sent.at(-1);
  assert.equal(first.kind, 'wallet');assert.deepEqual(first.expected, expected);
  assert.equal((await f.host.mutate('0', { type: 'preferences', slow: true })).code, 'BUSY');
  f.host.deliver(f.nonce, JSON.stringify({ v: 1, kind: 'wallet-result', context, id: first.id, status: 'OK', value: { ...expected, documents: [] } }));
  assert.equal((await pending).status, 'OK');
  const next = f.host.mutate('0', { type: 'preferences', slow: true });assert.equal(BigInt(f.sent.at(-1).id), BigInt(first.id) + 1n);
  f.host.dispose();assert.equal((await next).status, 'UNCERTAIN');
});
test('wallet ignores stale completion and gates malformed active receipt', async () => {
  const f = await fixture();f.host.accept(f.nonce, init(context));ready(f);
  const pending = f.host.wallet('archive-preview', { userRevision: '0', walletGenerationId: null, walletRevision: null }, {}), id = f.sent.at(-1).id;
  f.host.deliver(f.nonce, JSON.stringify({ v: 1, kind: 'wallet-result', context: { ...context, epoch: '2' }, id, status: 'OK', value: {} }));
  assert.equal((await f.host.wallet('snapshot', {}, {})).code, 'BUSY');
  f.host.deliver(f.nonce, JSON.stringify({ v: 1, kind: 'wallet-result', context, id, status: 'OK', value: {}, unexpected: true }));
  assert.equal((await pending).status, 'UNCERTAIN');assert.equal((await f.store.getSnapshot()).snapshot, null);
});
function request(method, args, id = '1') {
  const raw = Buffer.from(JSON.stringify({ method, args }));
  return JSON.stringify({ v: 1, context, id, seq: 0, count: 1, bytes: raw.length, sha256: createHash('sha256').update(raw).digest('hex'), data: raw.toString('base64') });
}
test('host gates real private state before subscriptions and binds only once', async () => {
  const f = await fixture();const observed = [];f.store.subscribe(v => observed.push(v));
  assert.equal((await f.store.load()).snapshot, null);assert.equal(observed.some(v => v.snapshot !== null), false);
  f.host.accept(f.nonce, init(context));assert.deepEqual(f.sent[0], { v: 1, kind: 'bound', context, nonce: f.nonce });
  f.host.deliver(f.nonce, request('readRecovery', []));await new Promise(r => setTimeout(r, 30));
  assert.equal(JSON.parse(Buffer.from(f.sent[1].data, 'base64')).status, 'OK');
  assert.throws(() => create({ store: f.store, bridge: f.bridge, crypto: webcrypto }));
  f.host.dispose();assert.equal(f.events.size, 0);
});
test('old document invalidation cannot dispose its same-URL successor', async () => {
  const f = await fixture();f.host.accept(f.nonce, init(context));
  f.host.invalidate({ ...context, documentId: '44444444-4444-4444-4444-444444444444' });
  f.host.deliver(f.nonce, request('readRecovery', []));await new Promise(r => setTimeout(r, 30));assert.equal(f.sent.length, 2);
  f.host.invalidate(context);f.host.deliver(f.nonce, request('readRecovery', [], '2'));
  await new Promise(r => setTimeout(r, 10));assert.equal(f.sent.length, 2);assert.equal((await f.store.getSnapshot()).snapshot, null);
});
test('pagehide prevents late bootstrap and malformed native context fails closed', async () => {
  const f = await fixture();f.events.get('pagehide')();f.host.accept(f.nonce, init(context));assert.equal(f.sent.length, 0);
  const invalid = await fixture();invalid.host.accept(invalid.nonce, init({ ...context, epoch: '01' }));invalid.host.accept(invalid.nonce, init(context));
  assert.equal(invalid.sent.length, 0);assert.equal((await invalid.store.getSnapshot()).snapshot, null);
});
test('queued predecessor init and frame deliveries cannot bind or poison successor', async () => {
  const old = await fixture(), next = await fixture();
  next.host.accept(old.nonce, init(context));next.host.deliver(old.nonce, request('readRecovery', []));assert.equal(next.sent.length, 0);
  next.host.accept(next.nonce, init(context));assert.equal(next.sent[0].kind, 'bound');
  next.host.deliver(old.nonce, request('readRecovery', []));
  next.host.deliver(next.nonce, request('readRecovery', []));await new Promise(r => setTimeout(r, 30));
  assert.equal(JSON.parse(Buffer.from(next.sent[1].data, 'base64')).status, 'OK');old.host.dispose();next.host.dispose();
});
test('startup gate also hides a load already awaiting its storage hash', async () => {
  const map = new Map();let resume, entered;
  const paused = new Promise(r => { resume = r; }), reached = new Promise(r => { entered = r; });
  const crypto = { getRandomValues: a => webcrypto.getRandomValues(a), subtle: { digest: async (...args) => { entered();await paused;return webcrypto.subtle.digest(...args); } } };
  const store = createStore({ crypto, storage: { getItem: k => map.get(k) ?? null, setItem: (k, v) => map.set(k, v), removeItem: k => map.delete(k) } });
  const loading = store.load();await reached;store.gateStartup();
  const states = [];store.subscribe(v => states.push(v));resume();await loading;
  assert.equal(states.some(v => v.snapshot !== null || v.writable), false);assert.equal((await store.getSnapshot()).snapshot, null);
});
test('missing platform bridge fails closed instead of restoring browser fallback', async () => {
  const map = new Map();const store = createStore({ crypto: webcrypto, storage: { getItem: k => map.get(k) ?? null, setItem: (k, v) => map.set(k, v), removeItem: k => map.delete(k) } });
  await store.load();assert.notEqual((await store.getSnapshot()).snapshot, null);
  assert.throws(() => create({ store, bridge: null, crypto: webcrypto }));
  assert.equal((await store.load()).snapshot, null);
});
test('personal mutation freezes intent, rejects overlap and ignores stale completion', async () => {
  const f = await fixture();f.host.accept(f.nonce, init(context));ready(f);
  const change = { type: 'preferences', slow: true }, pending = f.host.mutate('0', change);change.slow = false;
  const request = f.sent.at(-1);assert.equal(request.change.slow, true);assert.equal(request.kind, 'mutate');
  assert.equal((await f.host.mutate('0', change)).code, 'BUSY');
  const reply = { v: 1, kind: 'mutation-result', context, id: request.id, status: 'SAVED', revision: '1', generationId: 'gen_' + 'a'.repeat(32) };
  f.host.deliver(f.nonce, JSON.stringify({ ...reply, context: { ...context, epoch: '0' } }));
  assert.equal((await f.host.mutate('0', change)).code, 'BUSY');
  f.host.deliver(f.nonce, JSON.stringify(reply));assert.equal((await pending).status, 'SAVED');
  const next = f.host.mutate('1', change);f.host.dispose();assert.equal((await next).status, 'UNCERTAIN');
});
test('elapsed personal deadline quarantines late success even before timer callback', async () => {
  let time = 0;const f = await fixture({ now: () => time, after: () => () => {} });
  f.host.accept(f.nonce, init(context));ready(f);const pending = f.host.mutate('0', { type: 'preferences', slow: true });
  const id = f.sent.at(-1).id;time = 180001;
  f.host.deliver(f.nonce, JSON.stringify({ v: 1, kind: 'mutation-result', context, id, status: 'SAVED', revision: '1', generationId: 'gen_' + 'a'.repeat(32) }));
  assert.equal((await pending).status, 'UNCERTAIN');
  assert.equal((await f.host.mutate('1', { type: 'preferences', slow: false })).code, 'RECOVERY_REQUIRED');
  assert.equal((await f.store.getSnapshot()).snapshot, null);
});
