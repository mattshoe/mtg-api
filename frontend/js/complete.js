// Card-name autocomplete.
//
// Straight to Scryfall from the browser, not through the Worker. Their
// autocomplete endpoint sends `access-control-allow-origin: *` and is built
// for exactly this, and a keystroke's worth of traffic has no business going
// through Cloudflare's shared egress — which Scryfall rate-limits, and which
// is already why the nightly price refresh runs on the Mac instead.

import { h } from './util.js';

const API = 'https://api.scryfall.com/cards/autocomplete';
const MIN = 2;

// Scryfall asks for a gap between calls. A person types faster than that, so
// the request is debounced and the one in flight is abandoned when a newer
// one starts.
const WAIT = 180;

const cache = new Map();

async function suggest(term, signal) {
  const key = term.toLowerCase();
  if (cache.has(key)) return cache.get(key);
  const res = await fetch(`${API}?q=${encodeURIComponent(term)}`, {
    signal,
    headers: { Accept: 'application/json' },
  });
  if (!res.ok) throw new Error(`scryfall ${res.status}`);
  const names = (await res.json()).data || [];
  cache.set(key, names);
  return names;
}

/**
 * Attach autocomplete to a text input.
 *
 * Returns the wrapper to put in the DOM; the input itself stays yours.
 * `onPick` fires with the chosen name, after the input is already set.
 */
export function autocomplete(input, { onPick, limit = 10 } = {}) {
  const list = h('ul.ac-list', { hidden: true });
  const wrap = h('div.ac', input, list);

  let items = [];
  let active = -1;
  let timer = null;
  let inflight = null;

  const close = () => { list.hidden = true; active = -1; };

  function render() {
    if (!items.length) { close(); return; }
    list.replaceChildren(...items.map((name, i) => h('li', {
      class: i === active ? 'on' : '',
      // mousedown, not click: blur would close the list first.
      onmousedown: (e) => { e.preventDefault(); choose(i); },
      onmouseenter: () => { active = i; render(); },
    }, name)));
    list.hidden = false;
  }

  function choose(i) {
    const name = items[i];
    if (!name) return;
    input.value = name;
    close();
    input.dispatchEvent(new Event('input', { bubbles: true }));
    if (onPick) onPick(name);
  }

  async function look() {
    const term = input.value.trim();
    if (term.length < MIN) { items = []; close(); return; }
    if (inflight) inflight.abort();
    inflight = new AbortController();
    try {
      items = (await suggest(term, inflight.signal)).slice(0, limit);
      active = -1;
      render();
    } catch (e) {
      // An aborted lookup is the next keystroke doing its job, and being
      // offline is not worth an error in a convenience.
      if (e.name !== 'AbortError') { items = []; close(); }
    }
  }

  input.setAttribute('autocomplete', 'off');
  input.setAttribute('role', 'combobox');
  input.setAttribute('aria-autocomplete', 'list');

  input.addEventListener('input', () => {
    clearTimeout(timer);
    timer = setTimeout(look, WAIT);
  });
  input.addEventListener('blur', () => setTimeout(close, 120));
  input.addEventListener('keydown', (e) => {
    if (list.hidden || !items.length) return;
    if (e.key === 'ArrowDown') { e.preventDefault(); active = (active + 1) % items.length; render(); }
    else if (e.key === 'ArrowUp') { e.preventDefault(); active = (active - 1 + items.length) % items.length; render(); }
    else if (e.key === 'Enter' && active >= 0) { e.preventDefault(); choose(active); }
    else if (e.key === 'Escape') { e.stopPropagation(); close(); }
  });

  return wrap;
}
