(function (root) {
  'use strict';
  // A synchronous read facade for the existing shell, backed by one async writer.
  // Session-only UI changes never imply that a write was persisted.
  function create(store, report) {
    let view = null, queue = Promise.resolve(), disposed = false, epoch = 0, replacing = false;
    const receive = next => { if (!disposed) view = next; };
    const unsubscribe = store.subscribe(receive);
    let ready;
    ready = store.load().then(next => { receive(next); return next; }).catch(() => {
      view = { state: 'RECOVERY_REQUIRED', writable: false, snapshot: null, reason: 'STORAGE_UNAVAILABLE' };
      report('Personal data could not be read. Changes will not be saved.');
      return view;
    });
    function get(key, fallback) {
      const data = view && view.snapshot;
      if (!data) return fallback;
      if (key === 'slow' || key === 'tab') return data.preferences[key];
      if (key === 'builder') {
        try { return data.legacyBuilderRaw === null ? fallback : JSON.parse(data.legacyBuilderRaw); }
        catch (_) { return fallback; }
      }
      return fallback;
    }
    function set(key, value) {
      if (replacing) {
        report('A restore is in progress. This temporary change was not saved.');
        return Promise.resolve({ status: 'FAILED', code: 'RESTORE_IN_PROGRESS' });
      }
      const intentEpoch = epoch;
      // Freeze the click-time value before queuing; Builder mutates its object.
      let change;
      try {
        if (key === 'slow' || key === 'tab') change = { type: 'preferences', [key]: value };
        else if (key === 'builder') change = { type: 'builder', raw: value === null ? null : JSON.stringify(value) };
        else return Promise.resolve({ status: 'FAILED', code: 'INVALID_DATA' });
      } catch (_) { return Promise.resolve({ status: 'FAILED', code: 'INVALID_DATA' }); }
      const task = queue.then(async () => {
        await ready;
        if (disposed) return { status: 'FAILED', code: 'DISPOSED' };
        if (intentEpoch !== epoch) return { status: 'FAILED', code: 'RESTORE_CONFLICT' };
        const current = await store.getSnapshot();
        if (intentEpoch !== epoch) return { status: 'FAILED', code: 'RESTORE_CONFLICT' };
        if (!current.writable || !current.snapshot) {
          report('Changes are temporary. Personal data is read-only; reload to check recovery.');
          return { status: 'FAILED', code: current.reason || 'RECOVERY_REQUIRED' };
        }
        const same = change.type === 'builder' ? current.snapshot.legacyBuilderRaw === change.raw
          : current.snapshot.preferences[key] === value;
        if (same) return { status: 'UNCHANGED' };
        const result = await store.mutate(current.snapshot.revision, change);
        if (!result || result.status !== 'SAVED') report(result && result.status === 'UNCERTAIN'
          ? 'Save outcome is uncertain. Reload before changing personal data again.'
          : 'This change was not saved. Reload personal data and try again.');
        return result;
      }).catch(() => {
        if (!disposed) report('This change could not be saved. Reload personal data to check recovery.');
        return { status: 'FAILED', code: 'STORAGE_UNAVAILABLE' };
      });
      queue = task.then(() => {});
      return task;
    }
    function replaceConfirmed(revision, candidate) {
      if (replacing || disposed) return Promise.resolve({ status: 'FAILED', code: 'RESTORE_CONFLICT' });
      replacing = true; epoch++;
      // Drain any already-running write, discard older queued UI intents, then
      // honor the preview's expected revision. Never rebase a restore silently.
      const task = queue.then(async () => {
        await ready;
        if (disposed) return { status: 'FAILED', code: 'DISPOSED' };
        return store.replaceConfirmed(revision, candidate);
      }).finally(() => { replacing = false; });
      queue = task.then(() => {}, () => {});
      return task;
    }
    return Object.freeze({ ready, get, set, replaceConfirmed, dispose() { disposed = true; unsubscribe(); } });
  }
  const api = Object.freeze({ create });
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  else root.ItalyPersonal = api;
})(typeof globalThis !== 'undefined' ? globalThis : this);
