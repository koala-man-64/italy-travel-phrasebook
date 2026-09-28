const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const html = fs.readFileSync(require('node:path').join(__dirname, '../index.html'), 'utf8');
const data = html.slice(html.indexOf('const CATEGORIES ='), html.indexOf('// 4. HELPERS'));
const phrasesStart = html.indexOf('const Phrases =');
const source = html.slice(phrasesStart, html.indexOf('    })();', phrasesStart) + '    })();'.length);

const ctx = {};
vm.runInNewContext(data + '\nthis.CATEGORIES = CATEGORIES; this.PHRASES = PHRASES;', ctx);
const { CATEGORIES, PHRASES } = ctx;

const LOCAL_CATEGORIES = ['local', 'rome', 'sorrento', 'amalfi', 'capri', 'naples', 'calcio'];
const FOOTBALL = /calcio|serie a|derby|tifa|scudetto|juve stabia|napoli/i;

test('every phrase has a known category and complete Italian, English and pronunciation', () => {
  const ids = new Set(CATEGORIES.map(c => c.id));
  const seen = new Set();
  for (const p of PHRASES) {
    assert.ok(ids.has(p.cat), `unknown category "${p.cat}" on "${p.it}"`);
    for (const key of ['it', 'en', 'phon']) {
      assert.equal(typeof p[key], 'string', `${key} missing on "${p.it}"`);
      assert.ok(p[key].trim(), `${key} empty on "${p.it}"`);
    }
    assert.match(p.phon, /[A-Z]{2}/, `no stressed syllable in "${p.phon}"`);
    assert.ok(!seen.has(p.it), `duplicate phrase "${p.it}"`);
    seen.add(p.it);
  }
});

test('each local-conversation category has a question set and every region talks football', () => {
  for (const id of LOCAL_CATEGORIES) {
    assert.ok(CATEGORIES.some(c => c.id === id), `category ${id} not registered`);
    const set = PHRASES.filter(p => p.cat === id);
    assert.ok(set.length >= 6, `${id} has only ${set.length} phrases`);
    assert.ok(set.every(p => p.it.endsWith('?')), `${id} has a non-question entry`);
    if (id !== 'local') {
      assert.ok(set.some(p => FOOTBALL.test(`${p.it} ${p.en} ${p.note}`)), `${id} has no football question`);
    }
  }
});

test('the Phrases tab renders a pill and a group for every local-conversation category', () => {
  const nodes = new Map();
  const $ = selector => {
    if (!nodes.has(selector)) nodes.set(selector, {
      innerHTML: '', value: '', hidden: false, textContent: '', handlers: {},
      classList: { toggle() {} },
      addEventListener(event, handler) { this.handlers[event] = handler; },
    });
    return nodes.get(selector);
  };
  vm.runInNewContext(source + '\nPhrases.init();', {
    CATEGORIES, PHRASES, $, $$: () => [], escapeHtml: String, fold: s => s.toLowerCase(),
    ICONS: { speaker: '', expand: '' }, document: { addEventListener() {} },
  });
  const pills = $('#phraseCats').innerHTML;
  const list = $('#phraseList').innerHTML;
  for (const id of LOCAL_CATEGORIES) {
    assert.match(pills, new RegExp(`data-cat="${id}"`), `no pill for ${id}`);
  }
  const label = CATEGORIES.find(c => c.id === 'sorrento').label;
  assert.match(list, new RegExp(`<h2 class="group-title">[^<]*${label}</h2>`));
  assert.equal((list.match(/class="card phrase"/g) || []).length, PHRASES.length);
  assert.match(list, /Tifa Roma o Lazio\?/);
  assert.equal($('#phraseMeta').textContent, `${PHRASES.length} phrases`);
});
