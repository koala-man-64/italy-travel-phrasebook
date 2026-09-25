const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const html = fs.readFileSync(require('node:path').join(__dirname, '../index.html'), 'utf8');
const start = html.indexOf('const Vocab =');
const source = html.slice(start, html.indexOf('    })();', start) + '    })();'.length);

test('all vocabulary cards render pronunciation and still speak only Italian', () => {
  const nodes = new Map();
  const $ = selector => {
    if (!nodes.has(selector)) nodes.set(selector, {
      innerHTML: '', value: '', handlers: {},
      addEventListener(event, handler) { this.handlers[event] = handler; },
    });
    return nodes.get(selector);
  };
  let spoken;
  vm.runInNewContext(source + '\nVocab.init();', {
    $, $$: () => [], escapeHtml: String, fold: s => s.toLowerCase(),
    document: { addEventListener() {} }, Speech: { speak(value) { spoken = value; } },
  });
  const cards = $('#vocabList').innerHTML;
  const pronunciations = [...cards.matchAll(/class="vocab-phon" lang="en">([^<]+)</g)].map(m => m[1]);
  assert.equal(pronunciations.length, 198);
  assert.equal((cards.match(/class="vocab-item"/g) || []).length, pronunciations.length);
  assert.ok(pronunciations.every(p => p !== 'undefined' && /[A-Z]{2}/.test(p)));
  assert.match(cards, /Buongiorno<\/span><span class="vocab-phon" lang="en">bwohn-JOHR-noh/);
  assert.match(cards, /Caffè<\/span><span class="vocab-phon" lang="en">kahf-FEH/);
  $('.vocab-it').textContent = 'Buongiorno';
  $('#vocabList').handlers.click({ target: { closest: () => ({}) } });
  assert.equal(spoken, 'Buongiorno');
});
