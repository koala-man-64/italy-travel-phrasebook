(function (root, factory) {
  'use strict';
  const api = factory();
  if (typeof module === 'object' && module.exports) module.exports = api;
  else root.ItalyDestinationGuide = api;
})(typeof globalThis !== 'undefined' ? globalThis : this, function () {
  'use strict';
  const CATEGORIES = Object.freeze({ see: 'See', eat: 'Eat', drink: 'Drink' });
  const text = value => typeof value === 'string' && value.trim().length > 0;
  const assert = (condition, message) => { if (!condition) throw Error(message); };
  const https = value => { try { const url = new URL(value); return url.protocol === 'https:' && !url.username && !url.password; } catch (_) { return false; } };
  function index(rows, label) {
    assert(Array.isArray(rows) && rows.length > 0, 'Missing ' + label);
    const result = new Map();
    for (const row of rows) {
      assert(row && text(row.id) && /^[a-z0-9_-]+$/.test(row.id) && !result.has(row.id), 'Duplicate or invalid ' + label);
      result.set(row.id, row);
    }
    return result;
  }
  function prepare(guide, credits, content) {
    assert(guide && guide.format === 'itguide-destinations' && guide.schemaVersion === 1 &&
      guide.tripId === content.trip.tripId && /^\d{4}-\d{2}-\d{2}$/.test(guide.checkedOn), 'Destination guide mismatch');
    assert(credits && credits.format === 'itguide-photo-credits' && credits.schemaVersion === 1, 'Photo credits mismatch');
    const photos = index(credits.photos, 'photos'), destinations = index(guide.destinations, 'destinations');
    const days = new Map(content.trip.days.map(row => [row.id, row]));
    const events = new Map(content.trip.events.map(row => [row.id, row]));
    const places = new Set(content.trip.places.map(row => row.id));
    const anchors = new Map(content.anchors.days.map(row => [row.dayId, row.legacyAnchor]));
    for (const photo of photos.values()) {
      assert(/^photos\/itinerary\/[a-z0-9-]+\.webp$/.test(photo.path) &&
        Number.isSafeInteger(photo.width) && photo.width > 0 && Number.isSafeInteger(photo.height) && photo.height > 0 &&
        ['alt', 'title', 'creator', 'license', 'changes'].every(key => text(photo[key])) &&
        https(photo.sourceUrl) && https(photo.licenseUrl) && /^[a-f0-9]{64}$/.test(photo.sha256), 'Invalid photo attribution');
    }
    const cardIds = new Set();
    for (const destination of destinations.values()) {
      assert(text(destination.name) && text(destination.intro), 'Missing destination description');
      assert(Array.isArray(destination.placeIds) && destination.placeIds.length > 0 &&
        destination.placeIds.every(id => places.has(id)), 'Broken place reference');
      const cards = index(destination.recommendations, 'recommendations');
      for (const card of cards.values()) {
        assert(!cardIds.has(card.id), 'Duplicate recommendation identity'); cardIds.add(card.id);
        assert(Object.hasOwn(CATEGORIES, card.category) && ['title', 'description', 'tip', 'mapsQuery', 'photoLabel'].every(key => text(card[key])) &&
          photos.has(card.photoId) && Array.isArray(card.sourceUrls) && card.sourceUrls.length > 0 && card.sourceUrls.every(https) &&
          typeof card.alcoholic === 'boolean' && (card.venue === undefined || text(card.venue)), 'Invalid recommendation');
      }
      for (const category of Object.keys(CATEGORIES)) assert([...cards.values()].some(card => card.category === category), 'Missing category');
      assert(Array.isArray(destination.appearances) && destination.appearances.length > 0, 'Missing destination days');
      const seenDays = new Set();
      for (const appearance of destination.appearances) {
        assert(days.has(appearance.dayId) && /^itinerary-day-\d+$/.test(anchors.get(appearance.dayId)) &&
          !seenDays.has(appearance.dayId), 'Broken or duplicate day reference'); seenDays.add(appearance.dayId);
        assert(Array.isArray(appearance.eventIds) && appearance.eventIds.length > 0 &&
          new Set(appearance.eventIds).size === appearance.eventIds.length &&
          appearance.eventIds.every(id => events.get(id)?.dayId === appearance.dayId && days.get(appearance.dayId).eventIds.includes(id)), 'Broken event reference');
        assert(Array.isArray(appearance.recommendationIds) && appearance.recommendationIds.length > 0 &&
          new Set(appearance.recommendationIds).size === appearance.recommendationIds.length &&
          appearance.recommendationIds.every(id => cards.has(id)), 'Broken recommendation reference');
      }
    }
    return { guide, photos, destinations, anchors };
  }
  function make(doc, tag, value, className) {
    const node = doc.createElement(tag);
    if (value !== undefined) node.textContent = value;
    if (className) node.className = className;
    return node;
  }
  function renderCard(doc, card, photo, checkedOn) {
    const article = make(doc, 'article', undefined, 'destination-card');
    const figure = make(doc, 'figure', undefined, 'destination-photo');
    const img = make(doc, 'img');
    for (const [key, value] of Object.entries({ src: photo.path, alt: photo.alt, width: photo.width, height: photo.height, loading: 'lazy', decoding: 'async' })) img.setAttribute(key, String(value));
    const failed = make(doc, 'p', 'Photo unavailable. The recommendation is still available.', 'destination-photo-missing'); failed.hidden = true;
    img.addEventListener('error', () => { img.hidden = true; failed.hidden = false; });
    figure.append(img, failed, make(doc, 'figcaption', card.photoLabel));
    const body = make(doc, 'div', undefined, 'destination-card-body');
    body.append(make(doc, 'h5', card.title), make(doc, 'p', card.description));
    if (card.alcoholic) body.append(make(doc, 'span', 'Contains alcohol', 'destination-alcohol'));
    body.append(make(doc, 'p', card.tip, 'destination-tip'));
    if (card.venue) body.append(make(doc, 'p', card.venue, 'destination-venue'));
    const maps = make(doc, 'a', 'Maps', 'destination-map');
    maps.setAttribute('href', 'https://www.google.com/maps/search/?api=1&query=' + encodeURIComponent(card.mapsQuery));
    maps.setAttribute('aria-label', 'Open Maps for ' + card.title); body.append(maps);
    const credit = make(doc, 'details', undefined, 'destination-credit');
    credit.append(make(doc, 'summary', 'Photo credit & sources'));
    credit.append(make(doc, 'p', photo.title + ' — ' + photo.creator), make(doc, 'p', photo.license + '. ' + photo.changes));
    // External pages stay selectable text: Android intentionally allows only its existing Maps route.
    credit.append(make(doc, 'p', photo.sourceUrl, 'destination-source'), make(doc, 'p', photo.licenseUrl, 'destination-source'));
    credit.append(make(doc, 'p', 'Guide checked: ' + checkedOn + '. Source pages (copy to a browser):'));
    for (const url of card.sourceUrls) credit.append(make(doc, 'p', url, 'destination-source'));
    body.append(credit); article.append(figure, body); return article;
  }
  function renderDestination(doc, prepared, destination, appearance) {
    const section = make(doc, 'details', undefined, 'destination-guide');
    section.id = 'explore-' + destination.id + '-' + appearance.dayId;
    const summary = make(doc, 'summary', undefined, 'destination-summary');
    summary.append(make(doc, 'h4', 'Explore ' + destination.name, 'destination-title'), make(doc, 'span', 'See · Eat · Drink', 'destination-kicker'));
    section.append(summary, make(doc, 'p', destination.intro, 'destination-intro'));
    const cards = destination.recommendations.filter(card => appearance.recommendationIds.includes(card.id));
    for (const [category, label] of Object.entries(CATEGORIES)) {
      const entries = cards.filter(card => card.category === category); if (!entries.length) continue;
      const group = make(doc, 'details', undefined, 'destination-category');
      group.append(make(doc, 'summary', label + ' · ' + entries.length));
      const list = make(doc, 'div', undefined, 'destination-cards');
      for (const card of entries) list.append(renderCard(doc, card, prepared.photos.get(card.photoId), prepared.guide.checkedOn));
      group.append(list); section.append(group);
    }
    section.append(make(doc, 'p', 'Photos and guide work offline. Maps needs a connection. Suggestions are optional; follow your booked times and tour meeting point.', 'destination-notice'));
    return section;
  }
  function mount(document, window, content) {
    const mounted = [], controller = new AbortController(); let disposed = false;
    async function load(name) {
      const response = await window.fetch(name, { signal: controller.signal });
      if (!response.ok) throw Error('Guide asset unavailable');
      const body = await response.text(); if (body.length > 1048576) throw Error('Guide asset too large');
      return JSON.parse(body);
    }
    Promise.all([load('destination-content.json'), load('photo-credits.json')]).then(([guide, credits]) => {
      if (disposed) return;
      const prepared = prepare(guide, credits, content);
      const linked = new Set(), sections = new Map();
      function linkEvent(eventId, destination, section) {
        const key = destination.id + '/' + eventId;
        const eventAnchor = content.anchors.events.find(row => row.eventId === eventId);
        const eventDay = eventAnchor && document.getElementById(eventAnchor.legacyDayAnchor);
        const event = eventDay?.querySelector('.itinerary-events > li[data-event-id="' + eventId + '"]');
        if (!event || linked.has(key)) return;
        const link = make(document, 'a', 'Explore ' + destination.name + ' · See, eat & drink', 'destination-event-link');
        link.setAttribute('href', '#' + section.id);
        link.addEventListener('click', () => { section.open = true; });
        event.append(link); mounted.push(link); linked.add(key);
      }
      for (const destination of prepared.destinations.values()) {
        for (const appearance of destination.appearances) {
          const day = document.getElementById(prepared.anchors.get(appearance.dayId)); if (!day) continue;
          const section = renderDestination(document, prepared, destination, appearance); day.append(section); mounted.push(section);
          if (!sections.has(destination.id)) sections.set(destination.id, section);
          for (const eventId of appearance.eventIds) linkEvent(eventId, destination, section);
        }
      }
      // Return journeys, hotels and stations link to the existing guide instead of repeating it.
      for (const destination of prepared.destinations.values()) {
        const section = sections.get(destination.id); if (!section) continue;
        for (const event of content.trip.events) {
          if (event.placeIds.some(id => destination.placeIds.includes(id))) linkEvent(event.id, destination, section);
        }
      }
    }).catch(() => {
      if (disposed) return;
      // Isolate optional guide failures from Today, booking identities, documents and the itinerary.
      for (const node of mounted.splice(0)) node.remove();
      const firstDay = document.getElementById(content.anchors.days[0]?.legacyAnchor);
      if (firstDay) {
        const notice = make(document, 'p', 'Destination photos and recommendations are unavailable. Your itinerary and bookings remain available.', 'destination-notice');
        firstDay.append(notice); mounted.push(notice);
      }
    });
    return () => { if (disposed) return; disposed = true; controller.abort(); for (const node of mounted.splice(0)) node.remove(); };
  }
  return Object.freeze({ prepare, renderCard, renderDestination, mount });
});
