(function (root, factory) {
  'use strict';
  const node = typeof module === 'object' && module.exports;
  const api = factory(node ? require('./json-transfer.js') : root.ItalyJsonTransferClient,
    node ? require('./wallet-web-port.js') : root.ItalyWalletWebPort,
    node ? require('./wallet-channel.js') : root.ItalyWalletChannel);
  if (node) module.exports = api; else root.ItalyWalletHostFactory = api;
}(typeof globalThis !== 'undefined' ? globalThis : this, function (json, ports, channels) {
  'use strict';
  const owners = new WeakSet();
  function create({ store, bridge, crypto, lifecycle, after, now }) {
    // Missing platform support or crypto must not fall back to exposing private state.
    store.gateStartup();
    if (!bridge || typeof bridge.postMessage !== 'function' || owners.has(bridge)) throw new TypeError('Unavailable wallet bridge');
    owners.add(bridge);
    const nonce = Array.from(crypto.getRandomValues(new Uint8Array(16)), b => b.toString(16).padStart(2, '0')).join('');
    let context = null, channel = null, port = null, disposed = false, mutation = null, nextRequest = 0n;
    const idleListeners = new Set();
    let commandReady = false;
    const idle = () => queueMicrotask(() => { for (const listener of [...idleListeners]) { try { listener(); } catch (_) {} } });
    const clock = now || (() => performance.now());
    const schedule = after || ((ms, fn) => { const id = setTimeout(fn, ms);return () => clearTimeout(id); });
    function dispose() {
      if (disposed) return;disposed = true;
      if (mutation) { const p = mutation;mutation = null;p.cancel();p.resolve({ status: 'UNCERTAIN', code: 'RECOVERY_REQUIRED' }); }
      if (channel) channel.invalidate();else if (port) port.invalidate();
      else store.gateStartup();
      lifecycle?.removeEventListener('pagehide', dispose);
      // Deliberately do not release bridge ownership in this document. Rebinding
      // an unresolved operation requires a new document and native context.
    }
    function receive(raw) {
      if (disposed) return;
      if (channel) {
        try {
          const m = json.strictParse(raw, 102400);
          if (m.kind === 'wallet-ready') {
            if (m.v === 1 && Object.keys(m).sort().join('|') === 'context|kind|v' && m.context &&
                Object.keys(m.context).length === 4 && ['sessionId', 'documentId', 'epoch', 'requestId'].every(k => m.context[k] === context[k])) { commandReady = true; idle(); }
            return;
          }
          if (m.kind === 'wallet-result') {
            const p = mutation;
            if (!p || p.kind !== 'wallet' || m.id !== p.id || !m.context || Object.keys(m.context).length !== 4 || !['sessionId', 'documentId', 'epoch', 'requestId'].every(k => m.context[k] === context[k])) return;
            if (clock() >= p.deadline) { dispose(); return; }
            const success = ['OK', 'SAVED', 'OPENED', 'CANCELLED'].includes(m.status);
            if (m.v !== 1 || Object.keys(m).sort().join('|') !== (success ? 'context|id|kind|status|v|value' : 'code|context|id|kind|status|v') ||
                success && (!m.value || typeof m.value !== 'object' || Array.isArray(m.value)) ||
                !success && (!['FAILED', 'UNCERTAIN'].includes(m.status) || typeof m.code !== 'string' || !/^[A-Z_]{1,64}$/.test(m.code))) { dispose(); return; }
            p.cancel(); mutation = null;
            p.resolve(success ? { status: m.status, value: m.value } : { status: m.status, code: m.code }); idle(); return;
          }
          if (m.kind === 'mutation-result') {
            const p = mutation;
            if (!p || p.kind !== 'mutate' || m.id !== p.id || !m.context || Object.keys(m.context).length !== 4 || !['sessionId', 'documentId', 'epoch', 'requestId'].every(k => m.context[k] === context[k])) return;
            if (clock() >= p.deadline) { dispose();return; }
            const success = m.status === 'SAVED';
            const expected = success ? 'context|generationId|id|kind|revision|status|v' : 'code|context|id|kind|status|v';
            if (m.v !== 1 || Object.keys(m).sort().join('|') !== expected ||
              success && (typeof m.revision !== 'string' || !/^(0|[1-9][0-9]{0,18})$/.test(m.revision) || BigInt(m.revision) > 9223372036854775807n || !/^gen_[0-9a-f]{32}$/.test(m.generationId)) ||
              !success && (!['FAILED', 'UNCERTAIN'].includes(m.status) || typeof m.code !== 'string' || !/^[A-Z_]{1,64}$/.test(m.code))) { dispose();return; }
            p.cancel();mutation = null;
            p.resolve(success ? { status: 'SAVED', revision: m.revision, generationId: m.generationId } : { status: m.status, code: m.code });
            idle();
            return;
          }
          channel.receive(raw);
        } catch (_) { dispose(); }
        return;
      }
      try {
        const m = json.strictParse(raw, 2048), c = m.context;
        if (Object.keys(m).sort().join('|') !== 'context|kind|v' || m.v !== 1 || m.kind !== 'init' || !c ||
          Object.keys(c).sort().join('|') !== 'documentId|epoch|requestId|sessionId' ||
          !['sessionId', 'documentId', 'requestId'].every(k => typeof c[k] === 'string' && /^[0-9a-f-]{36}$/.test(c[k])) ||
          typeof c.epoch !== 'string' || !/^[1-9][0-9]{0,18}$/.test(c.epoch) || BigInt(c.epoch) > 9223372036854775807n) throw new Error('context');
        context = Object.freeze({ ...c });
        port = ports.create(store, context, owner => !disposed && owner.documentId === context.documentId && owner.epoch === context.epoch);
        channel = channels.create({ context, port, crypto, isCurrent: () => !disposed, after, now,
          send: frame => { if (!disposed) bridge.postMessage(frame); } });
        bridge.postMessage(JSON.stringify({ v: 1, kind: 'bound', context, nonce }));
      } catch (_) { dispose(); }
    }
    lifecycle?.addEventListener('pagehide', dispose);
    function invalidate(expected) {
      if (context && expected && ['sessionId', 'documentId', 'epoch', 'requestId'].every(k => expected[k] === context[k])) dispose();
    }
    function hello() { if (!disposed && !context) bridge.postMessage(JSON.stringify({ v: 1, kind: 'hello', nonce })); }
    function accept(expected, raw) { if (!disposed && !context && expected === nonce) receive(raw); }
    function deliver(expected, raw) { if (!disposed && channel && expected === nonce) receive(raw); }
    function mutate(expectedRevision, change) {
      return submit('mutate', { expectedRevision, change });
    }
    function wallet(op, expected, args = {}) { return submit('wallet', { op, expected, args }); }
    function submit(kind, fields) {
      if (disposed || !context || !channel) return Promise.resolve({ status: 'FAILED', code: 'RECOVERY_REQUIRED' });
      if (!commandReady) return Promise.resolve({ status: 'FAILED', code: 'BUSY' });
      if (mutation) return Promise.resolve({ status: 'FAILED', code: 'BUSY' });
      let request;
      try {
        if (nextRequest === 9223372036854775807n) throw new Error('limit');
        request = JSON.stringify({ v: 1, kind, context, id: String(nextRequest + 1n), ...fields });
        json.strictParse(request, 102400); // Freeze click-time data and bound actual UTF-8 before native admission.
      } catch (_) { return Promise.resolve({ status: 'FAILED', code: 'INVALID_REQUEST' }); }
      const id = String(++nextRequest);
      return new Promise(resolve => {
        // Picker budget (120s) precedes the independent transfer budget (180s).
        const budget = kind === 'wallet' && ['import-document', 'archive-preview', 'archive-export'].includes(fields.op) ? 300000 : 180000;
        const p = { id, kind, resolve, deadline: clock() + budget, cancel: () => {} };mutation = p;
        p.cancel = schedule(budget, dispose);
        try { if (!disposed && mutation === p) bridge.postMessage(request);else p.cancel(); }catch (_) { dispose(); }
      });
    }
    return Object.freeze({ hello, accept, deliver, dispose, invalidate, mutate, wallet,
      subscribeIdle(listener) { idleListeners.add(listener); return () => idleListeners.delete(listener); } });
  }
  return Object.freeze({ create });
}));
