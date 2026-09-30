(function (root, factory) {
  'use strict';
  const api = factory();
  if (typeof module === 'object' && module.exports) module.exports = api;
  else root.ItalyJsonTransferClient = api;
})(typeof globalThis === 'object' ? globalThis : this, function () {
  'use strict';
  const LIMIT = 524288, FRAME = 2097152;
  const owners = new WeakSet();
  const idPattern = /^(ses|req)_[0-9a-f]{32}$/;
  const codes = new Set(['UNSUPPORTED', 'BUSY', 'INVALID_REQUEST', 'INVALID_UTF8', 'MALFORMED',
    'FILE_LIMIT', 'UNSUPPORTED_SCHEMA', 'INVALID_DATA', 'PERMISSION_DENIED', 'PROVIDER_UNAVAILABLE',
    'IO_ERROR', 'INTERRUPTED', 'TIMEOUT']);
  function bytes(text) {
    if (typeof text !== 'string') throw new Error('INVALID_DATA');
    let n = 0;
    for (let i = 0; i < text.length; i++) {
      const c = text.charCodeAt(i);
      if (c >= 0xd800 && c <= 0xdbff) {
        const d = text.charCodeAt(++i);
        if (!(d >= 0xdc00 && d <= 0xdfff)) throw new Error('INVALID_UTF8');
        n += 4;
      } else if (c >= 0xdc00 && c <= 0xdfff) throw new Error('INVALID_UTF8');
      else n += c < 128 ? 1 : c < 2048 ? 2 : 3;
    }
    return n;
  }
  // Scan structure before JSON.parse so duplicate keys cannot disappear during decoding.
  function strictParse(raw, limit = FRAME) {
    if (!Number.isSafeInteger(limit) || limit < 1 || limit > 8388608 || typeof raw !== 'string' || raw.length > limit || bytes(raw) > limit) throw new Error('FILE_LIMIT');
    let at = 0;
    const ws = () => { while (/[ \r\n\t]/.test(raw[at] || 'x')) at++; };
    const string = () => {
      if (raw[at++] !== '"') throw new Error('MALFORMED');
      const start = at - 1;
      while (at < raw.length) {
        const c = raw[at++];
        if (c === '\\') at++;
        else if (c === '"') { const s = JSON.parse(raw.slice(start, at)); bytes(s); return s; }
      }
      throw new Error('MALFORMED');
    };
    function value(depth) {
      ws(); const c = raw[at];
      if (c === '"') { string(); return; }
      if (c === '{' || c === '[') {
        if (depth >= 24) throw new Error('MALFORMED');
        at++; ws(); const end = c === '{' ? '}' : ']'; const keys = new Set();
        if (raw[at] === end) { at++; return; }
        for (;;) {
          if (c === '{') {
            ws(); const key = string();
            if (keys.has(key)) throw new Error('MALFORMED'); keys.add(key);
            ws(); if (raw[at++] !== ':') throw new Error('MALFORMED');
          }
          value(depth + 1); ws();
          if (raw[at] === end) { at++; return; }
          if (raw[at++] !== ',') throw new Error('MALFORMED');
        }
      }
      const start = at;
      while (at < raw.length && !/[\s,}\]]/.test(raw[at])) at++;
      if (at === start) throw new Error('MALFORMED');
      const item = JSON.parse(raw.slice(start, at));
      if (typeof item === 'number' && !Number.isFinite(item)) throw new Error('MALFORMED');
    }
    value(0); ws(); if (at !== raw.length) throw new Error('MALFORMED');
    return JSON.parse(raw);
  }
  function exact(value, keys) {
    return value && typeof value === 'object' && !Array.isArray(value) &&
      Object.keys(value).length === keys.length && keys.every(k => Object.hasOwn(value, k));
  }
  function validResult(r) {
    const base = ['v', 'sessionId', 'requestId', 'op', 'status'];
    if (!r || r.v !== 1 || !idPattern.test(r.sessionId) || !r.sessionId.startsWith('ses_') ||
        !idPattern.test(r.requestId) || !r.requestId.startsWith('req_') || !['import', 'export', 'cancel'].includes(r.op)) return false;
    if (r.status === 'error') return exact(r, [...base, 'code', 'externalEffect']) && codes.has(r.code) &&
      ['none', 'partial', 'unknown'].includes(r.externalEffect) && (r.op !== 'import' || r.externalEffect === 'none');
    if (r.status === 'cancelled') return r.op !== 'cancel' && exact(r, [...base, 'externalEffect']) &&
      ['none', 'partial', 'unknown'].includes(r.externalEffect) && (r.op !== 'import' || r.externalEffect === 'none');
    if (r.status !== 'ok') return false;
    if (r.op === 'cancel') return exact(r, [...base, 'outcome']) && ['requested', 'already-terminal'].includes(r.outcome);
    if (r.op === 'export') return exact(r, [...base, 'bytesWritten', 'externalEffect']) && r.externalEffect === 'complete' &&
      Number.isInteger(r.bytesWritten) && r.bytesWritten > 0 && r.bytesWritten <= LIMIT;
    return exact(r, [...base, 'payload', 'externalEffect']) && r.externalEffect === 'none' &&
      typeof r.payload === 'string' && bytes(r.payload) > 0 && bytes(r.payload) <= LIMIT;
  }
  function create(options = {}) {
    const g = typeof globalThis === 'object' ? globalThis : {};
    const bridge = Object.hasOwn(options, 'bridge') ? options.bridge : g.ItalyJsonTransfer;
    const random = options.crypto || g.crypto;
    const clock = options.timers || g;
    const lifecycle = Object.hasOwn(options, 'lifecycle') ? options.lifecycle : g;
    let session = null, hello = null, active = null, disposed = false, used = new Set(), cancelling = null;
    const pending = new Map();
    const available = !!(bridge && typeof bridge.postMessage === 'function' && random &&
      typeof random.getRandomValues === 'function' && !owners.has(bridge));
    const localSession = 'ses_' + '0'.repeat(32);
    function id() {
      for (let attempt = 0; attempt < 8; attempt++) {
        const data = new Uint8Array(16); random.getRandomValues(data);
        const result = 'req_' + Array.from(data, x => x.toString(16).padStart(2, '0')).join('');
        if (!used.has(result)) { used.add(result); return result; }
      }
      throw new Error('UNSUPPORTED');
    }
    function error(op, code, requestId, effect = 'none') {
      return { v: 1, sessionId: session || localSession, requestId: requestId || 'req_' + '0'.repeat(32),
        op, status: 'error', code, externalEffect: effect };
    }
    function post(frame) {
      const raw = JSON.stringify(frame);
      if (bytes(raw) > FRAME) throw new Error('FILE_LIMIT');
      bridge.postMessage(raw);
    }
    function settle(requestId, result) {
      const item = pending.get(requestId); if (!item) return;
      pending.delete(requestId); clock.clearTimeout(item.timer);
      if (active === requestId) active = null;
      item.resolve(Object.freeze(result));
    }
    function ready() {
      if (session && used.size < 1000) return Promise.resolve(true);
      if (hello) return hello.promise;
      let resolve;
      const promise = new Promise(r => { resolve = r; });
      hello = { promise, resolve, timer: clock.setTimeout(() => {
        const old = hello; hello = null; if (old) old.resolve(false);
      }, 5000) };
      try { post({ v: 1, op: 'hello' }); }
      catch { clock.clearTimeout(hello.timer); hello = null; resolve(false); }
      return promise;
    }
    function receive(event) {
      if (disposed) return;
      try {
        const r = strictParse(event.data);
        if (exact(r, ['v', 'op', 'sessionId']) && r.v === 1 && r.op === 'ready' && /^ses_[0-9a-f]{32}$/.test(r.sessionId)) {
          if (!hello || pending.size) return;
          if (session !== r.sessionId) { session = r.sessionId; used = new Set(); }
          const old = hello; hello = null; clock.clearTimeout(old.timer); old.resolve(true); return;
        }
        const item = pending.get(r.requestId);
        if (validResult(r) && r.sessionId === session && item?.op === r.op &&
            !(r.op === 'export' && r.status === 'ok' && r.bytesWritten !== item.bytes)) settle(r.requestId, r);
      } catch { /* Untrusted/malformed/stale results cannot complete another request. Watchdog remains. */ }
    }
    function send(op, extra, watchdog = 155000) {
      let requestId;
      try { requestId = id(); } catch { return Promise.resolve(error(op, 'UNSUPPORTED')); }
      const frame = { v: 1, sessionId: session, requestId, op, ...extra };
      return new Promise(resolve => {
        const timer = clock.setTimeout(() => {
          if (op !== 'cancel') cancel();
          settle(requestId, error(op, 'TIMEOUT', requestId, op === 'export' ? 'unknown' : 'none'));
        }, watchdog);
        pending.set(requestId, { op, resolve, timer, bytes: op === 'export' ? bytes(extra.payload) : 0 });
        if (op !== 'cancel') active = requestId;
        try { post(frame); } catch (e) { settle(requestId, error(op, e.message === 'FILE_LIMIT' ? 'FILE_LIMIT' : 'PROVIDER_UNAVAILABLE', requestId)); }
      });
    }
    let starting = null;
    async function transfer(op, payload) {
      if (disposed || !available) return error(op, disposed ? 'INTERRUPTED' : 'UNSUPPORTED');
      if (active || starting || pending.size) return error(op, 'BUSY');
      if (op === 'export') {
        try {
          if (!payload || bytes(payload) > LIMIT) return error(op, 'FILE_LIMIT');
          strictParse(payload);
        } catch (e) { return error(op, codes.has(e.message) ? e.message : 'MALFORMED'); }
      }
      const startup = { cancelled: false, cancel: null };
      const cancelled = new Promise(resolve => { startup.cancel = () => resolve(false); });
      starting = startup;
      let ok;
      try {
        // Use the final slot for a harmless cancellation acknowledgment, leaving no active
        // operation stranded at the cap without an available cancel request ID.
        const initialize = async () => {
          if (session && used.size === 999) await send('cancel', { targetRequestId: [...used][998] }, 5000);
          if (startup.cancelled || disposed) return false;
          return ready();
        };
        ok = await Promise.race([initialize(), cancelled]);
      } finally { if (starting === startup) starting = null; }
      if (disposed) return error(op, 'INTERRUPTED');
      if (startup.cancelled) return Object.freeze({ v: 1, sessionId: session || localSession,
        requestId: 'req_' + '0'.repeat(32), op, status: 'cancelled', externalEffect: 'none' });
      if (!ok) return error(op, 'UNSUPPORTED');
      return send(op, op === 'export' ? { payload } : {});
    }
    function cancel() {
      if (!available || disposed) return Promise.resolve(error('cancel', disposed ? 'INTERRUPTED' : 'UNSUPPORTED'));
      if (starting) {
        const wasCancelled = starting.cancelled;
        starting.cancelled = true; starting.cancel();
        if (hello) { const old = hello; hello = null; clock.clearTimeout(old.timer); old.resolve(false); }
        return Promise.resolve(Object.freeze({ v: 1, sessionId: session || localSession,
          requestId: 'req_' + '0'.repeat(32), op: 'cancel', status: 'ok', outcome: wasCancelled ? 'already-terminal' : 'requested' }));
      }
      if (cancelling) return cancelling;
      if (!active) return Promise.resolve(Object.freeze({ v: 1, sessionId: session || localSession,
        requestId: 'req_' + '0'.repeat(32), op: 'cancel', status: 'ok', outcome: 'already-terminal' }));
      if (used.size >= 1000) return Promise.resolve(error('cancel', 'INVALID_REQUEST'));
      cancelling = send('cancel', { targetRequestId: active }, 5000).finally(() => { cancelling = null; });
      return cancelling;
    }
    function dispose() {
      if (disposed) return;
      // Best-effort explicit cancel while this document still exists; native invalidation is authoritative.
      if (active && available) {
        try { post({ v: 1, sessionId: session, requestId: id(), op: 'cancel', targetRequestId: active }); } catch { /* no guarantee */ }
      }
      disposed = true;
      if (starting) { starting.cancelled = true; starting.cancel(); }
      if (hello) { clock.clearTimeout(hello.timer); hello.resolve(false); hello = null; }
      for (const [requestId, item] of pending) settle(requestId, error(item.op, 'INTERRUPTED', requestId, item.op === 'export' ? 'unknown' : 'none'));
      if (available) { if (bridge.onmessage === receive) bridge.onmessage = null; owners.delete(bridge); }
      if (lifecycle?.removeEventListener) lifecycle.removeEventListener('pagehide', dispose);
    }
    if (available) { owners.add(bridge); bridge.onmessage = receive; }
    if (lifecycle?.addEventListener) lifecycle.addEventListener('pagehide', dispose);
    return Object.freeze({ available, importJson: () => transfer('import'), exportJson: payload => transfer('export', payload), cancel, dispose });
  }
  return Object.freeze({ create, strictParse });
});
