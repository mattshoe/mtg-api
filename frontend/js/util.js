// Small helpers. No framework, so these carry a lot of the weight.

// SVG lives in its own namespace. document.createElement('svg') yields an
// HTMLUnknownElement that renders nothing at all — which is why the lock
// and close icons were showing as empty boxes.
const SVG_NS = 'http://www.w3.org/2000/svg';
const SVG_TAGS = new Set(['svg', 'path', 'circle', 'rect', 'line', 'g',
  'polyline', 'polygon', 'ellipse', 'text', 'defs', 'use']);

/** Create an element. `h('div.card', {onclick}, ...children)` */
export function h(spec, props, ...kids) {
  const [tag, ...classes] = String(spec).split('.');
  const name = tag || 'div';
  const isSvg = SVG_TAGS.has(name);
  const el = isSvg
    ? document.createElementNS(SVG_NS, name)
    : document.createElement(name);
  // className is read-only on an SVGElement; the attribute is not.
  if (classes.length) {
    if (isSvg) el.setAttribute('class', classes.join(' '));
    else el.className = classes.join(' ');
  }

  if (props && (typeof props !== 'object' || props instanceof Node || Array.isArray(props))) {
    kids.unshift(props);
    props = null;
  }
  for (const [k, v] of Object.entries(props || {})) {
    if (v === null || v === undefined || v === false) continue;
    if (k === 'class') {
      if (isSvg) el.setAttribute('class', `${el.getAttribute('class') || ''} ${v}`.trim());
      else el.className += ` ${v}`;
    } else if (k === 'html') el.innerHTML = v;
    else if (k === 'text') el.textContent = v;
    else if (k === 'style' && typeof v === 'object') Object.assign(el.style, v);
    else if (k.startsWith('on') && typeof v === 'function') el.addEventListener(k.slice(2), v);
    else if (k === 'dataset') Object.assign(el.dataset, v);
    else if (v === true) el.setAttribute(k, '');
    else el.setAttribute(k, v);
  }
  add(el, kids);
  return el;
}

function add(el, kids) {
  for (const k of kids.flat(4)) {
    if (k === null || k === undefined || k === false || k === '') continue;
    el.append(k instanceof Node ? k : document.createTextNode(String(k)));
  }
}

export const $ = (sel, root = document) => root.querySelector(sel);
export const $$ = (sel, root = document) => [...root.querySelectorAll(sel)];

/** Replace an element's children. */
export function fill(el, ...kids) {
  el.replaceChildren();
  add(el, kids);
  return el;
}

