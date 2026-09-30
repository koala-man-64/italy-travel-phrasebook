'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { webcrypto, createHash } = require('node:crypto');
const { create } = require('../wallet-channel.js');
const context = { sessionId: 's', documentId: 'd', epoch: '1', requestId: 'r' };
function frames(value, id = '1') {
  const raw = Buffer.from(JSON.stringify(value));
  return Array.from({ length: Math.ceil(raw.length / 16384) }, (_, seq) => JSON.stringify({
    v: 1, context, id, seq, count: Math.ceil(raw.length / 16384), bytes: raw.length,
    sha256: createHash('sha256').update(raw).digest('hex'), data: raw.subarray(seq * 16384, (seq + 1) * 16384).toString('base64')
  }));
}
function fixture(invoke = async () => ({ status: 'OK', value: '✅'.repeat(12000) })) {
  let invalidated = 0, calls = 0, timeout, current = true, finish, time = 0;
  const replies = [], done = new Promise(r => { finish = r; });
  const channel = create({ context, crypto: webcrypto, isCurrent: () => current, now: () => time,
    after: (_, fn) => { timeout = fn; return () => {}; },
    port: { invoke: async (...args) => { calls++; return invoke(...args); }, invalidate: () => { invalidated++; } },
    send: raw => { replies.push(JSON.parse(raw)); if (replies.at(-1).seq === replies.at(-1).count - 1) finish(); }
  });
  return { channel, replies, done, timeout: () => timeout(), advance: () => { time = 5001; }, stale: () => { current = false; }, get invalidated() { return invalidated; }, get calls() { return calls; } };
}
test('bounded multiframe Unicode roundtrip and monotonic next request', async () => {
  const f = fixture();frames({ method: 'captureExact', args: ['é'.repeat(20000)] }).forEach(f.channel.receive);await f.done;
  assert.equal(f.calls, 1);assert.equal(f.invalidated, 0);
  const bytes = Buffer.concat(f.replies.map(r => Buffer.from(r.data, 'base64')));
  assert.equal(createHash('sha256').update(bytes).digest('hex'), f.replies[0].sha256);
  assert.equal(JSON.parse(bytes).value, '✅'.repeat(12000));
  frames({ method: 'enterRecovery', args: [] }, '2').forEach(f.channel.receive);
  await new Promise(r => setTimeout(r, 20));assert.equal(f.calls, 2);
});
test('replay cannot invoke a completed writer twice', async () => {
  const f = fixture();const request = frames({ method: 'enterRecovery', args: [] });
  request.forEach(f.channel.receive);await f.done;request.forEach(f.channel.receive);
  assert.equal(f.calls, 1);assert.equal(f.invalidated, 1);
});
test('bad hash, duplicate frame, oversized envelope and context loss fail closed', async () => {
  for (const alter of [
    data => data.map(raw => { const f = JSON.parse(raw);f.sha256 = '0'.repeat(64);return JSON.stringify(f); }),
    data => [data[0], data[0]],
    () => [' '.repeat(102401)],
    data => { const f = JSON.parse(data[0]);f.context.epoch = '2';return [JSON.stringify(f)]; }
  ]) {
    const f = fixture();alter(frames({ method: 'readRecovery', args: ['x'.repeat(20000)] })).forEach(f.channel.receive);
    await new Promise(r => setTimeout(r, 10));assert.equal(f.calls, 0);assert.equal(f.invalidated, 1);
  }
});
test('timeout during real pending operation suppresses result and quarantines successor', async () => {
  let release;const operation = new Promise(r => { release = r; });const f = fixture(() => operation);
  frames({ method: 'enterRecovery', args: [] }).forEach(f.channel.receive);
  await new Promise(r => setTimeout(r, 20));assert.equal(f.calls, 1);f.timeout();
  release({ status: 'OK', value: true });await new Promise(r => setTimeout(r, 10));
  frames({ method: 'readRecovery', args: [] }, '2').forEach(f.channel.receive);
  assert.equal(f.replies.length, 0);assert.equal(f.calls, 1);assert.equal(f.invalidated, 1);
});
test('elapsed deadline guards writer publication even before timeout callback runs', async () => {
  let release, guard;const operation = new Promise(r => { release = r; });
  const f = fixture((c, m, a, admit) => { guard = admit; return operation; });
  frames({ method: 'releaseReady', args: [] }).forEach(f.channel.receive);
  await new Promise(r => setTimeout(r, 20));assert.equal(guard(), true);
  f.advance();assert.equal(guard(), false);release({ status: 'OK', value: null });
  await new Promise(r => setTimeout(r, 10));assert.equal(f.replies.length, 0);assert.equal(f.invalidated, 1);
});
test('duplicate JSON keys are rejected before dispatch', () => {
  const f = fixture();const raw = frames({ method: 'enterRecovery', args: [] })[0].replace('"v":1', '"v":1,"v":1');
  f.channel.receive(raw);assert.equal(f.calls, 0);assert.equal(f.invalidated, 1);
});
