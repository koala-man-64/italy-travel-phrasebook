const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { projectCapabilities, projectSnapshot, mount } = require('../speech-readiness.js');

// Selected-1.9 fixture: docs/wave-0-contracts/fixtures/audio19-state.json.
// Kept local so this module's tests need no other checkout or runtime dependency.
function raw(fields = {}) {
  return { v: 1, event: 'state', seq: 8, ack: 5, requestId: 4, sessionEpoch: 2,
    state: 'result', source: 'it', text: 'Buongiorno.', translation: 'Good morning.',
    machineTranslated: true, canReplay: true, models: { it: true, en: true },
    translationReady: true, voices: { it: true, en: false }, progress: '', error: '', recordingMs: 0, ...fields };
}
function expected(fields = {}) {
  return { schemaVersion: 1, source: 'OfflineConversation-v1', protocolBaseline: 'selected-1.9',
    freshness: 'current', sequence: 8, voices: { it: 'reported-ready', en: 'reported-unavailable' },
    recognitionModels: { it: 'reported-ready', en: 'reported-ready' }, translationModels: 'reported-ready',
    microphonePermission: 'unknown', offlineDeviceProof: 'unverified', ...fields };
}
function assertUnknown(value, freshness = 'unknown') {
  assert.equal(value.freshness, freshness);
  assert.equal(value.sequence, null);
  assert.deepEqual(value.voices, { it: 'unknown', en: 'unknown' });
  assert.deepEqual(value.recognitionModels, { it: 'unknown', en: 'unknown' });
  assert.equal(value.translationModels, 'unknown');
  assert.equal(value.microphonePermission, 'unknown');
  assert.equal(value.offlineDeviceProof, 'unverified');
}

test('accepted selected-1.9 fixture projects to the accepted SpeechCapabilities fixture', () => {
  assert.deepEqual(projectCapabilities(raw()), expected());
  assert.deepEqual(projectCapabilities(JSON.stringify(raw())), expected());
  assert.deepEqual(projectSnapshot(expected()), expected());
});

test('voice, recognition and translation reports remain independent', () => {
  const value = projectCapabilities(raw({ models: { it: false, en: false }, translationReady: false }));
  assert.equal(value.voices.it, 'reported-ready');
  assert.deepEqual(value.recognitionModels, { it: 'reported-unavailable', en: 'reported-unavailable' });
  assert.equal(value.translationModels, 'reported-unavailable');
  const missingVoice = projectCapabilities(raw({ voices: { it: false, en: true } }));
  assert.equal(missingVoice.voices.it, 'reported-unavailable');
  assert.equal(missingVoice.recognitionModels.it, 'reported-ready');
});

test('freshness invalidation and duplicate/out-of-order sequence never retain readiness', () => {
  assertUnknown(projectCapabilities(raw(), { fresh: false }), 'stale');
  for (const lastSequence of [8, 9, -1, 0.5, '7', NaN, Infinity, Number.MAX_SAFE_INTEGER + 1]) {
    assertUnknown(projectCapabilities(raw(), { lastSequence }));
  }
  assert.equal(projectCapabilities(raw(), { lastSequence: 7 }).freshness, 'current');
  assertUnknown(projectCapabilities(raw(), { fresh: 'true' }));
  assertUnknown(projectCapabilities(undefined));
});

test('unsupported sources and historical baselines do not acquire native readiness', () => {
  for (const source of ['web', 'legacy-native', 'none', 'future', null]) assertUnknown(projectCapabilities(raw(), { source }));
  for (const protocolBaseline of ['historical-1.8', 'unknown', 'selected-2.0', null]) assertUnknown(projectCapabilities(raw(), { protocolBaseline }));
});

test('all selected state fields are required; unknown root and nested fields fail closed', () => {
  for (const key of Object.keys(raw())) {
    const value = raw(); delete value[key]; assertUnknown(projectCapabilities(value));
  }
  assertUnknown(projectCapabilities(raw({ extra: true })));
  assertUnknown(projectCapabilities(raw({ voices: { it: true, en: true, extra: true } })));
  assertUnknown(projectCapabilities(raw({ models: { it: true } })));
});

