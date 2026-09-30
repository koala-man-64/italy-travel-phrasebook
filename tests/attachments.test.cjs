'use strict';
const test = require('node:test'), assert = require('node:assert/strict');
const { mount } = require('../attachments.js');
const { document, nodes, flush, defer } = require('./fixtures/3b/dom.cjs');
const F = require('./fixtures/3b/facade.js');
async function harness(wallet = F.create()) {
  const doc = document(), host = doc.createElement('div'), sentinel = doc.createElement('p'); sentinel.textContent = 'Owner content'; host.append(sentinel);
  const dispose = mount(host, { ...F.relation, wallet }); await flush();
  const button = text => nodes(host).find(n => n.tagName === 'button' && n.textContent === text);
  return { doc, host, wallet, dispose, button, sentinel };
}
test('safe hostile text, exact opaque open arguments, preserve owner content and idempotent dispose', async () => {
  const h = await harness(); assert.match(h.host.textContent, /<img src=x onerror=alert\(1\)>/);
  assert.equal(nodes(h.host).filter(n => ['img', 'iframe', 'object', 'a'].includes(n.tagName)).length, 0);
  h.button('Open').click(); await flush(); assert.deepEqual(h.wallet.calls[0], ['openDocument', F.view().version, F.id(1)]);
  assert.match(h.host.textContent, /Viewer launch accepted/); assert.equal(h.doc.activeElement, h.button('Open'));
  h.dispose(); h.dispose(); assert.deepEqual(h.host.children, [h.sentinel]); assert.equal(h.wallet.subscribers, 0);
});
test('attach, cancelled import and exact unlink retain other event relations', async () => {
  const initial = F.view(); const orphan = { ...F.relation, eventId: 'event_old' }; initial.attachments.push(orphan);
  const h = await harness(F.create(initial));
  h.button('Attach document').click(); h.button('Import document').click(); await flush();
  assert.equal(h.wallet.value.attachments.length, 2); assert.equal(h.doc.activeElement, h.button('Attach document'));
  h.button('Attach document').click(); h.button('Attach Biglietto & prenotazione “Roma”.pdf').click(); await flush();
  assert.equal(h.wallet.value.attachments.length, 3);
  h.button('Unlink').click(); h.button('Cancel').click(); assert.equal(h.wallet.value.attachments.length, 3);
  h.button('Unlink').click(); h.button('Confirm unlink').click(); await flush();
  assert.equal(h.wallet.value.attachments.length, 2); assert.ok(h.wallet.value.attachments.some(r => r.eventId === orphan.eventId));
  assert.equal(h.wallet.value.documents.length, 2);
});
for (const state of ['LOADING', 'READ_ONLY', 'UNSUPPORTED', 'RECOVERY_REQUIRED']) test(state + ' disables every wallet action including open', async () => {
  const raw = F.view(); raw.state = state; const h = await harness(F.create(raw));
  for (const button of nodes(h.host).filter(n => n.tagName === 'button')) { assert.equal(button.disabled, true); button.click(); }
  assert.equal(h.wallet.calls.length, 0);
});
for (const [name, edit] of [
  ['unknown state', v => { v.state = 'WRITABLE'; }],
  ['missing reason field', v => { delete v.reason; }],
  ['missing document', v => { v.documents.shift(); }],
  ['duplicate relation', v => { v.attachments.push(v.attachments[0]); }],
  ['URI ID', v => { v.documents[0].documentId = 'content://unsafe'; }],
  ['bad type', v => { v.documents[0].mediaType = 'text/html'; }],
  ['121 scalars', v => { v.documents[0].displayName = 'x'.repeat(121); }],
  ['lone surrogate', v => { v.documents[0].displayName = '\ud800'; }],
  ['control character', v => { v.documents[0].displayName = 'line\nname'; }],
  ['bidi', v => { v.documents[0].displayName = 'name\u202e.pdf'; }],
  ['oversize document', v => { v.documents[0].byteLength = 20 * 1024 * 1024 + 1; }],
  ['null wallet with documents', v => { v.version.walletGenerationId = null; v.version.walletRevision = null; }],
  ['noncanonical epoch', v => { v.version.epoch = '01'; }],
  ['overflow epoch', v => { v.version.epoch = '9223372036854775808'; }],
  ['51 documents', v => { v.documents = Array.from({ length: 51 }, (_, n) => ({ ...v.documents[0], documentId: F.id(n + 1) })); }],
  ['aggregate bytes', v => { v.documents = Array.from({ length: 13 }, (_, n) => ({ ...v.documents[0], documentId: F.id(n + 1), byteLength: 20 * 1024 * 1024 })); }]
]) test('fail closed on ' + name, async () => {
  const raw = F.view(); edit(raw); const h = await harness(F.create(raw));
  assert.match(h.host.textContent, /Reload/); assert.equal(h.button('Attach document').disabled, true); assert.equal(h.wallet.calls.length, 0);
});
test('120 Unicode scalars and exact native size limit accepted; extra fields never rendered', async () => {
  const raw = F.view(); raw.documents[0].displayName = '😀'.repeat(120); raw.documents[0].byteLength = 20 * 1024 * 1024;
  raw.documents[0].uri = 'SECRET_URI'; raw.documents[0].bytes = 'SECRET_BYTES';
  const h = await harness(F.create(raw)); assert.equal(h.button('Open').disabled, false); assert.doesNotMatch(h.host.textContent, /SECRET/);
});
test('null wallet accepts empty READY and leaves import selection usable', async () => {
  const raw = F.view(); raw.documents = []; raw.attachments = []; raw.version.walletGenerationId = null; raw.version.walletRevision = null;
  const h = await harness(F.create(raw)); h.button('Attach document').click(); assert.equal(h.button('Import document').disabled, false);
});
test('notification before initial snapshot and >2^53 epochs cannot regress', async () => {
  const pending = defer(), wallet = F.create(); wallet.getSnapshot = () => pending.promise;
  const h = await harness(wallet); const newer = F.view(); newer.version.epoch = '9007199254740993'; newer.state = 'READ_ONLY'; wallet.emit(newer);
  pending.resolve(F.view()); await flush(); assert.equal(h.button('Open').disabled, true);
  const older = F.view(); older.version.epoch = '9007199254740992'; wallet.emit(older); assert.equal(h.button('Open').disabled, true);
});
test('same epoch with changed content fails closed', async () => {
  const h = await harness(); const raw = h.wallet.value; raw.state = 'READ_ONLY'; h.wallet.emit(raw); assert.equal(h.button('Open').disabled, true); assert.match(h.host.textContent, /could not be confirmed/);
});
test('replacement closes selector and pending stale callback cannot announce success', async () => {
  const wallet = F.create(), pending = defer(); wallet.openDocument = () => pending.promise;
  const h = await harness(wallet); h.button('Attach document').click(); wallet.advance(v => { v.version.walletGenerationId = 'replacement'; });
  assert.equal(h.button('Cancel'), undefined); h.button('Open').click(); wallet.advance(v => { v.state = 'READ_ONLY'; });
  pending.resolve({ status: 'OPENED' }); await flush(); assert.doesNotMatch(h.host.textContent, /Viewer launch accepted/); assert.equal(h.button('Open').disabled, true);
});
test('rapid repeated clicks issue one command; stale detached controls remain inert', async () => {
  const wallet = F.create(), pending = defer(); let count = 0; wallet.openDocument = () => { count++; return pending.promise; };
  const h = await harness(wallet), open = h.button('Open'); open.click(); open.click(); assert.equal(count, 1);
  pending.resolve({ status: 'OPENED' }); await flush(); h.dispose(); open.click(); assert.equal(count, 1);
});
for (const result of [null, { status: 'ok' }, { status: 'SAVED' }, { status: 'OPENED', code: 42 }, { status: 'OPENED', uri: 'content://private' }, { status: 'FAILED', code: '<raw error>' }, { status: 'UNCERTAIN' }]) test('unverified open receipt gates actions: ' + JSON.stringify(result), async () => {
  const wallet = F.create(); wallet.openDocument = async () => result;
  const h = await harness(wallet); h.button('Open').click(); await flush(); assert.equal(h.button('Open').disabled, true); assert.doesNotMatch(h.host.textContent, /<raw error>|Viewer launch accepted/);
});
test('known failure retains choice; no mutation retry', async () => {
  const wallet = F.create(); let count = 0; wallet.attach = async () => { count++; return { status: 'FAILED', code: 'LIMIT_REACHED' }; };
  const h = await harness(wallet); h.button('Attach document').click(); h.button('Attach Biglietto & prenotazione “Roma”.pdf').click(); await flush();
  assert.match(h.host.textContent, /limit reached/); assert.ok(h.button('Cancel')); assert.equal(count, 1);
});
test('dispose while snapshot and operation pending suppresses callbacks without affecting successor', async () => {
  const wallet = F.create(), pending = defer(); wallet.openDocument = () => pending.promise;
  const h = await harness(wallet); h.button('Open').click(); h.dispose();
  const other = await harness(); pending.resolve({ status: 'OPENED' }); await flush();
  assert.deepEqual(h.host.children, [h.sentinel]); assert.doesNotMatch(other.host.textContent, /Viewer launch accepted/);
  const read = defer(); const next = F.create(); next.getSnapshot = () => read.promise;
  const later = await harness(next); later.dispose(); read.resolve(F.view()); await flush(); assert.deepEqual(later.host.children, [later.sentinel]);
});
test('Escape returns focus; background notification does not steal outside focus', async () => {
  const h = await harness(); h.button('Attach document').click();
  const section = nodes(h.host).find(n => n.tagName === 'section'); let prevented = false;
  for (const fn of section.handlers.get('keydown')) fn({ key: 'Escape', preventDefault() { prevented = true; } });
  assert.ok(prevented); assert.equal(h.doc.activeElement, h.button('Attach document'));
  const outside = h.doc.createElement('button'); outside.focus(); h.wallet.advance(() => {}); assert.equal(h.doc.activeElement, outside);
});
test('detached selection cannot issue a command after replacement', async () => {
  const h = await harness(); h.button('Attach document').click();
  const stale = h.button('Attach Biglietto & prenotazione “Roma”.pdf');
  h.wallet.advance(v => { v.version.walletGenerationId = 'replacement'; }); stale.click();
  assert.equal(h.wallet.calls.length, 0);
});
test('rejected picker fails closed without exposing exception text or changing links', async () => {
  const wallet = F.create(); wallet.importDocument = async () => { throw Error('secret native diagnostic'); };
  const h = await harness(wallet); h.button('Attach document').click(); h.button('Import document').click(); await flush();
  assert.equal(h.button('Attach document').disabled, true); assert.doesNotMatch(h.host.textContent, /secret native diagnostic/);
  assert.equal(wallet.value.attachments.length, 1);
});
test('failed snapshot is gated unless superseded by a newer valid notification', async () => {
  const wallet = F.create(), pending = defer(); wallet.getSnapshot = () => pending.promise;
  const h = await harness(wallet); wallet.advance(v => { v.state = 'READ_ONLY'; }); pending.reject(Error('private read failure')); await flush();
  assert.match(h.host.textContent, /cannot be changed or opened/); assert.doesNotMatch(h.host.textContent, /could not be confirmed|private read failure/);
  const failing = F.create(); failing.getSnapshot = async () => { throw Error('private failure'); };
  const other = await harness(failing); assert.equal(other.button('Attach document').disabled, true);
});
test('focus goes to heading if background deletion removes its document control', async () => {
  const h = await harness(); h.button('Open').focus();
  h.wallet.advance(v => { v.attachments = []; v.documents = []; });
  assert.equal(h.doc.activeElement.tagName, 'h4'); assert.equal(h.doc.activeElement.textContent, 'Documents');
});
