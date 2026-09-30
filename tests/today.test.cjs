'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { spawnSync } = require('node:child_process');
const Today = require('../today.js');

// Accepted Wave 0 TripContent synthetic fixture, kept local so this suite is
// reproducible without another agent's checkout or private itinerary content.
const fixture = {
  format: 'itguide-trip', schemaVersion: 1, contentVersion: 'synthetic-1',
  tripId: 'trip_demo', timeZone: 'Europe/Rome', title: 'Synthetic city visit',
  days: [
    { id: 'day_arrival', date: '2030-06-10', label: 'Arrival', order: 1, eventIds: ['event_station'] },
    { id: 'day_walk', date: '2030-06-11', label: 'Walk', order: 2, eventIds: ['event_walk'] }
  ],
  events: [
    { id: 'event_station', dayId: 'day_arrival', title: 'Example station', timeLabel: 'After lunch',
      order: 1, exactLocal: null, placeIds: ['place_station'], phraseIds: ['phrase_ticket'] },
    { id: 'event_walk', dayId: 'day_walk', title: 'Example walk', timeLabel: '09:00',
      order: 1, exactLocal: { time: '09:00', offset: '+02:00' }, placeIds: ['place_station'], phraseIds: [] }
  ],
  places: [{ id: 'place_station', name: 'Synthetic station', checkedOn: '2030-01-01' }],
  phrases: [{ id: 'phrase_ticket', version: 1,
    snapshot: { it: 'Un biglietto, per favore.', en: 'One ticket, please.' },
    review: 'draft', context: 'Synthetic fixture; not editorial approval' }]
};
const clone = () => structuredClone(fixture);
function freeze(value) {
  Object.values(value).forEach(item => { if (item && typeof item === 'object') freeze(item); });
  return Object.freeze(value);
}

test('before, during and after select the contracted calendar day', () => {
  for (const [instant, state, id] of [
    ['2030-06-09T12:00:00Z', 'before', 'day_arrival'],
    ['2030-06-10T12:00:00Z', 'during', 'day_arrival'],
    ['2030-06-11T12:00:00Z', 'during', 'day_walk'],
    ['2030-06-12T12:00:00Z', 'after', 'day_walk']
  ]) {
    const result = Today.selectDay(freeze(clone()), instant);
    assert.equal(result.state, state);
    assert.equal(result.day.id, id);
    assert.equal(result.manual, false);
  }
});

test('a missing intermediate date selects the next authored day without inventing events', () => {
  const trip = clone(); trip.days[1].date = '2030-06-12';
  const result = Today.selectDay(trip, '2030-06-11T12:00:00Z');
  assert.equal(result.state, 'gap');
  assert.equal(result.date, '2030-06-11');
  assert.equal(result.day.id, 'day_walk');
  assert.deepEqual(result.events, [trip.events[1]]);
});

test('Rome midnight uses the Italy date even while UTC and a home zone are on yesterday', () => {
  assert.equal(Today.romeDate('2030-06-09T21:59:59Z'), '2030-06-09');
  assert.equal(Today.romeDate('2030-06-09T22:00:00Z'), '2030-06-10');
  assert.equal(Today.selectDay(fixture, '2030-06-09T22:00:00Z').state, 'during');
});

test('spring and autumn DST dates follow Rome rather than adding 24 hours', () => {
  for (const [instant, date] of [
    ['2030-03-30T22:59:59Z', '2030-03-30'], ['2030-03-30T23:00:00Z', '2030-03-31'],
    ['2030-03-31T00:59:59Z', '2030-03-31'], ['2030-03-31T01:00:00Z', '2030-03-31'],
    ['2030-03-31T21:59:59Z', '2030-03-31'], ['2030-03-31T22:00:00Z', '2030-04-01'],
    ['2030-10-26T21:59:59Z', '2030-10-26'], ['2030-10-26T22:00:00Z', '2030-10-27'],
    ['2030-10-27T00:30:00Z', '2030-10-27'], ['2030-10-27T01:30:00Z', '2030-10-27'],
    ['2030-10-27T22:59:59Z', '2030-10-27'], ['2030-10-27T23:00:00Z', '2030-10-28']
  ]) assert.equal(Today.romeDate(instant), date, instant);
});

test('selection is independent of the host time zone', () => {
  const script = `const t = require(${JSON.stringify(path.resolve(__dirname, '../today.js'))}); process.stdout.write(t.romeDate('2030-06-09T22:00:00Z'));`;
  for (const TZ of ['America/Chicago', 'Pacific/Honolulu', 'Asia/Tokyo']) {
    const result = spawnSync(process.execPath, ['-e', script], { encoding: 'utf8', env: { ...process.env, TZ } });
    assert.equal(result.status, 0, result.stderr);
    assert.equal(result.stdout, '2030-06-10', TZ);
  }
});