test('malformed booleans are unknown instead of being coerced to unavailable', () => {
  for (const value of [null, undefined, 0, 1, '', 'false', [], {}]) {
    for (const key of ['translationReady', 'machineTranslated', 'canReplay']) assertUnknown(projectCapabilities(raw({ [key]: value })));
    assertUnknown(projectCapabilities(raw({ voices: { it: value, en: true } })));
    assertUnknown(projectCapabilities(raw({ models: { it: true, en: value } })));
  }
});

test('version, event, source and state require exact supported values', () => {
  for (const fields of [{ v: '1' }, { v: 2 }, { event: 'ready' }, { source: 'IT' }, { state: 'success' }]) {
    assertUnknown(projectCapabilities(raw(fields)));
  }
});

test('selected correlation values are bounded without converting acknowledgement into success', () => {
  for (const fields of [{ seq: 0 }, { seq: -1 }, { seq: 1.2 }, { ack: 2147483648 },
    { requestId: 6 }, { requestId: -1 }, { sessionEpoch: -1 }, { sessionEpoch: 0.1 },
    { recordingMs: -1 }, { recordingMs: 0.1 }, { recordingMs: Number.MAX_SAFE_INTEGER + 1 }]) {
    assertUnknown(projectCapabilities(raw(fields)));
  }
  assert.equal(projectCapabilities(raw({ requestId: 0, ack: 0 })).freshness, 'current');
  assert.equal(projectCapabilities(raw({ seq: Number.MAX_SAFE_INTEGER, sessionEpoch: Number.MAX_SAFE_INTEGER })).freshness, 'current');
});

test('permission, recording, playing, prepared result and acknowledgement prove no device outcome', () => {
  for (const state of ['permission', 'recording', 'playing', 'result', 'error']) {
    const value = projectCapabilities(raw({ state, ack: 6, machineTranslated: false }));
    assert.equal(value.microphonePermission, 'unknown');
    assert.equal(value.offlineDeviceProof, 'unverified');
    assert.deepEqual(Object.keys(value), Object.keys(expected()));
  }
});

test('text is bounded in UTF-16, progress/error in Unicode scalars; malformed Unicode is rejected', () => {
  for (const key of ['text', 'translation']) {
    assert.equal(projectCapabilities(raw({ [key]: '😀'.repeat(1000) })).freshness, 'current');
    assertUnknown(projectCapabilities(raw({ [key]: '😀'.repeat(1000) + 'a' })));
  }
  for (const key of ['progress', 'error']) {
    assert.equal(projectCapabilities(raw({ [key]: '😀'.repeat(2048) })).freshness, 'current');
    assertUnknown(projectCapabilities(raw({ [key]: 'a'.repeat(2049) })));
  }
  for (const key of ['text', 'translation', 'progress', 'error']) {
    for (const value of ['\ud800', '\udfff', '\ud800a', '\ud800\ud800', null, 123]) assertUnknown(projectCapabilities(raw({ [key]: value })));
  }
});

test('serialized frame bounds apply before parsing and after object admission', () => {
  assertUnknown(projectCapabilities(' '.repeat(32769)));
  assertUnknown(projectCapabilities(' '.repeat(32769) + JSON.stringify(raw())));
  assertUnknown(projectCapabilities(raw({ text: '\u0001'.repeat(2000), translation: '\u0001'.repeat(2000), progress: '\u0001'.repeat(2048), error: '\u0001'.repeat(2048) })));
  assertUnknown(projectCapabilities('{bad json}'));
  assertUnknown(projectCapabilities(JSON.stringify(raw()) + '{}'));
  assertUnknown(projectCapabilities('['.repeat(50)));
});

