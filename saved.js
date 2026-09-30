(function (root, factory) {
  'use strict';
  const api = factory();
  if (typeof module === 'object' && module.exports) module.exports = api;
  else root.ItalySaved = api;
})(typeof globalThis !== 'undefined' ? globalThis : this, function () {
  'use strict';
  function element(doc, tag, text, className) {
    const node = doc.createElement(tag);
    if (text !== undefined) node.textContent = text;
    if (className) node.className = className;
    return node;
  }
  function resultMessage(result) {
    if (result && result.status === 'SAVED') return 'Saved on this device.';
    if (result && result.status === 'UNCERTAIN') return 'Save could not be verified. Changes are locked. Reload to check recovery before trying again.';
    return 'No verified change: ' + (result && result.code || 'UNAVAILABLE') + '.';
  }
  function mount(host, { store, speech, transfer } = {}) {
    const doc = host.ownerDocument;
    const section = element(doc, 'section', undefined, 'italy-saved');
    section.setAttribute('aria-label', 'Saved phrases');
    const title = element(doc, 'h2', 'Saved phrases');
    const status = element(doc, 'p', 'Loading personal data…', 'saved-status');
    status.setAttribute('role', 'status'); status.setAttribute('aria-live', 'polite');
    const body = element(doc, 'div');
    section.append(title, status, body); host.append(section);
    let disposed = false, busy = false, current = null, preview = null, operation = null, restoreFocus = null;
    const transferAvailable = transfer && transfer.available !== false && typeof transfer.importJson === 'function' && typeof transfer.exportJson === 'function' && typeof transfer.cancel === 'function';
    const say = text => { if (!disposed) status.textContent = text; };
    function button(parent, label, action, work, disabled = false) {
      const node = element(doc, 'button', label); node.type = 'button'; node.dataset.savedAction = action; node.disabled = disabled;
      node.addEventListener('click', () => { if (!node.disabled && !disposed) return work(); }); parent.append(node); return node;
    }
    async function perform(work) {
      if (busy || disposed) return;
      busy = true; render();
      try { await work(); } catch (_) { say('Operation failed. No success was verified. A selected export file may be partial.'); }
      finally { busy = false; operation = null; if (!disposed) { current = await store.getSnapshot(); render(); } }
    }
    function transferMessage(result, kind) {
      if (!result) return 'Transfer result unavailable. A selected export file may be partial.';
      const warning = result.externalEffect === 'partial' || result.externalEffect === 'unknown' || (kind === 'export' && result.status !== 'ok' && result.externalEffect !== 'none')
        ? ' A selected file may contain partial personal data; it was not wiped.' : '';
      if (result.status === 'cancelled') return 'Transfer cancelled.' + warning;
      if (result.status === 'error') return 'Transfer failed: ' + (result.code || 'UNAVAILABLE') + '.' + warning;
      if (kind === 'export' && result.status === 'ok' && result.externalEffect === 'complete') return 'Personal JSON exported. Keep the file private.';
      return 'Transfer did not provide a verified result.' + warning;
    }
    async function importData() {
      await perform(async () => {
        const revision = current.snapshot.revision;
        preview = null; operation = { kind: 'import' }; render(); say('Choose a personal JSON file to preview. Nothing will be replaced yet.');
        const result = await transfer.importJson();
        if (disposed) return;
        if (!result || result.status !== 'ok' || result.externalEffect !== 'none' || typeof result.payload !== 'string') {
          say(transferMessage(result, 'import')); return;
        }
        const checked = await store.previewImport(result.payload);
        if (disposed) return;
        if (checked.status === 'FAILED') { say('Import rejected: ' + checked.code + '. Nothing was replaced.'); return; }
        preview = { ...checked, revision };
        say('Preview ready. Review the replacement scope, then confirm or cancel.');
      });
      if (!disposed && preview) body.querySelector('[data-saved-action="confirm"]')?.focus();
    }
    async function exportData() {
      await perform(async () => {
        const result = await store.exportSnapshot(current.snapshot.revision);
        if (disposed) return;
        if (result.status !== 'OK') { say(resultMessage(result)); return; }
        operation = { kind: 'export' }; render(); say('Choose where to save your personal JSON file.');
        const terminal = await transfer.exportJson(result.payload);
        say(transferMessage(terminal, 'export'));
      });
    }
    async function cancelTransfer() {
      const target = operation;
      if (!target || !busy) return;
      try {
        const result = await transfer.cancel();
        if (!disposed && busy && operation === target) say(result && result.status === 'ok'
          ? 'Cancellation requested. Waiting for the transfer result; a selected file may contain partial data.'
          : 'Cancellation was not confirmed. Waiting for the transfer result.');
      } catch (_) { if (busy && operation === target) say('Cancellation was not confirmed. A selected file may contain partial data.'); }
    }
    function render() {
      if (disposed) return;
      const focused = doc.activeElement;
      const focusKey = body.contains(focused) ? focused.dataset.savedAction : null;
      if (focusKey) restoreFocus = focusKey;
      body.replaceChildren();
      if (!current) return;
      const snapshot = current.snapshot, writable = current.writable && !!snapshot;
      if (!writable) body.append(element(doc, 'p', 'Personal data is read-only: ' + (current.reason || current.state) + '. Reload to recheck recovery.', 'saved-warning'));
      if (!snapshot) return;
      const list = element(doc, 'ul', undefined, 'saved-list');
      for (const item of snapshot.saved) {
        const row = element(doc, 'li');
        const italian = element(doc, 'p', item.snapshot.it, 'saved-italian'); italian.lang = 'it';
        row.append(italian, element(doc, 'p', item.snapshot.en));
        button(row, 'Listen', 'listen-' + item.id, () => perform(async () => { await speech.listen(item.snapshot.it); }), busy || !speech || typeof speech.listen !== 'function');
        button(row, 'Show', 'show-' + item.id, () => perform(async () => { await speech.show(item.snapshot.it, item.snapshot.en); }), busy || !speech || typeof speech.show !== 'function');
        button(row, 'Remove', 'remove-' + item.id, () => perform(async () => {
          say(resultMessage(await store.mutate(snapshot.revision, { type: 'remove', id: item.id })));
        }), busy || !writable);
        list.append(row);
      }
      if (snapshot.saved.length === 0) body.append(element(doc, 'p', 'No saved phrases yet. Save a complete phrase from Builder.'));
      body.append(list);
      button(body, 'Stop audio', 'stop', async () => { try { await speech.stop(); } catch (_) { say('Audio stop was not confirmed.'); } }, !speech || typeof speech.stop !== 'function');
      body.append(element(doc, 'p', 'Personal JSON includes Saved text, review history, preferences and Builder state. It replaces those items when restored. It excludes documents. Keep exported files private.', 'saved-disclosure'));
      if (!transferAvailable) body.append(element(doc, 'p', 'JSON import and export are unavailable in this browser.', 'saved-warning'));
      button(body, 'Import JSON…', 'import', importData, busy || !writable || !transferAvailable);
      button(body, 'Export personal JSON…', 'export', exportData, busy || !writable || !transferAvailable);
      if (operation) button(body, 'Cancel transfer', 'cancel-transfer', cancelTransfer);
      if (preview) {
        const box = element(doc, 'div', undefined, 'saved-preview');
        box.append(element(doc, 'h3', 'Confirm replacement'));
        box.append(element(doc, 'p', `Replace ${snapshot.saved.length} current Saved phrases and ${snapshot.progress.length} review records with ${preview.counts.saved} Saved phrases and ${preview.counts.progress} review records.`));
        for (const warning of preview.warnings) box.append(element(doc, 'p', warning));
        button(box, 'Replace personal data', 'confirm', () => perform(async () => {
          const accepted = preview; preview = null;
          say(resultMessage(await store.replaceConfirmed(accepted.revision, accepted.validatedCandidate)));
        }), busy || !writable);
        button(box, 'Cancel preview', 'cancel-preview', async () => {
          preview = null; render(); say('Preview cancelled. Nothing was replaced.'); body.querySelector('[data-saved-action="import"]')?.focus();
        }, busy);
        body.append(box);
      }
      if (restoreFocus) {
        const replacement = Array.from(body.querySelectorAll('button')).find(node => node.dataset.savedAction === restoreFocus && !node.disabled);
        if (replacement) replacement.focus();
        else { status.tabIndex = -1; status.focus(); }
        if (!busy) restoreFocus = null;
      }
    }
    const unsubscribe = store.subscribe(value => { if (!disposed) { current = value; render(); } });
    void store.getSnapshot().then(value => { if (!disposed && !current) { current = value; say(value.writable ? 'Personal data ready.' : 'Personal data needs recovery.'); render(); } }).catch(() => say('Personal data unavailable.'));
    return () => { disposed = true; unsubscribe(); preview = null; if (operation && transferAvailable) void Promise.resolve().then(() => transfer.cancel()).catch(() => {}); section.remove(); };
  }
  function mountBuilder(host, { store, getPhrase } = {}) {
    const doc = host.ownerDocument, section = element(doc, 'div', undefined, 'italy-saved');
    const button = element(doc, 'button', 'Save current phrase'); button.type = 'button'; button.disabled = true;
    const status = element(doc, 'p', 'Loading personal data…'); status.setAttribute('role', 'status');
    section.append(button, status); host.append(section);
    let disposed = false, busy = false, current = null;
    function update(value) { current = value; if (!disposed) button.disabled = busy || !value.writable || !value.snapshot; }
    const click = async () => {
      if (busy || button.disabled || disposed) return;
      const restoreFocus = doc.activeElement === button;
      busy = true; button.disabled = true;
      try {
        const snapshot = getPhrase();
        const result = await store.mutate(current.snapshot.revision, { type: 'save', snapshot, sourcePhraseId: null, sourceContentVersion: null });
        if (!disposed) status.textContent = resultMessage(result);
      } catch (_) { if (!disposed) status.textContent = 'A complete Italian and English phrase is required.'; }
      finally {
        busy = false;
        if (!disposed) {
          update(await store.getSnapshot());
          if (restoreFocus && !button.disabled && (!doc.activeElement || doc.activeElement === doc.body || doc.activeElement === button)) button.focus();
        }
      }
    };
    button.addEventListener('click', click);
    const unsubscribe = store.subscribe(update);
    void store.getSnapshot().then(value => { if (!disposed && !current) { update(value); status.textContent = value.writable ? 'Save the complete Italian and English phrase.' : 'Saving is unavailable until personal data recovers.'; } }).catch(() => { if (!disposed) status.textContent = 'Personal data unavailable.'; });
    return () => { disposed = true; unsubscribe(); button.removeEventListener('click', click); section.remove(); };
  }
  return Object.freeze({ mount, mountBuilder });
});
