'use strict';
// Frozen itinerary regression: reads pinned fixtures and writes nothing.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { execFileSync } = require('node:child_process');
const root = path.resolve(__dirname, '..');
const contracts = process.env.TRIP_CONTRACT_DIR ||
  path.join(__dirname, 'fixtures', 'trip-contracts');
const sourceDir = process.env.TRIP_G0_SOURCE_DIR ||
  path.join(__dirname, 'fixtures', 'trip-source');
const digest = value => crypto.createHash('sha256').update(value).digest('hex');
const contractPins = {
  'validate.cjs': '83b47cdbaa8c12d834c13944695d833e226c16a417a141b0f61102294b2bf2d8',
  'contracts.schema.json': '7d7b5390ae8a0971c794788f80e024c46efd02bd19fdf8e2ad066ab019d28b1f'
};
// Verify before loading. Missing or changed authority fails rather than skipping.
for (const [name, hash] of Object.entries(contractPins)) {
  assert.equal(digest(fs.readFileSync(path.join(contracts, name))), hash, name);
}
const { validate, strictJson, TRIP_LIMIT } = require(path.join(contracts, 'validate.cjs'));
const tripBytes = fs.readFileSync(path.join(root, 'trip-content.json'));
const trip = strictJson(tripBytes, TRIP_LIMIT);
const anchors = strictJson(fs.readFileSync(path.join(root, 'trip-content-anchors.json')), TRIP_LIMIT);
const clone = value => structuredClone(value);
const sorted = values => [...values].sort();
const unique = values => assert.equal(new Set(values).size, values.length);
const keys = (value, expected) => assert.deepEqual(Object.keys(value).sort(), expected.sort());
const byId = (values, id) => {
  const found = values.find(value => value.id === id);
  assert.ok(found, 'Missing referenced ID');
  return found;
};

