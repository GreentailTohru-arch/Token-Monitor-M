/* The bundled renderer also runs on Android WebViews without these APIs. */
(() => {
  'use strict';
  function fallback(target, name, implementation) {
    if (typeof target[name] !== 'function') {
      Object.defineProperty(target, name, {
        value: implementation, configurable: true, writable: true
      });
    }
  }

  fallback(Object, 'hasOwn', (object, key) => Object.prototype.hasOwnProperty.call(object, key));
  function at(index) {
    const offset = Math.trunc(Number(index)) || 0;
    const position = offset < 0 ? this.length + offset : offset;
    return position >= 0 && position < this.length ? this[position] : undefined;
  }
  fallback(Array.prototype, 'at', at);
  fallback(String.prototype, 'at', at);

  function replaceChildren(...children) {
    const doc = this.ownerDocument || this;
    const replacement = doc.createDocumentFragment();
    replacement.append(...children);
    while (this.firstChild) this.removeChild(this.firstChild);
    this.appendChild(replacement);
  }
  for (const type of [Element, Document, DocumentFragment]) {
    fallback(type.prototype, 'replaceChildren', replaceChildren);
  }
  fallback(Element.prototype, 'getAnimations', () => []);
})();
