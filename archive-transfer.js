(function (root, factory) {
  'use strict';
  const api = factory();
  if (typeof module === 'object' && module.exports) module.exports = api;
  else root.ItalyArchiveTransfer = api;
})(typeof globalThis !== 'undefined' ? globalThis : this, function () {
  'use strict';
  const CHUNK = 16384, LIMIT = 524288, MAX_CHUNKS = 32, ENVELOPE = 102400;
  const encoder = new TextEncoder();
  const fail = code => { const e = new Error(code); e.code = code; throw e; };
  const need = (v, c = 'INVALID_REQUEST') => { if (!v) fail(c); };
  const clone = v => JSON.parse(JSON.stringify(v));
  const fields = (v, keys) => need(v && typeof v === 'object' && !Array.isArray(v) && Object.keys(v).length === keys.length && keys.every(k => Object.hasOwn(v, k)));
  function unicode(s) {
    need(typeof s === 'string');
    for (let i = 0; i < s.length; i++) { const c = s.charCodeAt(i); if (c >= 0xd800 && c <= 0xdbff) { const d = s.charCodeAt(++i); need(d >= 0xdc00 && d <= 0xdfff); } else need(c < 0xdc00 || c > 0xdfff); }
    return s;
  }
  async function hash(raw, crypto) { return Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256', encoder.encode(raw))), b => b.toString(16).padStart(2, '0')).join(''); }
  function revision(s) { need(typeof s === 'string' && /^(0|[1-9][0-9]{0,18})$/.test(s) && BigInt(s) <= 9223372036854775807n); }
  function identity(v) {
    fields(v, ['generationId', 'revision', 'schemaVersion', 'sha256']); revision(v.revision);
    need(v.schemaVersion === 1 && /^gen_[0-9a-f]{32}$/.test(v.generationId) && /^[0-9a-f]{64}$/.test(v.sha256));
  }
  function pair(v) { fields(v, ['web', 'native']); identity(v.web); if (v.native !== null) identity(v.native); }
  const same = (a, b) => JSON.stringify(a) === JSON.stringify(b);
  async function chunks(raw, transferId, crypto = globalThis.crypto) {
    unicode(raw); need(/^[a-zA-Z0-9_-]{1,128}$/.test(transferId));
    need(encoder.encode(raw).length > 0 && encoder.encode(raw).length <= LIMIT, 'FILE_LIMIT');
    const parts = []; let part = '', size = 0;
    for (const scalar of raw) {
      const length = encoder.encode(scalar).length;
      if (size + length > CHUNK) { parts.push(part); part = ''; size = 0; }
      part += scalar; size += length;
    }
    if (part) parts.push(part); need(parts.length <= MAX_CHUNKS, 'FILE_LIMIT');
    const sha256 = await hash(raw, crypto);
    return parts.map((text, sequence) => ({ v: 1, transferId, sequence, count: parts.length, sha256, text }));
  }
  function assembler(transferId, count, sha256, crypto = globalThis.crypto) {
    need(/^[a-zA-Z0-9_-]{1,128}$/.test(transferId) && Number.isInteger(count) && count >= 1 && count <= MAX_CHUNKS && /^[0-9a-f]{64}$/.test(sha256));
    let next = 0, total = 0, raw = '', failed = false;
    return Object.freeze({ accept: async frame => {
      try {
        need(!failed); fields(frame, ['v', 'transferId', 'sequence', 'count', 'sha256', 'text']);
        need(frame.v === 1 && frame.transferId === transferId && frame.count === count && frame.sequence === next && frame.sha256 === sha256, 'TRANSACTION_CONFLICT');
        unicode(frame.text); const size = encoder.encode(frame.text).length;
        need(size > 0 && size <= CHUNK && total + size <= LIMIT && encoder.encode(JSON.stringify(frame)).length <= ENVELOPE, 'FILE_LIMIT');
        next++; total += size; raw += frame.text;
        if (next < count) return null;
        failed = true; need(await hash(raw, crypto) === sha256, 'HASH_MISMATCH'); return raw;
      } catch (e) { failed = true; throw e; }
    } });
  }
  /** Inject a root-owned trusted native transport; no URI, file bytes or raw error text. */
  function create({ transport, context, now = () => Date.now() }) {
    need(typeof transport === 'function' && typeof context === 'function');
    const previews = new WeakMap(); let busy = false, disposed = false, epoch = 0;
    const failure = code => ({ status: 'FAILED', code });
    async function run(op, args) {
      if (disposed) return failure('STALE_CONTEXT');
      if (busy) return failure('BUSY');
      busy = true; const c = clone(context()), e = epoch;
      try {
        const result = await transport(Object.freeze({ v: 1, op, context: c, ...clone(args) }));
        if (disposed || e !== epoch || !same(c, context())) return { status: 'UNCERTAIN', code: 'RECOVERY_REQUIRED' };
        need(result && ['OK', 'FAILED', 'UNCERTAIN'].includes(result.status), 'INVALID_REPLY');
        if (result.status === 'UNCERTAIN') return { status: 'UNCERTAIN', code: 'RECOVERY_REQUIRED' };
        if (result.status === 'FAILED') return failure(typeof result.code === 'string' && /^[A-Z_]{1,64}$/.test(result.code) ? result.code : 'IO_ERROR');
        return { status: 'OK', value: result.value };
      } catch (_) { return { status: 'UNCERTAIN', code: 'RECOVERY_REQUIRED' }; }
      finally { busy = false; }
    }
    return Object.freeze({
      disclosure: 'This unencrypted archive includes personal text and documents. Restore replaces current personal data and all documents.',
      preview: async expected => {
        try { pair(expected); } catch (_) { return failure('INVALID_REQUEST'); }
        const c = clone(context()), result = await run('archive-preview', { expected });
        if (result.status !== 'OK') return result;
        const v = result.value;
        if (!v || typeof v.token !== 'string' || !/^preview_[0-9a-f]{32}$/.test(v.token) || !Number.isInteger(v.documents) || v.documents < 0 || v.documents > 50) return failure('INVALID_REPLY');
        const token = Object.freeze({}); previews.set(token, { native: v.token, expected: clone(expected), context: c, expires: now() + 300000 });
        return { status: 'OK', value: { token, documents: v.documents, requiresConfirmation: true } };
      },
      confirm: async (token, expected) => {
        const p = token && previews.get(token);
        if (!p || now() >= p.expires || !same(p.context, context())) return failure('CONFIRMATION_REQUIRED');
        if (!same(p.expected, expected)) return failure('RESTORE_CONFLICT');
        if (busy) return failure('BUSY'); previews.delete(token);
        return run('archive-confirm', { token: p.native, expected });
      },
      cancel: async token => {
        const p = token && previews.get(token); if (!p) return failure('CONFIRMATION_REQUIRED');
        if (busy) return failure('BUSY'); previews.delete(token);
        return run('archive-cancel-preview', { token: p.native });
      },
      export: expected => { try { pair(expected); return run('archive-export', { expected }); } catch (_) { return Promise.resolve(failure('INVALID_REQUEST')); } },
      recover: () => run('archive-recover', {}),
      recoverPrevious: () => run('archive-recover-previous', {}),
      dispose: () => { disposed = true; epoch++; }
    });
  }
  return Object.freeze({ create, chunks, assembler, CHUNK, LIMIT, ENVELOPE });
});