function validateAdapter(data, map) {
  validate('TripContent', data);
  keys(map, ['format', 'schemaVersion', 'tripId', 'contentVersion', 'source', 'days', 'events', 'places', 'transportPack']);
  assert.equal(map.format, 'itguide-trip-anchors');
  assert.equal(map.schemaVersion, 1);
  assert.equal(map.tripId, data.tripId);
  assert.equal(map.contentVersion, data.contentVersion);
  keys(map.source, ['archiveSha256', 'indexSha256', 'member', 'extractedOn']);
  assert.equal(map.source.member, 'index.html');
  assert.equal(map.source.extractedOn, '2026-09-27');
  for (const field of ['archiveSha256', 'indexSha256']) assert.match(map.source[field], /^[0-9a-f]{64}$/);
  for (const [table, field] of [['days', 'dayId'], ['events', 'eventId'], ['places', 'placeId']]) {
    unique(map[table].map(item => item[field]));
    assert.deepEqual(sorted(map[table].map(item => item[field])), sorted(data[table].map(item => item.id)));
  }
  unique(map.days.map(day => day.legacyAnchor));
  unique(map.days.map(day => day.legacyTitleAnchor));
  for (const day of map.days) {
    keys(day, ['dayId', 'legacyAnchor', 'legacyTitleAnchor']);
    assert.match(day.legacyAnchor, /^itinerary-day-[1-8]$/);
    assert.equal(day.legacyTitleAnchor, day.legacyAnchor.replace('-day-', '-title-'));
  }
  unique(map.events.map(event => event.legacyDayAnchor + '/' + event.legacyEventOrdinal));
  for (const row of map.events) {
    keys(row, ['eventId', 'dayId', 'legacyDayAnchor', 'legacyEventOrdinal', 'sourceText']);
    const event = byId(data.events, row.eventId);
    assert.equal(row.dayId, event.dayId);
    assert.equal(row.legacyDayAnchor, map.days.find(day => day.dayId === row.dayId).legacyAnchor);
    assert.equal(row.legacyEventOrdinal, event.order + 1);
    assert.ok(Number.isInteger(row.legacyEventOrdinal) && row.legacyEventOrdinal >= 1 && row.legacyEventOrdinal <= 50);
    assert.equal(row.sourceText, event.title);
  }
  unique(map.places.map(place => place.sourceHref));
  for (const place of map.places) {
    keys(place, ['placeId', 'sourceHref', 'sourceNames']);
    const url = new URL(place.sourceHref);
    assert.equal(url.origin, 'https://www.google.com');
    assert.equal(url.pathname, '/maps/search/');
    assert.equal(url.searchParams.get('api'), '1');
    assert.ok(url.searchParams.get('query'));
    assert.ok(place.sourceNames.length > 0 && place.sourceNames.length <= 10);
    unique(place.sourceNames);
    for (const name of place.sourceNames) assert.ok(typeof name === 'string' && name.length > 0 && name.length <= 200);
    assert.ok(place.sourceNames.includes(byId(data.places, place.placeId).name));
  }
  const pack = map.transportPack;
  keys(pack, ['id', 'title', 'authoringStatus', 'independentReviewStatus', 'fluentHumanReviewStatus', 'reviewEvidence', 'prompts']);
  assert.equal(pack.id, 'transport_wave1');
  assert.equal(pack.title, 'Getting around');
  assert.equal(pack.authoringStatus, 'ai-authored');
  assert.equal(pack.independentReviewStatus, 'complete-ai-assisted');
  assert.equal(pack.fluentHumanReviewStatus, 'not-performed');
  assert.equal(pack.reviewEvidence.kind, 'independent-ai-bilingual-text-review');
  assert.equal(pack.reviewEvidence.utterancesSha256, '504eec8e9a639b999ccf674a8a6815a024a99965551c61d69062c6a06d0a7745');
  assert.equal(pack.reviewEvidence.utterancesSha256, digest(JSON.stringify({
    phrases: data.phrases.map(({id,snapshot,context}) => ({id,snapshot,context})).sort((a,b)=>a.id.localeCompare(b.id)),
    prompts: [...pack.prompts].sort((a,b)=>a.phraseId.localeCompare(b.phraseId))
  })));
  assert.equal(pack.prompts.length, 6);
  const all = [];
  for (const prompt of pack.prompts) {
    keys(prompt, ['phraseId', 'exampleReplyIds', 'exampleLabel']);
    assert.equal(prompt.exampleLabel, 'Example reply — illustrative only');
    assert.equal(prompt.exampleReplyIds.length, 1);
    all.push(prompt.phraseId, ...prompt.exampleReplyIds);
    const phrase = byId(data.phrases, prompt.phraseId);
    assert.equal(phrase.review, 'reviewed');
    assert.match(phrase.snapshot.it, /\?$/);
    assert.match(phrase.snapshot.en, /\?$/);
    for (const replyId of prompt.exampleReplyIds) {
      const reply = byId(data.phrases, replyId);
      assert.equal(reply.review, 'reviewed');
      assert.match(reply.context, /^Example reply only\. Illustrative language,/);
    }
  }
  unique(all);
  assert.deepEqual(sorted(all), sorted(data.phrases.map(phrase => phrase.id)));
  const promptIds = new Set(pack.prompts.map(prompt => prompt.phraseId));
  for (const event of data.events) for (const phraseId of event.phraseIds) assert.ok(promptIds.has(phraseId));
  for (const phraseId of promptIds) assert.ok(data.events.some(event => event.phraseIds.includes(phraseId)));
}

// Independent non-executing parse of the exact approved HTML; no private-source eval.
function readFrozenSource() {
  const script = String.raw`
import hashlib, html, json, re, sys
from pathlib import Path
from html.parser import HTMLParser
folder = Path(sys.argv[1])
# Publish only the exact HTML needed by this regression, not the private archive
# containing internal work notes. Its original byte hash stays pinned below.
raw = (folder / 'index.html').read_bytes()
class Text(HTMLParser):
    def __init__(self):
        super().__init__(); self.parts = []
    def handle_starttag(self, tag, attrs):
        if tag == 'span' and dict(attrs).get('class') == 'detail': self.parts.append(' ')
    def handle_data(self, data): self.parts.append(data)
def text(value):
    p = Text(); p.feed(value); return re.sub(r'\s+', ' ', ''.join(p.parts)).strip()
days = []
for anchor, block in re.findall(r'<article class="card itinerary-day" id="([^"]+)"(.*?)</article>', raw.decode(), re.S):
    day = {'anchor': anchor, 'date': re.search(r'datetime="([^"]+)"', block)[1],
           'label': text(re.search(r'<h3[^>]*>(.*?)</h3>', block, re.S)[1]), 'events': []}
    for row in re.findall(r'<li>(.*?)</li>', block, re.S):
        label = re.search(r'<span class="itinerary-time">(.*?)</span>', row, re.S)[1]
        content = re.search(r'<div class="itinerary-event">(.*?)</div>', row, re.S)[1]
        places = [{'href': html.unescape(href), 'name': text(name)}
                  for href, name in re.findall(r'<a class="map-link" href="([^"]+)">(.*?)</a>', content, re.S)]
        day['events'].append({'timeLabel': text(label), 'sourceText': text(content), 'places': places})
    days.append(day)
print(json.dumps({'indexSha256': hashlib.sha256(raw).hexdigest(), 'days': days}))
`;
  return JSON.parse(execFileSync(process.env.TRIP_PYTHON || 'python',
    ['-X', 'utf8', '-B', '-c', script, sourceDir], { encoding: 'utf8', maxBuffer: 1024 * 1024 }));
}