export const esc = (s) => String(s ?? '').replace(/[&<>"']/g,
  (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

export const num = (n) => (n === null || n === undefined ? '—' : Number(n).toLocaleString());

export const debounce = (fn, ms = 250) => {
  let t;
  return (...a) => { clearTimeout(t); t = setTimeout(() => fn(...a), ms); };
};

// ------------------------------------------------------------- card images

/**
 * Scryfall's image CDN, addressed straight from the id we already store —
 * no API call needed. `face` is 'front' or 'back'.
 * Sizes: small (146px), normal (488px), large, art_crop, border_crop.
 */
export function imageUrl(scryfallId, size = 'normal', face = 'front') {
  if (!scryfallId || scryfallId.length < 2) return '';
  return `https://cards.scryfall.io/${size}/${face}/${scryfallId[0]}/${scryfallId[1]}/${scryfallId}.jpg`;
}

// Only these layouts have a genuinely separate back image. Split and
// adventure cards print both halves on one face.
const TWO_SIDED = new Set(['transform', 'modal_dfc', 'reversible_card',
  'double_faced_token', 'meld', 'art_series']);
export const hasBackFace = (layout) => TWO_SIDED.has(layout);

// ------------------------------------------------------------- mana costs

const HYBRID_COLORS = { W: 'var(--w)', U: 'var(--u)', B: 'var(--b)', R: 'var(--r)', G: 'var(--g)', C: 'var(--c)' };

/** '{2}{W}{U}' -> a row of pips. */
export function manaCost(cost) {
  const wrap = h('span.mana');
  if (!cost) return wrap;
  for (const m of String(cost).matchAll(/\{([^}]+)\}/g)) {
    const sym = m[1].toUpperCase();
    if (sym.includes('/')) {
      const [a, b] = sym.split('/');
      wrap.append(h('span.ms.hybrid', {
        title: `{${sym}}`,
        style: { '--a1': HYBRID_COLORS[a] || 'var(--c)', '--a2': HYBRID_COLORS[b] || 'var(--c)' },
      }, sym.replace('/', '')));
    } else {
      wrap.append(h('span.ms', { dataset: { s: sym }, title: `{${sym}}` }, sym));
    }
  }
  return wrap;
}

/** 'WU' -> colour identity pips. */
export function identity(ci) {
  const wrap = h('span.mana');
  const s = ci || '';
  if (!s) wrap.append(h('span.ms', { dataset: { s: 'C' }, title: 'colorless' }, 'C'));
  for (const c of s) wrap.append(h('span.ms', { dataset: { s: c }, title: c }, c));
  return wrap;
}

export const COLORS = [
  ['W', 'White'], ['U', 'Blue'], ['B', 'Black'],
  ['R', 'Red'], ['G', 'Green'], ['C', 'Colorless'],
];

// ------------------------------------------------------------------- misc

export const RARITY_ORDER = ['common', 'uncommon', 'rare', 'mythic', 'special', 'bonus'];

export function rarityTag(r) {
  const cls = { mythic: 'bad', rare: 'warn', uncommon: 'info' }[r] || '';
  return h(`span.tag.${cls}`.replace(/\.$/, ''), r || '—');
}

/** Download some text as a file. */
export function download(filename, text, type = 'text/plain') {
  const url = URL.createObjectURL(new Blob([text], { type: `${type};charset=utf-8` }));
  const a = h('a', { href: url, download: filename });
  document.body.append(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

/** cols+rows -> CSV, quoting anything that needs it. */
export function toCsv(cols, rows) {
  const cell = (v) => {
    if (v === null || v === undefined) return '';
    const s = String(v);
    return /[",\n\r]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s;
  };
  return [cols.map(cell).join(','), ...rows.map((r) => r.map(cell).join(','))].join('\n');
}

export function toast(msg, kind = '') {
  const el = h(`div.toast.${kind}`.replace(/\.$/, ''), msg);
  $('#toasts').append(el);
  setTimeout(() => {
    el.style.transition = 'opacity .25s, transform .25s';
    el.style.opacity = '0';
    el.style.transform = 'translateX(12px)';
    setTimeout(() => el.remove(), 260);
  }, kind === 'bad' ? 7000 : 3200);
}

export const store = {
  get(key, fallback) {
    try {
      const v = localStorage.getItem(`mtg.${key}`);
      return v === null ? fallback : JSON.parse(v);
    } catch { return fallback; }
  },
  set(key, value) {
    try { localStorage.setItem(`mtg.${key}`, JSON.stringify(value)); } catch { /* private mode */ }
  },
  del(key) {
    try { localStorage.removeItem(`mtg.${key}`); } catch { /* ignore */ }
  },
};

export function loading(label = 'Loading') {
  return h('div.empty', h('span.spinner'), h('div', { style: { marginTop: '10px' } }, label));
}

export function errorBox(e) {
  return h('div.err', String(e?.message || e));
}

export function empty(title, detail) {
  return h('div.empty', h('h3', title), detail && h('div.small', detail));
}

/** A sortable bar chart, used all over Stats. */
export function barList(items, { max, format = num } = {}) {
  const top = max ?? Math.max(1, ...items.map((i) => i.value));
  return h('div', items.map((i) => h('div.bar-row',
    h('div.lbl', { title: i.label }, i.label),
    h('div.bar-track', h('div.bar-fill', {
      style: { width: `${Math.max(1, (i.value / top) * 100)}%`, background: i.color || 'var(--accent)' },
    })),
    h('div.val', format(i.value)))));
}
