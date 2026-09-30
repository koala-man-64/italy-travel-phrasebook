const { test } = require('node:test');
const assert = require('node:assert/strict');
const { compile } = require('../sentence-compiler.js');
const fs = require('node:fs');
const vm = require('node:vm');
const html = fs.readFileSync(require('node:path').join(__dirname, '../index.html'), 'utf8');
const data = html.slice(html.indexOf('const CATEGORIES ='), html.indexOf('// 4. HELPERS'));
const context = {};
vm.runInNewContext(data + '\nthis.templates = TEMPLATES;', context);

test('every existing template and each replacement produces aligned bounded bilingual text', () => {
  for (const templates of Object.values(context.templates)) for (const template of templates) {
    for (let slot = 0; slot < template.slots.length; slot++) {
      for (let option = 0; option < template.slots[slot].options.length; option++) {
        const selected = template.slots.map(() => 0);
        selected[slot] = option;
        const before = JSON.stringify(template);
        const result = compile(template, selected);
        assert.equal(result.tokens.join(''), result.it);
        assert.ok(result.en.trim() && result.it.trim());
        assert.ok(result.en.length <= 2000 && result.it.length <= 2000);
        assert.doesNotMatch(result.it, /\s+[,.!?]/);
        assert.doesNotMatch(result.en, /\s+[,.!?]/);
        assert.equal(JSON.stringify(template), before);
      }
    }
  }
});

test('elision, punctuation, missing pronunciation, and invalid selections use safe defaults', () => {
  const template = { slots: [
    { options: [{ it: "Dov'", en: 'Where', phon: 'dohv' }] },
    { options: [{ it: 'è la stazione', en: 'is the station' }] },
    { options: [{ it: '?', en: '?' }] }
  ] };
  assert.deepEqual(compile(template, [99, -1, '0']), {
    tokens: ["Dov'", 'è la stazione', '?'], it: "Dov'è la stazione?", en: 'Where is the station?', phon: 'dohv'
  });
  assert.throws(() => compile({ slots: [{ options: [] }] }, []), TypeError);
});
