'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const UserData = require('../user-data.js');
const Personal = require('../personal.js');
function fixture() {
  const values = new Map();
  const storage = { getItem: key => values.get(key) ?? null, setItem: (key, value) => values.set(key, value) };
  const store = UserData.create({ storage, crypto: require('node:crypto').webcrypto, now: () => new Date('2026-09-27T12:00:00Z') });
  const messages = [];
  return { store, storage, values, messages, shell: Personal.create(store, message => messages.push(message)) };
}
test('shell restores migration and freezes queued Builder edits while serializing preference revisions', async () => {
  const f = fixture();
  f.values.set('itguide.slow', 'true');
  await f.shell.ready;
  assert.equal(f.shell.get('slow', false), true);
  const builder = { cat: 'hotel', sel: [1] };
  const first = f.shell.set('builder', builder);
  builder.sel[0] = 9;
  const second = f.shell.set('tab', 'vocab');
  assert.equal((await first).status, 'SAVED');
  assert.equal((await second).status, 'SAVED');
  assert.equal(f.shell.get('builder', null).sel[0], 1);
  assert.equal(f.shell.get('tab', ''), 'vocab');
  assert.equal((await f.store.getSnapshot()).snapshot.revision, '2');
  assert.equal((await f.shell.set('tab', 'vocab')).status, 'UNCHANGED');
  assert.equal(f.values.get('itguide.slow'), 'true');
  assert.equal(f.values.has('itguide.tab'), false);
  assert.deepEqual(f.messages, []);
});
test('shell reports failed persistence without claiming success or overwriting legacy keys', async () => {
  const f = fixture(); await f.shell.ready;
  f.storage.setItem = () => { throw new Error('full'); };
  assert.notEqual((await f.shell.set('slow', true)).status, 'SAVED');
  assert.match(f.messages.at(-1), /not saved|uncertain/i);
  assert.equal(f.shell.get('slow', false), false);
  assert.equal((await f.store.getSnapshot()).snapshot.revision, '0');
});
test('shell consumes confirmed replacement snapshot and disposed writes do nothing', async () => {
  const f = fixture(); await f.shell.ready;
  const exported = await f.store.exportSnapshot('0');
  const data = JSON.parse(exported.payload); data.preferences.tab = 'itinerary';
  const preview = await f.store.previewImport(JSON.stringify(data));
  assert.equal((await f.store.replaceConfirmed('0', preview.validatedCandidate)).status, 'SAVED');
  assert.equal(f.shell.get('tab', ''), 'itinerary');
  f.shell.dispose();
  assert.equal((await f.shell.set('slow', true)).code, 'DISPOSED');
  assert.equal((await f.store.getSnapshot()).snapshot.revision, '1');
});
test('confirmed restore fences queued older Builder intentions and preserves imported state', async () => {
  const f = fixture(); await f.shell.ready;
  const data = JSON.parse((await f.store.exportSnapshot('0')).payload);
  data.legacyBuilderRaw = '{"cat":"imported"}';
  const preview = await f.store.previewImport(JSON.stringify(data));
  const first = f.shell.set('builder', {cat:'old-one'});
  const second = f.shell.set('builder', {cat:'old-two'});
  const restore = f.shell.replaceConfirmed('0', preview.validatedCandidate);
  const during = f.shell.set('builder', {cat:'old-during'});
  assert.equal((await restore).status, 'SAVED');
  assert.equal((await first).code, 'RESTORE_CONFLICT');
  assert.equal((await second).code, 'RESTORE_CONFLICT');
  assert.equal((await during).code, 'RESTORE_IN_PROGRESS');
  assert.equal((await f.store.getSnapshot()).snapshot.legacyBuilderRaw, data.legacyBuilderRaw);
  assert.equal((await f.shell.set('builder', {cat:'new-after'})).status, 'SAVED');
});
