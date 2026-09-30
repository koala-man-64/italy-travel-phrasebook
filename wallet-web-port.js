(function (root, factory) {
  'use strict';
  const api = factory();
  if (typeof module === 'object' && module.exports) module.exports = api;
  else root.ItalyWalletWebPort = api;
}(typeof globalThis !== 'undefined' ? globalThis : this, function () {
  'use strict';
  // Internal adapter for the authenticated native coordinator channel. Never dispatch
  // page-provided method names directly against UserData or expose its fence object.
  const arities = Object.freeze({ readRecovery: 0, initializeBaseline: 0, captureExact: 1, prepareCandidate: 4,
    stageInactive: 6, readStaged: 1, exportStage: 1, restoreEscrow: 2,
    activateStaged: 2, restorePrevious: 2, verifyActive: 1, verifyPair: 1,
    verifyRecoveryPair: 1, verifyReceipt: 2, releaseReady: 2, discardStage: 3 });
  const failed = code => ({ status: 'FAILED', code });
  function validContext(c) {
    return c && typeof c === 'object' && !Array.isArray(c) &&
      ['sessionId', 'documentId', 'epoch', 'requestId'].every(k =>
        typeof c[k] === 'string' && c[k].length > 0 && c[k].length <= 160) &&
      Object.keys(c).length === 4 && /^(0|[1-9][0-9]{0,18})$/.test(c.epoch) &&
      BigInt(c.epoch) <= 9223372036854775807n;
  }
  function create(store, context, isCurrent) {
    if (!validContext(context) || typeof isCurrent !== 'function') throw new TypeError('Invalid wallet context');
    const owner = Object.freeze({ ...context });
    let disposed = false, fence = null, tail = Promise.resolve();
    // UserData.enterRecovery gates synchronously, before its queued result resolves.
    const initial = store.enterRecovery(owner).then(r => {
      if (r.status === 'OK') fence = r.value;
      return r.status === 'OK' ? { status: 'OK', value: null } : r;
    });
    const live = c => !disposed && validContext(c) &&
      ['sessionId', 'documentId', 'epoch'].every(k => c[k] === owner[k]) && isCurrent(owner) === true;
    function invoke(c, method, args, admit = () => true) {
      // Copy at submission: asynchronous queuing must not observe caller mutation.
      let frozenContext, values;
      try {
        if (typeof admit !== 'function' || admit() !== true || !live(c) || !Object.prototype.hasOwnProperty.call(arities, method) && method !== 'enterRecovery') return Promise.resolve(failed('STALE_CONTEXT'));
        if (!Array.isArray(args) || args.length !== (method === 'enterRecovery' ? 0 : arities[method])) return Promise.resolve(failed('INVALID_REQUEST'));
        frozenContext = { ...c }; values = JSON.parse(JSON.stringify(args));
      } catch (_) { return Promise.resolve(failed('INVALID_REQUEST')); }
      const allowed = () => live(frozenContext) && admit() === true;
      const task = tail.then(async () => {
        if (!allowed()) return failed('STALE_CONTEXT');
        const ready = await initial;
        if (ready.status !== 'OK') return ready;
        if (!allowed()) return failed('STALE_CONTEXT');
        if (method === 'enterRecovery') {
          const r = await store.enterRecovery(owner);
          if (r.status === 'OK') fence = r.value;
          return allowed() ? { status: r.status, ...(r.code ? { code: r.code } : {}), value: null } : failed('STALE_CONTEXT');
        }
        if (!fence) return failed('RECOVERY_REQUIRED');
        let r;
        if (method === 'prepareCandidate') {
          const [operation, prior, native, input] = values;
          if (operation === 'restore') r = await store.prepareRestore(fence, prior, input, native);
          else if (['import', 'delete', 'web-mutation'].includes(operation)) {
            if (typeof input !== 'string') return failed('INVALID_REQUEST');
            r = await store.prepareMutation(fence, prior, JSON.parse(input), native);
          } else return failed('INVALID_REQUEST');
        } else if (method === 'releaseReady') {
          r = await store.releaseReady(fence, ...values, allowed);
        } else {
          // The closed, own-property table above is the sole method admission list.
          r = await store[method](fence, ...values);
        }
        if (method === 'releaseReady' && r.status === 'OK') fence = null;
        // Lifecycle cancellation cannot undo a completed write; suppress its old result.
        if (!allowed()) {
          await store.enterRecovery(owner);
          return failed('STALE_CONTEXT');
        }
        if (r.status !== 'OK') return r;
        if (method === 'captureExact') return { status: 'OK', value: r.value.raw };
        if (method === 'readRecovery') return { status: 'OK', value: { active: r.value.active,
          previous: r.value.previous, transactionId: r.value.transaction?.transactionId ?? null } };
        return r;
      }).catch(() => ({ status: 'UNCERTAIN', code: 'RECOVERY_REQUIRED' }));
      tail = task.then(() => undefined);
      return task;
    }
    return Object.freeze({ invoke, ready: initial, invalidate() {
      disposed = true;
      // Hide a previously READY snapshot synchronously; no lifecycle event is a commit.
      store.enterRecovery(owner).catch(() => undefined);
    } });
  }
  return Object.freeze({ create });
}));
