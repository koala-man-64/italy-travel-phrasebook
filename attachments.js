(function (root, factory) {
  'use strict';
  const api = factory();
  if (typeof module === 'object' && module.exports) module.exports = api;
  else root.ItalyAttachments = api;
})(typeof globalThis !== 'undefined' ? globalThis : this, function () {
  'use strict';
  const MAX = 9223372036854775807n;
  const docId = /^doc_[0-9a-f]{32}$/;
  const tripIdPattern = /^trip_[a-z0-9][a-z0-9_-]{0,63}$/;
  const eventIdPattern = /^event_[a-z0-9][a-z0-9_-]{0,63}$/;
  const states = ['LOADING', 'READY', 'READ_ONLY', 'UNSUPPORTED', 'RECOVERY_REQUIRED'];
  const messages = Object.freeze({
    LOADING: 'Checking documents…', READY: '',
    READ_ONLY: 'Documents cannot be changed or opened right now.',
    UNSUPPORTED: 'Documents are available in the Android app.',
    RECOVERY_REQUIRED: 'Document storage needs recovery. Reload to check again.',
    REVISION_CONFLICT: 'Your documents changed. Review the refreshed list and try again.',
    RESTORE_CONFLICT: 'Your documents changed. Review the refreshed list and try again.',
    LIMIT_REACHED: 'Document or attachment limit reached.',
    UNSUPPORTED_TYPE: 'Choose a PDF, PNG or JPEG document.',
    FILE_LIMIT: 'This document exceeds the document size limit.',
    STORAGE_UNAVAILABLE: 'Document storage is unavailable. Reload to check again.',
    VIEWER_UNAVAILABLE: 'The document viewer is unavailable. Reload before trying again.',
    UNAVAILABLE: 'Documents are unavailable here.',
    UNKNOWN: 'This action could not be confirmed. Reload to check your documents.'
  });
  function need(value) { if (!value) throw Error('INVALID_VIEW'); }
  function decimal(value) { return typeof value === 'string' && /^(0|[1-9][0-9]{0,18})$/.test(value) && BigInt(value) <= MAX; }
  function label(value) {
    return typeof value === 'string' && [...value].length > 0 && [...value].length <= 120 &&
      !/[\p{Cc}\p{Cf}\p{Cs}]/u.test(value);
  }
  function version(value) {
    need(value && decimal(value.userRevision) && decimal(value.epoch));
    need(value.walletGenerationId === null ? value.walletRevision === null :
      typeof value.walletGenerationId === 'string' && value.walletGenerationId.length > 0 &&
      value.walletGenerationId.length <= 128 && decimal(value.walletRevision));
    return Object.freeze({ userRevision: value.userRevision, walletGenerationId: value.walletGenerationId,
      walletRevision: value.walletRevision, epoch: value.epoch });
  }
  const sameVersion = (a, b) => a && b && a.epoch === b.epoch && a.userRevision === b.userRevision &&
    a.walletGenerationId === b.walletGenerationId && a.walletRevision === b.walletRevision;
  const samePair = (a, b) => a && b && a.userRevision === b.userRevision &&
    a.walletGenerationId === b.walletGenerationId && a.walletRevision === b.walletRevision;
  function receiptValid(result, allowed) {
    if (!result || typeof result !== 'object' || Array.isArray(result) || !allowed.includes(result.status)) return false;
    const keys = Object.keys(result);
    if (!keys.every(key => ['status', 'code', 'value'].includes(key))) return false;
    if (result.status === 'FAILED' || result.status === 'UNCERTAIN')
      return keys.length === 2 && typeof result.code === 'string' && !Object.hasOwn(result, 'value');
    if (Object.hasOwn(result, 'code')) return false;
    if (!Object.hasOwn(result, 'value')) return true; // Synthetic v1 facade fixtures have no value.
    const value = result.value;
    if (!value || typeof value !== 'object' || Array.isArray(value)) return false;
    if (result.status === 'SAVED') {
      if (Object.keys(value).sort().join('|') !== 'userRevision|walletGenerationId|walletRevision') return false;
      try { version({ ...value, epoch: '0' }); return true; } catch (_) { return false; }
    }
    return Object.keys(value).length === 0;
  }
  function project(raw) {
    need(raw && states.includes(raw.state) && (raw.reason === null || typeof raw.reason === 'string'));
    const v = version(raw.version);
    need(Array.isArray(raw.documents) && raw.documents.length <= 50);
    need(Array.isArray(raw.attachments) && raw.attachments.length <= 1000);
    const ids = new Set(), triples = new Set(); let total = 0;
    const documents = raw.documents.map(item => {
      need(item && typeof item.documentId === 'string' && docId.test(item.documentId) && !ids.has(item.documentId));
      need(label(item.displayName) && ['application/pdf', 'image/png', 'image/jpeg'].includes(item.mediaType));
      need(Number.isSafeInteger(item.byteLength) && item.byteLength >= 0 && item.byteLength <= 20 * 1024 * 1024);
      total += item.byteLength; ids.add(item.documentId);
      return Object.freeze({ documentId: item.documentId, displayName: item.displayName,
        mediaType: item.mediaType, byteLength: item.byteLength });
    });
    need(total <= 250 * 1024 * 1024);
    const attachments = raw.attachments.map(item => {
      need(item && typeof item.tripId === 'string' && tripIdPattern.test(item.tripId) &&
        typeof item.eventId === 'string' && eventIdPattern.test(item.eventId) &&
        typeof item.documentId === 'string' && ids.has(item.documentId));
      const key = [item.tripId, item.eventId, item.documentId].join('/');
      need(!triples.has(key)); triples.add(key);
      return Object.freeze({ tripId: item.tripId, eventId: item.eventId, documentId: item.documentId });
    });
    need(v.walletGenerationId !== null || (!documents.length && !attachments.length));
    return Object.freeze({ state: raw.state, version: v, documents: Object.freeze(documents), attachments: Object.freeze(attachments) });
  }
  function mount(element, { tripId, eventId, wallet }) {
    const doc = element.ownerDocument;
    const make = (tag, text, cls) => {
      const node = doc.createElement(tag); if (text !== undefined) node.textContent = text;
      if (cls) node.className = cls; return node;
    };
    const section = make('section', undefined, 'italy-attachments');
    section.setAttribute('aria-label', 'Event documents');
    const heading = make('h4', 'Documents'); heading.tabIndex = -1;
    const body = make('div'), status = make('p', '', 'attachment-status');
    status.setAttribute('role', 'status'); status.setAttribute('aria-live', 'polite');
    section.append(heading, body, status); element.append(section);
    let current = null, disposed = false, busy = false, locked = false, panel = null;
    let controls = new Map(), operation = 0, request = 0, unsubscribe = () => {};
    const writable = () => !locked && !busy && current?.state === 'READY';
    function say(text, error = false) {
      if (disposed) return;
      status.setAttribute('role', error ? 'alert' : 'status');
      status.setAttribute('aria-live', error ? 'assertive' : 'polite');
      status.textContent = text;
    }
    function failClosed() {
      if (disposed) return;
      locked = true; panel = null; operation++; render(); say(messages.UNKNOWN, true);
    }
    function receive(raw) {
      if (disposed) return;
      try {
        const incomingVersion = version(raw?.version);
        if (current && BigInt(incomingVersion.epoch) < BigInt(current.version.epoch)) return;
        const next = project(raw);
        if (current && next.version.epoch === current.version.epoch) {
          need(JSON.stringify(next) === JSON.stringify(current)); return;
        }
        const changed = current && !sameVersion(current.version, next.version);
        current = next;
        // The facade publishes LOADING and a refreshed READY during our own
        // command. Resolve its receipt against the final pair before deciding
        // whether a concurrent update superseded the command.
        if (changed && !busy) { panel = null; operation++; }
        render();
        if (!locked) say(messages[next.state] || (changed ? 'Document list updated.' : ''), next.state === 'RECOVERY_REQUIRED');
      } catch (_) { failClosed(); }
    }
    async function refresh() {
      const serial = ++request, before = current?.version;
      try {
        const value = await wallet.getSnapshot();
        if (!disposed && serial === request) receive(value);
      } catch (_) {
        // A newer valid notification supersedes failure of an older snapshot read.
        if (!disposed && serial === request && (!current || sameVersion(before, current.version))) failClosed();
      }
    }
    function focus(key) { const node = controls.get(key); (node && !node.disabled ? node : heading).focus(); }
    function button(parent, key, text, action, enabled = writable()) {
      const node = make('button', text); node.type = 'button'; node.disabled = !enabled;
      controls.set(key, node); node.addEventListener('click', () => {
        if (!disposed && controls.get(key) === node && !node.disabled && writable()) action();
      }); parent.append(node); return node;
    }
    function render() {
      if (disposed) return;
      const active = doc.activeElement;
      const previousKey = [...controls].find(([, node]) => node === active)?.[0];
      const hadFocus = body.contains(active);
      body.replaceChildren(); controls = new Map(); section.setAttribute('aria-busy', String(busy));
      const state = make('p', locked ? messages.RECOVERY_REQUIRED : messages[current?.state || 'LOADING'], 'attachment-state');
      body.append(state);
      const relations = current?.attachments.filter(row => row.tripId === tripId && row.eventId === eventId) || [];
      const linked = new Set(relations.map(row => row.documentId));
      const list = make('ul', undefined, 'attachment-list'); body.append(list);
      for (const relation of relations) {
        const document = current.documents.find(row => row.documentId === relation.documentId);
        const item = make('li'), name = make('p', document.displayName, 'attachment-name');
        const actions = make('div', undefined, 'attachment-actions'); item.append(name, actions); list.append(item);
        const open = button(actions, 'open:' + document.documentId, 'Open', () => perform('openDocument', document.documentId, 'open:' + document.documentId));
        open.setAttribute('aria-label', 'Open ' + document.displayName);
        const unlink = button(actions, 'unlink:' + document.documentId, 'Unlink', () => { panel = { kind: 'unlink', relation }; render(); focus('cancel'); });
        unlink.setAttribute('aria-label', 'Unlink ' + document.displayName);
      }
      if (!relations.length && current?.state === 'READY') body.append(make('p', 'No documents linked to this event.'));
      button(body, 'attach', 'Attach document', () => { panel = { kind: 'select' }; render(); focus('cancel'); });
      if (panel) {
        const group = make('div', undefined, 'attachment-panel'); group.setAttribute('role', 'group');
        group.setAttribute('aria-label', panel.kind === 'select' ? 'Choose a document' : 'Confirm unlink'); body.append(group);
        if (panel.kind === 'select') {
          group.append(make('p', 'Choose an existing document, or import one first.'));
          for (const document of current.documents.filter(row => !linked.has(row.documentId))) {
            button(group, 'select:' + document.documentId, 'Attach ' + document.displayName,
              () => perform('attach', { tripId, eventId, documentId: document.documentId }, 'attach'));
          }
          button(group, 'import', 'Import document', () => perform('importDocument', undefined, 'attach'));
        } else {
          const relation = panel.relation;
          group.append(make('p', 'Remove this link from this itinerary event? The document stays in your wallet.'));
          button(group, 'confirm', 'Confirm unlink', () => perform('unlink', relation, 'attach'));
        }
        button(group, 'cancel', 'Cancel', closePanel);
      }
      if (hadFocus) focus(previousKey);
    }
    function closePanel() { if (busy || disposed) return; panel = null; render(); focus('attach'); }
    function escape(event) { if (event.key === 'Escape' && panel && !busy) { event.preventDefault(); closePanel(); } }
    section.addEventListener('keydown', escape);
    async function perform(method, argument, returnFocus) {
      if (!writable()) return;
      const expected = current.version, ticket = ++operation;
      busy = true; render(); say('Working…');
      let result;
      try { result = await wallet[method](expected, ...(argument === undefined ? [] : [argument])); }
      catch (_) { result = null; }
      if (disposed) return;
      const allowed = method === 'openDocument' ? ['OPENED', 'FAILED', 'UNCERTAIN'] :
        method === 'importDocument' ? ['SAVED', 'CANCELLED', 'FAILED', 'UNCERTAIN'] : ['SAVED', 'UNCHANGED', 'FAILED', 'UNCERTAIN'];
      const receipt = receiptValid(result, allowed);
      const finalPair = current?.version;
      const changedPair = result?.status === 'SAVED' && result.value && samePair(result.value, finalPair);
      const unchangedPair = samePair(expected, finalPair);
      const isCurrent = ticket === operation && (result?.status === 'SAVED' ?
        (changedPair || (!result.value && current?.state === 'READY')) : unchangedPair);
      if (isCurrent) {
        if (!receipt || (result.status === 'SAVED' && current?.state !== 'READY')) {
          locked = true; panel = null; say(messages.UNKNOWN, true);
        } else if (result.status === 'UNCERTAIN') {
          locked = true; panel = null; say(messages.RECOVERY_REQUIRED, true);
        } else if (result.status === 'FAILED') {
          const known = Object.hasOwn(messages, result.code) && !states.includes(result.code);
          if (!known) { locked = true; panel = null; }
          say(known ? messages[result.code] : messages.UNKNOWN, true);
        } else {
          panel = null;
          say(result.status === 'OPENED' ? 'Viewer launch accepted.' : result.status === 'CANCELLED' ? '' :
            method === 'importDocument' ? 'Document imported. Use Attach document to link it to this event.' :
              result.status === 'UNCHANGED' ? 'No change was needed.' : 'Document links saved.');
        }
      } else if (ticket === operation) {
        panel = null;
        if (!locked) say(messages[current?.state] || 'Document list updated.', current?.state === 'RECOVERY_REQUIRED');
      }
      busy = false;
      const hadFocus = section.contains(doc.activeElement);
      render(); if (hadFocus) focus(returnFocus);
      await refresh();
    }
    render();
    try {
      need(typeof tripId === 'string' && tripIdPattern.test(tripId) && typeof eventId === 'string' && eventIdPattern.test(eventId));
      need(wallet && ['subscribe', 'getSnapshot', 'attach', 'unlink', 'importDocument', 'openDocument'].every(key => typeof wallet[key] === 'function'));
      unsubscribe = wallet.subscribe(receive); need(typeof unsubscribe === 'function');
      void refresh();
    } catch (_) { failClosed(); }
    return () => {
      if (disposed) return;
      disposed = true; operation++; request++;
      try { unsubscribe(); } catch (_) { /* Disposed callbacks remain inert. */ }
      section.removeEventListener('keydown', escape); section.remove();
    };
  }
  return Object.freeze({ mount });
});
