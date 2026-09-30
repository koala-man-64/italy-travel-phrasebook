'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { webcrypto } = require('node:crypto');
const api = require('../archive-transfer.js');
const expected = { web: { generationId: 'gen_' + 'a'.repeat(32), revision: '0', schemaVersion: 1, sha256: 'a'.repeat(64) }, native: null };
const ctx = { sessionId: 's', documentId: 'd', epoch: '0' };
test('UTF8 framing roundtrip bounds escaped envelope separately and never splits surrogate pairs', async () => {
  const raw = ('😀\n"\\'.repeat(8000)), frames = await api.chunks(raw, 'transfer', webcrypto);
  const receiver = api.assembler('transfer', frames.length, frames[0].sha256, webcrypto); let restored;
  for (const f of frames) { assert.ok(Buffer.byteLength(f.text) <= 16384); assert.ok(Buffer.byteLength(JSON.stringify(f)) <= api.ENVELOPE); restored = await receiver.accept(f); }
  assert.equal(restored, raw); await assert.rejects(receiver.accept(frames[0]));
});
test('reordered, duplicated, malformed, extra fields and changed hash fail permanently', async () => {
  const frames = await api.chunks('x'.repeat(20000), 'transfer', webcrypto);
  for (const bad of [{ ...frames[0], sequence: 1 }, { ...frames[0], path: '/tmp' }, { ...frames[0], text: '\ud800' }, { ...frames[0], sha256: '0'.repeat(64) }]) {
    const receiver = api.assembler('transfer', frames.length, frames[0].sha256, webcrypto); await assert.rejects(receiver.accept(bad)); await assert.rejects(receiver.accept(frames[0]));
  }
  await assert.rejects(api.chunks('x'.repeat(api.LIMIT + 1), 'transfer', webcrypto));
});
test('preview is read-only until explicit branded confirmation of same pair', async () => {
  const calls = [], client = api.create({ context: () => ctx, transport: async frame => { calls.push(frame); return { status: 'OK', value: { token: 'preview_' + 'a'.repeat(32), documents: 2 } }; } });
  const p = await client.preview(expected); assert.equal(calls.length, 1); assert.equal(p.value.requiresConfirmation, true);
  assert.equal((await client.confirm({}, expected)).code, 'CONFIRMATION_REQUIRED');
  assert.equal((await client.confirm(p.value.token, { ...expected, native: expected.web })).code, 'RESTORE_CONFLICT');
  assert.equal((await client.confirm(p.value.token, expected)).status, 'OK'); assert.equal(calls[1].op, 'archive-confirm');
  assert.equal((await client.confirm(p.value.token, expected)).code, 'CONFIRMATION_REQUIRED');
  assert.match(client.disclosure, /unencrypted/);
});
test('single operation, late context and disposal cannot manufacture success; no retry', async () => {
  let resolve, calls = 0, context = ctx;
  const client = api.create({ context: () => context, transport: () => { calls++; return new Promise(r => { resolve = r; }); } });
  const pending = client.export(expected); assert.equal((await client.recover()).code, 'BUSY');
  context = { ...ctx, epoch: '1' }; resolve({ status: 'OK' }); assert.equal((await pending).status, 'UNCERTAIN'); assert.equal(calls, 1);
  client.dispose(); assert.equal((await client.export(expected)).code, 'STALE_CONTEXT');
});
test('expiry/cancel require real preview and provider strings stay out of replies', async () => {
  let now = 0; const client = api.create({ context: () => ctx, now: () => now, transport: async frame => frame.op === 'archive-preview' ? { status: 'OK', value: { token: 'preview_' + 'b'.repeat(32), documents: 0 } } : { status: 'FAILED', code: '/private/provider/name' } });
  const p = await client.preview(expected); assert.equal((await client.cancel(p.value.token)).code, 'IO_ERROR');
  const q = await client.preview(expected); now = 300001; assert.equal((await client.confirm(q.value.token, expected)).code, 'CONFIRMATION_REQUIRED');
});
