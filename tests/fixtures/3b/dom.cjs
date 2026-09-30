'use strict';
class Element {
  constructor(tag, doc) { this.tagName = tag; this.ownerDocument = doc; this.children = []; this.attrs = {}; this.handlers = new Map(); this._text = ''; this.parentNode = null; }
  set textContent(value) { this._text = String(value); this.replaceChildren(); }
  get textContent() { return this._text + this.children.map(child => child.textContent).join(' '); }
  set innerHTML(_) { throw Error('HTML forbidden'); }
  append(...children) { for (const child of children) { child.parentNode = this; this.children.push(child); } }
  replaceChildren(...children) { for (const child of this.children) child.parentNode = null; this.children = []; this.append(...children); }
  contains(node) { return this === node || this.children.some(child => child.contains(node)); }
  remove() { if (this.parentNode) this.parentNode.children = this.parentNode.children.filter(child => child !== this); this.parentNode = null; }
  setAttribute(key, value) { this.attrs[key] = value; }
  addEventListener(key, fn) { if (!this.handlers.has(key)) this.handlers.set(key, new Set()); this.handlers.get(key).add(fn); }
  removeEventListener(key, fn) { this.handlers.get(key)?.delete(fn); }
  click() { if (!this.disabled) { this.focus(); for (const fn of this.handlers.get('click') || []) fn(); } }
  focus() { if (!this.disabled) this.ownerDocument.activeElement = this; }
}
function document() { const doc = { activeElement: null }; doc.createElement = tag => new Element(tag, doc); return doc; }
const nodes = root => [root, ...root.children.flatMap(nodes)];
const flush = async () => { for (let n = 0; n < 12; n++) await new Promise(resolve => setImmediate(resolve)); };
const defer = () => { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b; }); return { promise, resolve, reject }; };
module.exports = { document, nodes, flush, defer };
