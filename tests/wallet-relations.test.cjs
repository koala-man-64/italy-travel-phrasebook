const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const { webcrypto } = require('node:crypto');
const { prepareContent, currentRelation } = require('../trip.js');
const { create } = require('../user-data.js');
const vm = require('node:vm');
const read = name => JSON.parse(fs.readFileSync(require('node:path').join(__dirname, '..', name), 'utf8'));
const content = prepareContent(read('trip-content.json'), read('trip-content-anchors.json'));
const relation = { tripId: content.trip.tripId, eventId: content.trip.events[0].id, documentId: 'doc_' + 'a'.repeat(32) };
test('relation authority rejects unloaded, foreign and historical events', () => {
  assert.equal(currentRelation(null, relation), false);
  assert.equal(currentRelation(content, relation), true);
  assert.equal(currentRelation(content, { ...relation, tripId: 'trip_unknown' }), false);
  assert.equal(currentRelation(content, { ...relation, eventId: 'event_unknown' }), false);
});
function pageScope() {
  const html = fs.readFileSync(require('node:path').join(__dirname, '../index.html'), 'utf8');
  const start = html.indexOf('    let currentTripContent = null;');
  assert.notEqual(start, -1);
  let hide;
  const sandbox = { window: { addEventListener(name, fn) { assert.equal(name, 'pagehide'); hide = fn; } } };
  vm.runInNewContext(html.slice(start, html.indexOf('    const basePersonalStore', start)) +
    '\nthis.bind = bindTripContent; this.read = () => currentTripContent;', sandbox);
  return { bind: sandbox.bind, read: sandbox.read, hide: () => hide() };
}
test('pagehide before delayed content completion cannot restore relation authority', async () => {
  const page = pageScope(); let resolve;
  const loading = new Promise(done => { resolve = done; }).then(page.bind);
  page.hide(); resolve(content); const dispose = await loading;
  assert.equal(page.read(), null); dispose(); assert.equal(page.read(), null);
});
test('old mount disposal cannot clear a successor and pagehide revokes current content', () => {
  const page = pageScope(), dispose = page.bind(content), successor = { ...content };
  page.bind(successor); dispose(); assert.equal(page.read(), successor);
  page.hide(); assert.equal(page.read(), null);
});
test('actual fenced writer checks current content on every add without rewriting historical restores', async () => {
  const image = new Map(); let active = content;
  const store = create({ storage: { getItem: k => image.get(k) ?? null, setItem: (k,v) => image.set(k,v), removeItem: k => image.delete(k) },
    crypto: webcrypto, now: () => new Date('2026-09-27T12:00:00Z'), isCurrentRelation: r => currentRelation(active, r) });
  await store.load();
  const f = (await store.enterRecovery({ sessionId: 's', documentId: 'd', epoch: '1', requestId: 'r' })).value;
  const prior = (await store.readRecovery(f)).value.active;
  const native = { generationId: 'gen_' + 'b'.repeat(32), revision: '1', schemaVersion: 1, sha256: 'c'.repeat(64) };
  const candidate = await store.prepareMutation(f, prior, { type: 'attach', relation }, native);
  assert.equal(candidate.status, 'OK');
  active = null;
  assert.equal((await store.prepareMutation(f, prior, { type: 'attach', relation }, native)).code, 'REFERENCE_MISMATCH');
  const historical = JSON.parse(candidate.value); historical.attachments[0].eventId = 'event_123456789abc';
  const restored = await store.prepareRestore(f, prior, JSON.stringify(historical), native);
  assert.equal(restored.status, 'OK');
  assert.equal(JSON.parse(restored.value).attachments[0].eventId, 'event_123456789abc');
});
