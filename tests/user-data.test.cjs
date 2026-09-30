'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { webcrypto, createHash } = require('node:crypto');
const { create } = require('../user-data.js');
const K = { a: 'itguide.user.v1.a', b: 'itguide.user.v1.b', m: 'itguide.user.v1.manifest' };
const now = () => new Date('2030-06-10T12:00:00Z');
function storage(seed = {}) {
  const map = new Map(Object.entries(seed));
  return { map, getItem: k => map.has(k) ? map.get(k) : null, setItem: (k, v) => map.set(k, v) };
}
function setup(seed) { const s = storage(seed); return { s, store: create({ storage: s, crypto: webcrypto, now }) }; }
const save = (italian = 'Ciao', english = 'Hello') => ({ type: 'save', snapshot: { italian, english }, sourcePhraseId: null, sourceContentVersion: null });
async function ready() { const result = setup(); await result.store.load(); return result; }
async function payload(store) { const { snapshot } = await store.getSnapshot(); return (await store.exportSnapshot(snapshot.revision)).payload; }
test('manifest byte limit accepts 2048 and rejects 2049 without resetting stored data', async () => {
  const { s, store } = await ready();
  const manifest = s.map.get(K.m);
  s.map.set(K.m, manifest + ' '.repeat(2048 - Buffer.byteLength(manifest)));
  assert.equal((await store.load()).writable, true);
  assert.equal((await store.exportSnapshot('0')).status, 'OK');
  s.map.set(K.m, s.map.get(K.m) + ' ');
  const before = new Map(s.map);
  const loaded = await store.load();
  assert.equal(loaded.writable, false);
  assert.equal(loaded.state, 'RECOVERY_REQUIRED');
  assert.equal((await store.mutate('0', save())).status, 'FAILED');
  assert.equal((await store.exportSnapshot('0')).status, 'FAILED');
  assert.deepEqual(s.map, before);
});
function install(s, data) {
  const raw = JSON.stringify(data), m = { format: 'itguide-user-manifest', schemaVersion: 1,
    active: { slot: 'a', revision: data.revision, generationId: data.generationId, sha256: createHash('sha256').update(raw).digest('hex') }, previous: null };
  s.map.clear(); s.map.set(K.a, raw); s.map.set(K.m, JSON.stringify(m));
}
test('initialization, immutable notifications, migration once and exact legacy retention', async () => {
  const raw = '{ "oldIndex": 7 }';
  const { s, store } = setup({ 'itguide.slow': 'true', 'itguide.tab': '"vocab"', 'itguide.builder': raw });
  const events = []; const unsub = store.subscribe(v => events.push(v));
  const result = await store.load();
  assert.equal(result.state, 'READY'); assert.equal(result.snapshot.revision, '0');
  assert.deepEqual(result.snapshot.preferences, { slow: true, tab: 'vocab' });
  assert.equal(result.snapshot.legacyBuilderRaw, raw); assert.equal(s.getItem('itguide.builder'), raw);
  assert.throws(() => { result.snapshot.preferences.slow = false; }, TypeError);
  s.map.set('itguide.slow', 'broken'); await store.load(); assert.equal(events.length, 2);
  unsub(); await store.mutate('0', { type: 'preferences', slow: false }); assert.equal(events.length, 2);
});
for (const bad of ['null', '1', '"true"', 'true trailing']) test('malformed legacy slow preserved: ' + bad, async () => {
  const { s, store } = setup({ 'itguide.slow': bad }); assert.equal((await store.load()).writable, false);
  assert.equal(s.getItem(K.m), null); assert.equal(s.getItem('itguide.slow'), bad);
});
test('save/remove/review/preferences/Builder serialize, preserve orphans and use exact CAS', async () => {
  const { store } = await ready();
  const [one, two] = await Promise.all([store.mutate('0', save()), store.mutate('0', save())]);
  assert.equal(one.status, 'SAVED'); assert.equal(two.code, 'REVISION_CONFLICT');
  assert.equal((await store.mutate('1', { type: 'review', phraseId: 'phrase_orphan', reviewedAtUtc: '2030-06-11T00:00:00Z' })).status, 'SAVED');
  await store.mutate('2', { type: 'review', phraseId: 'phrase_orphan', reviewedAtUtc: '2030-06-12T00:00:00Z' });
  await store.mutate('3', { type: 'preferences', tab: 'phrases', slow: true });
  await store.mutate('4', { type: 'builder', raw: '{"old":1}' });
  const state = await store.getSnapshot(); assert.equal(state.snapshot.progress[0].reviewCount, 2);
  assert.deepEqual(state.snapshot.saved[0].snapshot, { it: 'Ciao', en: 'Hello' });
  assert.equal((await store.mutate('5', { type: 'remove', id: state.snapshot.saved[0].id })).status, 'SAVED');
  assert.equal((await store.getSnapshot()).snapshot.progress.length, 1);
});
test('multiple adapters sharing storage cannot lose a mutation', async () => {
  const { s, store } = await ready(), other = create({ storage: s, crypto: webcrypto, now }); await other.load();
  const results = await Promise.all([store.mutate('0', save()), other.mutate('0', save())]);
  assert.deepEqual(results.map(r => r.status), ['SAVED', 'FAILED']); assert.equal(results[1].code, 'REVISION_CONFLICT');
  assert.equal((await other.getSnapshot()).writable, false); assert.equal((await other.load()).snapshot.revision, '1');
});
test('quota failure leaves authoritative state, slot readback mismatch fails, manifest failure is uncertain', async () => {
  for (const mode of ['quota', 'slot-mismatch', 'manifest-throw', 'manifest-mismatch', 'manifest-read']) {
    const { s, store } = await ready(); const original = s.getItem(K.a); const get = s.getItem;
    s.setItem = (key, value) => {
      if (key === K.b && mode === 'quota') throw new Error('quota');
      if (key === K.m && mode === 'manifest-throw') throw new Error('manifest');
      s.map.set(key, ((mode === 'slot-mismatch' && key === K.b) || (mode === 'manifest-mismatch' && key === K.m)) ? value + 'x' : value);
      if (mode === 'manifest-read' && key === K.m) s.getItem = () => { throw new Error('read'); };
    };
    const result = await store.mutate('0', save());
    assert.equal(result.status, mode.startsWith('manifest') ? 'UNCERTAIN' : 'FAILED', mode);
    s.getItem = get; assert.equal(s.getItem(K.a), original);
    assert.equal((await store.getSnapshot()).snapshot.revision, '0');
    if (result.status === 'UNCERTAIN') assert.equal((await store.mutate('0', save())).status, 'FAILED');
  }
});
test('manifest write followed by thrown error reloads established revision without blind retry', async () => {
  const { s, store } = await ready();
  s.setItem = (k, v) => { s.map.set(k, v); if (k === K.m) throw new Error('uncertain'); };
  assert.equal((await store.mutate('0', save())).status, 'UNCERTAIN');
  assert.equal((await store.load()).snapshot.revision, '1');
});
test('corrupt active falls back visibly read-only; missing manifest and unsupported inactive block', async () => {
  const { s, store } = await ready(); await store.mutate('0', save()); s.map.set(K.b, '{');
  assert.equal((await store.load()).state, 'FALLBACK');
  assert.equal((await store.mutate('0', save())).status, 'FAILED');
  s.map.delete(K.m); assert.equal((await store.load()).state, 'RECOVERY_REQUIRED');
  const newer = setup({ [K.b]: '{"schemaVersion":2}' });
  assert.equal((await newer.store.load()).reason, 'UNSUPPORTED_VERSION'); assert.equal(newer.s.getItem(K.a), null);
});
test('valid active with unknown inactive is never writable; corrupt previous is visible', async () => {
  const { s, store } = await ready(); s.map.set(K.b, '{"schemaVersion":2}');
  assert.equal((await store.load()).reason, 'UNSUPPORTED_VERSION');
  s.map.delete(K.b); await store.load(); await store.mutate('0', save()); s.map.set(K.a, 'bad');
  assert.equal((await store.load()).reason, 'INVALID_PREVIOUS');
});
test('export/import roundtrip requires genuine preview token and local revision', async () => {
  const source = await ready(); await source.store.mutate('0', save('<b>Ciao</b>', 'Hello 😀'));
  await source.store.mutate('1', { type: 'review', phraseId: 'phrase_old', reviewedAtUtc: '2030-06-10T00:00:00Z' });
  const target = await ready(); const raw = await payload(source.store), preview = await target.store.previewImport(raw);
  assert.deepEqual(preview.counts, { saved: 1, progress: 1 });
  assert.equal((await target.store.replaceConfirmed('0', {})).code, 'CONFIRMATION_REQUIRED');
  assert.equal((await target.store.getSnapshot()).snapshot.saved.length, 0); // preview/cancel does not write
  assert.equal((await target.store.replaceConfirmed('0', preview.validatedCandidate)).status, 'SAVED');
  const restored = (await target.store.getSnapshot()).snapshot;
  assert.equal(restored.revision, '1'); assert.notEqual(restored.generationId, JSON.parse(raw).generationId);
  assert.deepEqual(restored.saved, JSON.parse(raw).saved); assert.deepEqual(restored.progress, JSON.parse(raw).progress);
  assert.equal((await target.store.replaceConfirmed('1', preview.validatedCandidate)).code, 'CONFIRMATION_REQUIRED');
  assert.equal((await target.store.exportSnapshot('0')).code, 'REVISION_CONFLICT');
});
test('strict JSON rejects duplicates, invalid Unicode/UTF8, BOM, trailing bytes, depth and nonfinite', async () => {
  const { store } = await ready(); const raw = await payload(store);
  const bad = [raw.replace('"schemaVersion":1', '"schemaVersion":1,"schemaVersion":1'), raw + ' null', '\ufeff' + raw,
    raw.replace('"saved":[]', '"saved":[],"x":"\\ud800"'), raw.replace('"schemaVersion":1', '"schemaVersion":1e999'),
    '['.repeat(25) + '0' + ']'.repeat(25), new Uint8Array([0xc3, 0x28]), new Uint8Array([0xef, 0xbb, 0xbf, 0x7b, 0x7d])];
  for (const value of bad) assert.equal((await store.previewImport(value)).status, 'FAILED');
  assert.equal((await store.previewImport(new TextEncoder().encode(raw))).counts.saved, 0);
});
test('unknown fields, invalid dates, revisions, duplicate identities, count and UTF8 bounds', async () => {
  const { store } = await ready(); const base = JSON.parse(await payload(store));
  const bad = [v => { v.extra = true; }, v => { v.schemaVersion = 2; }, v => { v.updatedAtUtc = '2030-02-30T00:00:00Z'; },
    v => { v.revision = '9223372036854775808'; }, v => { v.revision = '01'; }, v => { v.legacyBuilderRaw = '{"x":"' + '😀'.repeat(2100) + '"}'; },
    v => { v.progress = [{ phraseId: 'phrase_x', reviewCount: 1, lastReviewedAtUtc: v.updatedAtUtc }, { phraseId: 'phrase_x', reviewCount: 2, lastReviewedAtUtc: v.updatedAtUtc }]; }];
  for (const edit of bad) { const data = structuredClone(base); edit(data); assert.equal((await store.previewImport(JSON.stringify(data))).status, 'FAILED'); }
  assert.equal((await store.previewImport(' '.repeat(512 * 1024) + '{}')).code, 'FILE_LIMIT');
  assert.equal((await store.mutate('0', { ...save(), extra: true })).code, 'UNKNOWN_FIELD');
  assert.equal((await store.mutate('0', { type: 'something' })).code, 'UNKNOWN_MUTATION');
  assert.equal((await store.mutate('0', save('😀'.repeat(2000)))).status, 'SAVED');
  assert.equal((await store.mutate('1', save('😀'.repeat(2001)))).status, 'FAILED');
});
test('signed-64 revision exactness and overflow, review count maximum, collection limits', async () => {
  const { s, store } = await ready(); const data = JSON.parse(await payload(store)); data.revision = '9007199254740993'; install(s, data); await store.load();
  assert.equal((await store.mutate(data.revision, save())).revision, '9007199254740994');
  data.revision = '9223372036854775807'; install(s, data); await store.load(); assert.equal((await store.mutate(data.revision, save())).code, 'REVISION_LIMIT');
  data.revision = '0'; data.progress = [{ phraseId: 'phrase_x', reviewCount: 1000000, lastReviewedAtUtc: data.updatedAtUtc }];
  install(s, data); await store.load(); assert.equal((await store.mutate('0', { type: 'review', phraseId: 'phrase_x', reviewedAtUtc: data.updatedAtUtc })).code, 'REVIEW_LIMIT');
  data.progress = Array.from({ length: 500 }, (_, i) => ({ phraseId: 'phrase_' + i, reviewCount: 0, lastReviewedAtUtc: data.updatedAtUtc }));
  install(s, data); await store.load(); assert.equal((await store.mutate('0', { type: 'review', phraseId: 'phrase_new', reviewedAtUtc: data.updatedAtUtc })).code, 'PROGRESS_LIMIT');
  data.saved = Array.from({ length: 200 }, (_, i) => ({ id: 'sav_' + i.toString(16).padStart(32, '0'), sourcePhraseId: null, sourceContentVersion: null, snapshot: { it: 'a', en: 'b' }, createdAtUtc: data.updatedAtUtc }));
  install(s, data); await store.load(); assert.equal((await store.mutate('0', save())).code, 'SAVED_LIMIT');
});
test('wallet JSON cannot replace or export without archive context', async () => {
  const { s, store } = await ready(); const data = JSON.parse(await payload(store)); data.wallet = { generationId: data.generationId, revision: '0' };
  assert.equal((await store.previewImport(JSON.stringify(data))).code, 'ARCHIVE_REQUIRED');
  install(s, data); assert.equal((await store.load()).writable, false);
  assert.equal((await store.exportSnapshot('0')).code, 'ARCHIVE_REQUIRED');
});
test('read failure and interrupted first creation never silently reinitialize', async () => {
  const { s, store } = setup(); s.getItem = () => { throw new Error('read'); }; assert.equal((await store.load()).writable, false);
  assert.equal(s.map.size, 0);
  const orphan = setup({ [K.a]: '{}' }); assert.equal((await orphan.store.load()).state, 'RECOVERY_REQUIRED'); assert.equal(orphan.s.getItem(K.m), null);
});
test('candidate tokens are store-bound, cancelled preview is inert and stale revision refuses replacement', async () => {
  const a = await ready(), b = await ready(); const raw = await payload(a.store);
  const preview = await a.store.previewImport(raw);
  assert.equal((await b.store.replaceConfirmed('0', preview.validatedCandidate)).code, 'CONFIRMATION_REQUIRED');
  await a.store.mutate('0', save());
  assert.equal((await a.store.replaceConfirmed('0', preview.validatedCandidate)).code, 'REVISION_CONFLICT');
  assert.equal((await a.store.getSnapshot()).snapshot.saved.length, 1);
});
test('bounded collections can still exceed total UTF8 limit; failed import writes nothing', async () => {
  const { store } = await ready(); const data = JSON.parse(await payload(store));
  data.saved = Array.from({ length: 200 }, (_, i) => ({ id: 'sav_' + i.toString(16).padStart(32, '0'), sourcePhraseId: null, sourceContentVersion: null,
    snapshot: { it: '😀'.repeat(2000), en: 'a' }, createdAtUtc: data.updatedAtUtc }));
  assert.equal((await store.previewImport(JSON.stringify(data))).code, 'FILE_LIMIT');
  assert.equal((await store.getSnapshot()).snapshot.revision, '0');
});
test('secure generation collisions cannot reuse authority', async () => {
  const s = storage(), crypto = { subtle: webcrypto.subtle, getRandomValues: array => array.fill(1) };
  const store = create({ storage: s, crypto, now }); await store.load();
  assert.equal((await store.mutate('0', save())).code, 'GENERATION_COLLISION');
  assert.equal((await store.getSnapshot()).snapshot.revision, '0');
});
