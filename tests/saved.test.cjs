'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { webcrypto } = require('node:crypto');
const Saved = require('../saved.js');
const Data = require('../user-data.js');
class Element {
  constructor(tag, doc) { this.tagName = tag; this.ownerDocument = doc; this.children = []; this.dataset = {}; this.attributes = {}; this.listeners = new Map(); this.parentNode = null; this._text = ''; }
  set textContent(value) { this._text = String(value); this.replaceChildren(); }
  get textContent() { return this._text + this.children.map(child => child.textContent).join(' '); }
  set innerHTML(_) { throw Error('HTML is forbidden'); }
  append(...children) { for (const child of children) { child.parentNode = this; this.children.push(child); } }
  replaceChildren(...children) { this.children.forEach(child => { child.parentNode = null; }); this.children = []; this.append(...children); }
  remove() { if (this.parentNode) this.parentNode.children = this.parentNode.children.filter(child => child !== this); this.parentNode = null; }
  contains(other) { return this === other || this.children.some(child => child.contains(other)); }
  setAttribute(key, value) { this.attributes[key] = value; }
  addEventListener(name, fn) { if (!this.listeners.has(name)) this.listeners.set(name, new Set()); this.listeners.get(name).add(fn); }
  removeEventListener(name, fn) { this.listeners.get(name)?.delete(fn); }
  click() { return Promise.all(this.disabled ? [] : Array.from(this.listeners.get('click') || [], fn => fn())); }
  focus() { this.ownerDocument.activeElement = this; }
  querySelectorAll(selector) {
    const match = node => selector === 'button' ? node.tagName === 'button' : node.dataset.savedAction === /"([^"]+)"/.exec(selector)?.[1];
    return this.children.flatMap(child => [...(match(child) ? [child] : []), ...child.querySelectorAll(selector)]);
  }
  querySelector(selector) { return this.querySelectorAll(selector)[0] || null; }
}
const flush = async () => { for (let i = 0; i < 15; i++) await new Promise(resolve => setImmediate(resolve)); };
const defer = () => { let resolve; const promise = new Promise(r => { resolve = r; }); return { promise, resolve }; };
async function harness(transfer, overrides = {}) {
  const values = new Map(), storage = { getItem: k => values.get(k) ?? null, setItem: (k, v) => values.set(k, v) };
  const store = Data.create({ storage, crypto: webcrypto, now: () => new Date('2030-01-01T00:00:00Z') }); await store.load();
  const doc = { activeElement: null, createElement: tag => new Element(tag, doc) }, host = new Element('div', doc);
  const sentinel = new Element('p', doc); sentinel.textContent = 'Owner content'; host.append(sentinel);
  const speechCalls = [], speech = { listen: text => speechCalls.push(['listen', text]), stop: () => speechCalls.push(['stop']), show: (...text) => speechCalls.push(['show', ...text]) };
  const dispose = Saved.mount(host, { store: { ...store, ...overrides }, speech, transfer }); await flush();
  return { store, host, doc, dispose, sentinel, speechCalls, action: name => host.querySelector(`[data-saved-action="${name}"]`) };
}
async function exported(store) { const { snapshot } = await store.getSnapshot(); return (await store.exportSnapshot(snapshot.revision)).payload; }
test('real unsupported transfer adapter never enables import/export', async () => {
  const client = require('../json-transfer.js').create({ bridge: null });
  const h = await harness(client);
  assert.equal(h.action('import').disabled, true);
  assert.equal(h.action('export').disabled, true);
  assert.match(h.host.textContent, /unavailable in this browser/);
  h.dispose(); client.dispose();
});
test('dispose during export snapshot preparation prevents late provider side effects', async () => {
  const snapshot = defer(); let exports = 0;
  const h = await harness({ importJson: async () => {}, exportJson: async () => { exports++; }, cancel: async () => {} },
    { exportSnapshot: () => snapshot.promise });
  const click = h.action('export').click(); await flush(); h.dispose();
  snapshot.resolve({ status: 'OK', payload: '{}' }); await click;
  assert.equal(exports, 0);
});
test('unsupported Browser transfer is visible, disabled; dispose preserves owner content', async () => {
  const h = await harness(); assert.match(h.host.textContent, /unavailable in this browser/);
  assert.equal(h.action('import').disabled, true); assert.equal(h.action('export').disabled, true);
  h.dispose(); assert.deepEqual(h.host.children, [h.sentinel]);
});
test('Saved renders safe text, uses speech facade, removes through store and restores focus', async () => {
  const h = await harness(); await h.store.mutate('0', { type: 'save', snapshot: { italian: '<img onerror=bad()>', english: 'Hello' }, sourcePhraseId: null, sourceContentVersion: null });
  const id = (await h.store.getSnapshot()).snapshot.saved[0].id;
  assert.match(h.host.textContent, /<img onerror=bad\(\)>/);
  h.action('listen-' + id).focus(); await h.action('listen-' + id).click();
  assert.deepEqual(h.speechCalls[0], ['listen', '<img onerror=bad()>']); assert.equal(h.doc.activeElement.dataset.savedAction, 'listen-' + id);
  await h.action('show-' + id).click(); assert.deepEqual(h.speechCalls[1], ['show', '<img onerror=bad()>', 'Hello']);
  await h.action('remove-' + id).click(); assert.equal((await h.store.getSnapshot()).snapshot.saved.length, 0); h.dispose();
});
test('import preview requires explicit confirm; cancel preview never replaces', async () => {
  let raw; const h = await harness({ importJson: async () => ({ status: 'ok', payload: raw, externalEffect: 'none' }), exportJson: async () => {}, cancel: async () => ({ status: 'ok' }) });
  raw = await exported(h.store);
  const before = await h.store.getSnapshot(); await h.action('import').click();
  assert.match(h.host.textContent, /Confirm replacement/); assert.match(h.host.textContent, /preferences and Builder/);
  assert.equal((await h.store.getSnapshot()).snapshot.revision, before.snapshot.revision);
  assert.equal(h.doc.activeElement.dataset.savedAction, 'confirm');
  await h.action('cancel-preview').click(); assert.equal(h.action('confirm'), null);
  assert.equal((await h.store.getSnapshot()).snapshot.revision, '0'); assert.equal(h.doc.activeElement.dataset.savedAction, 'import');
  await h.action('import').click(); await h.action('confirm').click();
  assert.equal((await h.store.getSnapshot()).snapshot.revision, '1'); assert.equal(h.action('confirm'), null); h.dispose();
});
test('stale preview cannot overwrite a newer practice result', async () => {
  let raw; const h = await harness({ importJson: async () => ({ status: 'ok', payload: raw, externalEffect: 'none' }), exportJson: async () => {}, cancel: async () => ({ status: 'ok' }) });
  raw = await exported(h.store); await h.action('import').click();
  await h.store.mutate('0', { type: 'review', phraseId: 'phrase_x', reviewedAtUtc: '2030-01-01T00:00:00Z' });
  await h.action('confirm').click(); assert.match(h.host.textContent, /REVISION_CONFLICT/);
  assert.equal((await h.store.getSnapshot()).snapshot.progress.length, 1); h.dispose();
});
test('cancel request waits for target terminal result and warns about partial export', async () => {
  const pending = defer(); let cancels = 0, actual;
  const h = await harness({ importJson: async () => {}, exportJson: async raw => { actual = raw; return pending.promise; }, cancel: async () => { cancels++; return { status: 'ok', outcome: 'requested' }; } });
  h.action('export').click(); await flush(); assert.equal(JSON.parse(actual).format, 'itguide-user-data');
  assert.equal(h.action('import').disabled, true); h.action('cancel-transfer').click(); await flush();
  assert.equal(cancels, 1); assert.match(h.host.textContent, /Waiting for the transfer result/);
  assert.equal(h.action('export').disabled, true);
  pending.resolve({ status: 'cancelled', externalEffect: 'partial' }); await flush();
  assert.match(h.host.textContent, /not wiped/); assert.equal(h.action('export').disabled, false); h.dispose();
});
test('failed import cannot preview or mutate, successful export requires complete external effect', async () => {
  const h = await harness({ importJson: async () => ({ status: 'error', code: 'INVALID_UTF8', externalEffect: 'none' }), exportJson: async () => ({ status: 'ok', externalEffect: 'unknown' }), cancel: async () => ({ status: 'ok' }) });
  await h.action('import').click(); assert.match(h.host.textContent, /INVALID_UTF8/); assert.equal(h.action('confirm'), null);
  await h.action('export').click(); assert.match(h.host.textContent, /did not provide a verified result/); assert.match(h.host.textContent, /not wiped/);
  assert.equal((await h.store.getSnapshot()).snapshot.revision, '0'); h.dispose();
});
test('disposing a pending import quarantines late candidate and requests cancellation', async () => {
  const pending = defer(); let cancels = 0;
  const h = await harness({ importJson: () => pending.promise, exportJson: async () => {}, cancel: async () => { cancels++; } });
  const raw = await exported(h.store); h.action('import').click(); await flush(); h.dispose(); await flush();
  pending.resolve({ status: 'ok', payload: raw, externalEffect: 'none' }); await flush();
  assert.equal(cancels, 1); assert.deepEqual(h.host.children, [h.sentinel]); assert.equal((await h.store.getSnapshot()).snapshot.revision, '0');
});
test('late cancel acknowledgment cannot overwrite a terminal export result', async () => {
  const pending = defer(), cancellation = defer();
  const h = await harness({ importJson: async () => {}, exportJson: () => pending.promise, cancel: () => cancellation.promise });
  h.action('export').click(); await flush(); h.action('cancel-transfer').click(); await flush();
  pending.resolve({ status: 'ok', externalEffect: 'complete' }); await flush();
  cancellation.resolve({ status: 'ok', outcome: 'already-terminal' }); await flush();
  assert.match(h.host.textContent, /Personal JSON exported/); assert.doesNotMatch(h.host.textContent, /Waiting for the transfer result/); h.dispose();
});
test('Builder takes complete click-time phrase, handles read-only store and disposes', async () => {
  const h = await harness(); h.dispose(); let phrase = { italian: 'Ciao', english: 'Hello' };
  const dispose = Saved.mountBuilder(h.host, { store: h.store, getPhrase: () => phrase }); await flush();
  phrase = { italian: 'Grazie', english: 'Thank you' }; await h.host.querySelectorAll('button')[0].click(); await flush();
  assert.deepEqual((await h.store.getSnapshot()).snapshot.saved[0].snapshot, { it: 'Grazie', en: 'Thank you' });
  phrase = { italian: 'Missing English' }; await h.host.querySelectorAll('button')[0].click(); await flush(); assert.equal((await h.store.getSnapshot()).snapshot.saved.length, 1);
  dispose(); assert.deepEqual(h.host.children, [h.sentinel]);
});
