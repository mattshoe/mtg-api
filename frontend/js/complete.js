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
  let nodes = [];
  let active = -1;
  let timer = null;
  let inflight = null;

  const close = () => { list.hidden = true; active = -1; };

  /** Move the highlight without rebuilding anything. */
  function highlight(i) {
    active = i;
    nodes.forEach((li, n) => li.classList.toggle('on', n === active));
  }

  /**
   * Build the list once per result set.
   *
   * Rebuilding on hover destroyed the very element being pressed: moving
   * the pointer fired mouseenter, which replaced every node, so the
   * mousedown landed on an element that no longer existed and the click did
   * nothing. Nodes are stable now and hover only flips a class.
   */
  function render() {
    if (!items.length) { close(); return; }
    nodes = items.map((name, i) => h('li', {
      onmouseenter: () => highlight(i),
    }, name));
    list.replaceChildren(...nodes);
    active = -1;
    list.hidden = false;
  }

  // Delegated, and mousedown rather than click: the input blurs on press,
  // and a blur-driven close would remove the target before click fired.
  list.addEventListener('mousedown', (e) => {
    const li = e.target.closest('li');
    if (!li) return;
    e.preventDefault();
    choose(nodes.indexOf(li));
  });

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
    if (e.key === 'ArrowDown') { e.preventDefault(); highlight((active + 1) % items.length); }
    else if (e.key === 'ArrowUp') { e.preventDefault(); highlight((active - 1 + items.length) % items.length); }
    else if (e.key === 'Enter' && active >= 0) { e.preventDefault(); choose(active); }
    else if (e.key === 'Escape') { e.stopPropagation(); close(); }
  });

  return wrap;
}
