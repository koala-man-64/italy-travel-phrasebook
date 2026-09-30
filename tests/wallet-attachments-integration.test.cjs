'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { create } = require('../wallet.js');
const { mount } = require('../attachments.js');
const { document, nodes, flush, defer } = require('./fixtures/3b/dom.cjs');

const documentId = 'doc_' + 'a'.repeat(32);
const relation = { tripId: 'trip_a', eventId: 'event_a', documentId };
const nativeDocument = { documentId, displayName: 'Train ticket.pdf', mediaType: 'application/pdf', byteLength: 42 };
const pair = revision => ({ userRevision: revision, walletGenerationId: 'gen_' + 'b'.repeat(32), walletRevision: '1' });
const state = (revision = '1', attachments = []) => ({ state: 'READY', writable: true,
  snapshot: { revision, wallet: { generationId: pair(revision).walletGenerationId, revision: '1' }, attachments } });

async function harness() {
  let current = state(), listener, operation = null;
  const calls = [], host = {
    subscribeIdle() { return () => {}; },
    async wallet(op, expected, args) {
      calls.push({ op, expected, args });
      if (op === 'snapshot') return { status: 'OK', value: { ...pair(current.snapshot.revision), documents: [nativeDocument] } };
      return operation ? operation(op, expected, args) : { status: 'FAILED', code: 'NOT_FOUND' };
    }
  };
  const store = { subscribe(fn) { listener = fn; fn(current); return () => { listener = null; }; }, getSnapshot: async () => current };
  const wallet = create({ store, host }), doc = document(), element = doc.createElement('div');
  const dispose = mount(element, { ...relation, wallet });
  const button = text => nodes(element).find(n => n.tagName === 'button' && n.textContent === text);
  const emit = value => { current = value; listener(value); };
  await flush();
  return { wallet, element, button, calls, emit, dispose,
    onCommand(fn) { operation = fn; }, current: () => current };
}

test('mounted widget accepts actual SAVED version and OPENED value through facade epochs', async () => {
  const h = await harness();
  h.onCommand(async op => {
    if (op === 'attach') { h.emit(state('2', [relation])); return { status: 'SAVED', value: pair('2') }; }
    if (op === 'open-document') return { status: 'OPENED', value: {} };
    return { status: 'FAILED', code: 'NOT_FOUND' };
  });
  h.button('Attach document').click(); h.button('Attach Train ticket.pdf').click(); await flush();
  assert.match(h.element.textContent, /Document links saved/);
  assert.ok(h.button('Open')); assert.equal(h.button('Open').disabled, false);
  h.button('Open').click(); await flush(); assert.match(h.element.textContent, /Viewer launch accepted/);
  assert.deepEqual(h.calls.filter(c => c.op !== 'snapshot').map(c => c.op), ['attach', 'open-document']);
  h.dispose(); h.wallet.dispose();
});

test('UNCERTAIN gates mounted actions; known failure retains the selection', async () => {
  const failure = await harness();
  failure.onCommand(async () => ({ status: 'FAILED', code: 'REVISION_CONFLICT' }));
  failure.button('Attach document').click(); failure.button('Attach Train ticket.pdf').click(); await flush();
  assert.match(failure.element.textContent, /documents changed/); assert.ok(failure.button('Cancel'));
  failure.dispose(); failure.wallet.dispose();

  const uncertain = await harness();
  uncertain.onCommand(async () => ({ status: 'UNCERTAIN', code: 'RECOVERY_REQUIRED' }));
  uncertain.button('Attach document').click(); uncertain.button('Attach Train ticket.pdf').click(); await flush();
  assert.equal(uncertain.button('Attach document').disabled, true);
  assert.match(uncertain.element.textContent, /recovery/i);
  uncertain.dispose(); uncertain.wallet.dispose();
});

test('external successor pair suppresses stale completion from mounted widget', async () => {
  const h = await harness(), pending = defer();
  h.onCommand(() => pending.promise);
  h.button('Attach document').click(); h.button('Attach Train ticket.pdf').click(); await flush();
  h.emit(state('3', [])); pending.resolve({ status: 'SAVED', value: pair('2') }); await flush();
  assert.doesNotMatch(h.element.textContent, /Document links saved/);
  assert.equal(h.button('Attach document').disabled, false);
  h.dispose(); h.wallet.dispose();
});
