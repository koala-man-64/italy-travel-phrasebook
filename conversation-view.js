(function (root) {
  'use strict';

  // Inline line icons stay available offline and inherit the active turn's colors.
  const icons = {
    back: 'M15 5 8 12l7 7', next: 'm9 5 7 7-7 7',
    close: 'm6 6 12 12M6 18 18 6', done: 'm5 12 4 4L19 6',
    more: 'M5 11v2m7-2v2m7-2v2',
    edit: 'm14 5 5 5M4 20l5-1L20 8a2 2 0 0 0-5-5L4 14Z',
    phrases: 'M5 4h14v14H9l-4 3Zm4 4h6m-6 4h6',
    show: 'M4 4h16v12H9l-5 4Zm4 6h8m-3-3 3 3-3 3',
    speaker: 'M4 9h4l5-4v14l-5-4H4Zm12-1a6 6 0 0 1 0 8m3-11a10 10 0 0 1 0 14',
    slow: 'M5 14a6 6 0 0 1 12 0Zm12-2h3a2 2 0 0 1 0 4h-3M7 14v4m8-4v4M3 14h2m3-4 3 4 3-4',
    keyboard: 'M3 5h18v14H3ZM6 9h1m3 0h1m3 0h1m3 0h0M6 12h1m3 0h1m3 0h1m3 0h0M7 16h10',
    mic: 'M9 5a3 3 0 0 1 6 0v7a3 3 0 0 1-6 0Zm-3 6v1a6 6 0 0 0 12 0v-1m-6 7v4m-4 0h8',
    stop: 'M6 6h12v12H6Z',
    reply: 'M20 5H4v12h11l5 4ZM8 9h8m-8 4h5',
    replay: 'M4 10a8 8 0 1 1 1 8M4 4v6h6m0-2 6 4-6 4Z',
    rotate: 'M4 10a8 8 0 0 1 14-5M4 4v6h6m10 4a8 8 0 0 1-14 5m14 1v-6h-6',
    setup: 'M4 6h5m4 0h7M4 12h9m4 0h3M4 18h3m4 0h9M9 3v6m4 0V3m0 6H9m4 0v6m4 0V9m0 6h-4M7 15v6m4 0v-6m0 6H7',
    download: 'M12 3v12m-5-5 5 5 5-5M4 16v5h16v-5',
    voice: 'M9 4a3 3 0 0 1 6 0v6a3 3 0 0 1-6 0Zm-3 5v1a6 6 0 0 0 12 0V9m-6 7v5m-3 0h6',
    licenses: 'M6 3h9l4 4v14H6Zm9 0v5h4M9 12h7m-7 4h7',
    clear: 'M3 6h18M9 6V3h6v3M6 6l1 15h10l1-15M10 10v7m4-7v7'
  };
  const actionIcons = {
    Back: ['back', 'Back'], NeedsSetup: ['setup', 'Offline setup needed'],
    Flip: ['rotate', 'Rotate screen'], More: ['more', 'More conversation options'], Close: ['close', 'Close conversation'],
    Edit: ['edit', 'Edit message'], Choose: ['phrases', 'Choose phrase'], Show: ['show', 'Show in Italian'],
    SpeakItalian: ['speaker', 'Ascolta'], SpeakItalianSlow: ['slow', 'Ascolta lentamente'], Record: ['mic', 'Parla'],
    TypeItalian: ['keyboard', 'Scrivi una risposta in italiano'],
    NextMessage: ['next', 'Next message'], EditorDone: ['done', 'Done editing'], ChoiceDone: ['done', 'Use phrase'],
    CancelOperation: ['close', 'Cancel'], Stop: ['stop', 'Ferma'], Recover: ['back', 'Back to conversation'],
    ReturnItalian: ['show', 'Show Italian message'], ReturnReply: ['reply', 'Read their reply'],
    SpeakEnglish: ['speaker', 'Speak English'], Replay: ['replay', 'Replay Italian recording'],
    Correct: ['edit', 'Correct transcript'], OpenSetup: ['setup', 'Setup'], Clear: ['clear', 'Clear conversation'],
    Setup: ['download', 'Prepare models'], CancelSetup: ['close', 'Cancel setup'], InstallVoice: ['voice', 'Install voice'],
    Licenses: ['licenses', 'Licenses'], Previous: ['back', 'Previous page'], Next: ['next', 'Next page']
  };

  function mountIcons(find) {
    for (const [suffix, [icon, label]] of Object.entries(actionIcons)) {
      const button = find('conversation' + suffix);
      const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
      svg.setAttribute('viewBox', '0 0 24 24');
      svg.setAttribute('aria-hidden', 'true');
      svg.setAttribute('focusable', 'false');
      const path = document.createElementNS(svg.namespaceURI, 'path');
      path.setAttribute('d', icons[icon]);
      svg.appendChild(path);
      button.replaceChildren(svg);
      button.classList.add('conversation-icon');
      button.setAttribute('aria-label', label);
      button.title = label;
    }
  }

  // Offsets are UTF-16 offsets, matching textarea selectionStart/selectionEnd.
  // Splitting at grapheme boundaries preserves accents and joined emoji.
  function paginateText(text, fits) {
    if (!text) return [{ start: 0, end: 0, text: '' }];
    const segments = typeof Intl.Segmenter === 'function'
      ? Array.from(new Intl.Segmenter(undefined, { granularity: 'grapheme' }).segment(text), x => x.segment)
      : Array.from(text);
    const offsets = [0];
    segments.forEach(x => offsets.push(offsets[offsets.length - 1] + x.length));
    const pages = [];
    let at = 0;
    while (at < segments.length) {
      let low = at + 1, high = segments.length, end = at + 1;
      while (low <= high) {
        const mid = Math.floor((low + high) / 2);
        if (fits(text.slice(offsets[at], offsets[mid]))) { end = mid; low = mid + 1; }
        else high = mid - 1;
      }
      // Prefer complete words without losing any whitespace or source offsets.
      if (end < segments.length) {
        for (let i = end; i > at + (end - at) / 2; i--) {
          if (/\s/u.test(segments[i - 1])) { end = i; break; }
        }
      }
      pages.push({ start: offsets[at], end: offsets[end], text: text.slice(offsets[at], offsets[end]) });
      at = end;
    }
    return pages;
  }

  function replacePage(text, page, replacement, start, end, limit = 2000) {
    const available = Math.max(0, limit - (text.length - (page.end - page.start)));
    let accepted = replacement.slice(0, available);
    // Never leave an orphan surrogate when a paste reaches the message limit.
    if (/[\uD800-\uDBFF]$/.test(accepted)) accepted = accepted.slice(0, -1);
    return { text: text.slice(0, page.start) + accepted + text.slice(page.end),
      start: page.start + Math.min(start, accepted.length), end: page.start + Math.min(end, accepted.length) };
  }

  function mount(document, window, actions) {
    const $ = id => document.getElementById(id);
    const dialog = $('conversationDialog');
    const screens = Array.from(dialog.querySelectorAll('[data-screen]'));
    const editor = $('conversationEditor');
    const header = $('conversationTitle').parentElement;
    const shell = header.parentElement;
    const pager = $('conversationPrevious').parentElement;
    const editorActions = $('conversationEditorDone').parentElement;
    const titles = { draft: 'Your turn', italian: 'Italiano', reply: 'Their reply', editor: 'Edit message',
      choices: 'Choose a phrase', busy: 'One moment', error: 'Try again', more: 'More', setup: 'Offline setup', licenses: 'Licenses' };
    let screen = 'draft', returnScreen = 'draft', pageIndex = 0, pages = [], snapshot = null;
    let rotated = true, step = -2, lastPublished = null, lastReply = '', lastError = '', frame = 0;
    let editTarget = 'draft', editText = '', selection = { start: 0, end: 0, direction: 'none' }, composing = false;
    let renderedSelection = null;
    let nativeTyping = false;
    function faceKeyboard(active) {
      const bridge = window.ItalyKeyboardOrientation;
      if (!bridge || typeof bridge.postMessage !== 'function') return false;
      if (nativeTyping !== active) {
        bridge.postMessage(active ? 'italian' : 'restore');
        nativeTyping = active;
      }
      return active;
    }
    let licenses = 'Loading licenses…', licensePromise = null;
    let textSource = '', currentTextNode = null, optionNodes = [], optionLabels = new WeakMap();
    const on = (id, callback) => { const el = $(id); if (el) el.addEventListener('click', callback); };
    mountIcons($);

    function navigate(next, focus = true) {
      if (screen === 'editor' && next !== 'editor') { editor.blur(); pages = []; }
      screen = next; pageIndex = 0;
      paint();
      if (focus) $('conversationTitle').focus({ preventScroll: true });
    }
    function baseScreen() { return snapshot && snapshot.s.reply ? 'reply' : snapshot && snapshot.s.published ? 'italian' : 'draft'; }
    function reset() {
      faceKeyboard(false);
      snapshot = null; lastPublished = null; lastReply = ''; lastError = ''; rotated = true;
      editText = ''; selection = { start: 0, end: 0, direction: 'none' }; composing = false;
      screen = 'draft'; returnScreen = 'draft'; pageIndex = 0; pages = [];
      textSource = ''; currentTextNode = null; renderedSelection = null;
      if ($('conversationItalianUser')) $('conversationItalianUser').textContent = '';
      if ($('conversationItalianUserCard')) $('conversationItalianUserCard').hidden = true;
      editor.value = ''; editor.blur();
    }
    function schedule() {
      if (!frame) frame = window.requestAnimationFrame(() => { frame = 0; if (!composing) paint(true); });
    }
    function viewport() {
      const v = window.visualViewport;
      dialog.style.setProperty('--conversation-height', `${v ? v.height : window.innerHeight}px`);
      dialog.style.setProperty('--conversation-top', `${v ? v.offsetTop : 0}px`);
    }
    function textFitter(node, height, width, isEditor = false) {
      const probe = document.createElement('div');
      probe.className = 'conversation-measure';
      const style = window.getComputedStyle(node);
      Object.assign(probe.style, { width: `${width}px`, font: style.font, fontSize: style.fontSize,
        fontWeight: style.fontWeight, lineHeight: style.lineHeight, letterSpacing: style.letterSpacing,
        whiteSpace: 'pre-wrap', overflowWrap: 'anywhere', padding: '0', border: '0' });
      dialog.appendChild(probe);
      return { fits(text) { probe.textContent = text + (isEditor ? '\u200b' : ''); return probe.getBoundingClientRect().height <= height - 1; },
        dispose() { probe.remove(); } };
    }
    function paginateNode(node, text, body) {
      let height = body.clientHeight;
      for (const child of body.children) if (child !== node && !child.hidden) height -= child.getBoundingClientRect().height + 8;
      const fitter = textFitter(node, height, body.clientWidth);
      pages = paginateText(text, fitter.fits); fitter.dispose();
      pageIndex = Math.min(pageIndex, pages.length - 1);
      node.textContent = pages[pageIndex].text;
    }
    function italianFitter(mainNode, userCard, userText, height, width) {
      const probeMain = document.createElement('div');
      probeMain.className = 'conversation-measure';
      const mainStyle = window.getComputedStyle(mainNode);
      Object.assign(probeMain.style, { width: `${width}px`, font: mainStyle.font, fontSize: mainStyle.fontSize,
        fontWeight: mainStyle.fontWeight, lineHeight: mainStyle.lineHeight, letterSpacing: mainStyle.letterSpacing,
        whiteSpace: 'pre-wrap', overflowWrap: 'anywhere', padding: '0', border: '0' });

      const probeUserWrap = document.createElement('div');
      probeUserWrap.className = 'conversation-measure';
      const cardStyle = window.getComputedStyle(userCard);
      Object.assign(probeUserWrap.style, { width: `${width}px`, display: 'flex', flexDirection: 'column',
        boxSizing: 'border-box',
        padding: cardStyle.padding, border: cardStyle.border, gap: cardStyle.gap });

      const probeEyebrow = document.createElement('div');
      const eyebrow = userCard.querySelector('.conversation-user-eyebrow');
      if (eyebrow) {
        const eyeStyle = window.getComputedStyle(eyebrow);
        Object.assign(probeEyebrow.style, { font: eyeStyle.font, fontSize: eyeStyle.fontSize,
          fontWeight: eyeStyle.fontWeight, letterSpacing: eyeStyle.letterSpacing, margin: eyeStyle.margin });
        probeEyebrow.textContent = eyebrow.textContent;
        probeUserWrap.appendChild(probeEyebrow);
      }

      const probeUserText = document.createElement('div');
      const textStyle = window.getComputedStyle(userText);
      Object.assign(probeUserText.style, { font: textStyle.font, fontSize: textStyle.fontSize,
        fontWeight: textStyle.fontWeight, lineHeight: textStyle.lineHeight, letterSpacing: textStyle.letterSpacing,
        whiteSpace: 'pre-wrap', overflowWrap: 'anywhere', margin: '0', padding: '0' });
      probeUserWrap.appendChild(probeUserText);

      dialog.appendChild(probeMain);
      dialog.appendChild(probeUserWrap);

      return {
        fits(text) {
          probeMain.textContent = text;
          probeUserText.textContent = text;
          const hMain = probeMain.getBoundingClientRect().height;
          const hUser = probeUserWrap.getBoundingClientRect().height;
          return (hMain + hUser + 8) <= height - 1;
        },
        dispose() {
          probeMain.remove();
          probeUserWrap.remove();
        }
      };
    }
    function paginateItalian(mainNode, userCard, userText, text, body) {
      if (!userCard || !userText) {
        paginateNode(mainNode, text, body);
        return;
      }
      userCard.hidden = !text;
      let height = body.clientHeight;
      for (const child of body.children) {
        if (child !== mainNode && child !== userCard && !child.hidden) {
          height -= child.getBoundingClientRect().height + 8;
        }
      }
      const fitter = italianFitter(mainNode, userCard, userText, height, body.clientWidth);
      pages = paginateText(text, fitter.fits);
      fitter.dispose();
      pageIndex = Math.min(pageIndex, pages.length - 1);
      mainNode.textContent = pages[pageIndex].text;
      userText.textContent = pages[pageIndex].text;
    }
    function captureSelection(force = false) {
      if (screen !== 'editor' || !pages[pageIndex]) return;
      if (!force && renderedSelection && editor.selectionStart === renderedSelection.start && editor.selectionEnd === renderedSelection.end && editor.selectionDirection === renderedSelection.direction) return;
      selection = { start: pages[pageIndex].start + editor.selectionStart,
        end: pages[pageIndex].start + editor.selectionEnd, direction: editor.selectionDirection };
    }
    function paginateEditor(followCaret) {
      const style = window.getComputedStyle(editor);
      const horizontal = parseFloat(style.paddingLeft) + parseFloat(style.paddingRight);
      const vertical = parseFloat(style.paddingTop) + parseFloat(style.paddingBottom);
      const fitter = textFitter(editor, editor.clientHeight - vertical, editor.clientWidth - horizontal, true);
      const anchor = pages[pageIndex] ? pages[pageIndex].start : 0;
      pages = paginateText(editText, fitter.fits); fitter.dispose();
      const offset = followCaret ? selection.start : anchor;
      pageIndex = Math.max(0, pages.findIndex((p, i) => offset >= p.start && (offset < p.end || i === pages.length - 1)));
      const page = pages[pageIndex];
      editor.value = page.text;
      editor.maxLength = Math.max(page.text.length, 2000 - editText.length + page.text.length);
      editor.setSelectionRange(Math.max(0, Math.min(page.text.length, selection.start - page.start)),
        Math.max(0, Math.min(page.text.length, selection.end - page.start)), selection.direction);
      renderedSelection = { start: editor.selectionStart, end: editor.selectionEnd, direction: editor.selectionDirection };
      editor.setAttribute('aria-label', `${editTarget === 'draft' ? 'English message' : 'Italian transcript'}, page ${pageIndex + 1} of ${pages.length}`);
      editor.setAttribute('lang', editTarget === 'transcript' ? 'it' : 'en');
      editor.setAttribute('placeholder', editTarget === 'transcript' ? 'Scrivi la tua risposta in italiano…' : 'What would you like to say?');
    }
    function commitEditor() {
      if (!pages[pageIndex]) return;
      const result = replacePage(editText, pages[pageIndex], editor.value, editor.selectionStart, editor.selectionEnd);
      editText = result.text; selection = { start: result.start, end: result.end, direction: editor.selectionDirection };
      actions.edit(editTarget, editText);
      paint(true);
    }
    function beginEdit(target) {
      editTarget = target; editText = target === 'draft' ? snapshot.s.draft : snapshot.s.transcript;
      selection = { start: 0, end: 0, direction: 'none' }; pages = [];
      navigate('editor', false); editor.focus({ preventScroll: true });
    }
    function choices() {
      const categories = $('conversationCategories'), templates = $('conversationTemplates'), slots = $('conversationSlots');
      categories.hidden = step !== -2; templates.hidden = step !== -1; slots.hidden = step < 0;
      Array.from(slots.children).forEach((slot, i) => { slot.hidden = step !== i; });
      if (step === -2) return { node: categories, title: 'Choose a category' };
      if (step === -1) return { node: templates, title: 'Choose a formula' };
      const slot = slots.children[step];
      if (!slot) { screen = 'draft'; return null; }
      const label = slot.querySelector('strong');
      if (label) label.hidden = true;
      return { node: slot.querySelector('.conversation-chips'), title: label ? label.textContent : 'Choose a word' };
    }
    function paginateOptions(container, body) {
      optionNodes = Array.from(container.querySelectorAll('button'));
      pages = [];
      let group = [], used = 0;
      for (const node of optionNodes) {
        if (!optionLabels.has(node)) optionLabels.set(node, Array.from(node.childNodes).map(child => child.cloneNode(true)));
        node.replaceChildren(...optionLabels.get(node).map(child => child.cloneNode(true)));
        node.hidden = false;
        const height = node.getBoundingClientRect().height;
        if (height > body.clientHeight) {
          if (group.length) { pages.push(group); group = []; used = 0; }
          const style = window.getComputedStyle(node);
          const fitter = textFitter(node, body.clientHeight - 18, body.clientWidth - parseFloat(style.paddingLeft) - parseFloat(style.paddingRight) - 2);
          paginateText(node.textContent, fitter.fits).forEach(part => pages.push([{ node, text: part.text }]));
          fitter.dispose();
        } else {
          if (group.length && used + 8 + height > body.clientHeight) { pages.push(group); group = []; used = 0; }
          group.push({ node }); used += height + (group.length > 1 ? 8 : 0);
        }
      }
      if (group.length) pages.push(group);
      if (!pages.length) pages.push([]);
      pageIndex = Math.min(pageIndex, pages.length - 1);
      optionNodes.forEach(node => { node.hidden = true; });
      pages[pageIndex].forEach(({node, text}) => { node.hidden = false; if (text !== undefined) node.textContent = text; });
    }
    function paint(followCaret = false) {
      if (!snapshot || !dialog.open) return;
      viewport();
      pager.hidden = false; // Reserve navigation before measuring multipage content.
      const { s, n, error } = snapshot;
      // Landscape keyboards can leave only ~120 CSS pixels. Keep all controls
      // in one row so the editor retains a readable line and never scrolls.
      const compactEditor = screen === 'editor' && (window.visualViewport ? window.visualViewport.height : window.innerHeight) < 240;
      dialog.dataset.compactEditor = String(compactEditor);
      $('conversationTitle').hidden = compactEditor;
      editorActions.hidden = compactEditor;
      if (compactEditor) {
        header.insertBefore(pager, $('conversationClose'));
        header.insertBefore($('conversationEditorDone'), $('conversationClose'));
      } else {
        shell.appendChild(pager);
        editorActions.appendChild($('conversationEditorDone'));
      }
      const isItalianEditor = screen === 'editor' && editTarget === 'transcript';
      const isItalianBusy = screen === 'busy' && s.pending && ['record', 'reply'].includes(s.pending.kind);
      const italian = screen === 'italian' || isItalianEditor || isItalianBusy;
      dialog.dataset.facing = italian ? 'it' : 'en';
      // Native orientation turns the system IME too; do not rotate the editor twice.
      const nativeEditor = faceKeyboard(isItalianEditor && rotated);
      dialog.dataset.rotated = String(italian && rotated && !nativeEditor);
      dialog.dataset.screen = screen;
      screens.forEach(node => { node.hidden = node.dataset.screen !== screen; });
      $('conversationTitle').textContent = isItalianEditor ? 'Scrivi risposta' : titles[screen];
      const doneText = $('conversationEditorDoneText');
      if (doneText) doneText.textContent = isItalianEditor ? 'Traduci' : 'Done';

      $('conversationEditorDone').setAttribute('aria-label', isItalianEditor ? 'Traduci la risposta' : 'Done editing message');
      $('conversationEditorDone').setAttribute('title', isItalianEditor ? 'Traduci la risposta' : 'Done editing message');
      $('conversationFlip').hidden = !italian;
      $('conversationFlip').setAttribute('aria-label', italian ? 'Ruota schermo di 180°' : 'Rotate screen');
      $('conversationFlip').setAttribute('title', italian ? 'Ruota schermo di 180°' : 'Rotate screen');
      $('conversationMore').hidden = ['editor','choices','more','setup','licenses','busy','error'].includes(screen);
      $('conversationNeedsSetup').hidden = !['draft','italian','reply','error'].includes(screen) || (n.models.it && n.translationReady && n.voices.it && n.voices.en);
      $('conversationBack').hidden = compactEditor || ['draft','italian','reply'].includes(screen);
      $('conversationBack').disabled = screen === 'busy';
      $('conversationBack').setAttribute('aria-label', italian ? 'Indietro' : 'Back');
      $('conversationBack').setAttribute('title', italian ? 'Indietro' : 'Back');
      $('conversationClose').setAttribute('aria-label', italian ? 'Chiudi conversazione' : 'Close conversation');
      $('conversationClose').setAttribute('title', italian ? 'Chiudi conversazione' : 'Close conversation');

      $('conversationPrevious').setAttribute('aria-label', italian ? 'Pagina precedente' : 'Previous page');
      $('conversationNext').setAttribute('aria-label', italian ? 'Pagina successiva' : 'Next page');
      ['conversationPrevious', 'conversationNext'].forEach(id => { $(id).title = $(id).getAttribute('aria-label'); });
      $('conversationSetup').hidden = n.state === 'preparing';
      $('conversationLicenses').hidden = n.state === 'preparing';
      $('conversationInstallVoice').hidden = n.state === 'preparing' || (n.voices.it && n.voices.en);
      let active = screens.find(node => node.dataset.screen === screen);
      const body = active.querySelector('.conversation-body');
      currentTextNode = null;
      const textByScreen = {
        draft: ['conversationDraftPreview', s.draft || 'What would you like to say?'],
        italian: ['conversationItalian', s.published ? s.published.it : ''],
        reply: ['conversationReply', s.reply || 'No reply yet.'],
        busy: ['conversationStatus', n.state === 'recording' ? `Parla ora\n${Math.floor(n.recordingMs / 1000)} / 30 s` : n.state === 'permission' ? 'Allow microphone access, then tap Parla again.' : n.state === 'finalizing' ? 'Sto trascrivendo…' : n.state === 'translating' ? 'Translating on this phone…' : s.pending && s.pending.kind === 'record' ? 'Sto ascoltando…' : 'Translating on this phone…'],
        error: ['conversationError', error || 'Something went wrong. Please try again.'],
        setup: ['conversationSetupText', `${$('conversationReadiness').textContent}\n\n${n.progress || 'Prepare speech, translation and offline voices once on Wi-Fi.'}\n\nRecording stays in memory for the current reply. No conversation history is saved. Translation stays on this phone. Google’s SDK may send usage metrics and check for updates when connected.`],
        licenses: ['conversationLicenseText', licenses]
      };
      if (screen === 'italian') {
        currentTextNode = $('conversationItalian');
        textSource = s.published ? s.published.it : '';
        paginateItalian(currentTextNode, $('conversationItalianUserCard'), $('conversationItalianUser'), textSource, body);
      } else if (textByScreen[screen]) {
        const [id, text] = textByScreen[screen]; currentTextNode = $(id); textSource = text;
        paginateNode(currentTextNode, textSource, body);
      } else if (screen === 'editor') paginateEditor(followCaret);
      else if (screen === 'choices') {
        const choice = choices();
        if (!choice) { paint(); return; }
        $('conversationTitle').textContent = choice.title;
        paginateOptions(choice.node, body);
      } else if (screen === 'more') {
        $('conversationReturnItalian').disabled = !s.published;
        $('conversationReturnReply').disabled = !s.reply;
        $('conversationCorrect').disabled = !s.published || !s.transcript;
        paginateOptions($('conversationMenu'), body);
      }
      $('conversationPage').textContent = compactEditor ? `${pageIndex + 1}/${Math.max(1, pages.length)}` : `${pageIndex + 1} / ${Math.max(1, pages.length)}`;
      $('conversationPrevious').disabled = pageIndex === 0;
      $('conversationNext').disabled = pageIndex >= pages.length - 1;
      pager.hidden = pages.length <= 1;
      $('conversationEdit').disabled = $('conversationDraft').disabled;
      $('conversationChoose').disabled = $('conversationDraft').disabled;
      if ($('conversationTypeItalian')) $('conversationTypeItalian').disabled = !s.published || (s.pending && ['publish','record','reply'].includes(s.pending.kind)) || screen === 'busy';
    }
    function update(s, n, error) {
      snapshot = { s, n, error };
      // Native readiness/progress frames may arrive while the keyboard owns an
      // unfinished IME composition. Do not replace its buffer until compositionend.
      if (composing) return;
      if (s.published && s.published !== lastPublished) { screen = 'italian'; pageIndex = 0; }
      if (s.reply && s.reply !== lastReply) { screen = 'reply'; pageIndex = 0; }
      if (s.pending && ['publish','record','reply'].includes(s.pending.kind)) {
        if (screen !== 'busy') { returnScreen = baseScreen(); pageIndex = 0; }
        screen = 'busy';
      } else if (screen === 'busy') { screen = baseScreen(); pageIndex = 0; }
      if (error && error !== lastError) { returnScreen = baseScreen(); screen = 'error'; pageIndex = 0; }
      lastPublished = s.published; lastReply = s.reply; lastError = error;
      paint();
    }
    function turnPage(delta) {
      if (screen === 'editor') {
        captureSelection();
        pageIndex = Math.max(0, Math.min(pages.length - 1, pageIndex + delta));
        selection = { start: pages[pageIndex].start, end: pages[pageIndex].start, direction: 'none' };
        paint(true); editor.focus({ preventScroll: true });
      } else { pageIndex = Math.max(0, Math.min(pages.length - 1, pageIndex + delta)); paint(); }
    }
    on('conversationPrevious', () => turnPage(-1)); on('conversationNext', () => turnPage(1));
    on('conversationEdit', () => beginEdit('draft'));
    on('conversationTypeItalian', () => beginEdit('transcript'));
    on('conversationCorrect', () => beginEdit('transcript'));
    on('conversationEditorDone', () => { commitEditor(); navigate(editTarget === 'draft' ? 'draft' : 'italian'); if (editTarget === 'transcript') actions.translateReply(); });
    editor.addEventListener('input', () => { if (!composing) commitEditor(); });
    editor.addEventListener('select', () => captureSelection());
    editor.addEventListener('keyup', () => captureSelection(true));
    editor.addEventListener('pointerup', () => captureSelection(true));
    editor.addEventListener('compositionstart', () => { composing = true; });
    editor.addEventListener('compositionend', () => {
      composing = false; commitEditor();
      // A canceled/no-op composition still needs to deliver deferred native errors.
      if (snapshot) update(snapshot.s, snapshot.n, snapshot.error);
    });
    on('conversationChoose', () => { step = -2; navigate('choices'); });
    on('conversationChoiceDone', () => navigate('draft'));
    ['conversationCategories','conversationTemplates','conversationSlots'].forEach(id => $(id).addEventListener('click', e => {
      if (!e.target.closest('button') || e.target.closest('button').disabled) return;
      step++; pageIndex = 0;
      if (step >= $('conversationSlots').children.length) navigate('draft'); else paint();
    }));
    on('conversationMore', () => { returnScreen = screen; navigate('more'); });
    on('conversationBack', () => {
      if (screen === 'choices' && step > -2) { step--; pageIndex = 0; paint(); }
      else if (screen === 'licenses') navigate('setup');
      else if (screen === 'editor') { commitEditor(); navigate(editTarget === 'draft' ? 'draft' : baseScreen()); }
      else navigate(screen === 'more' ? returnScreen : 'draft');
    });
    on('conversationFlip', () => { rotated = !rotated; paint(); });
    on('conversationReturnItalian', () => navigate('italian'));
    on('conversationReturnReply', () => navigate('reply'));
    on('conversationNextMessage', () => navigate('draft'));
    on('conversationOpenSetup', () => navigate('setup'));
    on('conversationNeedsSetup', () => navigate('setup'));
    on('conversationRecover', () => { actions.dismissError(); navigate(returnScreen); });
    on('conversationCancelOperation', () => { actions.cancel(); navigate(baseScreen()); });
    on('conversationLicenses', () => {
      navigate('licenses');
      if (!licensePromise) licensePromise = window.fetch('third-party-licenses.txt').then(r => { if (!r.ok) throw Error(); return r.text(); })
        .then(text => { licenses = text; if (screen === 'licenses') paint(); })
        .catch(() => { licensePromise = null; licenses = 'Licenses could not be loaded. Go back and reopen to retry.'; if (screen === 'licenses') paint(); });
    });
    window.addEventListener('resize', schedule);
    if (window.visualViewport) { window.visualViewport.addEventListener('resize', schedule); window.visualViewport.addEventListener('scroll', schedule); }
    if (window.ResizeObserver) new window.ResizeObserver(schedule).observe(dialog);
    if (document.fonts) document.fonts.ready.then(schedule);
    return { update, reset, showSetup: () => navigate('setup'), refresh: schedule };
  }
  const api = { paginateText, replacePage, mount };
  if (typeof module === 'object' && module.exports) module.exports = api;
  root.ConversationView = api;
})(typeof window !== 'undefined' ? window : globalThis);
