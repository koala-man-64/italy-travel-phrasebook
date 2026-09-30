(function (root, factory) {
  'use strict';
  const api = factory();
  if (typeof module === 'object' && module.exports) module.exports = api;
  else root.ItalyPlaces = api;
})(typeof globalThis !== 'undefined' ? globalThis : this, function () {
  'use strict';
  function mount(element, { trip, eventId }) {
    const doc = element.ownerDocument;
    const make = (tag, text, cls) => {
      const node = doc.createElement(tag); if (text !== undefined) node.textContent = text;
      if (cls) node.className = cls; return node;
    };
    const section = make('section', undefined, 'italy-places'); section.setAttribute('aria-label', 'Prepared places');
    section.append(make('h4', 'Places for this event'));
    const unavailable = () => section.append(make('p', 'Place details unavailable. Your itinerary remains available.'));
    try {
      const event = trip.events.find(row => row.id === eventId);
      if (!event || !Array.isArray(event.placeIds)) throw Error('MISSING_EVENT');
      for (const placeId of new Set(event.placeIds)) {
        const place = trip.places.find(row => row.id === placeId);
        const contexts = trip.events.filter(row => row.placeIds.includes(placeId)).map(row => ({
          event: row, day: trip.days.find(day => day.id === row.dayId)
        }));
        if (!place || typeof place.name !== 'string' || typeof place.checkedOn !== 'string' ||
          contexts.some(row => !row.day || typeof row.event.title !== 'string' || typeof row.day.label !== 'string')) {
          unavailable(); continue;
        }
        contexts.sort((a, b) => a.day.order - b.day.order || a.event.order - b.event.order);
        const card = make('details', undefined, 'place-card'); card.append(make('summary', place.name));
        card.append(make('p', 'Itinerary source date: ' + place.checkedOn));
        const list = make('ul');
        for (const row of contexts) {
          const item = make('li');
          item.append(make('p', row.day.label + (row.event.timeLabel ? ' · ' + row.event.timeLabel : '')),
            make('p', row.event.title)); list.append(item);
        }
        card.append(list); section.append(card);
      }
      if (!event.placeIds.length) section.append(make('p', 'No prepared place notes for this event.'));
      section.append(make('p', 'Prepared itinerary notes. Check current opening times and transport details separately.', 'place-notice'));
      section.append(make('p', 'Existing Maps links need an internet connection; no offline maps are included.', 'place-notice'));
    } catch (_) { unavailable(); }
    element.append(section);
    let disposed = false;
    return () => { if (!disposed) { disposed = true; section.remove(); } };
  }
  return Object.freeze({ mount });
});
