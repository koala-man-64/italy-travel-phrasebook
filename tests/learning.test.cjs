'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const Learning = require('../learning.js');
const authored = require('../learning-content.json');
const fixture = { contentVersion: 'synthetic-reviewed-fixture', phrases: [
  { id: 'phrase_alpha', review: 'reviewed', snapshot: { it: 'Buongiorno.', en: 'Good morning.' } },
  { id: 'phrase_beta', review: 'reviewed', snapshot: { it: 'Grazie.', en: 'Thank you.' } },
  { id: 'phrase_draft', review: 'draft', snapshot: { it: 'Bozza.', en: 'Draft.' } }
] };
const progress = (count, timestamp = '2026-09-27T23:59:59Z', phraseId = 'phrase_alpha') =>
  ({ phraseId, reviewCount: count, lastReviewedAtUtc: timestamp });
const flush = () => new Promise(resolve => setImmediate(resolve));
const deferred = () => { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b; }); return { promise, resolve, reject }; };

test('UTC review-date schedule implements 1/3/7/14/30 days and caps large counts', () => {
  assert.equal(Learning.dueAt(null), null);
  assert.equal(Learning.dueAt(progress(0)), null);
  const dates = ['2026-09-28', '2026-09-30', '2026-10-04', '2026-10-11', '2026-10-27'];
  dates.forEach((date, index) => assert.equal(Learning.dueAt(progress(index + 1)), `${date}T00:00:00.000Z`));
  assert.equal(Learning.dueAt(progress(1000000)), '2026-10-27T00:00:00.000Z');
  assert.equal(Learning.dueAt(progress(1, '2028-02-28T12:34:56Z')), '2028-02-29T00:00:00.000Z');
  assert.equal(Learning.dueAt(progress(1, '2026-12-31T00:00:00Z')), '2027-01-01T00:00:00.000Z');
  assert.equal(Learning.dueAt(progress(5, '2099-12-31T23:59:59Z')), '2100-01-30T00:00:00.000Z');
});
test('invalid timestamps and counts fail closed', () => {
  for (const count of [-1, 1.5, NaN, Infinity, 1000001, '1']) assert.throws(() => Learning.dueAt(progress(count)));
  for (const stamp of ['2026-02-29T00:00:00Z', '2026-04-31T00:00:00Z', '2026-09-27T24:00:00Z', '2026-09-27', '2026-09-27T12:00:00+02:00', '2026-09-27T00:00:00.000Z', 'invalid']) {
    assert.throws(() => Learning.dueAt(progress(1, stamp)));
  }
});
test('due boundary is inclusive, orphan progress preserved and drafts excluded', () => {
  const records = [progress(1), progress(5, '2026-09-01T12:00:00Z', 'phrase_removed')];
  const before = JSON.stringify(records);
  const phrases = Learning.prepareContent(fixture);
  const prior = Learning.schedule(phrases, records, new Date('2026-09-27T23:59:59.999Z'));
  assert.deepEqual(prior.map(x => [x.phrase.id, x.due]), [['phrase_beta', true], ['phrase_alpha', false]]);
  const at = Learning.schedule(phrases, records, new Date('2026-09-28T00:00:00Z'));
  assert.ok(at.every(x => x.due));
  assert.equal(JSON.stringify(records), before);
  assert.throws(() => Learning.schedule(phrases, [records[0], records[0]], new Date()));
  assert.throws(() => Learning.schedule(phrases, [], new Date(NaN)));
});
test('schedule ties are deterministic regardless of input order', () => {
  const left = Learning.schedule(Learning.prepareContent(fixture), [], new Date());
  const right = Learning.schedule(Learning.prepareContent({ phrases: [...fixture.phrases].reverse() }), [], new Date());
  assert.deepEqual(left, right);
});
test('authored content is independently reviewed and bound to the exact approved copy', () => {
  assert.equal(authored.phrases.length, 12);
  assert.match(authored.provenance, /AI-assisted/);
  assert.equal(authored.reviewEvidence.kind, 'independent-ai-bilingual-text-review');
  assert.equal(authored.reviewEvidence.humanReview, 'not-performed');
  const canonical = JSON.stringify(authored.phrases.map(({id, version, snapshot, context}) => ({id, version, snapshot, context})));
  const approved = '03bc48531bed9a99fdbfab2e57e1573de77d35b32fad4a70c4251d1776e116cd';
  assert.equal(require('node:crypto').createHash('sha256').update(canonical).digest('hex'), approved);
  assert.equal(authored.reviewEvidence.canonicalPhrasesSha256, approved);
  assert.ok(authored.phrases.every(x => x.review === 'reviewed' && x.version === 1 && x.context));
  assert.equal(Learning.schedule(Learning.prepareContent(authored), [], new Date()).length, 12);
  assert.throws(() => Learning.prepareContent({ phrases: [fixture.phrases[0], fixture.phrases[0]] }));
});

