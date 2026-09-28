// Shell: routing, keyboard shortcuts, quick find.

import * as api from './api.js';
import { h, $, $$, fill, debounce, imageUrl, toast } from './util.js';
import {
  adminButton, isAdmin, onAdminChange, promptUnlock, lock,
  authHeader, rejected,
} from './admin.js';
import { openCard, closeCard, openCardId, hideCardForRoute } from './card.js';
import { pushOverlay, dropOverlay } from './overlay.js';
import {
  registerWorker, loadShare, sharedNow, watchShares, watchLaunches,
} from './share.js';
import * as search from './search.js';
import * as decks from './decks.js';
import * as manage from './manage.js';
import { mountEntry, unmountEntry } from './kmp.js';
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
const GATED = new Set(['entry', 'entryjs', 'logs']);

const ROUTES = {
  search: (rest) => search.show(rest),
  decks: (rest) => decks.show(rest),
  // The multiplatform build. Its rules are shared with the Android app,
  // so the two cannot disagree about what is reachable.
  entry: () => showEntry(),
  // The hand-written one, still here and one hash away, so a bad deploy
  // of the bundle is a link rather than a rollback.
  entryjs: () => manage.show(),
  stats: (rest) => stats.show(rest),
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

// Add and remove were separate pages and are one now. Anything still
// pointing at the old names — a bookmark, an older service worker's share
// redirect — lands on the wizard rather than on nothing.
const MOVED = { add: 'entry', remove: 'entry' };

async function route() {
  const { view, rest, query } = parseHash();
  if (MOVED[view]) { location.replace(`#/${MOVED[view]}`); return; }
  const fn = ROUTES[view];

  // Navigating out from under the drawer closes it. Leaving it up over a
  // page it does not belong to is how back got confusing in the first place.
  hideCardForRoute();

  // And a live Compose composition must not be left attached to a node
  // the router is about to replace.
  if (currentView === 'entry' && view !== 'entry') unmountEntry();

  for (const a of $$('#tabs a')) a.classList.toggle('on', a.dataset.view === view);
  $('#tabs').classList.remove('open');
  $('#nav-toggle').setAttribute('aria-expanded', 'false');

  if (!fn) { location.hash = '#/search'; return; }

  // A bookmark or a back button can still point at a gated view. Bounce to
  // search and offer the password rather than rendering a shell that
  // cannot do anything.
  if (GATED.has(view) && !isAdmin()) {
    location.replace(`#/search${query ? `?${query}` : ''}`);
    // replace, not assign: promptUnlock hands this the history entry its
    // own dialog was occupying, so the view we were headed for takes that
    // slot and back still goes to the page before it.
    promptUnlock(() => { location.replace(`#/${view}${rest ? `/${rest}` : ''}`); });
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

/**
 * The entry route, multiplatform build first.
 *
 * If the bundle will not load there is still a working wizard in this
 * repo, so fall back to it rather than showing an empty page.
 */
async function showEntry() {
  try {
    await mountEntry(sharedNow()?.list, authHeader().authorization?.slice(7));
  } catch {
    toast('Loading the new wizard failed, using the old one', 'bad');
    manage.show();
  }
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

  const go = { s: 'search', d: 'decks', e: 'entry', g: 'stats', c: 'console', v: 'logs' }[e.key];
  if (go) {
    // The shortcuts for gated views are as hidden as their tabs.
    if (!GATED.has(go) || isAdmin()) location.hash = `#/${go}`;
    return;
  }
  if (e.key === 'l') (isAdmin() ? lock() : promptUnlock());
  if (e.key === '/') { e.preventDefault(); openPalette(); }
  if (e.key === '?') {
    toast(`s search · d decks${isAdmin() ? ' · e entry' : ''} · g stats · c console`
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

registerWorker();

// A decklist or CSV shared from another Android app lands on the add page
// with the list already in the box. The worker's redirect puts us on #/entry
// directly, so on boot this only has to cover the app being resumed
// somewhere else. It must happen before the first route(): sending the
// share somewhere after routing meant two navigations fighting over the
// same history entry, and the login dialog's own entry lost.
async function boot() {
  if (!location.hash) location.hash = '#/search';
  // Before the first render, because the add page takes the list without
  // awaiting anything — it has to already be in hand by then.
  await loadShare();

  if (sharedNow() && parseHash().view !== 'entry') {
    location.replace('#/entry');   // fires hashchange, which routes
    return;
  }
  route();
}

// A share arriving while the app is already open. The list itself stays in
// the cache either way, so this only has to get the user to the page.
const toAdd = () => {
  if (parseHash().view === 'entry') route();
  else location.hash = '#/entry';
};
watchShares(toAdd);
// And the same for a file opened with the app rather than shared to it.
watchLaunches(toAdd);

boot();
