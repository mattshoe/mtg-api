// Shell: routing, keyboard shortcuts, quick find.

import * as api from './api.js';
import { h, $, $$, fill, debounce, imageUrl, toast } from './util.js';
import {
  adminButton, isAdmin, onAdminChange, promptUnlock, lock,
  authHeader, rejected,
} from './admin.js';
import { openCard, closeCard, openCardId, hideCardForRoute } from './card.js';
import { pushOverlay, dropOverlay } from './overlay.js';
import * as search from './search.js';
import * as decks from './decks.js';
import * as manage from './manage.js';
import * as stats from './stats.js';
import * as sqlConsole from './console.js';
import * as logs from './logs.js';

// Hand the API client the auth hooks. Done here rather than by importing
// admin.js from api.js, so the dependency runs one way only.
api.useAuth(authHeader, rejected);

// ---------------------------------------------------------------- routing

// Views that do nothing without a token. While locked they are not
// reachable and their tabs are not in the DOM's flow at all — the lock in
// the header is the only sign they exist.
const GATED = new Set(['add', 'remove', 'logs']);

const ROUTES = {
  search: (rest) => search.show(rest),
  decks: (rest) => decks.show(rest),
  add: () => manage.show('add'),
  remove: () => manage.show('remove'),
  stats: () => stats.show(),
  console: () => sqlConsole.show(),
  logs: () => logs.show(),
};

