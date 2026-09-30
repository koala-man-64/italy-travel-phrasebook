const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const html = fs.readFileSync(require('node:path').join(__dirname, '../index.html'), 'utf8').replace(/\r\n/g, '\n');
const start = html.indexOf('    if (nativeWallet) {\n      let restored');
assert.notEqual(start, -1);
const source = html.slice(start, html.indexOf('    const ICONS', start));
function setup() {
  const notices = []; let listener; let restored = 0;
  vm.runInNewContext(source, { nativeWallet: true,
    basePersonalStore: { subscribe(fn) { listener = fn; } },
    personalNotice(message) { notices.push(message); },
    queueMicrotask(fn) { fn(); }, restorePersonalUI() { restored++; } });
  return { emit: value => listener(value), notices, restored: () => restored };
}
test('Android transient recovery stays silent and restores UI once on writable READY', () => {
  const ui = setup();
  ui.emit({ state: 'RECOVERING', writable: false });
  ui.emit({ state: 'READY', writable: false });
  assert.equal(ui.restored(), 0);
  ui.emit({ state: 'READY', writable: true });
  ui.emit({ state: 'READY', writable: true });
  assert.equal(ui.restored(), 1); assert.deepEqual(ui.notices, []);
});
test('Android terminal recovery failure gives actionable read-only notice', () => {
  const ui = setup(); ui.emit({ state: 'RECOVERY_REQUIRED', writable: false });
  assert.match(ui.notices[0], /Restart the app/); assert.equal(ui.restored(), 0);
});