test('manual selection keeps clock-derived status and rejects unknown stable IDs', () => {
  const result = Today.selectDay(fixture, '2030-06-09T12:00:00Z', 'day_walk');
  assert.equal(result.day.id, 'day_walk');
  assert.equal(result.automaticDay.id, 'day_arrival');
  assert.equal(result.state, 'before');
  assert.equal(result.manual, true);
  assert.throws(() => Today.selectDay(fixture, '2030-06-10T12:00:00Z', 'itinerary-day-1'), /Unknown trip day/);
});

test('calendar and authored event order ignore array positions without mutating content', () => {
  const trip = clone();
  trip.events.push({ ...trip.events[0], id: 'event_early', title: 'Morning activity', timeLabel: 'Morning', order: 0 });
  trip.days[0].eventIds = ['event_station', 'event_early'];
  trip.days.reverse(); trip.events.reverse();
  const before = JSON.stringify(trip);
  const result = Today.selectDay(freeze(trip), '2030-06-10T12:00:00Z');
  assert.equal(result.day.id, 'day_arrival');
  assert.deepEqual(result.events.map(event => event.id), ['event_early', 'event_station']);
  assert.deepEqual(result.events.map(event => event.timeLabel), ['Morning', 'After lunch']);
  assert.equal(JSON.stringify(trip), before);
});

test('invalid instants and empty content fail explicitly instead of showing a false day', () => {
  for (const instant of [undefined, null, 'invalid', new Date(NaN)]) {
    assert.throws(() => Today.romeDate(instant), RangeError);
  }
  assert.throws(() => Today.selectDay({ ...fixture, days: [] }, new Date()), /TripContent/);
});

// Minimal DOM harness exercises lifecycle and text sinks, not browser layout,
// native select accessibility, tab routing or Android behavior.
class Element {
  constructor(tag, doc) {
    this.tagName = tag; this.ownerDocument = doc; this.children = [];
    this.attributes = {}; this.listeners = new Map(); this.parentNode = null; this._text = '';
  }
  set textContent(text) { this._text = String(text); this.replaceChildren(); }
  get textContent() { return this._text + this.children.map(child => child.textContent).join(''); }
  set innerHTML(_) { throw Error('HTML injection is forbidden'); }
  appendChild(child) { child.parentNode = this; this.children.push(child); return child; }
  append(...children) { children.forEach(child => this.appendChild(child)); }
  replaceChildren(...children) { this.children.forEach(child => { child.parentNode = null; }); this.children = []; this.append(...children); }
  remove() { if (this.parentNode) this.parentNode.children = this.parentNode.children.filter(child => child !== this); this.parentNode = null; }
  setAttribute(name, value) { this.attributes[name] = String(value); }
  addEventListener(type, fn) { if (!this.listeners.has(type)) this.listeners.set(type, new Set()); this.listeners.get(type).add(fn); }
  removeEventListener(type, fn) { this.listeners.get(type)?.delete(fn); }
  emit(type) { this.listeners.get(type)?.forEach(fn => fn({ target: this })); }
  focus() { this.ownerDocument.activeElement = this; }
}
function find(root, className) {
  if (root.className === className) return root;
  return root.children.map(child => find(child, className)).find(Boolean);
}
function harness(trip = freeze(clone()), instant = '2030-06-10T12:00:00Z', subscribeOverride) {
  const doc = { createElement: tag => new Element(tag, doc), activeElement: null };
  const mount = new Element('div', doc);
  const sentinel = new Element('p', doc); sentinel.textContent = 'Owner content'; mount.appendChild(sentinel);
  let now = instant, unsubscribeCount = 0;
  const subscribers = new Set(), navigated = [];
  const services = { trip, clock: {
    now: () => now,
    subscribe: subscribeOverride || (fn => { subscribers.add(fn); return () => { unsubscribeCount++; subscribers.delete(fn); }; })
  }, navigation: { openDay: id => navigated.push(id) } };
  const dispose = Today.mount(mount, services);
  return { mount, doc, services, dispose, subscribers, navigated, sentinel,
    get unsubscribeCount() { return unsubscribeCount; },
    el: name => find(mount, `italy-today-${name}`),
    tick(instant) { now = instant; subscribers.forEach(fn => fn()); }
  };
}

test('mount uses accessible controls, authoritative broad time labels and stable-ID navigation', () => {
  const h = harness();
  assert.equal(h.el('select').value, 'day_arrival');
  assert.equal(h.el('select').parentNode.tagName, 'label');
  assert.equal(h.el('status').attributes.role, 'status');
  assert.match(h.el('status').textContent, /Today in Italy: 2030-06-10/);
  assert.equal(h.el('time').textContent, 'After lunch');
  assert.equal(h.el('event').attributes['data-event-id'], 'event_station');
  h.el('open').emit('click');
  assert.deepEqual(h.navigated, ['day_arrival']);
  assert.equal(h.mount.children[0], h.sentinel);
  h.dispose();
});

