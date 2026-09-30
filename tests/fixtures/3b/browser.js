'use strict';
(async function () {
  const F = SyntheticWallet, $ = id => document.getElementById(id);
  const trip = await (await fetch('../../../trip-content.json')).json();
  let wallet, dispose = () => {}, removePlaces = () => {};
  function reset() {
    dispose(); removePlaces(); wallet = F.create();
    for (const method of ['openDocument', 'attach', 'unlink', 'importDocument']) {
      const original = wallet[method]; wallet[method] = async (...args) => {
        const result = await original(...args); $('audit').textContent = 'Simulated command: ' + method + ' · ' + result.status + ' · calls: ' + wallet.calls.length; return result;
      };
    }
    dispose = ItalyAttachments.mount($('attachments'), { ...F.relation, wallet });
    removePlaces = ItalyPlaces.mount($('places'), { trip, eventId: F.relation.eventId }); $('state').value = 'READY';
  }
  $('reset').onclick = reset;
  $('state').onchange = () => wallet.advance(v => { v.state = $('state').value; });
  $('replace').onclick = () => wallet.advance(v => { v.version.walletGenerationId = 'replacement'; });
  $('uncertain').onclick = () => { wallet.openDocument = async () => ({ status: 'UNCERTAIN' }); };
  $('cancel').onclick = () => { wallet.importDocument = async () => ({ status: 'CANCELLED' }); };
  $('import').onclick = () => { wallet.importDocument = async () => { wallet.advance(v => v.documents.push({ documentId: F.id(3), displayName: 'Synthetic new booking.pdf', mediaType: 'application/pdf', byteLength: 100 })); return { status: 'SAVED' }; }; };
  $('zoom').onclick = () => { $('frame').style.fontSize = $('frame').style.fontSize ? '' : '200%'; };
  document.addEventListener('focusin', event => {
    if ($('frame').contains(event.target)) $('audit').textContent = 'Focus: ' + (event.target.getAttribute('aria-label') || event.target.textContent).slice(0,160);
  });
  function measure(host) {
    const controls = [...host.querySelectorAll('button,summary')].filter(n => n.getClientRects().length);
    const sizes = controls.map(n => ({ label: n.textContent, width: n.getBoundingClientRect().width, height: n.getBoundingClientRect().height }));
    return { width: host.getBoundingClientRect().width, overflow: host.scrollWidth > host.clientWidth, min44: sizes.every(s => s.width >= 44 && s.height >= 44), sizes };
  }
  $('measure').onclick = () => { $('report').textContent = JSON.stringify(measure($('frame')), null, 2); };
  const tick = async () => { for (let i = 0; i < 8; i++) await new Promise(r => setTimeout(r, 0)); };
  const deferred = () => { let resolve; return { promise: new Promise(r => { resolve = r; }), get resolve() { return resolve; } }; };
  const button = (host, name) => [...host.querySelectorAll('button')].find(b => b.textContent === name);
  $('run').onclick = async () => {
    $('run').disabled = true; const results = []; let cleanup = () => {};
    const assert = (value, name) => { if (!value) throw Error(name); results.push('PASS ' + name); };
    const setup = async (w = F.create()) => {
      cleanup(); const host = document.createElement('div'); $('sandbox').replaceChildren(host);
      const unmount = ItalyAttachments.mount(host, { ...F.relation, wallet: w }); cleanup = unmount; await tick(); return { w, host, unmount };
    };
    try {
      let h = await setup();
      assert(h.host.textContent.includes('<img src=x onerror=alert(1)>.pdf') && !h.host.querySelector('img,iframe,object,embed,a'), 'hostile text is literal, no document DOM or navigation');
      button(h.host, 'Attach document').click(); assert(document.activeElement === button(h.host, 'Cancel'), 'selector focus enters Cancel');
      button(h.host, 'Cancel').dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
      assert(document.activeElement === button(h.host, 'Attach document'), 'Escape restores Attach focus');
      button(h.host, 'Attach document').click(); button(h.host, 'Import document').click(); await tick();
      assert(h.w.value.attachments.length === 1 && document.activeElement === button(h.host, 'Attach document'), 'cancelled picker preserves state and focus');
      button(h.host, 'Attach document').click(); button(h.host, 'Attach Biglietto & prenotazione “Roma”.pdf').click(); await tick();
      assert(h.w.value.attachments.length === 2 && h.host.querySelectorAll('li').length === 2, 'attach renders committed notification');
      button(h.host, 'Unlink').click(); button(h.host, 'Confirm unlink').click(); await tick();
      assert(h.w.value.attachments.length === 1 && h.w.value.documents.length === 2, 'unlink preserves documents');
      h = await setup(); button(h.host, 'Attach document').click(); h.w.advance(v => { v.version.walletGenerationId = 'new'; });
      assert(!button(h.host, 'Cancel'), 'replacement invalidates selection');
      const w = F.create(), pending = deferred(); let calls = 0; w.openDocument = () => { calls++; return pending.promise; };
      h = await setup(w); const open = button(h.host, 'Open'); open.click(); open.click(); assert(calls === 1, 'double click issues one command');
      h.unmount(); const successor = await setup(); pending.resolve({ status: 'OPENED' }); await tick();
      assert(!successor.host.textContent.includes('Viewer launch accepted'), 'disposed callback cannot update successor');
      const delayed = F.create(), read = deferred(); delayed.getSnapshot = () => read.promise;
      h = await setup(delayed); delayed.advance(v => { v.state = 'READ_ONLY'; }); read.resolve(F.view()); await tick();
      assert(button(h.host, 'Open').disabled, 'late getSnapshot cannot regress notification');
      for (const state of ['READ_ONLY', 'UNSUPPORTED', 'RECOVERY_REQUIRED', 'LOADING']) {
        const v = F.view(); v.state = state; h = await setup(F.create(v));
        assert([...h.host.querySelectorAll('button')].every(b => b.disabled), state + ' gates all actions');
      }
      const invalid = F.view(); invalid.documents.shift(); h = await setup(F.create(invalid));
      assert(button(h.host, 'Attach document').disabled, 'missing document fails closed');
      const uncertain = F.create(); uncertain.openDocument = async () => ({ status: 'UNCERTAIN' }); h = await setup(uncertain);
      button(h.host, 'Open').click(); await tick(); assert(button(h.host, 'Open').disabled, 'uncertain outcome remains gated after READY refresh');
      const limits = F.create(); limits.attach = async () => ({ status: 'FAILED', code: 'LIMIT_REACHED' }); h = await setup(limits);
      button(h.host, 'Attach document').click(); button(h.host, 'Attach Biglietto & prenotazione “Roma”.pdf').click(); await tick();
      assert(h.w.value.attachments.length === 1 && h.host.textContent.includes('limit reached'), 'limit failure retains unrelated links');
      const rejected = F.create(); rejected.importDocument = async () => { throw Error('private native message'); }; h = await setup(rejected);
      button(h.host, 'Attach document').click(); button(h.host, 'Import document').click(); await tick();
      assert(button(h.host, 'Attach document').disabled && !h.host.textContent.includes('private native message'), 'rejected picker shows fixed failure and gates actions');
      const imported = F.create(); imported.importDocument = async () => { imported.advance(v => v.documents.push({ documentId: F.id(3), displayName: 'Synthetic import.pdf', mediaType: 'application/pdf', byteLength: 12 })); return { status: 'SAVED' }; };
      h = await setup(imported); button(h.host, 'Attach document').click(); button(h.host, 'Import document').click(); await tick();
      assert(imported.value.documents.length === 3 && imported.value.attachments.length === 1, 'successful import remains separate from explicit attachment');
      cleanup(); reset(); await tick(); $('frame').style.fontSize = '';
      assert(measure($('frame')).min44 && !measure($('frame')).overflow, '320px card: 44px targets and no horizontal overflow');
      $('frame').style.fontSize = '200%';
      assert(measure($('frame')).min44 && !measure($('frame')).overflow, '320px card at 200% text: no overflow and 44px targets');
      $('frame').style.fontSize = '';
      assert($('places').textContent.includes('Pantheon entry.') && $('places').textContent.includes('internet connection'), 'authored place context and Maps limitation rendered');
      results.push('Native/storage/actual device proof: NOT TESTED. Browser zoom distinct from 200% text fixture. Production shell integration: root-owned.');
    } catch (error) { results.push('FAIL ' + error.message); }
    finally { cleanup(); $('sandbox').replaceChildren(); $('report').textContent = results.join('\n'); $('run').disabled = false; }
  };
  reset();
})();
