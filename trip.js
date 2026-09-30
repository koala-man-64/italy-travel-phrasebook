(function (root) {
  'use strict';
  function freeze(value) {
    if (value && typeof value === 'object' && !Object.isFrozen(value)) {
      Object.values(value).forEach(freeze); Object.freeze(value);
    }
    return value;
  }
  // These are bundled, build-validated records, not a user-import interface.
  // Recheck identity/reference joins so a mixed cache generation fails closed.
  function prepareContent(trip, anchors) {
    if (!trip || trip.format !== 'itguide-trip' || trip.schemaVersion !== 1 || trip.timeZone !== 'Europe/Rome' ||
        !anchors || anchors.format !== 'itguide-trip-anchors' || anchors.schemaVersion !== 1 ||
        trip.tripId !== anchors.tripId || trip.contentVersion !== anchors.contentVersion) throw Error('Trip version mismatch');
    function index(rows, key) {
      if (!Array.isArray(rows)) throw Error('Missing trip collection');
      const map = new Map();
      rows.forEach(row => { if (!row || typeof row[key] !== 'string' || map.has(row[key])) throw Error('Duplicate trip identity'); map.set(row[key], row); });
      return map;
    }
    const days = index(trip.days, 'id'), events = index(trip.events, 'id'), phrases = index(trip.phrases, 'id');
    const links = index(anchors.days, 'dayId');
    if (!days.size || days.size !== links.size) throw Error('Missing day anchor');
    for (const day of days.values()) {
      const link = links.get(day.id);
      if (!link || !/^itinerary-day-\d+$/.test(link.legacyAnchor) || !/^itinerary-title-\d+$/.test(link.legacyTitleAnchor)) throw Error('Invalid day anchor');
      if (!Array.isArray(day.eventIds) || day.eventIds.some(id => !events.has(id) || events.get(id).dayId !== day.id)) throw Error('Broken event reference');
    }
    for (const row of anchors.events) {
      if (!events.has(row.eventId) || !links.has(row.dayId) || row.legacyDayAnchor !== links.get(row.dayId).legacyAnchor ||
          !Number.isSafeInteger(row.legacyEventOrdinal) || row.legacyEventOrdinal < 1) throw Error('Invalid event anchor');
    }
    for (const prompt of anchors.transportPack.prompts) {
      if (!phrases.has(prompt.phraseId) || prompt.exampleReplyIds.some(id => !phrases.has(id))) throw Error('Broken phrase reference');
    }
    return freeze({ trip, anchors });
  }
  // Find the next date boundary in Rome rather than adding a 24-hour day.
  function nextMidnight(instant, dateKey) {
    const start = new Date(instant).getTime(), date = dateKey(start);
    let low = start, high = start + 36 * 60 * 60 * 1000;
    while (high - low > 1) {
      const middle = Math.floor((low + high) / 2);
      if (dateKey(middle) === date) low = middle; else high = middle;
    }
    return high;
  }
  // Content is supplied only after prepareContent has validated bundled joins.
  // This grants itinerary identity only; the writer separately checks the document.
  function currentRelation(content, relation) {
    if (!content || !relation || relation.tripId !== content.trip.tripId) return false;
    const event = content.trip.events.find(row => row.id === relation.eventId);
    return !!event && content.trip.days.some(day => day.id === event.dayId && day.eventIds.includes(event.id));
  }
  function createClock(window, document, dateKey, now = () => new Date()) {
    const listeners = new Set();
    let timer = null, disposed = false, lastDate = dateKey(now());
    const cancel = () => { if (timer !== null) window.clearTimeout(timer); timer = null; };
    function tick(force = false) {
      cancel();
      if (disposed || document.hidden) return;
      const instant = now(), date = dateKey(instant);
      if (force || date !== lastDate) { lastDate = date; for (const listener of [...listeners]) listener(); }
      // A minute ceiling notices manual clock/time-zone changes while visible.
      timer = window.setTimeout(tick, Math.max(1, Math.min(60000, nextMidnight(instant, dateKey) - new Date(instant).getTime())));
    }
    const resume = () => tick(true), hide = () => cancel();
    const visibility = () => document.hidden ? hide() : resume();
    document.addEventListener('visibilitychange', visibility);
    window.addEventListener('pageshow', resume); window.addEventListener('pagehide', hide); window.addEventListener('focus', resume);
    tick();
    return Object.freeze({ now, subscribe(listener) { listeners.add(listener); return () => listeners.delete(listener); },
      dispose() { disposed = true; cancel(); listeners.clear(); document.removeEventListener('visibilitychange', visibility);
        window.removeEventListener('pageshow', resume); window.removeEventListener('pagehide', hide); window.removeEventListener('focus', resume); }
    });
  }
  function mountTransport(element, content, services) {
    const document = element.ownerDocument, { trip, anchors } = content;
    const phrases = new Map(trip.phrases.map(phrase => [phrase.id, phrase]));
    const prompts = anchors.transportPack.prompts.filter(prompt =>
      [prompt.phraseId, ...prompt.exampleReplyIds].every(id => phrases.get(id).review === 'reviewed'));
    if (!prompts.length) return () => {};
    const section = document.createElement('section'); section.className = 'transport-pack'; section.setAttribute('aria-label', 'Getting around');
    function node(tag, text, className) { const n = document.createElement(tag); n.textContent = text; if (className) n.className = className; return n; }
    function button(label, action) { const b = node('button', label, 'btn'); b.type = 'button'; b.addEventListener('click', () => action(b)); return b; }
    const disclosure = node('details', '', 'transport-disclosure'), body = node('div', '', 'transport-body');
    disclosure.append(node('summary', 'Getting around · ' + prompts.length + ' phrases', 'transport-toggle'), body); section.append(disclosure);
    body.append(node('h2', 'Getting around'), node('p', 'Ask for directions, tickets or a taxi. Replies are examples to help you recognize what you may hear.', 'transport-intro'));
    const controls = node('div', '', 'transport-actions');
    const slow = button('Slow audio', () => services.toggleSlow()); slow.setAttribute('data-action', 'slow'); slow.setAttribute('aria-pressed', 'false');
    controls.append(slow, button('Stop audio', () => services.stop())); body.append(controls);
    for (const prompt of prompts) {
      const phrase = phrases.get(prompt.phraseId), card = node('article', '', 'card transport-card'); card.setAttribute('data-phrase-id', phrase.id);
      const italian = node('p', phrase.snapshot.it, 'phrase-it'); italian.lang = 'it';
      const actions = node('div', '', 'transport-actions');
      actions.append(button('Listen', b => services.listen(phrase.snapshot.it, b)), button('Show', () => services.show(phrase.snapshot)));
      card.append(italian, node('p', phrase.snapshot.en, 'phrase-en'), actions);
      const details = node('details', '', 'transport-example'); details.append(node('summary', 'Example reply — illustrative only'));
      for (const id of prompt.exampleReplyIds) {
        const reply = phrases.get(id), it = node('p', reply.snapshot.it); it.lang = 'it';
        details.append(it, node('p', reply.snapshot.en, 'phrase-en'), button('Listen to reply', b => services.listen(reply.snapshot.it, b)));
      }
      details.append(node('p', 'Illustrative only. Confirm the actual direction, route or ticket rule with staff.', 'transport-note'));
      card.append(details); body.append(card);
    }
    element.append(section); element.hidden = false; services.syncSlow();
    return () => { section.remove(); element.hidden = true; };
  }
  async function mount(document, window, services) {
    const disposers = [], todayRoot = document.getElementById('today-root');
    const readinessRoot = document.getElementById('speech-readiness-root');
    if (readinessRoot) { disposers.push(services.readiness.mount(readinessRoot, { speech: services.speech })); readinessRoot.hidden = false; }
    try {
      const responses = await Promise.all([window.fetch('trip-content.json'), window.fetch('trip-content-anchors.json')]);
      const values = await Promise.all(responses.map(async response => {
        if (!response.ok) throw Error('Trip asset unavailable');
        const text = await response.text(); if (text.length > 524288) throw Error('Trip asset too large'); return JSON.parse(text);
      }));
      const content = prepareContent(...values), clock = createClock(window, document, services.today.romeDate);
      disposers.push(() => clock.dispose());
      const navigation = { openDay(id) {
        const link = content.anchors.days.find(day => day.dayId === id); if (!link) return;
        services.openTab('itinerary');
        const target = document.getElementById(link.legacyTitleAnchor), card = document.getElementById(link.legacyAnchor);
        if (target && card) { target.tabIndex = -1; target.focus({ preventScroll: true }); card.scrollIntoView({ block: 'start' }); }
      } };
      for (const row of content.anchors.events) {
        const day = document.getElementById(row.legacyDayAnchor), event = day && day.querySelectorAll('.itinerary-events > li')[row.legacyEventOrdinal - 1];
        if (event) {
          event.setAttribute('data-event-id', row.eventId);
          if (services.mountEvent) disposers.push(services.mountEvent(event, content, row.eventId));
        }
      }
      disposers.push(services.today.mount(todayRoot, { trip: content.trip, clock, navigation })); todayRoot.hidden = false;
      disposers.push(mountTransport(document.getElementById('transport-root'), content, services));
      if (services.bindContent) disposers.push(services.bindContent(content));
    } catch (_) {
      todayRoot.textContent = 'Trip overview is unavailable. Your full itinerary is below.'; todayRoot.hidden = false;
    }
    return () => disposers.reverse().forEach(dispose => dispose());
  }
  const api = { prepareContent, currentRelation, nextMidnight, createClock, mountTransport, mount };
  if (typeof module === 'object' && module.exports) module.exports = api;
  root.ItalyTrip = api;
})(typeof window !== 'undefined' ? window : globalThis);
