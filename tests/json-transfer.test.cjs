'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { create } = require('../json-transfer.js');
const sid = n => 'ses_' + n.toString(16).padStart(32, '0');
const payload = JSON.stringify({ format: 'itguide-user-data', schemaVersion: 1, revision: '0', generationId: 'gen_' + 'a'.repeat(32),
  updatedAtUtc: '2026-09-27T00:00:00Z', preferences: { slow: false, tab: 'phrases' }, legacyBuilderRaw: null, saved: [], progress: [], wallet: null, attachments: [] });
const turn = async () => { for (let i = 0; i < 5; i++) await Promise.resolve(); };
function fixture({ autoReady = true, autoCancel = false } = {}) {
  const sent = [], timers = new Map(), listeners = new Map(); let sequence = 0, timerId = 0, session = sid(1);
  const bridge = { onmessage: null, postMessage(raw) {
    const frame = JSON.parse(raw); sent.push(frame);
    if (autoReady && frame.op === 'hello') emit({ v: 1, op: 'ready', sessionId: session });
    if (autoCancel && frame.op === 'cancel') emit({ ...frame, targetRequestId: undefined, status: 'ok', outcome: 'already-terminal' });
  } };
  function emit(r) { bridge.onmessage?.({ data: typeof r === 'string' ? r : JSON.stringify(r) }); }
  const client = create({ bridge, crypto: { getRandomValues(a) { a.fill(0); new DataView(a.buffer).setUint32(12, ++sequence); return a; } },
    timers: { setTimeout(f, ms) { timers.set(++timerId, { f, ms }); return timerId; }, clearTimeout(id) { timers.delete(id); } },
    lifecycle: { addEventListener(e, f) { listeners.set(e, f); }, removeEventListener(e) { listeners.delete(e); } } });
  const reply = (frame, extra = {}) => emit({ v: 1, sessionId: frame.sessionId, requestId: frame.requestId, op: frame.op,
    status: 'ok', ...(frame.op === 'import' ? { payload, externalEffect: 'none' } : { bytesWritten: Buffer.byteLength(payload), externalEffect: 'complete' }), ...extra });
  return { client, bridge, sent, timers, listeners, emit, reply, setSession(s) { session = s; },
    fire(ms) { const item = [...timers.values()].find(t => t.ms === ms); assert.ok(item, 'timer exists'); item.f(); } };
}
test('browser unsupported is explicit and opens no fallback transfer', async () => {
  const c = create({ bridge: null, lifecycle: null }); assert.equal(c.available, false);
  assert.equal((await c.importJson()).code, 'UNSUPPORTED'); assert.equal((await c.exportJson(payload)).code, 'UNSUPPORTED'); c.dispose();
});
test('native issues session before operation and import preserves exact candidate bytes', async () => {
  const f = fixture(); const promise = f.client.importJson(); await turn();
  assert.deepEqual(f.sent[0], { v: 1, op: 'hello' }); const frame = f.sent[1];
  assert.deepEqual(Object.keys(frame), ['v', 'sessionId', 'requestId', 'op']); f.reply(frame);
  assert.equal((await promise).payload, payload); assert.equal(f.timers.size, 0); f.client.dispose();
});
test('concurrent starts fail BUSY even while handshake is pending', async () => {
  const f = fixture({ autoReady: false }); const first = f.client.importJson();
  assert.equal((await f.client.exportJson(payload)).code, 'BUSY'); f.fire(5000);
  assert.equal((await first).code, 'UNSUPPORTED'); f.client.dispose();
});
for (const op of ['import', 'export']) test(`${op} cancel during handshake suppresses late dispatch and permits retry`, async () => {
  const f = fixture({ autoReady: false });
  const first = op === 'import' ? f.client.importJson() : f.client.exportJson(payload);
  assert.equal((await f.client.cancel()).outcome, 'requested');
  const r = await first; assert.equal(r.status, 'cancelled'); assert.equal(r.externalEffect, 'none');
  f.emit({ v: 1, op: 'ready', sessionId: sid(1) }); await turn(); assert.deepEqual(f.sent.map(r => r.op), ['hello']);
  const retry = op === 'import' ? f.client.importJson() : f.client.exportJson(payload);
  f.emit({ v: 1, op: 'ready', sessionId: sid(1) }); await turn();
  assert.deepEqual(f.sent.map(r => r.op), ['hello', 'hello', op]); f.reply(f.sent.at(-1)); assert.equal((await retry).status, 'ok');
  f.client.dispose(); assert.equal(f.timers.size, 0);
});
test('dispose during handshake settles interrupted and cannot dispatch after late ready', async () => {
  const f = fixture({ autoReady: false }); const first = f.client.importJson(); f.client.dispose();
  assert.equal((await first).code, 'INTERRUPTED'); f.emit({ v: 1, op: 'ready', sessionId: sid(1) }); await turn();
  assert.deepEqual(f.sent.map(r => r.op), ['hello']); assert.equal(f.timers.size, 0);
});
test('export rejects malformed, duplicate, nonfinite, lone surrogate and oversized actual bytes', async () => {
  const f = fixture();
  for (const value of ['{"x":1,"x":2}', '{"x":1,"\\u0078":2}', 'NaN', '1e999', '{} {}', '\ufeff{}', '['.repeat(25) + ']'.repeat(25)])
    assert.equal((await f.client.exportJson(value)).code, 'MALFORMED', value);
  assert.equal((await f.client.exportJson('"\\ud800"')).code, 'INVALID_UTF8');
  assert.equal((await f.client.exportJson('"' + 'é'.repeat(262144) + '"')).code, 'FILE_LIMIT');
  assert.equal(f.sent.length, 0); f.client.dispose();
});
test('ignores wrong session, request, op, duplicate fields and unknown result fields', async () => {
  const f = fixture(); let done = false; const promise = f.client.importJson().then(r => { done = true; return r; }); await turn();
  const req = f.sent[1]; f.reply(req, { sessionId: sid(8) }); f.reply(req, { op: 'export' }); f.reply(req, { extra: true });
  f.reply(req, { requestId: 'req_' + 'f'.repeat(32) });
  f.emit(JSON.stringify({ v: 1, sessionId: req.sessionId, requestId: req.requestId, op: 'import', status: 'ok', payload, externalEffect: 'none' }).replace('"v":1', '"v":1,"v":1'));
  await turn(); assert.equal(done, false); f.reply(req); assert.equal((await promise).status, 'ok');
  f.reply(req, { status: 'error', code: 'IO_ERROR' }); assert.equal(f.timers.size, 0); f.client.dispose();
});
test('cancel acknowledgment and target result are separate terminal promises', async () => {
  const f = fixture(); const imported = f.client.importJson(); await turn(); const target = f.sent[1];
  const cancelled = f.client.cancel(); assert.equal(f.client.cancel(), cancelled);
  const command = f.sent[2]; assert.equal(command.targetRequestId, target.requestId);
  f.emit({ v: 1, sessionId: command.sessionId, requestId: command.requestId, op: 'cancel', status: 'ok', outcome: 'requested' });
  assert.equal((await cancelled).outcome, 'requested'); assert.equal(f.timers.size, 1);
  f.emit({ v: 1, sessionId: target.sessionId, requestId: target.requestId, op: 'import', status: 'cancelled', externalEffect: 'none' });
  assert.equal((await imported).status, 'cancelled'); assert.equal((await f.client.cancel()).outcome, 'already-terminal'); f.client.dispose();
});
test('incorrect native export byte count is quarantined', async () => {
  const f = fixture(); let done = false; const p = f.client.exportJson(payload).then(r => { done = true; return r; }); await turn();
  const frame = f.sent[1]; f.reply(frame, { bytesWritten: 1 }); await turn(); assert.equal(done, false);
  f.reply(frame); assert.equal((await p).status, 'ok'); f.client.dispose();
});
test('watchdog sends cancel and failed export never claims bytes wiped', async () => {
  const f = fixture(); const exported = f.client.exportJson(payload); await turn(); f.fire(155000);
  const result = await exported; assert.equal(result.code, 'TIMEOUT'); assert.equal(result.externalEffect, 'unknown');
  assert.equal(f.sent.at(-1).op, 'cancel'); f.client.dispose(); assert.equal(f.timers.size, 0);
});
test('pagehide interrupts pending operation and quarantines results', async () => {
  const f = fixture(); const promise = f.client.exportJson(payload); await turn(); const req = f.sent[1];
  f.listeners.get('pagehide')(); const r = await promise; assert.equal(r.code, 'INTERRUPTED'); assert.equal(r.externalEffect, 'unknown');
  f.reply(req); assert.equal(f.bridge.onmessage, null); assert.equal(f.timers.size, 0); assert.equal((await f.client.importJson()).code, 'INTERRUPTED');
});
test('one client owns a bridge listener', async () => {
  const f = fixture(); const other = create({ bridge: f.bridge, crypto: require('node:crypto').webcrypto, lifecycle: null });
  assert.equal(other.available, false); other.dispose(); assert.equal(typeof f.bridge.onmessage, 'function'); f.client.dispose();
});
test('reserves request 1000 for drain and then handshakes fresh session', async () => {
  const f = fixture({ autoCancel: true });
  for (let i = 0; i < 999; i++) { const p = f.client.importJson(); await turn(); f.reply(f.sent.at(-1)); await p; }
  f.setSession(sid(2)); const final = f.client.importJson(); await turn();
  assert.deepEqual(f.sent.slice(-3).map(r => r.op), ['cancel', 'hello', 'import']);
  assert.equal(f.sent.at(-1).sessionId, sid(2)); f.reply(f.sent.at(-1)); await final; f.client.dispose();
});