// Purposefully small DOM double: layout/keyboard behavior is checked separately in Browser.
class Element {
  constructor(tag, doc) { this.tagName = tag; this.ownerDocument = doc; this.children = []; this.attributes = {}; this.listeners = new Map(); this._text = ''; }
  set textContent(text) { this._text = String(text); this.replaceChildren(); }
  get textContent() { return this._text + this.children.map(child => child.textContent).join(''); }
  set innerHTML(_) { throw Error('HTML sink forbidden'); }
  appendChild(child) { child.parentNode = this; this.children.push(child); return child; }
  replaceChildren(...children) { this.children.forEach(x => { x.parentNode = null; }); this.children = []; children.forEach(x => this.appendChild(x)); }
  remove() { if (this.parentNode) this.parentNode.children = this.parentNode.children.filter(x => x !== this); this.parentNode = null; }
  setAttribute(name, value) { this.attributes[name] = String(value); }
  addEventListener(type, fn) { if (!this.listeners.has(type)) this.listeners.set(type, new Set()); this.listeners.get(type).add(fn); }
  removeEventListener(type, fn) { this.listeners.get(type)?.delete(fn); }
  emit(type) { if (!this.disabled) this.listeners.get(type)?.forEach(fn => fn({ target: this })); }
  focus() { this.ownerDocument.activeElement = this; }
}
const find = (node, name) => node.attributes['data-learning'] === name ? node : node.children.map(x => find(x, name)).find(Boolean);
function harness(options = {}) {
  const doc = { createElement: tag => new Element(tag, doc), activeElement: null };
  const mount = new Element('div', doc), sentinel = new Element('p', doc); mount.appendChild(sentinel);
  let state = { state: 'READY', writable: true, snapshot: { revision: '9007199254740993', progress: [progress(1, '2026-09-01T00:00:00Z', 'phrase_orphan')] } };
  const subscribers = new Set(), writes = [], listens = [];
  let stops = 0, unsubscriptions = 0;
  const services = {
    content: options.content || fixture, clock: { now: () => new Date('2026-09-27T23:59:59.123Z') },
    speech: { listen(text) { listens.push(text); return options.audio?.(); }, stop() { stops++; } },
    store: {
      async load() { if (options.load) return options.load(); return state; },
      async getSnapshot() { if (options.getSnapshot) return options.getSnapshot(); return state; },
      subscribe(fn) { subscribers.add(fn); return () => { unsubscriptions++; subscribers.delete(fn); }; },
      async mutate(revision, change) {
        writes.push({ revision, change });
        if (options.mutate) return options.mutate(revision, change);
        state = structuredClone(state);
        state.snapshot.revision = String(BigInt(revision) + 1n);
        const prior = state.snapshot.progress.find(x => x.phraseId === change.phraseId);
        if (prior) { prior.reviewCount++; prior.lastReviewedAtUtc = change.reviewedAtUtc; }
        else state.snapshot.progress.push(progress(1, change.reviewedAtUtc, change.phraseId));
        subscribers.forEach(fn => fn(state));
        return { status: 'SAVED', revision: state.snapshot.revision, generationId: 'gen_fixture' };
      }
    }
  };
  const dispose = Learning.mount(mount, services);
  return { doc, mount, sentinel, services, dispose, writes, listens, subscribers, el: name => find(mount, name),
    get state() { return state; }, get stops() { return stops; }, get unsubscriptions() { return unsubscriptions; },
    notify(next) { state = next; subscribers.forEach(fn => fn(next)); } };
}
test('listen, reveal and retry keep focus, language attributes and self-review semantics', async () => {
  const h = harness(); await flush();
  assert.equal(h.el('select').children.length, 2);
  assert.equal(h.el('transcript').hidden, true);
  const reveal = h.el('reveal'); reveal.focus(); reveal.emit('click');
  assert.equal(h.el('transcript').hidden, false);
  assert.equal(reveal.attributes['aria-expanded'], 'true'); assert.equal(h.doc.activeElement, reveal);
  assert.deepEqual(h.el('transcript').children.map(x => x.lang), ['it', 'en']);
  h.el('listen').emit('click'); await flush(); assert.deepEqual(h.listens, ['Buongiorno.']);
  h.el('retry').emit('click'); await flush();
  assert.equal(h.el('transcript').hidden, true); assert.equal(h.listens.length, 2);
  h.el('review').emit('click'); await flush();
  assert.deepEqual(h.writes, [{ revision: '9007199254740993', change: { type: 'review', phraseId: 'phrase_alpha', reviewedAtUtc: '2026-09-27T23:59:59Z' } }]);
  assert.match(h.el('status').textContent, /practice saved/); assert.match(h.el('due').textContent, /2026-09-28/);
  assert.equal(h.state.snapshot.progress[0].phraseId, 'phrase_orphan');
  assert.equal(h.state.snapshot.progress[0].reviewCount, 1);
  assert.equal(h.el('status').attributes.role, 'status');
  assert.equal(h.el('select').attributes['aria-label'], 'Practice phrase');
  h.dispose();
});
test('draft package cannot activate a drill or persist practice', async () => {
  const draft = structuredClone(authored);
  draft.phrases.forEach(phrase => { phrase.review = 'draft'; });
  const h = harness({ content: draft }); await flush();
  assert.match(h.el('draft').textContent, /12 draft/);
  assert.equal(h.el('review').disabled, true); assert.equal(h.el('listen').disabled, true);
  h.el('review').emit('click'); assert.equal(h.writes.length, 0); h.dispose();
});
test('double clicks issue one mutation and selection stays disabled until completion', async () => {
  const pending = deferred(); const h = harness({ mutate: () => pending.promise }); await flush();
  h.el('review').emit('click'); h.el('review').emit('click');
  assert.equal(h.writes.length, 1); assert.equal(h.el('select').disabled, true);
  pending.resolve({ status: 'FAILED', code: 'REVISION_CONFLICT' }); await flush(); h.dispose();
});
for (const result of [{ status: 'FAILED', code: 'STORAGE_FULL' }, { status: 'FAILED', code: 'REVISION_CONFLICT' }, { status: 'UNCERTAIN', code: 'READBACK_MISMATCH' }, undefined]) {
  test(`no false persistence on ${JSON.stringify(result)}; explicit reload recovers`, async () => {
    const h = harness({ mutate: () => result }); await flush(); const before = JSON.stringify(h.state);
    h.el('review').focus();
    h.el('review').emit('click'); await flush();
    assert.doesNotMatch(h.el('status').textContent, /practice saved/);
    assert.equal(h.el('review').disabled, true); assert.equal(JSON.stringify(h.state), before);
    assert.equal(h.doc.activeElement, h.el('reload'));
    h.el('reload').emit('click'); await flush(); assert.equal(h.el('review').disabled, false); h.dispose();
  });
}
test('thrown mutation/load/read and malformed schedule never imply success', async () => {
  const h = harness({ mutate: () => { throw Error('private payload not for UI'); } }); await flush();
  h.el('review').emit('click'); await flush();
  assert.match(h.el('status').textContent, /could not be confirmed/); assert.doesNotMatch(h.mount.textContent, /private payload/); h.dispose();
  const badLoad = harness({ load: () => { throw Error('blocked'); } }); await flush();
  assert.equal(badLoad.el('review').disabled, true); badLoad.dispose();
  const badRead = harness({ getSnapshot: () => { throw Error('blocked'); } }); await flush();
  assert.equal(badRead.el('review').disabled, true); assert.match(badRead.el('status').textContent, /could not be read/); badRead.dispose();
  const malformed = harness(); await flush();
  malformed.notify({ state: 'READY', writable: true, snapshot: { revision: '1', progress: [progress(-1)] } }); await flush();
  assert.equal(malformed.el('review').disabled, true); malformed.dispose();
});
test('read-only/fallback/recovery state keeps practice but blocks writes', async () => {
  for (const state of ['FALLBACK', 'RECOVERY_REQUIRED', 'UNKNOWN']) {
    const h = harness(); await flush();
    h.notify({ ...h.state, state, writable: false }); await flush();
    assert.equal(h.el('review').disabled, true); assert.equal(h.el('listen').disabled, false);
    assert.match(h.el('status').textContent, /read-only/); h.dispose();
  }
});
test('speech rejection and late speech completion are scoped to the active request', async () => {
  const pending = deferred(); const h = harness({ audio: () => pending.promise }); await flush();
  h.el('listen').emit('click'); h.el('stop').emit('click');
  pending.reject(Error('late failure')); await flush();
  assert.doesNotMatch(h.el('status').textContent, /Audio unavailable/); h.dispose();
  const bad = harness({ audio: () => Promise.reject(Error('private speech error')) }); await flush();
  bad.el('listen').emit('click'); await flush(); assert.match(bad.el('status').textContent, /Audio unavailable/);
  assert.equal(bad.writes.length, 0); bad.dispose();
});
test('content uses only text sinks; dispose removes owned nodes/listeners and ignores late work', async () => {
  const content = structuredClone(fixture); content.phrases[0].snapshot.it = '<img src=x onerror=bad()>';
  const pending = deferred(); const h = harness({ content, mutate: () => pending.promise }); await flush();
  assert.equal(h.el('transcript').children[0].textContent, content.phrases[0].snapshot.it);
  const button = h.el('review'), lateNotification = [...h.subscribers][0];
  h.el('listen').emit('click'); button.emit('click'); h.dispose(); h.dispose();
  pending.resolve({ status: 'SAVED' }); lateNotification(h.state); await flush();
  assert.deepEqual(h.mount.children, [h.sentinel]); assert.equal(h.subscribers.size, 0); assert.equal(h.unsubscriptions, 1);
  button.emit('click'); assert.equal(h.writes.length, 1); assert.ok(h.stops >= 2);
  const remount = Learning.mount(h.mount, h.services); await flush(); assert.equal(h.el('transcript').hidden, true); remount();
});
test('stale async snapshots cannot overwrite a newer read', async () => {
  const h = harness(); await flush(); const a = deferred(), b = deferred(); let count = 0;
  h.services.store.getSnapshot = () => (++count === 1 ? a.promise : b.promise);
  h.notify(h.state); h.notify(h.state);
  b.resolve({ ...h.state, writable: false, state: 'FALLBACK' }); await flush();
  a.resolve(h.state); await flush(); assert.equal(h.el('review').disabled, true); h.dispose();
});
test('subscription setup failure cleans the partial mount', () => {
  const doc = { createElement: tag => new Element(tag, doc) }, root = new Element('div', doc);
  assert.throws(() => Learning.mount(root, { content: fixture, clock: { now: () => new Date() }, speech: {},
    store: { subscribe() { throw Error('subscribe unavailable'); } } }), /subscribe unavailable/);
  assert.equal(root.children.length, 0);
});
test('module has no storage, bridge, microphone or network dependency and CSS scopes 44px controls', () => {
  const source = fs.readFileSync(path.join(__dirname, '../learning.js'), 'utf8');
  assert.doesNotMatch(source, /localStorage|sessionStorage|fetch\(|XMLHttpRequest|OfflineConversation|getUserMedia/);
  const css = fs.readFileSync(path.join(__dirname, '../learning.css'), 'utf8');
  assert.match(css, /min-height: 44px/); assert.match(css, /:focus-visible/);
});