test('private data accepts the pinned G0 contract and adapter', () => {
  assert.ok(tripBytes.length <= TRIP_LIMIT);
  validateAdapter(trip, anchors);
});
test('eight dates and all order fields match the authored itinerary', () => {
  assert.deepEqual(trip.days.map(day => day.date), [
    '2026-09-29', '2026-09-30', '2026-10-01', '2026-10-02',
    '2026-10-03', '2026-10-04', '2026-10-05', '2026-10-06'
  ]);
  assert.deepEqual(trip.days.map(day => day.order), [0, 1, 2, 3, 4, 5, 6, 7]);
  assert.deepEqual(trip.days.map(day => day.eventIds.length), [5, 4, 5, 5, 4, 2, 4, 1]);
  for (const day of trip.days) {
    assert.deepEqual(day.eventIds.map(id => byId(trip.events, id).order),
      Array.from({ length: day.eventIds.length }, (_, index) => index));
  }
});
test('all frozen facts, broad labels, source links and aliases survive extraction', () => {
  const source = readFrozenSource();
  assert.equal(source.indexSha256, '55946e482b1f4eab136201de54aa35c8a618eec27948b1564e3cf79524b54271');
  assert.equal(anchors.source.archiveSha256, 'af4127dd917c61d3fb6b1409e4e7ed79940dfc45dd6151a71f543a216ff88094');
  assert.equal(anchors.source.indexSha256, source.indexSha256);
  assert.equal(source.days.length, trip.days.length);
  const usedPlaces = new Set();
  for (const sourceDay of source.days) {
    const dayMap = anchors.days.find(day => day.legacyAnchor === sourceDay.anchor);
    const day = byId(trip.days, dayMap.dayId);
    assert.equal(day.date, sourceDay.date);
    assert.equal(day.label, sourceDay.label);
    assert.equal(day.eventIds.length, sourceDay.events.length);
    for (const [row, sourceEvent] of sourceDay.events.entries()) {
      const eventMap = anchors.events.find(event => event.legacyDayAnchor === sourceDay.anchor && event.legacyEventOrdinal === row + 1);
      const event = byId(trip.events, eventMap.eventId);
      assert.equal(day.eventIds[row], event.id);
      assert.equal(event.title, sourceEvent.sourceText);
      assert.equal(event.timeLabel, sourceEvent.timeLabel);
      assert.equal(event.exactLocal, null);
      assert.equal(eventMap.sourceText, sourceEvent.sourceText);
      assert.deepEqual(event.placeIds, sourceEvent.places.map(place => {
        const mapping = anchors.places.find(item => item.sourceHref === place.href);
        assert.ok(mapping.sourceNames.includes(place.name));
        usedPlaces.add(mapping.placeId);
        return mapping.placeId;
      }));
    }
  }
  assert.deepEqual(sorted(usedPlaces), sorted(trip.places.map(place => place.id)));
  assert.equal(trip.places.length, 39);
  for (const place of trip.places) assert.equal(place.checkedOn, anchors.source.extractedOn);
});
test('allocated IDs stay fixed and adapters resolve after array reordering', () => {
  const registry = [trip.tripId, ...['days', 'events', 'places', 'phrases'].map(key => sorted(trip[key].map(item => item.id)))];
  assert.equal(digest(JSON.stringify(registry)), '57b1fce4e03738de1100d51aec42a5185209cf3737c1abc7be8149842e27df86');
  for (const id of registry.flat()) assert.match(id, /^(trip|day|event|place|phrase)_[0-9a-f]{12}$/);
  const shuffled = clone(trip);
  for (const key of ['days', 'events', 'places', 'phrases']) shuffled[key].reverse();
  validateAdapter(shuffled, anchors);
  shuffled.days[0].label = 'Changed display label';
  shuffled.events[0].timeLabel = 'Later';
  validate('TripContent', shuffled);
  assert.equal(byId(shuffled.events, trip.events[0].id).id, trip.events[0].id);
});
test('the published identity-to-anchor registry cannot silently repurpose an ID', () => {
  const refs = {
    days: anchors.days,
    events: anchors.events.map(({ eventId, dayId, legacyDayAnchor, legacyEventOrdinal }) =>
      ({ eventId, dayId, legacyDayAnchor, legacyEventOrdinal })),
    places: anchors.places.map(({ placeId, sourceHref }) => ({ placeId, sourceHref })),
    prompts: anchors.transportPack.prompts
  };
  assert.equal(digest(JSON.stringify(refs)), '126e835edc69869854c33fdf9a279a59b5a4960e9b27ec410810c807a52b5daa');
});
for (const [label, mutation] of [
  ['empty day collection', data => { data.days = []; }],
  ['empty title', data => { data.events[0].title = ''; }],
  ['empty translation', data => { data.phrases[0].snapshot.en = ''; }],
  ['negative event order', data => { data.events[0].order = -1; }],
  ['day order overflow', data => { data.days[0].order = 367; }],
  ['event-to-day mismatch', data => { data.events[0].dayId = data.days[1].id; }],
  ['day-to-event omission', data => { data.days[0].eventIds.shift(); }],
  ['unknown event', data => { data.days[0].eventIds[0] = 'event_missing'; }],
  ['unknown place', data => { data.events[0].placeIds[0] = 'place_missing'; }],
  ['unknown phrase', data => { data.events[0].phraseIds[0] = 'phrase_missing'; }],
  ['duplicate ID', data => { data.events[1].id = data.events[0].id; }],
  ['duplicate event order', data => { data.events[1].order = data.events[0].order; }],
  ['duplicate day order', data => { data.days[1].order = data.days[0].order; }],
  ['invalid date', data => { data.days[0].date = '2026-02-30'; }],
  ['unknown field', data => { data.events[0].bookingConfirmed = true; }],
  ['unsupported version', data => { data.schemaVersion = 2; }]
]) {
  test('accepted validator rejects ' + label, () => {
    const broken = clone(trip); mutation(broken);
    assert.throws(() => validate('TripContent', broken));
  });
}
for (const [table, field, limit] of [
  ['days', 'label', 120], ['events', 'title', 200], ['events', 'timeLabel', 80],
  ['places', 'name', 200], ['phrases', 'context', 200]
]) {
  test(table + '.' + field + ' accepts bound and rejects overflow', () => {
    const candidate = clone(trip);
    candidate[table][0][field] = 'x'.repeat(limit);
    validate('TripContent', candidate);
    candidate[table][0][field] += 'x';
    assert.throws(() => validate('TripContent', candidate));
  });
}
test('phrase text and serialized JSON bounds are enforced', () => {
  const candidate = clone(trip);
  candidate.phrases[0].snapshot.it = 'x'.repeat(2000);
  validate('TripContent', candidate);
  candidate.phrases[0].snapshot.it += 'x';
  assert.throws(() => validate('TripContent', candidate));
  assert.throws(() => strictJson(Buffer.alloc(TRIP_LIMIT + 1, 32), TRIP_LIMIT));
  assert.throws(() => strictJson(Buffer.from('{"a":1,"a":2}'), TRIP_LIMIT));
});
test('collection ceilings are enforced', () => {
  for (const [field, count] of [['days', 367], ['events', 1001], ['places', 201], ['phrases', 2001]]) {
    const candidate = clone(trip);
    candidate[field] = Array.from({ length: count }, (_, index) => ({ ...candidate[field][0], id: field.slice(0, -1) + '_' + index }));
    assert.throws(() => validate('TripContent', candidate));
  }
});
for (const [label, mutation] of [
  ['legacy row collision', map => { map.events[1].legacyEventOrdinal = 1; }],
  ['wrong day bridge', map => { map.events[0].legacyDayAnchor = 'itinerary-day-2'; }],
  ['missing day bridge', map => { map.days.pop(); }],
  ['seventh prompt', map => { map.transportPack.prompts.push(clone(map.transportPack.prompts[0])); }],
  ['unlabeled example', map => { map.transportPack.prompts[0].exampleLabel = ''; }],
  ['unknown reply', map => { map.transportPack.prompts[0].exampleReplyIds[0] = 'phrase_missing'; }],
  ['reply as traveler prompt', map => { map.transportPack.prompts[0].phraseId = map.transportPack.prompts[0].exampleReplyIds[0]; }],
  ['unrecorded fluent approval', map => { map.transportPack.fluentHumanReviewStatus = 'approved'; }],
  ['schema drift', map => { map.navigation = {}; }]
]) {
  test('adapter rejects ' + label, () => {
    const broken = clone(anchors); mutation(broken);
    assert.throws(() => validateAdapter(trip, broken));
  });
}
