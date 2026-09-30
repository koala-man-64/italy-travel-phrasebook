(function (root) {
  'use strict';
  // Shared by the saved phrase builder and the ephemeral conversation composer.
  function compile(template, selections) {
    if (!template || !Array.isArray(template.slots)) throw new TypeError('Sentence template required');
    const parts = template.slots.map((slot, i) => {
      if (!Array.isArray(slot.options) || !slot.options.length) throw new TypeError('Sentence choices required');
      const selected = selections && selections[i];
      return Number.isInteger(selected) && slot.options[selected] ? slot.options[selected] : slot.options[0];
    });
    const tokens = parts.map((part, i) => {
      const next = parts[i + 1];
      return part.it + (!next || part.it.endsWith("'") || /^[,.!?]/.test(next.it) ? '' : ' ');
    });
    return {
      tokens,
      it: tokens.join(''),
      en: parts.map(part => part.en).join(' ').replace(/\s+/g, ' ').replace(/\s+([?,!.])/g, '$1'),
      phon: parts.map(part => part.phon || '').filter(value => /[a-z]/i.test(value)).join(' · ')
    };
  }
  if (typeof module === 'object' && module.exports) module.exports = { compile };
  else root.SentenceCompiler = { compile };
})(typeof window === 'object' ? window : globalThis);
