// Tiny DOM helpers. Every piece of text goes through textContent, never innerHTML: test inputs and model
// outputs are untrusted (prompt-injection tests contain markup on purpose), so they must never become HTML.

export function h(tag, attrs = {}, ...children) {
  const el = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs || {})) {
    if (v == null || v === false) continue;
    if (k === 'class') el.className = v;
    else if (k === 'style' && typeof v === 'object') Object.assign(el.style, v);
    else if (k.startsWith('on') && typeof v === 'function') el.addEventListener(k.slice(2), v);
    else if (k === 'dataset') Object.assign(el.dataset, v);
    else if (v === true) el.setAttribute(k, '');
    else el.setAttribute(k, v);
  }
  append(el, children);
  return el;
}

function append(el, children) {
  for (const c of children.flat(Infinity)) {
    if (c == null || c === false) continue;
    el.append(c instanceof Node ? c : document.createTextNode(String(c)));
  }
}

const SVG_NS = 'http://www.w3.org/2000/svg';

export function s(tag, attrs = {}, ...children) {
  const el = document.createElementNS(SVG_NS, tag);
  for (const [k, v] of Object.entries(attrs || {})) {
    if (v == null) continue;
    if (k.startsWith('on') && typeof v === 'function') el.addEventListener(k.slice(2), v);
    else el.setAttribute(k, v);
  }
  for (const c of children.flat(Infinity)) {
    if (c == null) continue;
    el.append(c instanceof Node ? c : document.createTextNode(String(c)));
  }
  return el;
}

/** Like Node.append, but skips null/false instead of rendering them as text. */
export function appendAll(el, ...nodes) {
  el.append(...nodes.flat(Infinity).filter((n) => n != null && n !== false));
  return el;
}

export function clear(el) {
  while (el.firstChild) el.removeChild(el.firstChild);
  return el;
}

export const fmt = {
  pct: (x, digits = 1) => (x == null || Number.isNaN(x) ? '–' : (x * 100).toFixed(digits) + '%'),
  ms: (x) => (x == null ? '–' : x >= 10000 ? (x / 1000).toFixed(1) + ' s' : Math.round(x).toLocaleString() + ' ms'),
  usd: (x) => (x == null ? 'n/a' : x < 0.01 ? '$' + x.toFixed(4) : '$' + x.toFixed(2)),
  int: (x) => (x == null ? '–' : Math.round(x).toLocaleString()),
  tokens: (x) => (x == null ? '–' : x >= 1e6 ? (x / 1e6).toFixed(2) + 'M' : x >= 1e3 ? (x / 1e3).toFixed(1) + 'k' : String(x)),
  date: (iso) => (iso ? new Date(iso).toLocaleString() : '–'),
  json: (v) => (v == null ? 'null' : JSON.stringify(v, null, 2)),
};

export function toast(message, kind = 'info') {
  const el = h('div', { class: `toast toast-${kind}`, role: 'status' }, message);
  document.getElementById('toasts').append(el);
  setTimeout(() => el.classList.add('show'), 10);
  setTimeout(() => { el.classList.remove('show'); setTimeout(() => el.remove(), 300); }, 4500);
}

// Shared tooltip for chart marks.
let tip;
export function tooltip(target, lines) {
  target.addEventListener('mouseenter', (e) => {
    tip = tip || document.body.appendChild(h('div', { class: 'tooltip', role: 'tooltip' }));
    clear(tip);
    lines.forEach((l, i) => tip.append(h('div', { class: i === 0 ? 'tt-title' : 'tt-line' }, l)));
    tip.style.display = 'block';
    move(e);
  });
  target.addEventListener('mousemove', move);
  target.addEventListener('mouseleave', () => { if (tip) tip.style.display = 'none'; });
}

function move(e) {
  if (!tip) return;
  const pad = 14;
  const w = tip.offsetWidth;
  const x = e.clientX + pad + w > window.innerWidth ? e.clientX - w - pad : e.clientX + pad;
  tip.style.left = x + 'px';
  tip.style.top = e.clientY + pad + 'px';
}
