// Test-only IPC adapter. Runs the shipping UserData writer against observable storage.
'use strict';
const { create } = require('../../user-data.js');
const { webcrypto } = require('node:crypto');
const readline = require('node:readline');
const map = new Map(); let fault = null, fence, store, port, channel;
const productionPort = process.argv.includes('--production-port');
const storage = {
  getItem: k => map.get(k) ?? null,
  setItem: (k, v) => {
    if (fault && k.endsWith(fault.key)) {
      const f = fault; fault = null; if (f.after) map.set(k, v); throw new Error('test storage fault');
    }
    map.set(k, v);
  }, removeItem: k => map.delete(k)
};
const options = { storage, crypto: webcrypto, now: () => new Date('2030-06-10T12:00:00Z') };
store = create(options);
function identity() { const p = JSON.parse(map.get('itguide.user.v1.manifest')).active; return { generationId: p.generationId, revision: p.revision, schemaVersion: 1, sha256: p.sha256 }; }
async function invoke(m, a) {
  if (m === 'legacy') { for (const [key, value] of Object.entries(a[0])) map.set(key, value);return { status: 'OK' }; }
  if (m === 'bind-channel') {
    port = require('../../wallet-web-port.js').create(store, a[0], () => true);
    channel = require('../../wallet-channel.js').create({ context: a[0], port, crypto: webcrypto, isCurrent: () => true,
      send: raw => process.stdout.write(raw + '\n') });
    return { status: 'OK' };
  }
  if (m === 'channel-frame') { channel.receive(a[0]); return undefined; }
  if (m === 'init') { await store.load(); return { status: 'OK', value: identity() }; }
  if (m === 'state') return { status: 'OK', value: await store.getSnapshot() };
  if (m === 'raw') return { status: 'OK', value: Object.fromEntries(map) };
  if (m === 'corrupt-all') { for (const key of ['a', 'b', 'manifest', 'transaction']) if (map.has('itguide.user.v1.' + key)) map.set('itguide.user.v1.' + key, '{}'); return { status: 'OK' }; }
  if (m === 'fault') { fault = a[0]; return { status: 'OK' }; }
  if (m === 'restart') { options.coordinator = {}; store = create(options); return { status: 'OK', value: await store.load() }; }
  if (m === 'enterRecovery' && productionPort) {
    if (!port) port = require('../../wallet-web-port.js').create(store, a[0], () => true);
    return port.invoke(a[0], m, []);
  }
  if (productionPort && m === 'port') return port.invoke(a[0], a[1], a[2]);
  if (m === 'enterRecovery') { const r = await store.enterRecovery(a[0]); fence = r.value; return { status: r.status, code: r.code, value: true }; }
  if (m === 'prepareCandidate') {
    const [op, prior, native, input] = a;
    return op === 'restore' ? store.prepareRestore(fence, prior, input, native) : store.prepareMutation(fence, prior, JSON.parse(input), native);
  }
  const r = await store[m](fence, ...a);
  if (m === 'captureExact' && r.status === 'OK') r.value = r.value.raw;
  if (m === 'readRecovery' && r.status === 'OK') r.value = { active: r.value.active, previous: r.value.previous, transactionId: r.value.transaction?.transactionId ?? null };
  return r;
}
let tail = Promise.resolve();
readline.createInterface({ input: process.stdin }).on('line', line => {
  tail = tail.then(async () => { try { const request = JSON.parse(line); const result = await invoke(request.method, request.args); if (result !== undefined) process.stdout.write(JSON.stringify(result) + '\n'); }
    catch (e) { process.stdout.write(JSON.stringify({ status: 'FAILED', code: e.code || 'IO_ERROR' }) + '\n'); } });
});
