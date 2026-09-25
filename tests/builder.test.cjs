const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const html = fs.readFileSync(require('node:path').join(__dirname, '../index.html'), 'utf8');
const data = html.slice(html.indexOf('const CATEGORIES ='), html.indexOf('// 4. HELPERS'));
const builderStart = html.indexOf('const Builder =');
const source = html.slice(builderStart, html.indexOf('    })();', builderStart) + '    })();'.length);

function setup(saved) {
  const elements = new Map();
  const $ = selector => {
    if (!elements.has(selector)) elements.set(selector, {
      handlers: {}, attributes: {}, innerHTML: '', textContent: '', open: false,
      addEventListener(type, fn) { this.handlers[type] = fn; },
      setAttribute(name, value) { this.attributes[name] = value; },
      showModal() { this.open = true; }, close() { this.open = false; }, focus() {},
    });
    return elements.get(selector);
  };
  const store = { get: () => saved, set(_key, value) { this.saved = JSON.parse(JSON.stringify(value)); } };
  vm.runInNewContext(data + source + '\nBuilder.init();', {
    $, $$: () => [], store, escapeHtml: s => String(s), scrollRailTo() {},
    document: { createElement: () => $('#wordSwap'), body: { appendChild() {} }, addEventListener() {} },
  });
  const click = (selector, dataset) => $(selector).handlers.click({ target: { closest: () => ({ dataset, setAttribute() {} }) } });
  return { $, store, click };
}

test('word replacement updates the whole phrase, translation, pronunciation and saved slot only', () => {
  const { $, store, click } = setup();
  click('#hudIt', { swapSlot: '1' });
  assert.equal($('#wordSwap').open, true);
  assert.equal($('#swapCurrent').textContent, 'un caffè');
  click('#swapOptions', { replacement: '3' });
  assert.equal($('#wordSwap').open, false);
  assert.deepEqual(store.saved.sel, [0, 3, 0, 0]);
  assert.match($('#hudIt').innerHTML, /Replace pizza/);
  assert.equal($('#hudEn').textContent, 'I would like a margherita pizza for two people, please.');
  assert.match($('#hudPhon').textContent, /PEET-tsah/);
  const restored = setup(store.saved);
  assert.equal(restored.$('#hudEn').textContent, $('#hudEn').textContent);
});

test('cancel leaves sentence unchanged; replacing a closer keeps punctuation attached', () => {
  const { $, store, click } = setup();
  const original = $('#hudIt').innerHTML;
  click('#hudIt', { swapSlot: '0' });
  $('#wordSwap').handlers.cancel({ preventDefault() {} });
  assert.equal($('#wordSwap').open, false);
  assert.equal($('#hudIt').innerHTML, original);
  assert.equal(store.saved, undefined);
  click('#hudIt', { swapSlot: '3' });
  click('#swapOptions', { replacement: '2' });
  const sentence = $('#hudIt').innerHTML.replace(/<[^>]*>/g, '');
  assert.equal(sentence, 'Vorrei un caffè per due persone?');
  assert.equal($('#hudEn').textContent, 'I would like an espresso for two people?');
});
