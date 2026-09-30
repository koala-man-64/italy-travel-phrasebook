(function (root) {
  'use strict';

  const romeFormatter = new Intl.DateTimeFormat('en-GB', {
    timeZone: 'Europe/Rome', calendar: 'gregory', numberingSystem: 'latn',
    year: 'numeric', month: '2-digit', day: '2-digit'
  });

  function romeDate(instant) {
    if (instant === null || instant === undefined) throw new RangeError('A current instant is required');
    const parts = Object.fromEntries(romeFormatter.formatToParts(new Date(instant))
      .map(part => [part.type, part.value]));
    return `${parts.year}-${parts.month}-${parts.day}`;
  }

  // TripContent is validated by the content boundary before injection. Do not
  // reinterpret timeLabel, exactLocal, or legacy DOM anchors here.
  function prepareTrip(trip) {
    if (!trip || trip.timeZone !== 'Europe/Rome' || !trip.days.length) {
      throw new TypeError('Validated Europe/Rome TripContent with at least one day is required');
    }
    return {
      calendarDays: [...trip.days].sort((a, b) => a.date.localeCompare(b.date)),
      displayDays: [...trip.days].sort((a, b) => a.order - b.order),
      events: new Map(trip.events.map(event => [event.id, event]))
    };
  }

  function selectPrepared(trip, instant, manualDayId) {
    const date = romeDate(instant);
    const days = trip.calendarDays;
    let state, automaticDay;
    if (date < days[0].date) {
      state = 'before'; automaticDay = days[0];
    } else if (date > days[days.length - 1].date) {
      state = 'after'; automaticDay = days[days.length - 1];
    } else {
      automaticDay = days.find(day => day.date >= date);
      state = automaticDay.date === date ? 'during' : 'gap';
    }
    const manual = manualDayId !== null && manualDayId !== undefined;
    const day = manual ? days.find(item => item.id === manualDayId) : automaticDay;
    if (!day) throw new RangeError('Unknown trip day ID');
    const events = day.eventIds.map(id => trip.events.get(id)).sort((a, b) => a.order - b.order);
    return { date, state, automaticDay, day, manual, events };
  }

  function selectDay(trip, instant, manualDayId = null) {
    return selectPrepared(prepareTrip(trip), instant, manualDayId);
  }

  function statusText(selection) {
    const { date, state, automaticDay, day, manual } = selection;
    const descriptions = {
      before: `Trip upcoming. First day: ${automaticDay.date}.`,
      during: `Today: ${automaticDay.label}.`,
      after: `Trip ended. Last day: ${automaticDay.date}.`,
      gap: `No itinerary day for today. Next planned day: ${automaticDay.date}.`
    };
    const selected = manual
      ? ` Viewing ${day.label} (${day.date}), selected manually. Choose Today to follow the calendar.`
      : '';
    return `Today in Italy: ${date}. ${descriptions[state]}${selected}`;
  }

  function mount(element, services) {
    if (!element || !element.ownerDocument || !services ||
        typeof services.clock?.now !== 'function' || typeof services.clock?.subscribe !== 'function' ||
        typeof services.navigation?.openDay !== 'function') {
      throw new TypeError('Today requires a mount, clock and day navigation');
    }
    const trip = prepareTrip(services.trip);
    let selection = selectPrepared(trip, services.clock.now(), null);
    let manualDayId = null;
    let disposed = false;
    let renderedDayId = null;
    const doc = element.ownerDocument;
    const make = (tag, className, text) => {
      const node = doc.createElement(tag);
      if (className) node.className = className;
      if (text !== undefined) node.textContent = text;
      return node;
    };
    const view = make('section', 'italy-today');
    view.setAttribute('aria-label', 'Today and trip days');
    const heading = make('h2', 'italy-today-heading', 'Your trip day');
    const controls = make('div', 'italy-today-controls');
    const label = make('label', 'italy-today-picker');
    const labelText = make('span', '', 'Choose a trip day');
    const select = make('select', 'italy-today-select');
    for (const day of trip.displayDays) {
      const option = make('option', '', `${day.date} · ${day.label}`);
      option.value = day.id;
      select.appendChild(option);
    }
    label.append(labelText, select);
    const today = make('button', 'italy-today-reset', 'Today');
    today.type = 'button';
    today.setAttribute('aria-label', 'Today in Italy');
    controls.append(label, today);
    const status = make('p', 'italy-today-status');
    status.setAttribute('role', 'status');
    status.setAttribute('aria-live', 'polite');
    status.setAttribute('aria-atomic', 'true');
    const summary = make('div', 'italy-today-summary');
    const title = make('h3', 'italy-today-day-title');
    const date = make('p', 'italy-today-date');
    const events = make('ol', 'italy-today-events');
    const empty = make('p', 'italy-today-empty', 'No events are listed for this day.');
    const open = make('button', 'italy-today-open', 'Open day in itinerary');
    open.type = 'button';
    summary.append(title, date, events, empty, open);
    view.append(heading, controls, status, summary);

    function render() {
      select.value = selection.day.id;
      today.setAttribute('aria-pressed', String(!selection.manual));
      view.setAttribute('data-state', selection.state);
      const message = statusText(selection);
      if (status.textContent !== message) status.textContent = message;
      // Keep every interactive node stable on clock notifications and day changes.
      if (renderedDayId === selection.day.id) return;
      renderedDayId = selection.day.id;
      title.textContent = selection.day.label;
      date.textContent = selection.day.date;
      summary.setAttribute('data-day-id', selection.day.id);
      open.setAttribute('aria-label', `Open ${selection.day.label} in itinerary`);
      events.replaceChildren();
      for (const event of selection.events) {
        const item = make('li', 'italy-today-event');
        item.setAttribute('data-event-id', event.id);
        item.append(make('span', 'italy-today-time', event.timeLabel),
          make('span', 'italy-today-event-title', event.title));
        events.appendChild(item);
      }
      events.hidden = selection.events.length === 0;
      empty.hidden = selection.events.length !== 0;
    }

    function refresh() {
      if (disposed) return;
      selection = selectPrepared(trip, services.clock.now(), manualDayId);
      render();
    }
    function onSelect() { manualDayId = select.value; refresh(); }
    function onToday() { manualDayId = null; refresh(); }
    function onOpen() { services.navigation.openDay(selection.day.id); }
    select.addEventListener('change', onSelect);
    today.addEventListener('click', onToday);
    open.addEventListener('click', onOpen);
    render();
    element.appendChild(view);

    function removeView() {
      select.removeEventListener('change', onSelect);
      today.removeEventListener('click', onToday);
      open.removeEventListener('click', onOpen);
      view.remove();
    }
    let unsubscribe;
    try {
      unsubscribe = services.clock.subscribe(refresh);
      if (typeof unsubscribe !== 'function') throw new TypeError('clock.subscribe must return unsubscribe');
    } catch (error) {
      disposed = true;
      removeView();
      throw error;
    }
    return function dispose() {
      if (disposed) return;
      disposed = true;
      try { unsubscribe(); } finally { removeView(); }
    };
  }

  const api = Object.freeze({ romeDate, selectDay, mount });
  if (typeof module === 'object' && module.exports) module.exports = api;
  else root.ItalyToday = api;
})(typeof globalThis !== 'undefined' ? globalThis : this);
