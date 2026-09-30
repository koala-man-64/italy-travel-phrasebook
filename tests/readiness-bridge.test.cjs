const { test } = require('node:test');
const assert = require('node:assert/strict');
const { createProtocol, createSpeechReadiness } = require('../conversation.js');

// Admission itself belongs to the stream's real projector tests. This fixture
// isolates owner lifecycle/correlation logic without copying that validator.
function projectCapabilities(raw, options) {
  const frame = typeof raw === 'string' ? JSON.parse(raw) : raw;
  const admitted = options.fresh && frame && frame.seq > options.lastSequence;
  return Object.freeze({ freshness: admitted ? 'current' : options.fresh ? 'unknown' : 'stale',
    source: options.source, sequence: admitted ? frame.seq : null,
    voices: Object.freeze({ it: admitted && frame.ready ? 'reported-ready' : 'unknown' }),
    microphonePermission: 'unknown', offlineDeviceProof: 'unverified' });
}
function fixture() {
  const sent = [];
  const command = createProtocol(message => sent.push(JSON.parse(message)));
  let origin = 'OfflineConversation-v1', setupCount = 0, status = () => command('status');
  const service = createSpeechReadiness({ projectCapabilities, source: () => origin,
    requestStatus: () => status(), openSetup: () => { setupCount++; } });
  return { service, sent, command, setSource(value) { origin = value; },
    setStatus(value) { status = value; }, get setupCount() { return setupCount; } };
}
const frame = (seq, ack = 0, epoch = 1) => ({ seq, ack, sessionEpoch: epoch, ready: true,
  text: 'private conversation', translation: 'not for readiness subscribers' });

test('subscribers receive only capability projections; unsubscribe and listener isolation work', () => {
  const { service } = fixture(), seen = [];
  service.facade.subscribe(() => { throw new Error('view failed'); });
  const unsubscribe = service.facade.subscribe(value => seen.push(value));
  assert.equal(seen[0].freshness, 'unknown');
  assert.equal(service.accept(JSON.stringify(frame(1))), true);
  assert.equal(seen.at(-1).voices.it, 'reported-ready');
  assert.equal(JSON.stringify(seen).includes('private conversation'), false);
  assert.equal(JSON.stringify(seen).includes('translation'), false);
  unsubscribe(); unsubscribe();
  service.accept(frame(2));
  assert.equal(seen.length, 2);
});

test('refresh uses the single existing request counter without replacing conversation operations', () => {
  const f = fixture();
  const recording = f.command('start', { source: 'it' });
  assert.equal(f.service.facade.refresh(), true);
  const stopping = f.command('stop');
  assert.deepEqual(f.sent.map(({ id, op }) => ({ id, op })),
    [{ id: 1, op: 'start' }, { id: 2, op: 'status' }, { id: 3, op: 'stop' }]);
  assert.equal(recording, 1); assert.equal(stopping, 3);
  assert.equal(f.service.facade.getSnapshot().freshness, 'stale');
  assert.equal(f.service.accept(frame(1, 1)), false);
  assert.equal(f.service.accept(frame(2, 2)), true);
});

test('background and resume fence delayed frames until a new status request is acknowledged', () => {
  const f = fixture();
  f.service.accept(frame(1));
  f.service.suspend();
  assert.equal(f.service.facade.getSnapshot().freshness, 'stale');
  assert.equal(f.service.accept(frame(2)), false);
  assert.equal(f.service.facade.refresh(), false);
  assert.equal(f.service.resume(), true);
  assert.equal(f.service.accept(frame(3, 0, 2)), false);
  assert.equal(f.service.accept(frame(4, 1, 2)), true);
  assert.equal(f.service.facade.getSnapshot().freshness, 'current');
});

test('old epochs and stale dispatcher sequence cannot become current reports', () => {
  const { service } = fixture();
  service.accept(frame(8, 0, 4));
  assert.equal(service.accept(frame(9, 0, 3)), false);
  assert.equal(service.facade.getSnapshot().sequence, 8);
  assert.equal(service.accept(frame(10, 0, 4), 10), false);
  assert.equal(service.facade.getSnapshot().freshness, 'unknown');
  assert.equal(service.accept(frame(11, 0, 4), 10), true);
});

test('unsupported sources cannot refresh/setup or reuse a formerly current native report', () => {
  const f = fixture();
  f.service.accept(frame(1));
  f.setSource('web');
  assert.equal(f.service.facade.getSnapshot().freshness, 'unknown');
  assert.equal(f.service.facade.refresh(), false);
  assert.equal(f.service.facade.openSetup(), false);
  assert.equal(f.sent.length, 0); assert.equal(f.setupCount, 0);
});

test('setup requires an explicit facade action and never starts model preparation itself', () => {
  const f = fixture();
  f.service.facade.subscribe(() => {});
  f.service.accept(frame(1));
  assert.equal(f.setupCount, 0);
  assert.equal(f.service.facade.openSetup(), true);
  assert.equal(f.setupCount, 1);
  assert.deepEqual(f.sent, []);
});

test('failed status dispatch keeps reports non-current and rejects late responses', () => {
  const f = fixture();
  f.service.accept(frame(1));
  f.setStatus(() => { throw new Error('bridge unavailable'); });
  assert.equal(f.service.facade.refresh(), false);
  assert.equal(f.service.facade.getSnapshot().freshness, 'stale');
  assert.equal(f.service.accept(frame(2, 20)), false);
});

test('reentrant frames during status dispatch cannot bypass the not-yet-known ack fence', () => {
  const f = fixture();
  f.setStatus(() => { assert.equal(f.service.accept(frame(2, 0)), false); return 7; });
  assert.equal(f.service.facade.refresh(), true);
  assert.equal(f.service.accept(frame(3, 6)), false);
  assert.equal(f.service.accept(frame(4, 7)), true);
});

test('disposal clears subscribers and permanently rejects frames, actions and resumed callbacks', () => {
  const f = fixture(), seen = [];
  f.service.facade.subscribe(value => seen.push(value));
  f.service.accept(frame(1));
  f.service.dispose();
  const count = seen.length;
  f.service.dispose(); f.service.resume(); f.service.accept(frame(2));
  f.service.facade.subscribe(() => { throw new Error('not called'); });
  assert.equal(f.service.facade.refresh(), false);
  assert.equal(f.service.facade.openSetup(), false);
  assert.equal(seen.length, count);
  assert.equal(f.service.facade.getSnapshot().freshness, 'stale');
});
