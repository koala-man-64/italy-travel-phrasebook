(function (root, factory) {
  'use strict';
  const api = factory();
  if (typeof module === 'object' && module.exports) module.exports = api;
  else root.ItalyColosseumGuide = api;
})(typeof globalThis !== 'undefined' ? globalThis : this, function () {
  'use strict';
  const text = value => typeof value === 'string' && value.trim().length > 0;
  const need = value => { if (!value) throw Error('Invalid Colosseum guide'); };
  function prepare(content, credits) {
    need(content?.format === 'itguide-colosseum' && content.schemaVersion === 1);
    need(credits?.format === 'itguide-photo-credits' && credits.schemaVersion === 1 && Array.isArray(credits.photos));
    const photos = new Map(credits.photos.map(photo => [photo.id, photo]));
    need(photos.size === credits.photos.length && text(content.title) && text(content.intro));
    need(/^\d{4}-\d{2}-\d{2}$/.test(content.checkedOn) && /^event_[a-f0-9]+$/.test(content.eventId));
    need(/^itinerary-day-\d+$/.test(content.returnAnchor));
    need(Array.isArray(content.stops) && content.stops.length === 6 && new Set(content.stops.map(stop => stop.id)).size === 6);
    for (const stop of content.stops) {
      need(/^[a-z-]+$/.test(stop.id) && ['title', 'where', 'caption', 'story', 'history', 'extra'].every(key => text(stop[key])));
      need(Array.isArray(stop.lookFor) && stop.lookFor.length === 3 && stop.lookFor.every(text));
      need(Array.isArray(stop.gallery) && stop.gallery.length === 2 && stop.gallery.every(photo => text(photo.photoId) && text(photo.caption)));
    }
    for (const id of photoIds(content)) {
      const photo = photos.get(id);
      need(photo && /^photos\/itinerary\/[a-z0-9-]+\.webp$/.test(photo.path));
      need(['alt', 'title', 'creator', 'license', 'changes'].every(key => text(photo[key])));
      need([photo.sourceUrl, photo.licenseUrl].every(url => typeof url === 'string' && /^https:\/\/[^\s]+$/.test(url)));
      need(Number.isSafeInteger(photo.width) && photo.width > 0 && Number.isSafeInteger(photo.height) && photo.height > 0);
      need(/^[a-f0-9]{64}$/.test(photo.sha256));
    }
    need(Array.isArray(content.sources) && content.sources.length > 0 && content.sources.every(source => text(source.title) && /^https:\/\/colosseo\.it\/en\/[^\s]*$/.test(source.url)));
    need(Array.isArray(content.timeline) && content.timeline.length > 0 && content.timeline.every(row => text(row.when) && text(row.text)));
    return { content, photos };
  }
  function photoIds(content) {
    return [...new Set([content.heroPhotoId, ...content.stops.flatMap(stop => [stop.photoId, ...stop.gallery.map(photo => photo.photoId)])])];
  }
  function make(doc, tag, text, className) {
    const node = doc.createElement(tag);
    if (text !== undefined) node.textContent = text;
    if (className) node.className = className;
    return node;
  }
  function button(doc, label, action, className = 'guide-button') {
    const node = make(doc, 'button', label, className); node.type = 'button';
    node.addEventListener('click', action); return node;
  }
  function photoFigure(doc, photo, caption, eager = false) {
    const figure = make(doc, 'figure', undefined, 'guide-photo');
    const image = make(doc, 'img');
    for (const [key, value] of Object.entries({ src: photo.path, alt: photo.alt, width: photo.width, height: photo.height, loading: eager ? 'eager' : 'lazy', decoding: 'async' })) image.setAttribute(key, String(value));
    const fallback = make(doc, 'p', 'Photo unavailable. You can still read everything at this stop.', 'guide-photo-fallback'); fallback.hidden = true;
    image.addEventListener('error', () => { image.hidden = true; fallback.hidden = false; });
    figure.append(image, fallback, make(doc, 'figcaption', caption));
    return figure;
  }
  function render(host, prepared, openTab) {
    const doc = host.ownerDocument, { content, photos } = prepared;
    const hero = make(doc, 'header', undefined, 'guide-hero');
    hero.append(make(doc, 'p', 'ROME / A CLOSER LOOK', 'guide-eyebrow'), make(doc, 'h2', content.title), make(doc, 'p', content.intro));
    hero.append(photoFigure(doc, photos.get(content.heroPhotoId), 'The Colosseum · six things to notice', true));
    const jump = make(doc, 'nav', undefined, 'guide-jump'); jump.setAttribute('aria-label', 'Colosseum stops');
    const stopHost = make(doc, 'section', undefined, 'guide-stop');
    const controls = make(doc, 'div', undefined, 'guide-controls');
    const status = make(doc, 'p', '', 'guide-position'); status.setAttribute('aria-live', 'polite'); status.setAttribute('aria-atomic', 'true');
    let selected = 0; const selectors = [];
    function focusStop() { stopHost.querySelector?.('h3')?.focus({ preventScroll: true }); stopHost.scrollIntoView?.({ block: 'start', behavior: 'instant' }); }
    function show(index, focus = true) {
      selected = Math.max(0, Math.min(content.stops.length - 1, index));
      const stop = content.stops[selected];
      const title = make(doc, 'h3', stop.title); title.tabIndex = -1;
      stopHost.replaceChildren(make(doc, 'p', stop.where, 'guide-eyebrow'), title, photoFigure(doc, photos.get(stop.photoId), stop.caption, true));
      stopHost.append(make(doc, 'h4', 'Look for this'));
      const list = make(doc, 'ul'); for (const line of stop.lookFor) list.append(make(doc, 'li', line));
      stopHost.append(list, make(doc, 'p', stop.story, 'guide-story'));
      const gallery = make(doc, 'div', undefined, 'guide-gallery');
      gallery.append(make(doc, 'h4', 'A closer look'));
      for (const detail of stop.gallery) gallery.append(photoFigure(doc, photos.get(detail.photoId), detail.caption));
      stopHost.append(gallery);
      const history = make(doc, 'details', undefined, 'guide-disclosure'); history.append(make(doc, 'summary', 'More history'), make(doc, 'p', stop.history));
      stopHost.append(history, make(doc, 'p', stop.extra, 'guide-note'));
      selectors.forEach((node, i) => node.setAttribute('aria-pressed', String(i === selected)));
      previous.disabled = selected === 0; next.disabled = selected === content.stops.length - 1;
      status.textContent = 'Stop ' + (selected + 1) + ' of ' + content.stops.length;
      if (focus) focusStop();
    }
    const actions = make(doc, 'div', undefined, 'guide-actions');
    actions.append(button(doc, 'Start exploring', () => show(0)), button(doc, 'Browse stops', () => { selectors[0].focus(); jump.scrollIntoView?.({ block: 'start', behavior: 'instant' }); }, 'guide-button guide-secondary'));
    hero.append(actions);
    const note = make(doc, 'p', 'Use any stop in any order. Follow your booked tour, signs and staff directions. Arena, underground and attic access depend on your ticket. All photos and text work offline.', 'guide-note');
    content.stops.forEach((stop, i) => { const node = button(doc, (i + 1) + '. ' + stop.title, () => show(i), 'guide-stop-choice'); selectors.push(node); jump.append(node); });
    const previous = button(doc, '← Previous', () => show(selected - 1), 'guide-button guide-secondary');
    const next = button(doc, 'Next →', () => show(selected + 1));
    controls.append(previous, status, next);
    const timeline = make(doc, 'section', undefined, 'guide-timeline'); timeline.append(make(doc, 'h3', 'Two thousand years, in a moment'));
    const chronology = make(doc, 'ol');
    for (const row of content.timeline) { const item = make(doc, 'li'); item.append(make(doc, 'strong', row.when), make(doc, 'p', row.text)); chronology.append(item); }
    timeline.append(chronology);
    const sources = make(doc, 'details', undefined, 'guide-disclosure guide-sources'); sources.append(make(doc, 'summary', 'Photo credits & historical sources'), make(doc, 'p', 'Guide checked ' + content.checkedOn + '. Source addresses are available to copy into a browser.'));
    for (const source of content.sources) sources.append(make(doc, 'p', source.title), make(doc, 'p', source.url, 'guide-source-url'));
    for (const id of photoIds(content)) {
      const photo = photos.get(id), credit = make(doc, 'div', undefined, 'guide-credit');
      credit.append(make(doc, 'strong', photo.title + ' — ' + photo.creator), make(doc, 'p', photo.license + '. ' + photo.changes), make(doc, 'p', photo.sourceUrl, 'guide-source-url'), make(doc, 'p', photo.licenseUrl, 'guide-source-url')); sources.append(credit);
    }
    const onward = make(doc, 'section', undefined, 'guide-onward');
    onward.append(make(doc, 'h3', 'Next: the Forum & Palatine'), make(doc, 'p', 'Trade the amphitheatre for Rome’s civic centre and the hill of the imperial palaces. Check your own booking for entry conditions and timing.'), button(doc, 'Back to your itinerary', () => { openTab('itinerary'); doc.getElementById?.(content.returnAnchor)?.scrollIntoView({ block: 'start' }); }));
    host.replaceChildren(hero, note, jump, stopHost, controls, timeline, onward, sources);
    show(0, false);
  }
  function mount(doc, win, openTab) {
    const host = doc.getElementById('colosseum-root');
    if (!host) return () => {};
    let disposed = false; const controller = new AbortController(); const links = [];
    async function load(name) {
      const response = await win.fetch(name, { signal: controller.signal });
      if (!response.ok) throw Error('Guide asset unavailable');
      const body = await response.text(); if (body.length > 1048576) throw Error('Guide asset too large');
      return JSON.parse(body);
    }
    Promise.all([load('colosseum-content.json'), load('photo-credits.json')]).then(([content, credits]) => {
      if (disposed) return;
      render(host, prepare(content, credits), openTab);
      const event = doc.querySelector('#colosseum-itinerary-event .itinerary-event');
      if (event) { const link = button(doc, 'Open Colosseum guide', () => openTab('guide'), 'guide-event-link'); event.append(link); links.push(link); }
    }).catch(() => {
      if (!disposed) host.replaceChildren(make(doc, 'h2', 'Colosseum guide unavailable'), make(doc, 'p', 'Reload the app to try again. Your itinerary and saved information remain available.'));
    });
    return () => { disposed = true; controller.abort(); links.forEach(link => link.remove()); host.replaceChildren(); };
  }
  return Object.freeze({ prepare, render, mount });
});