test('JSON duplicate keys including escaped keys and nested fields are rejected', () => {
  const value = JSON.stringify(raw());
  for (const duplicate of [value.replace('"v":1', '"v":2,"v":1'),
    value.replace('"v":1', '"\\u0076":2,"v":1'),
    value.replace('"it":true', '"it":false,"it":true')]) assertUnknown(projectCapabilities(duplicate));
  assert.equal(projectCapabilities(JSON.stringify(raw({ text: '{"v":2,"v":1} [brackets] \\"' }))).freshness, 'current');
});

test('accessors fail closed and inherited serialization hooks are never evaluated', () => {
  const value = raw();
  Object.defineProperty(value, 'text', { get() { throw Error('must not run'); } });
  assertUnknown(projectCapabilities(value));
  assert.equal(projectCapabilities(Object.assign(Object.create({ toJSON() { throw Error('must not run'); } }), raw())).freshness, 'current');
  assertUnknown(projectCapabilities([]));
  assertUnknown(projectCapabilities(null));
});

test('ordinary cross-realm raw objects and capability DTOs are accepted', () => {
  const input = vm.runInNewContext('JSON.parse(input)', { input: JSON.stringify(raw()) });
  const dto = vm.runInNewContext('JSON.parse(input)', { input: JSON.stringify(expected()) });
  assert.deepEqual(projectCapabilities(input), expected());
  assert.deepEqual(projectSnapshot(dto), expected());
});

test('projection retains no raw/private text, makes a deep frozen copy, and leaves inputs untouched', () => {
  const input = raw({ text: 'PRIVATE TEXT', translation: 'PRIVATE TRANSLATION', progress: 'PRIVATE PROGRESS', error: 'PRIVATE ERROR' });
  const before = JSON.stringify(input), value = projectCapabilities(input);
  assert.equal(JSON.stringify(input), before);
  assert.ok(!JSON.stringify(value).includes('PRIVATE'));
  assert.ok(Object.isFrozen(value) && Object.isFrozen(value.voices) && Object.isFrozen(value.recognitionModels));
  input.voices.it = false;
  assert.equal(value.voices.it, 'reported-ready');
  const dto = expected(), copy = projectSnapshot(dto);
  dto.voices.it = 'reported-unavailable';
  assert.equal(copy.voices.it, 'reported-ready');
  assert.ok(Object.isFrozen(copy) && Object.isFrozen(copy.voices));
});

test('malformed capability DTOs and unsupported versions fail closed', () => {
  for (const key of Object.keys(expected())) {
    const value = expected(); delete value[key]; assertUnknown(projectSnapshot(value));
  }
  for (const fields of [{ schemaVersion: 2 }, { source: 'future' }, { sequence: 0 }, { sequence: null },
    { sequence: NaN }, { freshness: 'fresh' }, { voices: { it: true, en: false } },
    { recognitionModels: { it: 'ready', en: 'unknown' } }, { translationModels: true },
    { microphonePermission: 'granted' }, { offlineDeviceProof: 'verified' }, { transcript: 'private' }]) {
    assertUnknown(projectSnapshot(expected(fields)));
  }
});

test('stale or unsupported DTO readiness is downgraded even if its flags claim ready', () => {
  assertUnknown(projectSnapshot(expected({ freshness: 'stale' })), 'stale');
  assertUnknown(projectSnapshot(expected({ freshness: 'unknown' })));
  assertUnknown(projectSnapshot(expected({ protocolBaseline: 'historical-1.8' })));
  for (const source of ['web', 'legacy-native', 'none']) assertUnknown(projectSnapshot(expected({ source })));
});

