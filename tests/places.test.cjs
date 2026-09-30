'use strict';
const test = require('node:test'), assert = require('node:assert/strict');
const { mount } = require('../places.js');
const { document, nodes } = require('./fixtures/3b/dom.cjs');
const source = require('../trip-content.json');
function fixture(trip = structuredClone(source), eventId = 'event_02fa46067011') {
  const doc = document(), host = doc.createElement('div'), keep = doc.createElement('p'); host.append(keep);
  const dispose = mount(host, { trip, eventId }); return { host, dispose, keep };
}
test('authored place summary includes source date and connectivity limits without links or invented facts', () => {
  const h = fixture(); assert.match(h.host.textContent, /Pantheon entry/); assert.match(h.host.textContent, /5–6 PM/);
  assert.match(h.host.textContent, /Itinerary source date: 2026-09-27/); assert.match(h.host.textContent, /Maps links need an internet connection/);
  assert.equal(nodes(h.host).filter(n => ['a', 'img', 'iframe'].includes(n.tagName)).length, 0);
  h.dispose(); h.dispose(); assert.deepEqual(h.host.children, [h.keep]);
});
test('hostile authored text remains literal; all current events mount without missing references', () => {
  const trip = structuredClone(source); trip.places.find(p => p.id === 'place_c50ffb7dff15').name = '<img onerror=alert(1)>';
  assert.match(fixture(trip).host.textContent, /<img onerror=alert\(1\)>/);
  for (const event of source.events) assert.doesNotMatch(fixture(source, event.id).host.textContent, /details unavailable/);
});
test('missing place, day and event fail visibly without changing source', () => {
  const trip = structuredClone(source); trip.places = [];
  assert.match(fixture(trip).host.textContent, /details unavailable/);
  const noDays = structuredClone(source); noDays.days = []; assert.match(fixture(noDays).host.textContent, /details unavailable/);
  assert.match(fixture(source, 'event_missing').host.textContent, /details unavailable/);
});
