'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { prepare, renderCard, renderDestination, mount } = require('../destination-guide.js');
const { document: fakeDocument, nodes, flush, defer } = require('./fixtures/3b/dom.cjs');
const root = path.resolve(__dirname, '..');
const read = name => JSON.parse(fs.readFileSync(path.join(root, name), 'utf8'));
const content = { trip: read('trip-content.json'), anchors: read('trip-content-anchors.json') };
const guide = read('destination-content.json');
const photoIds = [...new Set(guide.destinations.flatMap(d => d.recommendations.map(c => c.photoId)))];
const mockCredits = () => ({ format: 'itguide-photo-credits', schemaVersion: 1, photos: photoIds.map(id => ({
  id, path: 'photos/itinerary/' + id + '.webp', width: 800, height: 600, alt: 'Photo of ' + id,
  title: id, creator: 'Test photographer', sourceUrl: 'https://commons.wikimedia.org/wiki/File:Example.jpg',
  license: 'CC BY 4.0', licenseUrl: 'https://creativecommons.org/licenses/by/4.0/', changes: 'Resized, no crop', sha256: 'a'.repeat(64)
})) });

test('seven destination guides cover the trip, retain original identities and provide all categories', () => {
  const original = JSON.stringify(content);
  const prepared = prepare(guide, mockCredits(), content);
  assert.deepEqual([...prepared.destinations.keys()], ['rome','naples','sorrento','pompeii','positano','amalfi','ravello']);
  assert.equal(guide.destinations.reduce((sum, d) => sum + d.recommendations.length, 0), 50);
  const coveredPlaces = new Set(guide.destinations.flatMap(d => d.placeIds));
  for (const place of content.trip.places) assert.ok(coveredPlaces.has(place.id), 'Unlinked place: ' + place.name);
  for (const destination of guide.destinations) {
    for (const category of ['see','eat','drink']) assert.ok(destination.recommendations.filter(c => c.category === category).length >= 2);
    assert.ok(destination.recommendations.some(c => c.category === 'drink' && !c.alcoholic));
    assert.ok(destination.recommendations.some(c => c.alcoholic));
  }
  assert.equal(JSON.stringify(content), original);
  assert.equal(guide.destinations[0].appearances.length, 3);
});

test('real photos have complete rights metadata, exact hashes, WebP bytes, and fit the offline budget', () => {
  const credits = read('photo-credits.json');
  const prepared = prepare(guide, credits, content);
  const policy = read('android/app/asset-boundary.json');
  const worker = fs.readFileSync(path.join(root, 'sw.js'), 'utf8');
  let total = 0;
  for (const id of photoIds) {
    const photo = prepared.photos.get(id), bytes = fs.readFileSync(path.join(root, photo.path));
    assert.equal(bytes.toString('ascii', 0, 4), 'RIFF'); assert.equal(bytes.toString('ascii', 8, 12), 'WEBP');
    assert.equal(crypto.createHash('sha256').update(bytes).digest('hex'), photo.sha256);
    assert.match(photo.license, /CC[- ]?(?:BY|0)|public domain/i);
    assert.ok(policy.applicationAssets.includes(photo.path));
    assert.ok(worker.includes("'./" + photo.path + "'"));
    total += bytes.length;
  }
  assert.ok(total > 10000 && total <= 12 * 1024 * 1024, String(total));
  const allBytes = fs.readdirSync(path.join(root, 'photos/itinerary')).reduce((n, f) => n + fs.statSync(path.join(root, 'photos/itinerary', f)).size, 0);
  assert.ok(allBytes <= 12 * 1024 * 1024);
});

test('mixed trip IDs, foreign days/events/places, unknown photos and unsafe image paths fail validation', () => {
  const mutations = [
    g => { g.tripId = 'other-trip'; },
    g => { g.destinations[0].appearances[0].dayId = 'other-day'; },
    g => { g.destinations[0].appearances[0].eventIds[0] = content.trip.days[7].eventIds[0]; },
    g => { g.destinations[0].placeIds[0] = 'other-place'; },
    g => { g.destinations[0].recommendations[0].photoId = 'missing'; },
    g => { g.destinations[0].appearances[0].recommendationIds.push('missing'); },
    g => { g.destinations.push(structuredClone(g.destinations[0])); },
    g => { g.destinations[0].recommendations[0].sourceUrls = ['javascript:alert(1)']; }
  ];
  for (const mutate of mutations) { const changed = structuredClone(guide); mutate(changed); assert.throws(() => prepare(changed, mockCredits(), content)); }
  for (const bad of ['https://example.com/p.webp', '../secret.webp', 'photos/itinerary/%2e.webp', 'data:image/svg+xml,<svg>']) {
    const credits = mockCredits(); credits.photos[0].path = bad; assert.throws(() => prepare(guide, credits, content), /photo attribution/);
  }
  const incomplete = mockCredits(); incomplete.photos[0].creator = ''; assert.throws(() => prepare(guide, incomplete, content));
});

