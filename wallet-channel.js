(function (root, factory) {
  'use strict';
  const api = factory(typeof module === 'object' && module.exports ? require('./json-transfer.js') : root.ItalyJsonTransferClient);
  if (typeof module === 'object' && module.exports) module.exports = api;
  else root.ItalyWalletChannel = api;
}(typeof globalThis !== 'undefined' ? globalThis : this, function (json) {
  'use strict';
  const FRAME = 16384, ENVELOPE = 102400, TOTAL = 8388608;
  const encoder = new TextEncoder(), decoder = new TextDecoder('utf-8', { fatal: true });
  const keys = (o, expected) => o && typeof o === 'object' && !Array.isArray(o) &&
    Object.keys(o).sort().join('|') === expected.split(' ').sort().join('|');
  const equal = (a, b) => keys(a, 'sessionId documentId epoch requestId') &&
    ['sessionId', 'documentId', 'epoch'].every(k => a[k] === b[k]) && typeof a.requestId === 'string' && a.requestId.length > 0 && a.requestId.length <= 160;
  const hash = async (crypto, bytes) => Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256', bytes)), b => b.toString(16).padStart(2, '0')).join('');
  const base64 = bytes => { let text = ''; for (const b of bytes) text += String.fromCharCode(b); return btoa(text); };
  // Install only against the native-issued context on the trusted main document.
  // The host owns handshake/authentication; no UI method, URI or document bytes
  // are exposed here. 8MiB bounds escaped internal escrow, not personal imports.
  function create({ context, port, send, crypto, isCurrent, now = () => performance.now(), after = (ms, fn) => { const id = setTimeout(fn, ms); return () => clearTimeout(id); } }) {
    const owner = Object.freeze({ ...context });
    let pending = null, closed = false, lastId = 0n;
    function invalidate() {
      if (closed) return;
      closed = true; if (pending) pending.cancel(); pending = null;
      port.invalidate(); // Synchronous gate, including after a previously READY view.
    }
    function live(p) { return !closed && pending === p && isCurrent(owner) === true && now() < p.deadline; }
    async function complete(p) {
      try {
        const payload = new Uint8Array(p.total); let at = 0;
        for (const part of p.parts) { payload.set(part, at); at += part.length; }
        if (await hash(crypto, payload) !== p.hash) throw new Error('hash');
        if (!live(p)) { invalidate(); return; }
        const request = json.strictParse(decoder.decode(payload), TOTAL);
        if (!keys(request, 'method args') || typeof request.method !== 'string' || !Array.isArray(request.args)) throw new Error('request');
        const result = await port.invoke(p.context, request.method, request.args, () => live(p));
        if (!live(p)) { invalidate(); return; }
        const bytes = encoder.encode(JSON.stringify(result));
        if (!bytes.length || bytes.length > TOTAL) throw new Error('size');
        const digest = await hash(crypto, bytes), count = Math.ceil(bytes.length / FRAME);
        if (!live(p)) { invalidate(); return; }
        for (let seq = 0; seq < count; seq++) {
          if (!live(p)) { invalidate(); return; }
          const frame = JSON.stringify({ v: 1, context: p.context, id: p.id, seq, count, bytes: bytes.length, sha256: digest,
            data: base64(bytes.subarray(seq * FRAME, (seq + 1) * FRAME)) });
          if (encoder.encode(frame).length > ENVELOPE) throw new Error('envelope');
          if (seq === count - 1) { p.cancel(); pending = null; }
          send(frame);
        }
      } catch (_) { invalidate(); }
    }
    function receive(raw) {
      if (closed) return;
      try {
        if (isCurrent(owner) !== true || typeof raw !== 'string' || raw.length > ENVELOPE || encoder.encode(raw).length > ENVELOPE) throw new Error('context');
        const m = json.strictParse(raw, ENVELOPE);
        if (keys(m, 'v kind context') && m.v === 1 && m.kind === 'invalidate' && equal(m.context, owner)) { invalidate(); return; }
        if (!keys(m, 'v context id seq count bytes sha256 data') || m.v !== 1 || !equal(m.context, owner) ||
          typeof m.id !== 'string' || !/^[1-9][0-9]{0,18}$/.test(m.id) || BigInt(m.id) > 9223372036854775807n || !Number.isSafeInteger(m.bytes) || m.bytes < 1 || m.bytes > TOTAL ||
          !Number.isSafeInteger(m.seq) || m.seq < 0 || m.seq >= m.count || m.count !== Math.ceil(m.bytes / FRAME) ||
          !/^[0-9a-f]{64}$/.test(m.sha256) || typeof m.data !== 'string') throw new Error('frame');
        if (!pending) {
          if (m.seq !== 0 || BigInt(m.id) !== lastId + 1n) throw new Error('sequence');
          lastId = BigInt(m.id);
          const p = { context: Object.freeze({ ...m.context }), id: m.id, total: m.bytes, count: m.count, hash: m.sha256, parts: [], sequence: 0, deadline: now() + 5000, cancel: () => {} };
          pending = p; p.cancel = after(5000, invalidate);
        }
        const p = pending;
        if (!live(p)) throw new Error('deadline');
        if (p.id !== m.id || p.context.requestId !== m.context.requestId || p.sequence !== m.seq || p.total !== m.bytes || p.count !== m.count || p.hash !== m.sha256) throw new Error('sequence');
        const chunk = Uint8Array.from(atob(m.data), c => c.charCodeAt(0));
        if (chunk.length !== Math.min(FRAME, m.bytes - m.seq * FRAME) || base64(chunk) !== m.data) throw new Error('chunk');
        p.parts.push(chunk); p.sequence++;
        if (p.sequence === p.count) void complete(p);
      } catch (_) { invalidate(); }
    }
    return Object.freeze({ receive, invalidate });
  }
  return Object.freeze({ create });
}));