function ui(initial = expected(), overrides = {}) {
  class Element {
    constructor(tag, ownerDocument) { this.tagName = tag; this.ownerDocument = ownerDocument; this.children = []; this.handlers = {}; this.attributes = {}; this.hidden = false; this.disabled = false; this.open = false; this.textContent = ''; this.parentNode = null; }
    append(...children) { for (const child of children) { this.children.push(child); child.parentNode = this; } }
    removeChild(child) { this.children.splice(this.children.indexOf(child), 1); child.parentNode = null; }
    setAttribute(key, value) { this.attributes[key] = value; }
    addEventListener(name, handler) { (this.handlers[name] ||= new Set()).add(handler); }
    removeEventListener(name, handler) { this.handlers[name].delete(handler); }
    click() { if (!this.disabled) for (const handler of this.handlers.click || []) handler(); }
  }
  const document = { createElement: tag => new Element(tag, document) };
  const host = document.createElement('div'), sibling = document.createElement('p');
  sibling.textContent = 'Pre-existing host content'; host.append(sibling);
  let snapshot = initial, listener, removed = 0;
  const calls = [];
  const speech = { getSnapshot: () => snapshot,
    subscribe(fn) { listener = fn; return () => { removed++; }; },
    refresh() { calls.push('refresh'); }, openSetup() { calls.push('openSetup'); }, ...overrides };
  const dispose = mount(host, { speech });
  const descendants = () => { const walk = node => [node, ...node.children.flatMap(walk)]; return walk(host); };
  return { host, sibling, speech, calls, dispose, descendants, get removed() { return removed; },
    emit(value) { snapshot = value; listener(value); },
    button(label) { return descendants().find(node => node.tagName === 'button' && node.textContent === label); },
    text() { return descendants().map(node => node.textContent).filter(Boolean).join('\n'); } };
}

test('mount renders independent reports, truthful permission/proof and the Listen prerequisite', () => {
  const view = ui(projectCapabilities(raw({ models: { it: false, en: true }, translationReady: false })));
  assert.match(view.text(), /Italian voice\nReported ready/);
  assert.match(view.text(), /Italian recognition model\nReported unavailable/);
  assert.match(view.text(), /English recognition model\nReported ready/);
  assert.match(view.text(), /Translation models\nReported unavailable/);
  assert.match(view.text(), /does not need recognition or translation models/);
  assert.match(view.text(), /Microphone permission: unknown/);
  assert.match(view.text(), /audible playback: unverified/);
  assert.deepEqual(view.calls, []);
});

test('native disclosure defaults collapsed and subscription updates preserve expansion and focus target', () => {
  const view = ui();
  const details = view.descendants().find(node => node.tagName === 'details');
  const summary = view.descendants().find(node => node.tagName === 'summary');
  assert.equal(details.open, false);
  assert.match(view.text(), /Italian voice reported ready · try Listen/);
  details.open = true;
  view.emit(expected({ freshness: 'stale' }));
  assert.equal(details.open, true);
  assert.equal(view.descendants().find(node => node.tagName === 'summary'), summary);
  assert.match(view.text(), /Status out of date · check again/);
});

test('refresh and setup route only explicit user actions into the injected existing service', () => {
  const view = ui();
  assert.deepEqual(view.calls, []);
  view.button('Check status').click();
  view.button('Open speech setup').click();
  assert.deepEqual(view.calls, ['refresh', 'openSetup']);
});

test('subscription updates status in place and stale lifecycle events remove readiness', () => {
  const view = ui(), button = view.button('Check status');
  const count = view.descendants().length;
  view.emit(expected({ freshness: 'stale' }));
  assert.match(view.text(), /Status is out of date/);
  assert.match(view.text(), /Italian voice\nUnknown/);
  assert.equal(view.button('Check status'), button);
  assert.equal(view.descendants().length, count);
  view.emit(expected());
  assert.match(view.text(), /Italian voice\nReported ready/);
});

test('browser/legacy/historical snapshots explain unavailable reporting and hide native actions', () => {
  for (const initial of [expected({ source: 'web' }), expected({ source: 'legacy-native' }), expected({ protocolBaseline: 'historical-1.8' }), null]) {
    const view = ui(initial);
    assert.match(view.text(), /Native speech readiness is unavailable here/);
    assert.match(view.text(), /Browser speech, if offered, has separate voice availability/);
    assert.equal(view.button('Open speech setup').hidden, true);
    assert.equal(view.button('Check status').hidden, true);
    view.button('Open speech setup').click();
    assert.deepEqual(view.calls, []);
  }
});

