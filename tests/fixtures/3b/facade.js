(function (root) {
  'use strict';
  const id = n => 'doc_' + n.toString(16).padStart(32, '0');
  const relation = { tripId: 'trip_306765238f88', eventId: 'event_02fa46067011', documentId: id(1) };
  function view() {
    return { state: 'READY', reason: null, version: { userRevision: '0', walletGenerationId: 'synthetic-generation', walletRevision: '0', epoch: '0' },
      documents: [{ documentId: id(1), displayName: '<img src=x onerror=alert(1)>.pdf', mediaType: 'application/pdf', byteLength: 42 },
        { documentId: id(2), displayName: 'Biglietto & prenotazione “Roma”.pdf', mediaType: 'application/pdf', byteLength: 84 }], attachments: [relation] };
  }
  function create(initial = view()) {
    let value = structuredClone(initial); const listeners = new Set(), calls = [];
    const api = {
      calls, get value() { return structuredClone(value); },
      getSnapshot: async () => structuredClone(value),
      subscribe(fn) { listeners.add(fn); return () => listeners.delete(fn); },
      get subscribers() { return listeners.size; },
      emit(next) { value = structuredClone(next); for (const fn of listeners) fn(structuredClone(value)); },
      advance(edit) { const next = structuredClone(value); edit(next); next.version.epoch = String(BigInt(next.version.epoch) + 1n);
        next.version.userRevision = String(BigInt(next.version.userRevision) + 1n); api.emit(next); },
      async attach(expected, row) { calls.push(['attach', expected, row]); api.advance(v => v.attachments.push(row)); return { status: 'SAVED' }; },
      async unlink(expected, row) { calls.push(['unlink', expected, row]); api.advance(v => { v.attachments = v.attachments.filter(r => !(r.tripId === row.tripId && r.eventId === row.eventId && r.documentId === row.documentId)); }); return { status: 'SAVED' }; },
      async importDocument(expected) { calls.push(['importDocument', expected]); return { status: 'CANCELLED' }; },
      async openDocument(expected, documentId) { calls.push(['openDocument', expected, documentId]); return { status: 'OPENED' }; }
    }; return api;
  }
  const api = { create, view, relation, id };
  if (typeof module === 'object' && module.exports) module.exports = api; else root.SyntheticWallet = api;
})(typeof globalThis !== 'undefined' ? globalThis : this);
