(function (root) {
  'use strict';
  const intervals = Object.freeze([1, 3, 7, 14, 30]);
  const dayMs = 86400000;
  const compare = (a, b) => a < b ? -1 : a > b ? 1 : 0;

  function utcInstant(value) {
    if (typeof value !== 'string' || !/^20\d{2}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$/.test(value)) {
      throw new RangeError('Expected a supported UTC timestamp');
    }
    const instant = new Date(value);
    if (!Number.isFinite(instant.getTime()) || instant.toISOString() !== value.replace('Z', '.000Z')) {
      throw new RangeError('Invalid calendar timestamp');
    }
    return instant;
  }

  // Count 1 -> 1 day, 2 -> 3, 3 -> 7, 4 -> 14, >=5 -> 30.
  // Due at midnight UTC of the review date plus the interval (not Rome time).
  function dueAt(progress) {
    if (progress === undefined || progress === null) return null;
    if (!Number.isInteger(progress.reviewCount) || progress.reviewCount < 0 || progress.reviewCount > 1000000) {
      throw new RangeError('Invalid review count');
    }
    const reviewed = utcInstant(progress.lastReviewedAtUtc);
    if (progress.reviewCount === 0) return null;
    const midnight = Date.UTC(reviewed.getUTCFullYear(), reviewed.getUTCMonth(), reviewed.getUTCDate());
    return new Date(midnight + intervals[Math.min(progress.reviewCount, 5) - 1] * dayMs).toISOString();
  }

  function prepareContent(content) {
    if (!content || !Array.isArray(content.phrases)) throw new TypeError('Phrase content is required');
    const ids = new Set();
    return content.phrases.map(phrase => {
      if (!phrase || !/^phrase_[a-z0-9][a-z0-9_-]{0,63}$/.test(phrase.id) || ids.has(phrase.id) ||
          !['draft', 'reviewed'].includes(phrase.review) || !phrase.snapshot ||
          typeof phrase.snapshot.it !== 'string' || !phrase.snapshot.it.trim() ||
          typeof phrase.snapshot.en !== 'string' || !phrase.snapshot.en.trim()) {
        throw new TypeError('Invalid or duplicate phrase');
      }
      ids.add(phrase.id);
      return Object.freeze({ id: phrase.id, review: phrase.review,
        italian: phrase.snapshot.it, english: phrase.snapshot.en });
    });
  }

  function schedule(phrases, progress, now) {
    if (!(now instanceof Date) || !Number.isFinite(now.getTime())) throw new RangeError('Invalid clock');
    if (!Array.isArray(progress)) throw new TypeError('Progress array required');
    const byId = new Map();
    for (const entry of progress) {
      if (!entry || byId.has(entry.phraseId)) throw new TypeError('Duplicate or invalid progress');
      byId.set(entry.phraseId, entry);
    }
    // Orphan entries never become cards and are never sent as replacement data.
    return phrases.filter(phrase => phrase.review === 'reviewed').map(phrase => {
      const due = dueAt(byId.get(phrase.id));
      return { phrase, dueAtUtc: due, due: due === null || Date.parse(due) <= now.getTime() };
    }).sort((a, b) => Number(b.due) - Number(a.due) ||
      compare(a.dueAtUtc || '', b.dueAtUtc || '') || compare(a.phrase.id, b.phrase.id));
  }

  function mount(element, { store, speech, clock, content }) {
    const phrases = prepareContent(content);
    const doc = element.ownerDocument;
    const cleanups = [];
    let disposed = false, pending = false, locked = false, view = null, current = null;
    let readSequence = 0, speechSequence = 0, speaking = false, unsubscribe;
    const section = doc.createElement('section');
    section.className = 'italy-learning';
    section.setAttribute('aria-label', 'Listening practice');
    function node(tag, role, text) {
      const item = doc.createElement(tag);
      item.setAttribute('data-learning', role);
      if (text) item.textContent = text;
      section.appendChild(item);
      return item;
    }
    node('h3', 'heading', 'Listening practice');
    node('p', 'explanation', 'Listen, reveal the transcript, then try saying it yourself. Reviewed records your own practice; it does not score pronunciation.');
    const draft = node('p', 'draft');
    const draftCount = phrases.filter(p => p.review === 'draft').length;
    draft.textContent = draftCount ? `${draftCount} draft ${draftCount === 1 ? 'phrase awaits' : 'phrases await'} independent bilingual review and ${draftCount === 1 ? 'is' : 'are'} excluded from practice.` : '';
    draft.hidden = !draftCount;
    const label = node('label', 'label', 'Practice phrase');
    const select = doc.createElement('select');
    select.setAttribute('aria-label', 'Practice phrase');
    select.setAttribute('data-learning', 'select');
    label.appendChild(select);
    const due = node('p', 'due');
    const transcript = node('div', 'transcript');
    const italian = doc.createElement('p'); italian.lang = 'it'; transcript.appendChild(italian);
    const english = doc.createElement('p'); english.lang = 'en'; transcript.appendChild(english);
    transcript.hidden = true;
    function button(role, text, action) {
      const item = node('button', role, text); item.type = 'button';
      item.addEventListener('click', action);
      cleanups.push(() => item.removeEventListener('click', action));
      return item;
    }
    const listen = button('listen', 'Listen', () => play());
    const stop = button('stop', 'Stop listening', () => stopSpeech());
    const reveal = button('reveal', 'Reveal transcript', () => {
      if (disposed || !current) return;
      transcript.hidden = !transcript.hidden;
      reveal.setAttribute('aria-expanded', String(!transcript.hidden));
      reveal.textContent = transcript.hidden ? 'Reveal transcript' : 'Hide transcript';
    });
    reveal.setAttribute('aria-expanded', 'false');
    const retry = button('retry', 'Try again', () => {
      if (disposed || !current) return;
      hideTranscript(); play();
    });
    const review = button('review', 'Reviewed — self-assessed', () => recordReview());
    const reload = button('reload', 'Reload progress', () => load());
    const status = node('p', 'status', 'Loading progress…');
    status.setAttribute('role', 'status'); status.setAttribute('aria-live', 'polite');
    status.setAttribute('aria-atomic', 'true');
    function say(text) { if (!disposed) status.textContent = text; }
    function hideTranscript() {
      transcript.hidden = true; reveal.textContent = 'Reveal transcript'; reveal.setAttribute('aria-expanded', 'false');
    }
    function controls() {
      const available = Boolean(current);
      for (const item of [listen, reveal, retry]) item.disabled = !available;
      stop.disabled = !speaking;
      select.disabled = !available || pending;
      review.disabled = !available || pending || locked || !view || view.writable !== true ||
        !view.snapshot || !/^(0|[1-9]\d*)$/.test(view.snapshot.revision) || !['NEW', 'READY'].includes(view.state);
      reload.disabled = pending;
    }
    function stopSpeech() {
      speechSequence++; speaking = false; controls();
      try { Promise.resolve(speech.stop()).catch(() => say('Unable to stop audio.')); }
      catch (_) { say('Unable to stop audio.'); }
    }
    async function play() {
      if (disposed || !current) return;
      stopSpeech();
      const token = ++speechSequence;
      speaking = true; controls(); say('Listening requested. Use Try again to repeat.');
      try {
        const result = await speech.listen(current.phrase.italian);
        if (disposed || token !== speechSequence) return;
        if (result && ['FAILED', 'UNCERTAIN', 'CANCELLED', 'UNAVAILABLE'].includes(result.status)) {
          say('Audio unavailable. Reveal the transcript or try again.');
          speaking = false; controls();
        }
      } catch (_) {
        if (!disposed && token === speechSequence) {
          speaking = false; controls(); say('Audio unavailable. Reveal the transcript or try again.');
        }
      }
    }
    function render(next) {
      if (disposed) return;
      view = next;
      const cards = schedule(phrases, next && next.snapshot ? next.snapshot.progress : [], clock.now());
      const previousId = current && current.phrase.id;
      current = cards.find(card => card.phrase.id === previousId) || cards[0] || null;
      select.replaceChildren();
      for (const card of cards) {
        const option = doc.createElement('option'); option.value = card.phrase.id;
        option.textContent = `${card.due ? 'Due' : 'Later'}: ${card.phrase.english}`;
        select.appendChild(option);
      }
      if (current) {
        select.value = current.phrase.id;
        italian.textContent = current.phrase.italian; english.textContent = current.phrase.english;
        due.textContent = current.dueAtUtc ? `Next review: ${current.dueAtUtc.slice(0, 10)} (UTC)${current.due ? ' — due now' : ''}.` : 'New phrase — ready to practice.';
      } else due.textContent = 'No reviewed phrases are available yet.';
      if (previousId !== (current && current.phrase.id)) hideTranscript();
      if (!next || next.writable !== true || !['NEW', 'READY'].includes(next.state)) {
        say('Progress is read-only or unavailable. Practice is available; reviews cannot be saved.');
      }
      controls();
    }
    async function refresh() {
      const sequence = ++readSequence;
      try {
        const next = await store.getSnapshot();
        if (!disposed && sequence === readSequence) render(next);
      } catch (_) {
        if (!disposed && sequence === readSequence) {
          view = null; locked = true; controls(); say('Progress could not be read. Reload before saving a review.');
        }
      }
    }
    async function load() {
      if (disposed || pending) return;
      pending = true; controls(); say('Loading progress…');
      try {
        await store.load();
        if (disposed) return;
        locked = false;
        await refresh();
        if (!disposed && view && view.writable && !locked) say('Ready for self-reviewed practice.');
      } catch (_) { locked = true; say('Progress could not be loaded. Reload to try again.'); }
      finally { if (!disposed) { pending = false; controls(); } }
    }
    async function recordReview() {
      if (disposed || review.disabled) return;
      const restoreFocus = doc.activeElement === review;
      const phraseId = current.phrase.id, revision = view.snapshot && view.snapshot.revision;
      pending = true; controls(); say('Saving self-reviewed practice…');
      try {
        const now = clock.now();
        const stamp = now.toISOString().replace(/\.\d{3}Z$/, 'Z'); utcInstant(stamp);
        const result = await store.mutate(revision, { type: 'review', phraseId, reviewedAtUtc: stamp });
        if (disposed) return;
        if (result && result.status === 'SAVED') {
          await refresh();
          if (!disposed && !locked && view && view.writable) say('Self-reviewed practice saved. No pronunciation score was recorded.');
        } else {
          locked = true;
          say(result && result.status === 'UNCERTAIN'
            ? 'Save outcome is uncertain. Reload progress before another review.'
            : 'Review was not saved. Reload progress before trying again.');
        }
      } catch (_) { locked = true; say('Review save could not be confirmed. Reload progress before trying again.'); }
      finally {
        if (!disposed) {
          pending = false; controls();
          if (restoreFocus && (doc.activeElement === review || doc.activeElement === doc.body)) {
            (review.disabled ? reload : review).focus();
          }
        }
      }
    }
    function change() {
      if (disposed || pending) return;
      stopSpeech();
      const chosen = phrases.find(p => p.id === select.value && p.review === 'reviewed');
      if (!chosen) return;
      current = { phrase: chosen }; hideTranscript(); render(view);
    }
    select.addEventListener('change', change);
    cleanups.push(() => select.removeEventListener('change', change));
    function dispose() {
      if (disposed) return;
      disposed = true; readSequence++; speechSequence++;
      for (const cleanup of cleanups) cleanup();
      try { if (unsubscribe) unsubscribe(); }
      finally {
        if (speaking) { try { Promise.resolve(speech.stop()).catch(() => {}); } catch (_) {} }
        section.remove();
      }
    }
    element.appendChild(section);
    try {
      unsubscribe = store.subscribe(() => { if (!disposed) void refresh(); });
      controls(); void load();
    } catch (error) { dispose(); throw error; }
    return dispose;
  }
  const api = Object.freeze({ intervals, dueAt, prepareContent, schedule, mount });
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  else root.ItalyLearning = api;
})(typeof globalThis !== 'undefined' ? globalThis : this);