function parseHash() {
  const raw = location.hash.replace(/^#\/?/, '');
  const [pathPart, queryPart] = raw.split('?');
  const [view, ...segs] = pathPart.split('/').filter(Boolean);
  return { view: view || 'search', rest: segs.join('/'), query: queryPart || '' };
}

let currentView = null;

async function route() {
  const { view, rest, query } = parseHash();
  const fn = ROUTES[view];

  // Navigating out from under the drawer closes it. Leaving it up over a
  // page it does not belong to is how back got confusing in the first place.
  hideCardForRoute();

  for (const a of $$('#tabs a')) a.classList.toggle('on', a.dataset.view === view);
  $('#tabs').classList.remove('open');
  $('#nav-toggle').setAttribute('aria-expanded', 'false');

  if (!fn) { location.hash = '#/search'; return; }

  // A bookmark or a back button can still point at a gated view. Bounce to
  // search and offer the password rather than rendering a shell that
  // cannot do anything.
  if (GATED.has(view) && !isAdmin()) {
    location.replace(`#/search${query ? `?${query}` : ''}`);
    promptUnlock(() => { location.hash = `#/${view}${rest ? `/${rest}` : ''}`; });
    return;
  }

  // Search owns its own hash, so re-entering it with a new query string is a
  // filter change rather than a fresh mount.
  const sameView = currentView === view;
  currentView = view;
  if (view !== 'search') $('#view').dataset.view = view;

  try {
    if (view === 'search') await fn(query);
    else await fn(rest);
  } catch (e) {
    fill($('#view'), h('div.wrap', h('div.err', String(e.message || e))));
  }
  if (!sameView) window.scrollTo(0, 0);
}

addEventListener('hashchange', () => {
  // Search rewrites its own hash as filters change; do not remount for that.
  const { view, query } = parseHash();
  if (view === 'search' && currentView === 'search' && $('#view').dataset.view === 'search') {
    search.show(query);
    return;
  }
  route();
});

// ------------------------------------------------------------ quick find

let paletteItems = [];
let paletteIdx = 0;

function hidePalette() {
  if ($('#palette-scrim').hidden) return false;
  $('#palette-scrim').hidden = true;
  $('#palette-input').value = '';
  fill($('#palette-list'));
  return true;
}

const paletteBack = () => { hidePalette(); };

function closePalette() {
  if (hidePalette()) dropOverlay(paletteBack);
}

function openPalette() {
  if ($('#palette-scrim').hidden) pushOverlay(paletteBack);
  $('#palette-scrim').hidden = false;
  const input = $('#palette-input');
  input.focus();
  input.select();
}

const searchPalette = debounce(async (term) => {
  if (!term.trim()) { fill($('#palette-list')); paletteItems = []; return; }
  try {
    const rows = await api.rows(`
      SELECT MIN(id) AS id, name, scryfall_id, type_line, SUM(qty) AS qty, owner
        FROM cards
       WHERE name_norm LIKE ? OR lower(face1) LIKE ? OR lower(face2) LIKE ?
       GROUP BY owner, name_norm
       ORDER BY length(name), name LIMIT 12`,
    [`%${term.toLowerCase()}%`, `%${term.toLowerCase()}%`, `%${term.toLowerCase()}%`]);
    paletteItems = rows;
    paletteIdx = 0;
    renderPalette();
  } catch { /* typing fast, ignore */ }
}, 180);

function renderPalette() {
  fill($('#palette-list'), paletteItems.map((r, i) => h('li', {
    class: i === paletteIdx ? 'on' : '',
    onclick: () => { closePalette(); openCard(r.id); },
    onmouseenter: () => { paletteIdx = i; renderPalette(); },
  },
  h('img', {
    src: imageUrl(r.scryfall_id, 'small'),
    style: { width: '28px', borderRadius: '2px', flex: 'none' },
    loading: 'lazy', alt: '',
    onerror: (e) => { e.target.style.visibility = 'hidden'; },
  }),
  h('div', { style: { flex: '1', minWidth: '0' } },
    h('div', r.name),
    h('div.muted.small', r.type_line || '')),
  h('span.tag.mini', `${r.qty}× ${r.owner}`))));
}

// ------------------------------------------------------------- shortcuts

addEventListener('keydown', (e) => {
  const typing = /^(INPUT|TEXTAREA|SELECT)$/.test(e.target.tagName);

  if (e.key === 'Escape') {
    if (!$('#palette-scrim').hidden) { closePalette(); return; }
    if (!$('#drawer').hidden) { closeCard(); return; }
    return;
  }

  if ((e.metaKey || e.ctrlKey) && e.key === 'k') {
    e.preventDefault();
    openPalette();
    return;
  }

  if (!$('#palette-scrim').hidden) {
    if (e.key === 'ArrowDown') { e.preventDefault(); paletteIdx = Math.min(paletteItems.length - 1, paletteIdx + 1); renderPalette(); }
    if (e.key === 'ArrowUp') { e.preventDefault(); paletteIdx = Math.max(0, paletteIdx - 1); renderPalette(); }
    if (e.key === 'Enter' && paletteItems[paletteIdx]) {
      e.preventDefault();
      const it = paletteItems[paletteIdx];
      closePalette();
      openCard(it.id);
    }
    return;
  }

  if (typing) return;

  const go = { s: 'search', d: 'decks', a: 'add', r: 'remove', g: 'stats', c: 'console', v: 'logs' }[e.key];
  if (go) {
    // The shortcuts for gated views are as hidden as their tabs.
    if (!GATED.has(go) || isAdmin()) location.hash = `#/${go}`;
    return;
  }
  if (e.key === 'l') (isAdmin() ? lock() : promptUnlock());
  if (e.key === '/') { e.preventDefault(); openPalette(); }
  if (e.key === '?') {
    toast(`s search · d decks${isAdmin() ? ' · a add · r remove' : ''} · g stats · c console`
      + `${isAdmin() ? ' · v logs' : ''} · l ${isAdmin() ? 'lock' : 'unlock'}`
      + ' · / or ⌘K find · esc close');
  }
});

// ----------------------------------------------------------------- wiring

$('.topbar-right').prepend(adminButton());

// The gated tabs appear and disappear with the lock. Locking while one of
// them is open also has to move you off it, or the view stays on screen
// with a dead token behind it.
function paintTabs() {
  const on = isAdmin();
  for (const el of $$('#tabs [data-gated]')) el.hidden = !on;
}
onAdminChange((on) => {
  paintTabs();
  // Locking while on a gated view has to move you off it; the hashchange
  // re-renders on the way out.
  if (!on && GATED.has(parseHash().view)) { location.hash = '#/search'; return; }
  // Otherwise re-render where you are. Unlocking used to change nothing on
  // screen until you navigated away and back, so the edit buttons the
  // password just earned you stayed hidden on the page you were looking at.
  route();
  const card = openCardId();
  if (card) openCard(card);
});
paintTabs();
function closeNav() {
  $('#tabs').classList.remove('open');
  $('#nav-toggle').setAttribute('aria-expanded', 'false');
}

$('#nav-toggle').addEventListener('click', (e) => {
  e.stopPropagation();
  const open = $('#tabs').classList.toggle('open');
  $('#nav-toggle').setAttribute('aria-expanded', String(open));
});

// Picking anything in the menu closes it. route() did this, but only when
// the hash actually changed — tapping the tab you were already on left the
// menu sitting open over the page.
$('#tabs').addEventListener('click', (e) => { if (e.target.closest('a')) closeNav(); });
// And so does a tap anywhere else, the way a menu is expected to behave.
document.addEventListener('click', (e) => {
  if (!e.target.closest('#tabs, #nav-toggle')) closeNav();
});
$('#drawer-scrim').addEventListener('click', closeCard);
$('#palette-scrim').addEventListener('click', (e) => {
  if (e.target === $('#palette-scrim')) closePalette();
});
$('#palette-input').addEventListener('input', (e) => searchPalette(e.target.value));

if (!location.hash) location.hash = '#/search';
route();
