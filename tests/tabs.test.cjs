const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

const html = fs.readFileSync(require('node:path').join(__dirname, '../index.html'), 'utf8');
const source = html.slice(html.indexOf('const Tabs ='), html.indexOf('// 7. PHRASEBOOK'));

function setup(reducedMotion = false) {
  const elements = new Map();
  const $ = selector => {
    if (!elements.has(selector)) elements.set(selector, {
      handlers: {}, attributes: {}, hidden: false,
      addEventListener(type, fn) { this.handlers[type] = fn; },
      setAttribute(name, value) { this.attributes[name] = value; },
      focus() {},
      animate(frames, options) {
        this.animation = { frames, options, cancelled: false, cancel() { this.cancelled = true; } };
        return this.animation;
      },
    });
    return elements.get(selector);
  };
  const store = { get: () => 'builder', set(_key, value) { this.selected = value; } };
  vm.runInNewContext(source + '\nTabs.init();', {
    $, store, window: { innerWidth: 390, scrollY: 0, scrollTo() {}, matchMedia: () => ({ matches: reducedMotion }) },
    document: { dispatchEvent() {} }, CustomEvent: class {},
  });
  const emit = (type, x, y = 200, ignored = false, count = 1) => {
    const touch = { clientX: x, clientY: y };
    $('main').handlers[type]({
      touches: type === 'touchend' ? [] : Array(count).fill(touch),
      changedTouches: [touch], target: { closest: () => ignored },
    });
  };
  const swipe = (from, to) => { emit('touchstart', from); emit('touchend', to); };
  return { $, store, emit, swipe };
}

test('swipes switch adjacent panels and stop at either end', () => {
  const { $, store, swipe } = setup();
  swipe(280, 100);
  assert.equal(store.selected, 'vocab');
  assert.equal($('#panel-builder').hidden, true);
  assert.equal($('#panel-vocab').hidden, false);
  assert.equal($('#tab-vocab').attributes['aria-selected'], 'true');
  swipe(280, 100);
  assert.equal(store.selected, 'itinerary');
  swipe(280, 100);
  assert.equal(store.selected, 'itinerary');
  assert.equal($('#panel-guide').hidden, false);
  assert.equal($('#tab-guide').attributes['aria-selected'], 'true');
  swipe(280, 100);
  assert.equal($('#panel-guide').hidden, false);
  swipe(100, 280);
  swipe(100, 280);
  swipe(100, 280);
  swipe(100, 280);
  assert.equal(store.selected, 'phrases');
  swipe(100, 280);
  assert.equal(store.selected, 'phrases');
});

test('keyboard End opens Guide without persisting an unsupported backup tab', () => {
  const { $, store } = setup();
  const key = name => $('.tabbar [role="tablist"]').handlers.keydown({ key: name, preventDefault() {} });
  key('End');
  assert.equal($('#panel-guide').hidden, false);
  assert.equal(store.selected, 'builder');
  key('ArrowLeft');
  assert.equal($('#panel-itinerary').hidden, false);
  assert.equal(store.selected, 'itinerary');
  key('Home');
  assert.equal($('#panel-phrases').hidden, false);
});

test('vertical scrolls, small gestures, controls, edge gestures and multitouch do not switch tabs', () => {
  for (const kind of ['vertical', 'small', 'control', 'edge', 'multi', 'cancel']) {
    const { store, emit } = setup();
    emit('touchstart', kind === 'edge' ? 10 : 280, 200, kind === 'control', kind === 'multi' ? 2 : 1);
    if (kind === 'vertical') emit('touchmove', 240, 260);
    if (kind === 'cancel') emit('touchcancel', 240);
    emit('touchend', kind === 'small' ? 250 : 100);
    assert.equal(store.selected, 'builder', kind);
  }
});

test('swipe animation follows direction and cancels during rapid navigation', () => {
  const { $, swipe } = setup();
  assert.equal($('#panel-builder').animation, undefined);
  swipe(280, 100);
  const first = $('#panel-vocab').animation;
  assert.equal(first.frames[0].transform, 'translateX(24px)');
  assert.equal(first.options.duration, 160);
  swipe(100, 280);
  assert.equal(first.cancelled, true);
  assert.equal($('#panel-builder').animation.frames[0].transform, 'translateX(-24px)');
});

test('reduced motion switches tabs without animation', () => {
  const { $, store, swipe } = setup(true);
  swipe(280, 100);
  assert.equal(store.selected, 'vocab');
  assert.equal($('#panel-vocab').animation, undefined);
});