test('missing voice remains actionable and unrelated models do not gate setup/status', () => {
  const view = ui(projectCapabilities(raw({ voices: { it: false, en: false }, models: { it: false, en: false }, translationReady: false })));
  assert.match(view.text(), /Italian voice\nReported unavailable/);
  assert.equal(view.button('Open speech setup').hidden, false);
  assert.equal(view.button('Check status').disabled, false);
});

test('malformed updates do not render arbitrary content as text or markup', () => {
  const view = ui();
  view.emit(expected({ translationModels: '<img src=x onerror=alert(1)>' }));
  assert.match(view.text(), /Translation models\nUnknown/);
  assert.ok(!view.text().includes('<img'));
});

test('dispose unsubscribes once, removes only owned descendants and stops old event handlers', () => {
  const view = ui(), button = view.button('Open speech setup');
  view.dispose(); view.dispose();
  assert.equal(view.removed, 1);
  assert.deepEqual(view.host.children, [view.sibling]);
  button.click(); view.emit(expected());
  assert.deepEqual(view.calls, []);
  assert.deepEqual(view.host.children, [view.sibling]);
  const disposeAgain = mount(view.host, { speech: view.speech });
  assert.equal(view.host.children.length, 2);
  disposeAgain(); assert.equal(view.removed, 2);
});

test('failed explicit actions report failure without fabricating updated readiness', async () => {
  const view = ui(expected(), { refresh() { throw Error('private diagnostics'); }, openSetup() { return Promise.reject(Error('private path')); } });
  view.button('Check status').click();
  assert.match(view.text(), /Status check could not be requested/);
  view.button('Open speech setup').click();
  await Promise.resolve();
  assert.match(view.text(), /Speech setup could not be opened/);
  assert.ok(!view.text().includes('private'));
  assert.match(view.text(), /Italian voice\nReported ready/);
});

test('false action returns and late rejected promises after disposal are handled', async () => {
  let reject;
  const view = ui(expected(), { refresh() { return false; }, openSetup() { return new Promise((resolve, fail) => { reject = fail; }); } });
  view.button('Check status').click(); assert.match(view.text(), /Status check could not be requested/);
  view.button('Open speech setup').click(); view.dispose(); reject(Error('late'));
  await Promise.resolve();
  assert.deepEqual(view.host.children, [view.sibling]);
});

test('missing service methods and snapshot/subscription failures degrade without throwing', () => {
  const view = ui(expected(), { refresh: undefined, openSetup: undefined });
  assert.equal(view.button('Check status').hidden, true);
  assert.equal(view.button('Open speech setup').hidden, true);
  for (const overrides of [{ getSnapshot() { throw Error('offline'); } }, { subscribe() { throw Error('offline'); } }]) {
    const failed = ui(expected(), overrides);
    assert.match(failed.text(), /Speech status is unavailable/);
    failed.dispose();
  }
});

test('subscriber cleanup failure still removes UI and disposal remains idempotent', () => {
  const view = ui(expected(), { subscribe() { return () => { throw Error('cleanup failure'); }; } });
  assert.throws(view.dispose, /cleanup failure/);
  assert.deepEqual(view.host.children, [view.sibling]);
  assert.doesNotThrow(view.dispose);
});

test('browser script exports the same facade without mounting, storage, or bridge access', () => {
  const context = vm.createContext({});
  vm.runInContext(fs.readFileSync(path.join(__dirname, '..', 'speech-readiness.js'), 'utf8'), context);
  assert.deepEqual(Object.keys(context.ItalySpeechReadiness), ['projectCapabilities', 'projectSnapshot', 'mount']);
  assert.equal(vm.runInContext('ItalySpeechReadiness.projectCapabilities().microphonePermission', context), 'unknown');
  assert.equal(Object.keys(context).length, 1);
});
