(function (root, factory) {
  'use strict';
  const api = factory();
  if (typeof module === 'object' && module.exports) module.exports = api;
  else root.ItalyUserData = api;
})(typeof globalThis !== 'undefined' ? globalThis : this, function () {
  'use strict';
  const LIMIT = 512 * 1024;
  const MAX_REVISION = 9223372036854775807n;
  const KEYS = Object.freeze({ a: 'itguide.user.v1.a', b: 'itguide.user.v1.b', manifest: 'itguide.user.v1.manifest' });
  const queues = new WeakMap();
  const SHADOW = 'itguide.user.v1.transaction';
  const SHADOW_LIMIT = 2 * 1024 * 1024;
  const encoder = new TextEncoder();
  const fail = code => { const e = new Error(code); e.code = code; throw e; };
  const need = (condition, code = 'INVALID_DATA') => { if (!condition) fail(code); };
  const bytes = text => encoder.encode(text);
  function unicode(text) {
    need(typeof text === 'string');
    for (let i = 0; i < text.length; i++) {
      const c = text.charCodeAt(i);
      if (c >= 0xd800 && c <= 0xdbff) {
        const next = text.charCodeAt(++i);
        need(next >= 0xdc00 && next <= 0xdfff, 'INVALID_UNICODE');
      } else need(c < 0xdc00 || c > 0xdfff, 'INVALID_UNICODE');
    }
    return text;
  }
  // Deliberately small JSON parser: duplicate keys must be rejected before JSON.parse
  // can discard them. No eval, reviver, forgiving decoding, or prototype assignment.
  function parse(payload, limit = LIMIT) {
    if (payload instanceof Uint8Array) {
      need(payload.byteLength <= limit, 'FILE_LIMIT');
      try { payload = new TextDecoder('utf-8', { fatal: true, ignoreBOM: true }).decode(payload); }
      catch (_) { fail('INVALID_UTF8'); }
    }
    unicode(payload);
    need(bytes(payload).length <= limit, 'FILE_LIMIT');
    let at = 0;
    const space = () => { while (/[\x20\t\r\n]/.test(payload[at] || '\0')) at++; };
    function string() {
      const start = at++;
      while (at < payload.length) {
        const c = payload[at++];
        if (c === '"') {
          try { return unicode(JSON.parse(payload.slice(start, at))); }
          catch (e) { fail(e.code || 'INVALID_JSON'); }
        }
        if (c === '\\') at++;
      }
      fail('INVALID_JSON');
    }
    function value(depth) {
      space();
      const c = payload[at];
      if (c === '"') return string();
      if (c === '{' || c === '[') {
        need(depth < 24, 'DEPTH_LIMIT');
        at++; space();
        const object = c === '{', end = object ? '}' : ']';
        const result = object ? Object.create(null) : [];
        if (payload[at] === end) { at++; return result; }
        while (true) {
          space();
          if (object) {
            need(payload[at] === '"', 'INVALID_JSON');
            const key = string(); space();
            need(!Object.hasOwn(result, key), 'DUPLICATE_KEY');
            need(payload[at++] === ':', 'INVALID_JSON');
            result[key] = value(depth + 1);
          } else result.push(value(depth + 1));
          space();
          const next = payload[at++];
          if (next === end) return result;
          need(next === ',', 'INVALID_JSON');
        }
      }
      for (const [word, result] of [['true', true], ['false', false], ['null', null]]) {
        if (payload.startsWith(word, at)) { at += word.length; return result; }
      }
      const match = /^-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?/.exec(payload.slice(at));
      need(match, 'INVALID_JSON');
      at += match[0].length;
      const result = Number(match[0]); need(Number.isFinite(result), 'INVALID_JSON'); return result;
    }
    const result = value(0); space(); need(at === payload.length, 'INVALID_JSON'); return result;
  }
  function fields(value, required, optional = []) {
    need(value && typeof value === 'object' && !Array.isArray(value));
    need(required.every(key => Object.hasOwn(value, key)));
    need(Object.keys(value).every(key => required.includes(key) || optional.includes(key)), 'UNKNOWN_FIELD');
  }
  function text(value, max, pattern) {
    unicode(value); need([...value].length >= 1 && [...value].length <= max);
    if (pattern) need(pattern.test(value));
  }
  function revision(value) {
    text(value, 19, /^(0|[1-9][0-9]{0,18})$/);
    need(BigInt(value) <= MAX_REVISION, 'REVISION_LIMIT');
  }
  const generation = value => text(value, 36, /^gen_[0-9a-f]{32}$/);
  const phrase = value => text(value, 70, /^phrase_[a-z0-9][a-z0-9_-]{0,63}$/);
  function timestamp(value) {
    text(value, 20, /^20[0-9]{2}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$/);
    const d = new Date(value);
    need(Number.isFinite(d.getTime()) && d.toISOString().replace('.000Z', 'Z') === value, 'INVALID_TIMESTAMP');
  }
  function legacy(value) {
    if (value === null) return;
    text(value, 8192); const parsed = parse(value, 8192);
    need(parsed && typeof parsed === 'object' && !Array.isArray(parsed), 'INVALID_LEGACY_STATE');
  }
  function unique(items, key) { need(new Set(items.map(key)).size === items.length, 'DUPLICATE_ID'); }
  function validate(value) {
    fields(value, ['format', 'schemaVersion', 'revision', 'generationId', 'updatedAtUtc', 'preferences', 'legacyBuilderRaw', 'saved', 'progress', 'wallet', 'attachments']);
    need(value.format === 'itguide-user-data'); need(value.schemaVersion === 1, 'UNSUPPORTED_VERSION');
    revision(value.revision); generation(value.generationId); timestamp(value.updatedAtUtc);
    fields(value.preferences, ['slow', 'tab']); need(typeof value.preferences.slow === 'boolean');
    need(['phrases', 'builder', 'vocab', 'itinerary'].includes(value.preferences.tab));
    legacy(value.legacyBuilderRaw);
    need(Array.isArray(value.saved) && value.saved.length <= 200, 'SAVED_LIMIT');
    for (const item of value.saved) {
      fields(item, ['id', 'sourcePhraseId', 'sourceContentVersion', 'snapshot', 'createdAtUtc']);
      text(item.id, 36, /^sav_[0-9a-f]{32}$/);
      if (item.sourcePhraseId !== null) phrase(item.sourcePhraseId);
      if (item.sourceContentVersion !== null) text(item.sourceContentVersion, 64);
      fields(item.snapshot, ['it', 'en']); text(item.snapshot.it, 2000); text(item.snapshot.en, 2000);
      timestamp(item.createdAtUtc);
    }
    unique(value.saved, item => item.id);
    need(Array.isArray(value.progress) && value.progress.length <= 500, 'PROGRESS_LIMIT');
    for (const item of value.progress) {
      fields(item, ['phraseId', 'reviewCount', 'lastReviewedAtUtc']); phrase(item.phraseId);
      need(Number.isInteger(item.reviewCount) && item.reviewCount >= 0 && item.reviewCount <= 1000000, 'REVIEW_LIMIT');
      timestamp(item.lastReviewedAtUtc);
    }
    unique(value.progress, item => item.phraseId);
    need(Array.isArray(value.attachments) && value.attachments.length <= 1000);
    if (value.wallet !== null) {
      fields(value.wallet, ['generationId', 'revision']); generation(value.wallet.generationId); revision(value.wallet.revision);
    } else need(value.attachments.length === 0, 'REFERENCE_MISMATCH');
    for (const item of value.attachments) {
      fields(item, ['tripId', 'eventId', 'documentId']);
      text(item.tripId, 70, /^trip_[a-z0-9][a-z0-9_-]{0,63}$/);
      text(item.eventId, 70, /^event_[a-z0-9][a-z0-9_-]{0,63}$/);
      text(item.documentId, 36, /^doc_[0-9a-f]{32}$/);
    }
    unique(value.attachments, item => [item.tripId, item.eventId, item.documentId].join('/'));
    need(bytes(JSON.stringify(value)).length <= LIMIT, 'FILE_LIMIT');
    return value;
  }
  function pointer(p) {
    fields(p, ['slot', 'revision', 'generationId', 'sha256']); need(p.slot === 'a' || p.slot === 'b');
    revision(p.revision); generation(p.generationId); text(p.sha256, 64, /^[0-9a-f]{64}$/);
  }
  function manifest(value) {
    fields(value, ['format', 'schemaVersion', 'active', 'previous']);
    need(value.format === 'itguide-user-manifest'); need(value.schemaVersion === 1, 'UNSUPPORTED_VERSION');
    pointer(value.active);
    if (value.previous !== null) {
      pointer(value.previous);
      need(value.previous.slot !== value.active.slot && value.previous.generationId !== value.active.generationId);
      need(BigInt(value.previous.revision) + 1n === BigInt(value.active.revision), 'REVISION_CONFLICT');
    }
    return value;
  }
  function freeze(value) {
    if (value && typeof value === 'object') { Object.values(value).forEach(freeze); Object.freeze(value); }
    return value;
  }
  const clone = value => JSON.parse(JSON.stringify(value));
  const failure = e => ({ status: 'FAILED', code: e.code || 'STORAGE_ERROR' });
  function create(options = {}) {
    const storage = options.storage || globalThis.localStorage;
    const crypto = options.crypto || globalThis.crypto;
    const now = options.now || (() => new Date());
    const locks = globalThis.navigator && globalThis.navigator.locks;
    need(storage && typeof storage.getItem === 'function' && typeof storage.setItem === 'function', 'STORAGE_UNAVAILABLE');
    const listeners = new Set(), candidates = new WeakMap();
    let last = freeze({ state: 'RECOVERY_REQUIRED', snapshot: null, writable: false, reason: 'NOT_LOADED' });
    let established = null;
    let fence = null, epoch = 0, coordinated = !!options.coordinator, startupGated = coordinated;
    let verifiedNative = null;
    function publish(state, snapshot, writable, reason) {
      if ((fence || startupGated) && state !== 'RECOVERY_REQUIRED') { state = 'RECOVERING'; snapshot = null; writable = false; }
      last = freeze({ state, snapshot: snapshot ? clone(snapshot) : null, writable, ...(reason ? { reason } : {}) });
      for (const listener of listeners) { try { listener(last); } catch (_) { /* A view cannot change a committed result. */ } }
      return last;
    }
    const run = operation => {
      // Web Locks cover cooperating same-origin tabs; the host must still exclude
      // additional WebViews/processes when that API is unavailable.
      const next = (queues.get(storage) || Promise.resolve()).then(() => locks
        ? locks.request('itguide-user-v1-writer', operation) : operation());
      queues.set(storage, next.catch(() => {})); return next;
    };
    const utc = () => { const value = now().toISOString().replace(/\.\d{3}Z$/, 'Z'); timestamp(value); return value; };
    function id(prefix) {
      need(crypto && typeof crypto.getRandomValues === 'function', 'CRYPTO_UNAVAILABLE');
      return prefix + Array.from(crypto.getRandomValues(new Uint8Array(16)), b => b.toString(16).padStart(2, '0')).join('');
    }
    async function hash(raw) {
      need(crypto && crypto.subtle, 'CRYPTO_UNAVAILABLE');
      return Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256', bytes(raw))), b => b.toString(16).padStart(2, '0')).join('');
    }
    function readImage() {
      const result = { a: storage.getItem(KEYS.a), b: storage.getItem(KEYS.b), manifest: storage.getItem(KEYS.manifest) };
      need(Object.values(result).every(v => v === null || typeof v === 'string'), 'STORAGE_ERROR'); return result;
    }
    const same = (a, b) => a.a === b.a && a.b === b.b && a.manifest === b.manifest;
    async function match(p, image) {
      try {
        need(image[p.slot] !== null); const v = validate(parse(image[p.slot]));
        need(v.revision === p.revision && v.generationId === p.generationId && await hash(image[p.slot]) === p.sha256);
        return v;
      } catch (_) { return null; }
    }
    async function inspect(image, paired = false) {
      if (Object.values(image).every(v => v === null)) return { state: 'NEW', snapshot: null, writable: true };
      // An unsupported slot must never be overwritten, even if it is inactive.
      for (const slot of ['a', 'b']) if (image[slot] !== null) {
        try { const v = parse(image[slot]); if (v && v.schemaVersion !== 1) return { state: 'RECOVERY_REQUIRED', snapshot: null, writable: false, reason: 'UNSUPPORTED_VERSION' }; }
        catch (_) { /* A matching previous pointer may still provide read-only fallback. */ }
      }
      let m;
      try { m = manifest(parse(image.manifest, 2048)); }
      catch (e) { return { state: 'RECOVERY_REQUIRED', snapshot: null, writable: false, reason: e.code || 'INVALID_MANIFEST' }; }
      const active = await match(m.active, image);
      const previous = m.previous ? await match(m.previous, image) : null;
      if (active) {
        if (active.wallet !== null && !paired) return { state: 'RECOVERY_REQUIRED', snapshot: null, writable: false, reason: 'ARCHIVE_REQUIRED' };
        if (m.previous && !previous) return { state: 'RECOVERY_REQUIRED', snapshot: active, writable: false, reason: 'INVALID_PREVIOUS' };
        return { state: 'READY', snapshot: active, writable: true };
      }
      if (previous) return { state: 'FALLBACK', snapshot: previous, writable: false, reason: 'ACTIVE_CORRUPT' };
      return { state: 'RECOVERY_REQUIRED', snapshot: null, writable: false, reason: 'CORRUPT_STORAGE' };
    }
    async function persist(candidate, initial = false) {
      validate(candidate);
      const before = readImage(); need(established && same(before, established), 'REVISION_CONFLICT');
      const m = initial ? null : manifest(parse(before.manifest, 2048));
      const slot = m ? (m.active.slot === 'a' ? 'b' : 'a') : 'a';
      const raw = JSON.stringify(candidate);
      const p = { slot, revision: candidate.revision, generationId: candidate.generationId, sha256: await hash(raw) };
      need(same(readImage(), before), 'REVISION_CONFLICT');
      try {
        storage.setItem(KEYS[slot], raw);
        need(storage.getItem(KEYS[slot]) === raw, 'SLOT_READBACK');
        validate(parse(raw)); need(await hash(storage.getItem(KEYS[slot])) === p.sha256, 'SLOT_READBACK');
      } catch (e) {
        // A failed inactive write cannot change the authoritative manifest.
        try {
          const image = readImage();
          if (image.manifest !== before.manifest || (m && image[m.active.slot] !== before[m.active.slot])) throw e;
          established = image;
        } catch (_) { established = null; publish('RECOVERY_REQUIRED', last.snapshot, false, 'STORAGE_ERROR'); }
        return failure(e);
      }
      const staged = readImage();
      need(staged.manifest === before.manifest && (!m || staged[m.active.slot] === before[m.active.slot]), 'REVISION_CONFLICT');
      const rawManifest = JSON.stringify({ format: 'itguide-user-manifest', schemaVersion: 1, active: p, previous: m ? m.active : null });
      manifest(parse(rawManifest, 2048));
      try {
        storage.setItem(KEYS.manifest, rawManifest);
        const after = readImage();
        need(after.manifest === rawManifest && after[slot] === raw, 'MANIFEST_READBACK');
        const checked = await inspect(after); need(checked.state === 'READY', 'MANIFEST_READBACK');
        need(same(readImage(), after), 'MANIFEST_READBACK');
        established = after; publish('READY', candidate, true);
        return { status: 'SAVED', revision: candidate.revision, generationId: candidate.generationId };
      } catch (_) {
        established = null; publish('RECOVERY_REQUIRED', last.snapshot, false, 'UNCERTAIN');
        return { status: 'UNCERTAIN', code: 'MANIFEST_READBACK' };
      }
    }
    async function guard(expected) {
      need(!fence, 'RESTORE_CONFLICT');
      need(last.writable && last.snapshot && established, last.reason || 'READ_ONLY');
      revision(expected); need(last.snapshot.revision === expected, 'REVISION_CONFLICT');
      if (!same(readImage(), established)) {
        established = null; publish('RECOVERY_REQUIRED', last.snapshot, false, 'REVISION_CONFLICT'); fail('REVISION_CONFLICT');
      }
    }
    function next(candidate) {
      need(BigInt(last.snapshot.revision) < MAX_REVISION, 'REVISION_LIMIT');
      const importedGeneration = candidate.generationId;
      candidate.revision = String(BigInt(last.snapshot.revision) + 1n);
      candidate.generationId = id('gen_');
      const previous = established && established.manifest ? manifest(parse(established.manifest, 2048)).previous : null;
      need(candidate.generationId !== last.snapshot.generationId && candidate.generationId !== importedGeneration
        && (!previous || candidate.generationId !== previous.generationId), 'GENERATION_COLLISION');
      candidate.updatedAtUtc = utc(); return candidate;
    }
    const identity = p => ({ generationId: p.generationId, revision: p.revision, schemaVersion: 1, sha256: p.sha256 });
    const equalIdentity = (a, b) => a === null || b === null ? a === b :
      !!a && !!b && a.schemaVersion === 1 && b.schemaVersion === 1 && a.generationId === b.generationId && a.revision === b.revision && a.sha256 === b.sha256;
    function checkIdentity(p) { fields(p, ['generationId', 'revision', 'schemaVersion', 'sha256']); generation(p.generationId); revision(p.revision); need(p.schemaVersion === 1); text(p.sha256, 64, /^[0-9a-f]{64}$/); }
    function checkFence(f) { need(f && fence === f, 'STALE_CONTEXT'); }
    function binding(candidate, native) {
      if (native === null) need(candidate.wallet === null && candidate.attachments.length === 0, 'REFERENCE_MISMATCH');
      else { checkIdentity(native); need(candidate.wallet && candidate.wallet.generationId === native.generationId && candidate.wallet.revision === native.revision, 'REFERENCE_MISMATCH'); }
    }
    async function exact(expected) {
      checkIdentity(expected); const image = readImage(), checked = await inspect(image, true);
      need(checked.state === 'READY', 'RECOVERY_REQUIRED');
      const m = manifest(parse(image.manifest, 2048));
      need(equalIdentity(identity(m.active), expected) && same(readImage(), image), 'REVISION_CONFLICT');
      return { image, m, raw: image[m.active.slot], snapshot: checked.snapshot };
    }
    async function checkedStage(raw) {
      need(typeof raw === 'string', 'RECOVERY_REQUIRED'); const s = parse(raw, SHADOW_LIMIT);
      fields(s, ['v', 'transactionId', 'operation', 'sequence', 'before', 'hashes', 'raw', 'pointer', 'manifest', 'prior', 'candidate', 'native']);
      need(s.v === 1, 'UNSUPPORTED_VERSION'); text(s.transactionId, 36, /^txn_[0-9a-f]{32}$/);
      revision(s.sequence);
      need(['restore', 'delete', 'import', 'web-mutation'].includes(s.operation));
      fields(s.before, ['a', 'b', 'manifest']); fields(s.hashes, ['a', 'b', 'manifest']);
      for (const k of ['a', 'b', 'manifest']) {
        need(s.before[k] === null || typeof s.before[k] === 'string');
        need(s.hashes[k] === (s.before[k] === null ? null : await hash(s.before[k])), 'HASH_MISMATCH');
      }
      need((await inspect(s.before, true)).state === 'READY', 'RECOVERY_REQUIRED');
      const old = manifest(parse(s.before.manifest, 2048)), m = manifest(parse(s.manifest, 2048));
      checkIdentity(s.prior); checkIdentity(s.candidate); pointer(s.pointer);
      need(equalIdentity(identity(old.active), s.prior) && equalIdentity(identity(s.pointer), s.candidate), 'HASH_MISMATCH');
      need(JSON.stringify(m.active) === JSON.stringify(s.pointer) && JSON.stringify(m.previous) === JSON.stringify(old.active), 'INVALID_DATA');
      const candidate = validate(parse(s.raw)); binding(candidate, s.native);
      need(await hash(s.raw) === s.candidate.sha256 && candidate.generationId === s.candidate.generationId && candidate.revision === s.candidate.revision, 'HASH_MISMATCH');
      need(BigInt(s.candidate.revision) === BigInt(s.prior.revision) + 1n && s.candidate.generationId !== s.prior.generationId &&
        (!old.previous || old.previous.generationId !== s.candidate.generationId), 'REVISION_CONFLICT');
      return s;
    }
    const receipt = (s, phase) => ({ v: 1, transactionId: s.transactionId, operation: s.operation, store: 'web', phase, prior: s.prior, candidate: s.candidate });
    const protectedRun = (f, operation) => run(async () => {
      try { checkFence(f); return { status: 'OK', value: await operation() }; }
      catch (e) { publish('RECOVERY_REQUIRED', null, false, e.code || 'STORAGE_ERROR'); return failure(e); }
    });
    function setExact(key, raw) {
      if (raw === null) { need(typeof storage.removeItem === 'function', 'STORAGE_UNAVAILABLE'); storage.removeItem(key); }
      else storage.setItem(key, raw);
      need(storage.getItem(key) === raw, 'STORAGE_READBACK');
    }
    function rebase(candidate, source) {
      const imported = candidate.generationId; candidate.revision = String(BigInt(source.snapshot.revision) + 1n);
      candidate.generationId = id('gen_'); candidate.updatedAtUtc = utc();
      need(candidate.generationId !== imported && candidate.generationId !== source.snapshot.generationId &&
        (!source.m.previous || candidate.generationId !== source.m.previous.generationId), 'GENERATION_COLLISION');
      return validate(candidate);
    }
    function applyChange(c, change) {
      need(change && typeof change === 'object');
      switch (change.type) {
        case 'save':
          fields(change, ['type', 'snapshot', 'sourcePhraseId', 'sourceContentVersion']); fields(change.snapshot, ['italian', 'english']);
          c.saved.push({ id: id('sav_'), sourcePhraseId: change.sourcePhraseId, sourceContentVersion: change.sourceContentVersion,
            snapshot: { it: change.snapshot.italian, en: change.snapshot.english }, createdAtUtc: utc() }); break;
        case 'remove': fields(change, ['type', 'id']); need(c.saved.some(v => v.id === change.id), 'NOT_FOUND'); c.saved = c.saved.filter(v => v.id !== change.id); break;
        case 'review': {
          fields(change, ['type', 'phraseId', 'reviewedAtUtc']); phrase(change.phraseId); timestamp(change.reviewedAtUtc);
          let item = c.progress.find(v => v.phraseId === change.phraseId);
          if (!item) { item = { phraseId: change.phraseId, reviewCount: 0, lastReviewedAtUtc: change.reviewedAtUtc }; c.progress.push(item); }
          item.reviewCount++; item.lastReviewedAtUtc = change.reviewedAtUtc; break;
        }
        case 'preferences': fields(change, ['type'], ['slow', 'tab']); for (const k of ['slow', 'tab']) if (Object.hasOwn(change, k)) c.preferences[k] = change[k]; break;
        case 'builder': fields(change, ['type', 'raw']); c.legacyBuilderRaw = change.raw; break;
        case 'attach': fields(change, ['type', 'relation']); fields(change.relation, ['tripId', 'eventId', 'documentId']);
          need(typeof options.isCurrentRelation === 'function' && options.isCurrentRelation(change.relation) === true, 'REFERENCE_MISMATCH');
          c.attachments.push(clone(change.relation)); break;
        case 'unlink': fields(change, ['type', 'relation']); fields(change.relation, ['tripId', 'eventId', 'documentId']);
          c.attachments = c.attachments.filter(r => !['tripId', 'eventId', 'documentId'].every(k => r[k] === change.relation[k])); break;
        case 'delete-document': fields(change, ['type', 'documentId']); text(change.documentId, 36, /^doc_[0-9a-f]{32}$/); c.attachments = c.attachments.filter(r => r.documentId !== change.documentId); break;
        case 'native-binding': fields(change, ['type']); break;
        default: fail('UNKNOWN_MUTATION');
      }
      return c;
    }
    function legacyCandidate() {
      const slowRaw = storage.getItem('itguide.slow'), tabRaw = storage.getItem('itguide.tab');
      const slow = slowRaw === null ? false : parse(slowRaw, 32);
      const tab = tabRaw === null ? 'builder' : parse(tabRaw, 64);
      return validate({ format: 'itguide-user-data', schemaVersion: 1, revision: '0', generationId: id('gen_'), updatedAtUtc: utc(),
        preferences: { slow, tab }, legacyBuilderRaw: storage.getItem('itguide.builder'), saved: [], progress: [], wallet: null, attachments: [] });
    }
    const transactional = {
      // Must be called synchronously by the host before load/subscription mounting.
      enterRecovery: context => {
        if (fence) {
          if (!['sessionId', 'documentId', 'epoch'].every(k => fence.context[k] === context[k])) return Promise.resolve({ status: 'FAILED', code: 'STALE_CONTEXT' });
          const current = fence; return run(async () => ({ status: 'OK', value: current }));
        }
        coordinated = true; epoch++; fence = Object.freeze({ context: clone(context), epoch });
        const current = fence; publish('RECOVERING', null, false);
        return run(async () => ({ status: 'OK', value: current }));
      },
      readRecovery: f => protectedRun(f, async () => {
        const image = readImage(), raw = storage.getItem(SHADOW); let active = null, previous = null;
        try { const m = manifest(parse(image.manifest, 2048));
          if (await match(m.active, image)) active = identity(m.active);
          if (m.previous && await match(m.previous, image)) previous = identity(m.previous);
        } catch (_) { /* Recovery authority decides; never select by revision. */ }
        const transaction = raw === null ? null : await checkedStage(raw);
        need(same(readImage(), image) && storage.getItem(SHADOW) === raw, 'REVISION_CONFLICT');
        return { imageDigest: await hash(JSON.stringify(image)), active, previous, transaction };
      }),
      // Internal native-coordinator startup only, after proving native authority
      // empty. Never repair, select fallback, or overwrite a partial/corrupt image.
      initializeBaseline: f => protectedRun(f, async () => {
        need(storage.getItem(SHADOW) === null, 'RECOVERY_REQUIRED');
        const before = readImage(), checked = await inspect(before);
        checkFence(f); need(same(readImage(), before) && storage.getItem(SHADOW) === null, 'REVISION_CONFLICT');
        if (checked.state === 'NEW') {
          established = before;
          const result = await persist(legacyCandidate(), true);
          need(result.status === 'SAVED', result.code || 'RECOVERY_REQUIRED');
        } else need(checked.state === 'READY' && checked.writable && checked.snapshot.wallet === null, 'RECOVERY_REQUIRED');
        checkFence(f);
        const image = readImage(), m = manifest(parse(image.manifest, 2048));
        const expected = identity(m.active); await exact(expected);
        need(same(readImage(), image) && storage.getItem(SHADOW) === null, 'REVISION_CONFLICT');
        return expected;
      }),
      captureExact: (f, expected) => protectedRun(f, async () => ({ raw: (await exact(expected)).raw, identity: expected })),
      prepareMutation: (f, expected, change, native = verifiedNative) => protectedRun(f, async () => {
        const source = await exact(expected), c = applyChange(clone(source.snapshot), change);
        c.wallet = native === null ? null : { generationId: native.generationId, revision: native.revision };
        binding(c, native); return JSON.stringify(rebase(c, source));
      }),
      prepareRestore: (f, expected, raw, native) => protectedRun(f, async () => {
        const source = await exact(expected), c = validate(parse(raw));
        c.wallet = native === null ? null : { generationId: native.generationId, revision: native.revision };
        binding(c, native); return JSON.stringify(rebase(c, source));
      }),
      stageInactive: (f, tx, operation, expected, raw, native, sequence) => protectedRun(f, async () => {
        const source = await exact(expected), c = validate(parse(raw)); binding(c, native);
        const slot = source.m.active.slot === 'a' ? 'b' : 'a';
        const p = { slot, revision: c.revision, generationId: c.generationId, sha256: await hash(raw) };
        const hashes = {}; for (const k of ['a', 'b', 'manifest']) hashes[k] = source.image[k] === null ? null : await hash(source.image[k]);
        const s = { v: 1, transactionId: tx, operation, sequence, before: source.image, hashes, raw, pointer: p,
          manifest: JSON.stringify({ format: 'itguide-user-manifest', schemaVersion: 1, active: p, previous: source.m.active }),
          prior: expected, candidate: identity(p), native };
        const encoded = JSON.stringify(s); need(bytes(encoded).length <= SHADOW_LIMIT, 'STORAGE_LIMIT');
        await checkedStage(encoded); const old = storage.getItem(SHADOW);
        need(old === null || old === encoded, 'TRANSACTION_CONFLICT');
        need(same(readImage(), source.image), 'REVISION_CONFLICT'); setExact(SHADOW, encoded);
        need(same(readImage(), source.image), 'REVISION_CONFLICT'); return receipt(s, 'STAGED');
      }),
      readStaged: (f, tx) => protectedRun(f, async () => { const s = await checkedStage(storage.getItem(SHADOW)); need(s.transactionId === tx, 'TRANSACTION_CONFLICT'); return receipt(s, 'STAGED'); }),
      exportStage: (f, tx) => protectedRun(f, async () => { const raw = storage.getItem(SHADOW), s = await checkedStage(raw); need(s.transactionId === tx, 'TRANSACTION_CONFLICT'); return { raw, sha256: await hash(raw), byteLength: bytes(raw).length }; }),
      restoreEscrow: (f, raw, expectedHash) => protectedRun(f, async () => {
        await checkedStage(raw); need(await hash(raw) === expectedHash, 'HASH_MISMATCH');
        const old = storage.getItem(SHADOW); need(old === null || old === raw, 'TRANSACTION_CONFLICT'); setExact(SHADOW, raw);
      }),
      activateStaged: (f, tx, prepared) => protectedRun(f, async () => {
        const s = await checkedStage(storage.getItem(SHADOW));
        need(s.transactionId === tx && prepared.transactionId === tx && prepared.operation === s.operation && ['PREPARED', 'COMMITTED'].includes(prepared.phase), 'TRANSACTION_CONFLICT');
        need(equalIdentity(prepared.web.prior, s.prior) && equalIdentity(prepared.web.candidate, s.candidate) && equalIdentity(prepared.native.candidate, s.native), 'REFERENCE_MISMATCH');
        need(await hash(storage.getItem(SHADOW)) === prepared.webEscrowHash, 'HASH_MISMATCH');
        const current = readImage();
        need(current[s.pointer.slot === 'a' ? 'b' : 'a'] === s.before[s.pointer.slot === 'a' ? 'b' : 'a'] &&
          (current.manifest === s.before.manifest || current.manifest === s.manifest), 'TRANSACTION_CONFLICT');
        setExact(KEYS[s.pointer.slot], s.raw); setExact(KEYS.manifest, s.manifest);
        await exact(s.candidate); return receipt(s, 'ACTIVATED');
      }),
      restorePrevious: (f, tx, decision) => protectedRun(f, async () => {
        const s = await checkedStage(storage.getItem(SHADOW));
        need(tx === s.transactionId && decision.transactionId === tx && decision.decision === 'ROLLBACK' && equalIdentity(decision.pair.web, s.prior), 'TRANSACTION_CONFLICT');
        // Full image, including the slot displaced by activation, is preserved before any overwrite.
        setExact(KEYS.a, s.before.a); setExact(KEYS.b, s.before.b); setExact(KEYS.manifest, s.before.manifest);
        await exact(s.prior); return s.prior;
      }),
      verifyActive: (f, expected) => protectedRun(f, async () => { await exact(expected); return expected; }),
      verifyPair: (f, pair) => protectedRun(f, async () => { const source = await exact(pair.web); binding(source.snapshot, pair.native); return true; }),
      verifyRecoveryPair: (f, pair) => protectedRun(f, async () => {
        try { const source = await exact(pair.web); binding(source.snapshot, pair.native); return true; } catch (_) { /* Try retained images. */ }
        const s = await checkedStage(storage.getItem(SHADOW));
        if (equalIdentity(pair.web, s.prior)) {
          const m = manifest(parse(s.before.manifest, 2048)); binding(validate(parse(s.before[m.active.slot])), pair.native); return true;
        }
        need(equalIdentity(pair.web, s.candidate), 'REFERENCE_MISMATCH'); binding(validate(parse(s.raw)), pair.native); return true;
      }),
      verifyReceipt: (f, expected, escrowRaw) => protectedRun(f, async () => {
        const s = await checkedStage(escrowRaw), live = storage.getItem(SHADOW);
        need(live === escrowRaw && expected.v === 1 && expected.store === 'web' && expected.transactionId === s.transactionId &&
          expected.operation === s.operation && equalIdentity(expected.prior, s.prior) && equalIdentity(expected.candidate, s.candidate), 'TRANSACTION_CONFLICT');
        need(['STAGED', 'ACTIVATED'].includes(expected.phase), 'INVALID_DATA');
        if (expected.phase === 'ACTIVATED') await exact(s.candidate);
        return true;
      }),
      releaseReady: (f, checkpoint, native, admit = () => true) => protectedRun(f, async () => {
        need(typeof admit === 'function' && admit() === true, 'STALE_CONTEXT');
        need(checkpoint && checkpoint.v === 1 && equalIdentity(checkpoint.pair.native, native), 'REFERENCE_MISMATCH'); revision(checkpoint.sequence);
        const retained = storage.getItem(SHADOW);
        if (retained !== null) { const s = await checkedStage(retained); need(checkpoint.transactionId === s.transactionId &&
          (equalIdentity(checkpoint.pair.web, s.candidate) || equalIdentity(checkpoint.pair.web, s.prior)), 'RECOVERY_REQUIRED'); }
        const source = await exact(checkpoint.pair.web); binding(source.snapshot, native);
        // The trusted host supplies a synchronous lifecycle guard. Recheck after
        // hashing/readback and immediately before exposing any private snapshot.
        checkFence(f); need(admit() === true, 'STALE_CONTEXT');
        established = source.image; verifiedNative = native; fence = null; startupGated = false;
        publish('READY', source.snapshot, true); return null;
      }),
      discardStage: (f, tx, checkpoint, initialCheckpoint) => protectedRun(f, async () => {
        const s = await checkedStage(storage.getItem(SHADOW)); need(s.transactionId === tx, 'TRANSACTION_CONFLICT');
        // Host supplies a subsequent verified checkpoint after native retention/lease checks.
        need(checkpoint && initialCheckpoint && checkpoint.v === 1 && initialCheckpoint.v === 1 &&
          checkpoint.transactionId === tx && initialCheckpoint.transactionId === tx &&
          equalIdentity(checkpoint.pair.web, initialCheckpoint.pair.web) && equalIdentity(checkpoint.pair.native, initialCheckpoint.pair.native), 'RECOVERY_REQUIRED');
        revision(checkpoint.sequence); revision(initialCheckpoint.sequence);
        need(BigInt(checkpoint.sequence) > BigInt(initialCheckpoint.sequence) && BigInt(initialCheckpoint.sequence) > BigInt(s.sequence), 'RECOVERY_REQUIRED');
        const source = await exact(checkpoint.pair.web); binding(source.snapshot, checkpoint.pair.native);
        setExact(SHADOW, null);
      })
    };
    return Object.freeze({
      ...transactional,
      // Startup may hide data before a native document context exists. This
      // grants no recovery fence and never replaces an existing fence owner.
      gateStartup: () => {
        coordinated = true; startupGated = true; if (!fence) epoch++;
        return publish('RECOVERING', null, false);
      },
      load: () => run(async () => {
        try {
          if (coordinated || fence || storage.getItem(SHADOW) !== null)
            return publish('RECOVERING', null, false, 'RECOVERY_REQUIRED');
          const image = readImage(), checked = await inspect(image);
          need(same(readImage(), image), 'REVISION_CONFLICT');
          established = image;
          if (checked.state !== 'NEW') return publish(checked.state, checked.snapshot, checked.writable, checked.reason);
          const result = await persist(legacyCandidate(), true);
          if (result.status !== 'SAVED') return publish('RECOVERY_REQUIRED', null, false, result.code);
          return last;
        } catch (e) { established = null; return publish('RECOVERY_REQUIRED', null, false, e.code || 'STORAGE_ERROR'); }
      }),
      getSnapshot: async () => last,
      subscribe: listener => { need(typeof listener === 'function'); listeners.add(listener); return () => listeners.delete(listener); },
      mutate: (expectedRevision, change) => {
        const queuedEpoch = epoch;
        if (fence) return Promise.resolve({ status: 'FAILED', code: 'RESTORE_CONFLICT' });
        if (coordinated) {
          if (!options.coordinator || typeof options.coordinator.mutate !== 'function') return Promise.resolve({ status: 'FAILED', code: 'RECOVERY_REQUIRED' });
          // Delegate outside the writer queue; coordinator calls fenced methods on this same queue.
          return Promise.resolve().then(() => {
            need(epoch === queuedEpoch && !fence, 'RESTORE_CONFLICT');
            return options.coordinator.mutate(expectedRevision, clone(change));
          }).catch(failure);
        }
        return run(async () => {
          try { need(epoch === queuedEpoch, 'RESTORE_CONFLICT'); await guard(expectedRevision);
            return await persist(next(applyChange(clone(last.snapshot), change)));
          } catch (e) { return failure(e); }
        });
      },
      previewImport: async payload => {
        try {
          const candidate = parse(payload);
          need(candidate && candidate.wallet === null && Array.isArray(candidate.attachments) && candidate.attachments.length === 0, 'ARCHIVE_REQUIRED');
          validate(candidate);
          const token = Object.freeze(Object.create(null)); candidates.set(token, JSON.stringify(candidate));
          return freeze({ counts: { saved: candidate.saved.length, progress: candidate.progress.length },
            warnings: ['Replaces all Saved phrases, review progress, preferences and Builder state. Documents are not included.'], validatedCandidate: token });
        } catch (e) { return failure(e); }
      },
      replaceConfirmed: (expectedRevision, token) => run(async () => {
        try {
          need(!coordinated, 'ARCHIVE_REQUIRED');
          await guard(expectedRevision); need(last.snapshot.wallet === null, 'ARCHIVE_REQUIRED');
          need(token && candidates.has(token), 'CONFIRMATION_REQUIRED');
          const raw = candidates.get(token); candidates.delete(token);
          const c = validate(parse(raw)); need(c.wallet === null && c.attachments.length === 0, 'ARCHIVE_REQUIRED');
          return await persist(next(c));
        } catch (e) { return failure(e); }
      }),
      exportSnapshot: expectedRevision => run(async () => {
        try {
          await guard(expectedRevision); need(last.snapshot.wallet === null, 'ARCHIVE_REQUIRED');
          return { status: 'OK', payload: established[manifest(parse(established.manifest, 2048)).active.slot], revision: last.snapshot.revision };
        } catch (e) { return failure(e); }
      })
    });
  }
  return Object.freeze({ create });
});