test('cards render text safely, keep offline attribution and use only the existing Maps action', () => {
  const doc = fakeDocument(), credits = mockCredits();
  const card = { ...guide.destinations[0].recommendations[0], title: '<img src=x onerror=alert(1)>', mapsQuery: 'Rome & Vatican' };
  const output = renderCard(doc, card, credits.photos[0], guide.checkedOn), children = nodes(output);
  assert.ok(output.textContent.includes(card.title));
  const links = children.filter(n => n.tagName === 'a'); assert.equal(links.length, 1);
  const url = new URL(links[0].attrs.href);
  assert.equal(url.origin, 'https://www.google.com'); assert.equal(url.pathname, '/maps/search/');
  assert.equal(url.searchParams.get('api'), '1'); assert.equal(url.searchParams.get('query'), 'Rome & Vatican');
  const image = children.find(n => n.tagName === 'img'); assert.equal(image.attrs.loading, 'lazy');
  assert.equal(image.attrs.width, '800'); assert.equal(image.attrs.height, '600'); assert.ok(image.attrs.alt);
  assert.ok(output.textContent.includes(credits.photos[0].sourceUrl)); assert.ok(output.textContent.includes(credits.photos[0].licenseUrl));
  for (const handler of image.handlers.get('error')) handler();
  assert.equal(image.hidden, true); assert.equal(children.find(n => n.className === 'destination-photo-missing').hidden, false);
  assert.ok(output.textContent.includes(card.tip));
});

test('each Rome day shows only its selected recommendations and all destinations begin compact', () => {
  const prepared = prepare(guide, mockCredits(), content), rome = guide.destinations[0];
  const section = renderDestination(fakeDocument(), prepared, rome, rome.appearances[0]);
  assert.equal(!!section.open, false);
  assert.ok(section.textContent.includes('The Pantheon')); assert.ok(!section.textContent.includes('The Colosseum'));
  assert.equal(nodes(section).filter(n => n.className === 'destination-card').length, 6);
});

function fixture(fetch) {
  const doc = fakeDocument(), dayNodes = new Map(), eventNodes = new Map();
  for (const day of content.anchors.days) {
    const element = doc.createElement('article'); element.append(doc.createElement('p')); dayNodes.set(day.legacyAnchor, element);
  }
  for (const event of content.trip.events) { const node = doc.createElement('li'); node.append(doc.createElement('p')); eventNodes.set(event.id, node); }
  for (const day of content.anchors.days) {
    const eventIds = content.trip.days.find(row => row.id === day.dayId).eventIds;
    dayNodes.get(day.legacyAnchor).querySelector = selector => {
      const id = selector.match(/data-event-id="([^"]+)"/)?.[1];
      return selector.startsWith('.itinerary-events > li') && eventIds.includes(id) ? eventNodes.get(id) : null;
    };
  }
  doc.getElementById = id => dayNodes.get(id);
  doc.querySelector = selector => eventNodes.get(selector.match(/data-event-id="([^"]+)"/)?.[1]);
  return { doc, dayNodes, eventNodes, window: { fetch } };
}
const response = value => ({ ok: true, text: async () => JSON.stringify(value) });

test('mount attaches nine day sections, links all event places, and disposal preserves existing content', async () => {
  const f = fixture(async name => response(name === 'destination-content.json' ? guide : mockCredits()));
  const dispose = mount(f.doc, f.window, content); await flush();
  assert.equal([...f.dayNodes.values()].flatMap(nodes).filter(n => n.className === 'destination-guide').length, 9);
  const finalFlight = f.eventNodes.get(content.trip.days[7].eventIds[0]);
  assert.ok(finalFlight.textContent.includes('Explore Rome'));
  const link = nodes(finalFlight).find(n => n.tagName === 'a');
  const target = [...f.dayNodes.values()].flatMap(nodes).find(n => '#' + n.id === link.attrs.href);
  link.click(); assert.equal(target.open, true);
  for (const node of f.eventNodes.values()) assert.equal(new Set(node.children.filter(n => n.tagName === 'a').map(n => n.attrs.href)).size, node.children.filter(n => n.tagName === 'a').length);
  dispose(); dispose();
  for (const node of [...f.dayNodes.values(), ...f.eventNodes.values()]) assert.equal(node.children.length, 1);
});

test('bad or missing guide content preserves every original itinerary node and shows a scoped fallback', async () => {
  for (const fetch of [async () => { throw Error('offline missing asset'); }, async () => ({ ok: true, text: async () => '{bad json' }), async () => response({})]) {
    const f = fixture(fetch); const originals = [...f.dayNodes.values()].map(n => n.children[0]);
    const dispose = mount(f.doc, f.window, content); await flush();
    assert.ok([...f.dayNodes.values()][0].textContent.includes('Your itinerary and bookings remain available'));
    [...f.dayNodes.values()].forEach((n, i) => assert.equal(n.children[0], originals[i]));
    for (const node of f.eventNodes.values()) assert.equal(node.children.length, 1);
    dispose();
  }
});

test('Today duplicate event IDs never steal itinerary links or remove them when Today changes', async () => {
  const f = fixture(async name => response(name === 'destination-content.json' ? guide : mockCredits()));
  const today = f.doc.createElement('li'); today.setAttribute('data-event-id', content.trip.events[0].id);
  f.doc.querySelector = () => today; // Today appears first in the real document.
  const dispose = mount(f.doc, f.window, content); await flush();
  assert.equal(today.children.length, 0);
  const itineraryEvent = f.eventNodes.get(content.trip.events[0].id);
  assert.ok(itineraryEvent.textContent.includes('Explore Rome'));
  today.remove();
  assert.ok(itineraryEvent.textContent.includes('Explore Rome'));
  dispose();
});

test('late fetch completion after page disposal cannot reattach content', async () => {
  const pending = defer(); const f = fixture(async name => { await pending.promise; return response(name === 'destination-content.json' ? guide : mockCredits()); });
  const dispose = mount(f.doc, f.window, content); dispose(); pending.resolve(); await flush();
  for (const node of [...f.dayNodes.values(), ...f.eventNodes.values()]) assert.equal(node.children.length, 1);
});
