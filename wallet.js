(function (root, factory) {
  'use strict';
  const api = factory();
  if (typeof module === 'object' && module.exports) module.exports = api;
  else root.ItalyWallet = api;
})(typeof globalThis !== 'undefined' ? globalThis : this, function () {
  'use strict';
  const MAX = 9223372036854775807n;
  const decimal = v => typeof v === 'string' && /^(0|[1-9][0-9]{0,18})$/.test(v) && BigInt(v) <= MAX;
  const same = (a,b) => a && b && ['userRevision','walletGenerationId','walletRevision','epoch'].every(k => a[k] === b[k]);
  const pair = v => ({ userRevision: v.userRevision, walletGenerationId: v.walletGenerationId, walletRevision: v.walletRevision });
  const freeze = value => { if (value && typeof value === 'object') { Object.values(value).forEach(freeze); Object.freeze(value); } return value; };
  const need = yes => { if (!yes) throw Error('INVALID_SNAPSHOT'); };
  function create({ store, host }) {
    const listeners = new Set();
    let epoch = 0n, disposed = false, dirty = true, refreshing = null, operation = false, latest = null, idleSerial = 0;
    let view = freeze({ state: host ? 'LOADING' : 'UNSUPPORTED', version: { userRevision: '0', walletGenerationId: null, walletRevision: null, epoch: '0' }, documents: [], attachments: [], reason: null });
    const expected = state => ({ userRevision: state.snapshot.revision, walletGenerationId: state.snapshot.wallet?.generationId ?? null, walletRevision: state.snapshot.wallet?.revision ?? null });
    function publish(state, version = pair(view.version), documents = [], attachments = [], reason = null) {
      if (disposed) return view;
      if (epoch === MAX) { disposed = true; throw Error('EPOCH_LIMIT'); }
      view = freeze({ state, version: { ...version, epoch: String(++epoch) }, documents, attachments, reason });
      for (const listener of [...listeners]) { try { listener(view); } catch (_) {} }
      return view;
    }
    function validate(value, wanted, state) {
      need(value && Object.keys(value).sort().join('|') === 'documents|userRevision|walletGenerationId|walletRevision');
      need(decimal(value.userRevision) && Object.keys(wanted).every(k => wanted[k] === value[k]));
      need(Array.isArray(value.documents) && value.documents.length <= 50);
      const ids = new Set(); let bytes = 0;
      const documents = value.documents.map(d => {
        need(d && Object.keys(d).sort().join('|') === 'byteLength|displayName|documentId|mediaType');
        need(typeof d.documentId === 'string' && /^doc_[0-9a-f]{32}$/.test(d.documentId) && !ids.has(d.documentId));
        need(typeof d.displayName === 'string' && [...d.displayName].length > 0 && [...d.displayName].length <= 120 && !/[\p{Cc}\p{Cf}\p{Cs}]/u.test(d.displayName));
        need(['application/pdf','image/png','image/jpeg'].includes(d.mediaType) && Number.isSafeInteger(d.byteLength) && d.byteLength > 0 && d.byteLength <= 20 * 1024 * 1024);
        ids.add(d.documentId); bytes += d.byteLength; return { ...d };
      });
      need(bytes <= 250 * 1024 * 1024);
      const attachments = state.snapshot.attachments.map(r => { need(ids.has(r.documentId)); return { ...r }; });
      need(wanted.walletGenerationId !== null || (!documents.length && !attachments.length));
      return { documents, attachments };
    }
    function refresh() {
      if (disposed || !host || !dirty || refreshing || operation || !latest?.writable || latest.state !== 'READY') return refreshing || Promise.resolve(view);
      const captured = latest, wanted = expected(captured), observedIdle = idleSerial;
      refreshing = Promise.resolve().then(() => host.wallet('snapshot', wanted, {})).then(result => {
        if (disposed || latest !== captured) return view;
        if (result.status === 'FAILED' && result.code === 'BUSY') return view;
        dirty = false;
        if (result.status !== 'OK') return publish(result.code === 'UNSUPPORTED' ? 'UNSUPPORTED' : 'RECOVERY_REQUIRED', wanted, [], [], 'UNAVAILABLE');
        const data = validate(result.value, wanted, captured);
        return publish('READY', wanted, data.documents, data.attachments);
      }).catch(() => { if (disposed || latest !== captured) return view; dirty = false; return publish('RECOVERY_REQUIRED', wanted, [], [], 'UNAVAILABLE'); })
        .finally(() => { refreshing = null; if (!disposed && (latest !== captured || dirty && idleSerial !== observedIdle)) void refresh(); });
      return refreshing;
    }
    const unsubscribe = store.subscribe(state => {
      if (disposed) return;
      latest = state; dirty = true;
      if (!host) return;
      publish((state.state === 'READY' && state.writable) || state.state === 'RECOVERING' ? 'LOADING' : 'RECOVERY_REQUIRED', state.snapshot ? expected(state) : undefined);
      void refresh();
    });
    const idle = host ? host.subscribeIdle(() => { idleSerial++; if (dirty) void refresh(); }) : () => {};
    void store.getSnapshot().then(state => { if (!latest && !disposed) { latest = state; void refresh(); } });
    async function run(op, version, args = {}) {
      if (disposed || !host) return { status: 'FAILED', code: 'UNSUPPORTED' };
      if (operation || refreshing) return { status: 'FAILED', code: 'BUSY' };
      if (view.state !== 'READY' || !same(version, view.version)) return { status: 'FAILED', code: 'REVISION_CONFLICT' };
      operation = true;
      const captured = view.version;
      publish('LOADING');
      try {
        const result = await host.wallet(op, pair(captured), args);
        if (disposed) return { status: 'UNCERTAIN', code: 'RECOVERY_REQUIRED' };
        if (result.status === 'UNCERTAIN') { dirty = false; publish('RECOVERY_REQUIRED'); }
        else dirty = true;
        return result;
      } catch (_) { dirty = false; publish('RECOVERY_REQUIRED'); return { status: 'UNCERTAIN', code: 'RECOVERY_REQUIRED' }; }
      finally { operation = false; if (dirty) await refresh(); }
    }
    return Object.freeze({
      getSnapshot: () => refresh().then(() => view),
      subscribe(fn) { listeners.add(fn); fn(view); return () => listeners.delete(fn); },
      importDocument: version => run('import-document', version, { displayName: 'Travel document' }),
      attach: (version, relation) => run('attach', version, { relation }),
      unlink: (version, relation) => run('unlink', version, { relation }),
      openDocument: (version, documentId) => run('open-document', version, { documentId }),
      deleteDocument: (version, documentId) => run('delete-document', version, { documentId }),
      previewArchive: version => run('archive-preview', version),
      confirmArchive: (version, token) => run('archive-confirm', version, { token }),
      cancelArchive: (version, token) => run('archive-cancel', version, { token }),
      exportArchive: version => run('archive-export', version),
      dispose() { disposed = true; unsubscribe(); idle(); listeners.clear(); }
    });
  }
  return Object.freeze({ create });
});