test('manual selection persists on notifications; Today resets it without replacing focused controls', () => {
  const h = harness();
  const select = h.el('select'); select.focus(); select.value = 'day_walk'; select.emit('change');
  assert.equal(h.el('summary').attributes['data-day-id'], 'day_walk');
  assert.match(h.el('status').textContent, /selected manually/);
  h.tick('2030-06-09T12:00:00Z');
  assert.equal(h.el('select'), select);
  assert.equal(h.doc.activeElement, select);
  assert.equal(select.value, 'day_walk');
  assert.match(h.el('status').textContent, /Trip upcoming/);
  const today = h.el('reset'); today.focus(); today.emit('click');
  assert.equal(select.value, 'day_arrival');
  assert.equal(h.doc.activeElement, today);
  assert.equal(today.attributes['aria-pressed'], 'true');
  assert.doesNotMatch(h.el('status').textContent, /selected manually/);
  h.dispose();
});

test('Rome-midnight/resume notifications refresh the view and retain an unchanged event subtree', () => {
  const h = harness(freeze(clone()), '2030-06-09T21:59:59Z');
  const event = h.el('event');
  h.tick('2030-06-09T22:00:00Z');
  assert.match(h.el('status').textContent, /Today: Arrival/);
  assert.equal(h.el('event'), event);
  h.el('open').focus(); const open = h.el('open');
  h.tick('2030-06-10T22:00:00Z');
  assert.equal(h.el('select').value, 'day_walk');
  assert.equal(h.doc.activeElement, open);
  assert.equal(h.el('open'), open);
  open.emit('click'); assert.deepEqual(h.navigated, ['day_walk']);
  h.tick('2030-06-12T12:00:00Z');
  assert.match(h.el('status').textContent, /Trip ended/);
  h.dispose();
});

test('gap and empty-day summaries avoid implying invented events', () => {
  const trip = clone(); trip.days[1].date = '2030-06-12'; trip.days[1].eventIds = []; trip.events.pop();
  const h = harness(trip, '2030-06-11T12:00:00Z');
  assert.match(h.el('status').textContent, /No itinerary day for today. Next planned day: 2030-06-12/);
  assert.equal(h.el('empty').hidden, false); assert.equal(h.el('events').hidden, true);
  h.dispose();
});

test('content strings use text sinks and cannot add markup or override identity', () => {
  const trip = clone(); trip.days[0].label = '<img src=x onerror=alert(1)>';
  trip.events[0].title = '<script>bad()</script>'; trip.events[0].timeLabel = 'Morning <b>only</b>';
  const h = harness(trip);
  assert.equal(h.el('day-title').textContent, trip.days[0].label);
  assert.equal(h.el('event-title').children.length, 0);
  assert.equal(h.el('event-title').textContent, trip.events[0].title);
  assert.equal(h.el('time').textContent, trip.events[0].timeLabel);
  h.el('open').emit('click'); assert.deepEqual(h.navigated, ['day_arrival']);
  h.dispose();
});

test('disposal removes all listeners and subscriptions, is idempotent and resets the next view session', () => {
  const h = harness();
  const select = h.el('select'), open = h.el('open'), today = h.el('reset');
  const lateNotification = [...h.subscribers][0];
  select.value = 'day_walk'; select.emit('change');
  h.dispose(); h.dispose();
  assert.equal(h.unsubscribeCount, 1); assert.equal(h.subscribers.size, 0);
  assert.deepEqual(h.mount.children, [h.sentinel]);
  open.emit('click'); select.emit('change'); today.emit('click'); lateNotification();
  assert.deepEqual(h.navigated, []);
  const disposeAgain = Today.mount(h.mount, h.services);
  assert.equal(h.el('select').value, 'day_arrival');
  disposeAgain();
});

test('subscription failures remove the partial view, including a late callback', () => {
  let callback;
  const doc = { createElement: tag => new Element(tag, doc) }, mount = new Element('div', doc);
  assert.throws(() => Today.mount(mount, { trip: fixture,
    clock: { now: () => '2030-06-10T12:00:00Z', subscribe: fn => { callback = fn; throw Error('Subscription failed'); } },
    navigation: { openDay() {} }
  }), /Subscription failed/);
  assert.equal(mount.children.length, 0); callback(); assert.equal(mount.children.length, 0);
});

test('browser script exposes only the module API without mounting or accessing storage/bridge globals', () => {
  const context = {};
  vm.runInNewContext(fs.readFileSync(path.resolve(__dirname, '../today.js'), 'utf8'), context);
  assert.deepEqual(Object.keys(context), ['ItalyToday']);
  assert.equal(typeof context.ItalyToday.mount, 'function');
  assert.equal(Object.isFrozen(context.ItalyToday), true);
  const css = fs.readFileSync(path.resolve(__dirname, '../today.css'), 'utf8');
  assert.match(css, /min-height: 44px/);
});
