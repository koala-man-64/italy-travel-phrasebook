'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { prepare, render, mount } = require('../colosseum-guide.js');
const { document: fakeDocument, nodes, flush, defer } = require('./fixtures/3b/dom.cjs');
const root = path.resolve(__dirname, '..');
const read = name => JSON.parse(fs.readFileSync(path.join(root, name), 'utf8'));
const content = read('colosseum-content.json'), credits = read('photo-credits.json');
const allIds = [...new Set([content.heroPhotoId, ...content.stops.flatMap(stop => [stop.photoId, ...stop.gallery.map(photo => photo.photoId)])])];
const button = (host, label) => nodes(host).find(node => node.tagName === 'button' && node.textContent === label);

test('six immersive stops use 15 authentic bundled photos with complete provenance and exact hashes', () => {
  const prepared = prepare(content, credits), policy = read('android/app/asset-boundary.json');
  const worker = fs.readFileSync(path.join(root, 'sw.js'), 'utf8');
  assert.equal(content.stops.length, 6); assert.equal(allIds.length, 15);
  for (const id of allIds) {
    const photo = prepared.photos.get(id), bytes = fs.readFileSync(path.join(root, photo.path));
    assert.equal(bytes.toString('ascii', 0, 4), 'RIFF'); assert.equal(bytes.toString('ascii', 8, 12), 'WEBP');
    assert.equal(crypto.createHash('sha256').update(bytes).digest('hex'), photo.sha256);
    assert.match(photo.license, /CC BY|CC0|public domain/i);
    assert.ok(photo.creator && photo.sourceUrl && photo.licenseUrl && photo.changes);
    assert.ok(policy.applicationAssets.includes(photo.path)); assert.ok(worker.includes("'./" + photo.path + "'"));
  }
  const allPhotos = fs.readdirSync(path.join(root, 'photos/itinerary'));
  assert.ok(allPhotos.reduce((sum, name) => sum + fs.statSync(path.join(root, 'photos/itinerary', name)).size, 0) <= 12 * 1024 * 1024);
  assert.ok(read('trip-content.json').events.some(event => event.id === content.eventId));
  for (const file of ['colosseum-content.json', 'colosseum-guide.js', 'colosseum-guide.css']) {
    assert.ok(policy.applicationAssets.includes(file)); assert.ok(worker.includes("'./" + file + "'"));
  }
});

test('malformed or unsafe guide/photo references are rejected', () => {
  for (const mutate of [
    c => { c.stops[1].id = c.stops[0].id; }, c => { c.stops[0].photoId = 'missing'; },
    c => { c.stops[0].gallery = []; }, c => { c.stops[0].gallery[0].photoId = 'missing'; },
    c => { c.stops[0].lookFor.pop(); }, c => { c.sources[0].url = 'javascript:alert(1)'; },
    c => { c.returnAnchor = '../secret'; }, c => { c.eventId = '" onclick="alert(1)'; }
  ]) { const value = structuredClone(content); mutate(value); assert.throws(() => prepare(value, credits)); }
  const altered = structuredClone(credits); altered.photos.find(photo => photo.id === content.heroPhotoId).path = '../secret.webp';
  assert.throws(() => prepare(content, altered));
});

test('Previous, Next and browse buttons render descriptions with three photos per stop and preserve boundaries', () => {
  const doc = fakeDocument(), host = doc.createElement('div'), opened = [];
  render(host, prepare(content, credits), tab => opened.push(tab));
  assert.equal(button(host, '← Previous').disabled, true);
  for (let i = 0; i < 6; i++) {
    assert.ok(host.textContent.includes(content.stops[i].story));
    assert.equal(nodes(host).filter(node => node.tagName === 'img').length, 4); // hero + three stop photos
    assert.equal(nodes(host).find(node => node.className === 'guide-position').textContent, `Stop ${i + 1} of 6`);
    if (i < 5) button(host, 'Next →').click();
  }
  assert.equal(button(host, 'Next →').disabled, true);
  button(host, '2. Walk through the engineering').click();
  assert.ok(host.textContent.includes(content.stops[1].story));
  button(host, 'Start exploring').click(); assert.equal(button(host, '← Previous').disabled, true);
  button(host, 'Back to your itinerary').click(); assert.deepEqual(opened, ['itinerary']);
  for (const id of allIds) assert.ok(host.textContent.includes(credits.photos.find(photo => photo.id === id).creator));
});

test('photo failure preserves the description and exposes a readable fallback', () => {
  const doc = fakeDocument(), host = doc.createElement('div'); render(host, prepare(content, credits), () => {});
  const image = nodes(host).find(node => node.tagName === 'img');
  for (const handler of image.handlers.get('error')) handler();
  assert.equal(image.hidden, true);
  assert.equal(image.parentNode.children[1].hidden, false);
  assert.ok(host.textContent.includes(content.stops[0].story));
});

test('all content is rendered as text without HTML injection', () => {
  const doc = fakeDocument(), host = doc.createElement('div'), hostile = structuredClone(content);
  hostile.stops[0].story = '<img src=x onerror=alert(1)>';
  render(host, prepare(hostile, credits), () => {});
  assert.ok(host.textContent.includes(hostile.stops[0].story));
  assert.equal(nodes(host).filter(node => node.tagName === 'img').length, 4);
});

function environment(fetch) {
  const doc = fakeDocument(), host = doc.createElement('div'), event = doc.createElement('div'), opened = [];
  doc.getElementById = id => id === 'colosseum-root' ? host : null;
  doc.querySelector = selector => selector === '#colosseum-itinerary-event .itinerary-event' ? event : null;
  return { doc, host, event, opened, win: { fetch } };
}
test('mount attaches the itinerary shortcut and disposal removes only its own UI', async () => {
  const env = environment(async name => ({ ok: true, text: async () => JSON.stringify(name === 'photo-credits.json' ? credits : content) }));
  const dispose = mount(env.doc, env.win, tab => env.opened.push(tab)); await flush();
  button(env.event, 'Open Colosseum guide').click(); assert.deepEqual(env.opened, ['guide']);
  dispose(); assert.equal(env.event.children.length, 0); assert.equal(env.host.children.length, 0);
});
test('load failure is local to the guide, and late results cannot remount a disposed guide', async () => {
  const failed = environment(async () => ({ ok: false })); mount(failed.doc, failed.win, () => {}); await flush();
  assert.ok(failed.host.textContent.includes('Colosseum guide unavailable')); assert.equal(failed.event.children.length, 0);
  const pending = defer(), late = environment(() => pending.promise);
  const dispose = mount(late.doc, late.win, () => {}); dispose();
  pending.resolve({ ok: true, text: async () => '{}' }); await flush();
  assert.equal(late.host.children.length, 0); assert.equal(late.event.children.length, 0);
});
