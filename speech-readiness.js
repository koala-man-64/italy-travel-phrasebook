(function (root) {
  'use strict';

  const REPORTS = ['unknown', 'reported-ready', 'reported-unavailable'];
  const SOURCES = ['OfflineConversation-v1', 'legacy-native', 'web', 'none'];
  const BASELINES = ['selected-1.9', 'historical-1.8', 'unknown'];
  const STATES = ['ready', 'setup', 'preparing', 'permission', 'recording', 'finalizing', 'review', 'translating', 'result', 'playing', 'error'];
  const RAW_KEYS = ['v', 'event', 'seq', 'ack', 'requestId', 'sessionEpoch', 'state', 'source', 'text', 'translation', 'machineTranslated', 'canReplay', 'models', 'voices', 'translationReady', 'progress', 'error', 'recordingMs'];
  const CAPABILITY_KEYS = ['schemaVersion', 'source', 'protocolBaseline', 'freshness', 'sequence', 'voices', 'recognitionModels', 'translationModels', 'microphonePermission', 'offlineDeviceProof'];
  const safeInteger = (value, min = 0, max = Number.MAX_SAFE_INTEGER) => Number.isSafeInteger(value) && value >= min && value <= max;

  function exactObject(value, keys) {
    if (!value || typeof value !== 'object' || Array.isArray(value)) return false;
    const own = Object.getOwnPropertyNames(value);
    return own.length === keys.length && Object.getOwnPropertySymbols(value).length === 0 &&
      keys.every(key => Object.prototype.hasOwnProperty.call(value, key) &&
        Object.prototype.hasOwnProperty.call(Object.getOwnPropertyDescriptor(value, key), 'value'));
  }

  function pair(value, allowed) {
    return exactObject(value, ['en', 'it']) && allowed(value.en) && allowed(value.it);
  }

  function boundedText(value, limit, utf16 = false) {
    if (typeof value !== 'string' || value.length > limit * (utf16 ? 1 : 2)) return false;
    let scalars = 0;
    for (let i = 0; i < value.length; i++, scalars++) {
      const code = value.charCodeAt(i);
      if (code >= 0xd800 && code <= 0xdbff) {
        const next = value.charCodeAt(++i);
        if (!(next >= 0xdc00 && next <= 0xdfff)) return false;
      } else if (code >= 0xdc00 && code <= 0xdfff) return false;
    }
    return utf16 || scalars <= limit;
  }

  // Native frames have only a root and two language maps. Check duplicate keys
  // before JSON.parse loses them; the parser still owns JSON grammar validation.
  function readFrame(input) {
    if (typeof input !== 'string') return input;
    if (input.length > 32768) return null;
    const objects = [];
    try {
      for (let i = 0; i < input.length; i++) {
        const char = input[i];
        if (char === '{') {
          objects.push(new Set());
          if (objects.length > 2) return null;
        } else if (char === '}') objects.pop();
        else if (char === '[') return null;
        else if (char === '"') {
          const start = i++;
          while (i < input.length && input[i] !== '"') {
            if (input[i] === '\\') i++;
            i++;
          }
          const token = JSON.parse(input.slice(start, i + 1));
          let next = i + 1;
          while (/[\x20\t\r\n]/.test(input[next] || '!')) next++;
          if (input[next] === ':') {
            const keys = objects[objects.length - 1];
            if (!keys || keys.has(token)) return null;
            keys.add(token);
          }
        }
      }
      return JSON.parse(input);
    } catch (_) { return null; }
  }

  function validFrame(value) {
    if (!exactObject(value, RAW_KEYS)) return false;
    return value.v === 1 && value.event === 'state' && STATES.includes(value.state) &&
      ['en', 'it'].includes(value.source) && safeInteger(value.seq, 1) &&
      safeInteger(value.ack, 0, 2147483647) && safeInteger(value.requestId, 0, value.ack) &&
      safeInteger(value.sessionEpoch) && safeInteger(value.recordingMs) &&
      boundedText(value.text, 2000, true) && boundedText(value.translation, 2000, true) &&
      boundedText(value.progress, 2048) && boundedText(value.error, 2048) &&
      typeof value.machineTranslated === 'boolean' && typeof value.canReplay === 'boolean' &&
      typeof value.translationReady === 'boolean' &&
      pair(value.models, item => typeof item === 'boolean') && pair(value.voices, item => typeof item === 'boolean') &&
      frameLength(value) <= 32768;
  }

  function frameLength(value) {
    // Serialize only admitted data, never an inherited toJSON hook. This also
    // accepts ordinary bridge records from another realm without prototype tests.
    const copy = Object.fromEntries(RAW_KEYS.map(key => [key,
      key === 'models' || key === 'voices' ? { en: value[key].en, it: value[key].it } : value[key]]));
    return JSON.stringify(copy).length;
  }

  function capabilities(source, protocolBaseline, freshness, sequence, voices, models, translation) {
    return Object.freeze({ schemaVersion: 1, source, protocolBaseline, freshness, sequence,
      voices: Object.freeze(voices || { it: 'unknown', en: 'unknown' }),
      recognitionModels: Object.freeze(models || { it: 'unknown', en: 'unknown' }),
      translationModels: translation || 'unknown', microphonePermission: 'unknown', offlineDeviceProof: 'unverified' });
  }

  function projectCapabilities(rawSnapshot, options = {}) {
    const { source = 'OfflineConversation-v1', protocolBaseline = 'selected-1.9', fresh = true, lastSequence = 0 } = options || {};
    const origin = SOURCES.includes(source) ? source : 'none';
    const baseline = BASELINES.includes(protocolBaseline) ? protocolBaseline : 'unknown';
    const unknown = () => capabilities(origin, baseline, fresh === false ? 'stale' : 'unknown', null);
    if (origin !== 'OfflineConversation-v1' || baseline !== 'selected-1.9' || fresh !== true || !safeInteger(lastSequence)) return unknown();
    const raw = readFrame(rawSnapshot);
    if (!validFrame(raw) || raw.seq <= lastSequence) return unknown();
    const report = value => value ? 'reported-ready' : 'reported-unavailable';
    return capabilities(origin, baseline, 'current', raw.seq,
      { it: report(raw.voices.it), en: report(raw.voices.en) },
      { it: report(raw.models.it), en: report(raw.models.en) }, report(raw.translationReady));
  }

  function projectSnapshot(snapshot) {
    if (!exactObject(snapshot, CAPABILITY_KEYS)) return capabilities('none', 'unknown', 'unknown', null);
    const source = SOURCES.includes(snapshot.source) ? snapshot.source : 'none';
    const baseline = BASELINES.includes(snapshot.protocolBaseline) ? snapshot.protocolBaseline : 'unknown';
    const valid = snapshot.schemaVersion === 1 && source === snapshot.source && baseline === snapshot.protocolBaseline &&
      ['current', 'stale', 'unknown'].includes(snapshot.freshness) &&
      (snapshot.sequence === null || safeInteger(snapshot.sequence, 1)) &&
      pair(snapshot.voices, value => REPORTS.includes(value)) && pair(snapshot.recognitionModels, value => REPORTS.includes(value)) &&
      REPORTS.includes(snapshot.translationModels) && snapshot.microphonePermission === 'unknown' && snapshot.offlineDeviceProof === 'unverified';
    if (!valid || source !== 'OfflineConversation-v1' || baseline !== 'selected-1.9' || snapshot.freshness !== 'current' || snapshot.sequence === null) {
      return capabilities(source, baseline, valid && snapshot.freshness === 'stale' ? 'stale' : 'unknown', null);
    }
    return capabilities(source, baseline, 'current', snapshot.sequence,
      { it: snapshot.voices.it, en: snapshot.voices.en },
      { it: snapshot.recognitionModels.it, en: snapshot.recognitionModels.en }, snapshot.translationModels);
  }

  function mount(element, services = {}) {
    if (!element || !element.ownerDocument) throw new TypeError('A speech readiness mount element is required.');
    const document = element.ownerDocument;
    const speech = services.speech;
    let disposed = false, unsubscribe = null, current = projectSnapshot(null);
    function node(tag, className, text) {
      const result = document.createElement(tag);
      if (className) result.className = className;
      if (text) result.textContent = text;
      return result;
    }
    const panel = node('section', 'speech-readiness');
    panel.setAttribute('aria-label', 'Speech readiness');
    const disclosure = node('details', 'speech-readiness__details');
    const toggle = node('summary', 'speech-readiness__toggle');
    const caption = node('span', 'speech-readiness__caption');
    toggle.append(node('span', 'speech-readiness__label', 'Speech '), caption);
    const body = node('div', 'speech-readiness__body');
    const summary = node('p', 'speech-readiness__summary');
    summary.setAttribute('role', 'status');
    summary.setAttribute('aria-live', 'polite');
    const list = node('dl', 'speech-readiness__list');
    list.setAttribute('aria-live', 'polite');
    const rows = [
      ['Italian voice', 'Used by Listen for Italian phrases.'],
      ['Italian recognition model', 'Used to transcribe Italian speech.'],
      ['English recognition model', 'Used to transcribe English speech.'],
      ['Translation models', 'Used for on-device translation.']
    ].map(([label, help]) => {
      const row = node('div', 'speech-readiness__row');
      const term = node('dt', '', label), detail = node('dd');
      const status = node('span', 'speech-readiness__report');
      detail.append(status, node('span', 'speech-readiness__help', help));
      row.append(term, detail); list.append(row);
      return status;
    });
    const note = node('p', 'speech-readiness__note', 'Listen for existing Italian phrases needs an Italian voice; it does not need recognition or translation models.');
    const proof = node('p', 'speech-readiness__note', 'Microphone permission: unknown. Offline operation and audible playback: unverified on this device.');
    const actions = node('div', 'speech-readiness__actions');
    const refresh = node('button', '', 'Check status'), setup = node('button', '', 'Open speech setup');
    refresh.type = setup.type = 'button';
    const actionStatus = node('p', 'speech-readiness__action-status');
    actionStatus.setAttribute('role', 'status');
    actions.append(refresh, setup);
    body.append(summary, list, note, proof, actions, actionStatus);
    disclosure.append(toggle, body); panel.append(disclosure);
    element.append(panel);

    function render(snapshot) {
      if (disposed) return;
      current = projectSnapshot(snapshot);
      const supported = current.source === 'OfflineConversation-v1' && current.protocolBaseline === 'selected-1.9';
      const shortMessage = !supported ? 'Status unavailable here' :
        current.freshness === 'stale' ? 'Status out of date · check again' :
        current.voices.it === 'reported-ready' ? 'Italian voice reported ready · try Listen' :
        current.voices.it === 'reported-unavailable' ? 'Italian voice reported unavailable · view setup' :
        'Status unknown · check details';
      if (caption.textContent !== shortMessage) caption.textContent = shortMessage;
      const message = !supported ? 'Native speech readiness is unavailable here. Browser speech, if offered, has separate voice availability.' :
        current.freshness === 'stale' ? 'Status is out of date. Check again after returning to the app or changing speech setup.' :
        current.freshness === 'current' ? 'Reported by the app. These reports do not prove successful recognition or audible playback.' :
        'Speech readiness is unknown. Check status for a current report.';
      if (summary.textContent !== message) summary.textContent = message;
      [current.voices.it, current.recognitionModels.it, current.recognitionModels.en, current.translationModels].forEach((value, i) => {
        const label = value === 'reported-ready' ? 'Reported ready' : value === 'reported-unavailable' ? 'Reported unavailable' : 'Unknown';
        if (rows[i].textContent !== label) rows[i].textContent = label;
        rows[i].setAttribute('data-readiness', value);
      });
      refresh.hidden = !supported || !speech || typeof speech.refresh !== 'function';
      setup.hidden = !supported || !speech || typeof speech.openSetup !== 'function';
      actions.hidden = refresh.hidden && setup.hidden;
    }

    function act(method, button) {
      if (disposed || button.hidden || button.disabled) return;
      actionStatus.textContent = '';
      const failed = () => {
        if (!disposed) actionStatus.textContent = method === 'refresh' ? 'Status check could not be requested. Try again.' : 'Speech setup could not be opened. Try again.';
      };
      try {
        const result = speech[method]();
        if (result === false) failed();
        else if (result && typeof result.then === 'function') Promise.resolve(result).then(value => { if (value === false) failed(); }, failed);
      } catch (_) { failed(); }
    }
    const onRefresh = () => act('refresh', refresh), onSetup = () => act('openSetup', setup);
    refresh.addEventListener('click', onRefresh); setup.addEventListener('click', onSetup);
    try {
      if (speech && typeof speech.subscribe === 'function') {
        unsubscribe = speech.subscribe(render);
        if (typeof unsubscribe !== 'function') throw new TypeError('Speech subscribe must return an unsubscribe function.');
      }
      render(speech && typeof speech.getSnapshot === 'function' ? speech.getSnapshot() : null);
    } catch (_) {
      render(null);
      actionStatus.textContent = 'Speech status is unavailable. Reopen this view to try again.';
    }
    return function dispose() {
      if (disposed) return;
      disposed = true;
      refresh.removeEventListener('click', onRefresh); setup.removeEventListener('click', onSetup);
      try { if (typeof unsubscribe === 'function') unsubscribe(); }
      finally { if (panel.parentNode === element) element.removeChild(panel); }
    };
  }

  const api = Object.freeze({ projectCapabilities, projectSnapshot, mount });
  if (typeof module === 'object' && module.exports) module.exports = api;
  else root.ItalySpeechReadiness = api;
})(typeof globalThis === 'object' ? globalThis : this);
