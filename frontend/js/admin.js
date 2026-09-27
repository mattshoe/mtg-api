// Admin mode in the browser.
//
// The token is kept until you lock it, across reloads and across launches
// of the installed app. It used to live in a module variable and die with
// the page, which read well and was miserable to use: a share from the
// Android share sheet is a fresh page load, so every single one asked for
// the password again before it would show you the list it had just been
// handed.
//
// What is stored is the token the server issued, not the password, and the
// server is still the only thing that decides whether it is any good. The
// lock button and `wrangler secret put ADMIN_PASSWORD` are the two ways
// out.

import { API } from './api.js';
import { h, fill, toast, store } from './util.js';
import { pushOverlay, dropOverlay, forgetOverlay } from './overlay.js';

const KEY = 'admin';

let token = null;
// Null means it does not expire, which is what the server issues now. A
// number is one of the old twelve-hour tokens, still good until it lapses.
let expiresAt = null;
const listeners = new Set();

const live = (exp) => exp === null || exp === undefined || exp * 1000 > Date.now();

// Picked up before anything else runs, so the first route already knows
// whether the admin pages are reachable.
(function restore() {
  const saved = store.get(KEY, null);
  if (!saved?.token || !live(saved.expires_at)) {
    if (saved) store.del(KEY);
    return;
  }
  token = saved.token;
  expiresAt = saved.expires_at;
}());

export const isAdmin = () => Boolean(token) && live(expiresAt);
export const authHeader = () => (isAdmin() ? { authorization: `Bearer ${token}` } : {});

export function onAdminChange(fn) {
  listeners.add(fn);
  return () => listeners.delete(fn);
}

function announce() {
  for (const fn of listeners) fn(isAdmin());
}

export function lock({ quiet = false } = {}) {
  const was = isAdmin();
  token = null;
  expiresAt = null;
  store.del(KEY);
  announce();
  if (was && !quiet) toast('Admin mode off');
}

/** The server decides. A 200 is the only thing that unlocks anything. */
export async function unlock(password) {
  const res = await fetch(`${API}/admin`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ password }),
  });
  if (!res.ok) {
    let msg = 'wrong password';
    try { msg = (await res.json()).error || msg; } catch { /* keep default */ }
    throw new Error(msg);
  }
  const body = await res.json();
  token = body.token;
  expiresAt = body.expires_at ?? null;
  store.set(KEY, { token, expires_at: expiresAt });
  announce();
  return true;
}

/**
 * Called when the API says 401 on something we thought we could do — the
 * token expired, or the Worker was redeployed with a new password.
 */
export function rejected() {
  if (isAdmin()) {
    lock({ quiet: true });
    toast('Admin session ended — unlock again', 'bad');
  }
}

// ------------------------------------------------------------------- UI

let dialogOpen = false;

export function promptUnlock(afterUnlock) {
  if (dialogOpen) return;
  dialogOpen = true;

  const input = h('input', { type: 'password', placeholder: 'Password', autocomplete: 'current-password' });
  const err = h('div.err', { hidden: true });
  const btn = h('button.btn.primary', 'Unlock');

  const back = () => { dialogOpen = false; scrim.remove(); };
  const close = () => { back(); dropOverlay(back); };

  /**
   * Close on the way somewhere else.
   *
   * `history.back()` is asynchronous, so popping the dialog's entry and
   * then navigating is a race the pop wins — it lands last and undoes the
   * navigation. That is what sent an unlocked share straight back to the
   * library. The entry the dialog pushed is the current one, so `go` is
   * expected to replace it rather than push past it.
   */
  const closeAndGo = (go) => { back(); forgetOverlay(back); go(); };

  async function submit() {
    const pw = input.value;
    if (!pw) return;
    btn.disabled = true;
    btn.textContent = 'Checking…';
    err.hidden = true;
    try {
      await unlock(pw);
      if (afterUnlock) closeAndGo(afterUnlock);
      else close();
      toast('Admin mode on', 'ok');
    } catch (e) {
      err.textContent = String(e.message);
      err.hidden = false;
      input.select();
    } finally {
      btn.disabled = false;
      btn.textContent = 'Unlock';
    }
  }

  input.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') { e.preventDefault(); submit(); }
    if (e.key === 'Escape') close();
  });
  btn.addEventListener('click', submit);

  const scrim = h('div.palette-scrim', {
    onclick: (e) => { if (e.target === scrim) close(); },
  }, h('div.palette', { style: { padding: '18px' } },
    h('h2', { style: { marginBottom: '4px' } }, 'Admin mode'),
    h('div.muted.small', { style: { marginBottom: '14px' } },
      'Needed for adding and removing cards, and for any SQL that writes. '
      + 'Stays on until you lock it.'),
    h('div.field', input),
    err,
    h('div.flex', { style: { marginTop: '12px' } },
      btn,
      h('button.btn.ghost', { onclick: close }, 'Cancel'))));

  document.body.append(scrim);
  pushOverlay(back);
  input.focus();
}

/** The topbar lock button. */
export function adminButton() {
  const btn = h('button.icon-btn', { id: 'admin-btn' });

  const paint = () => {
    const on = isAdmin();
    btn.title = on ? 'Admin mode on — click to lock' : 'Admin mode off — click to unlock';
    btn.setAttribute('aria-label', btn.title);
    btn.style.color = on ? 'var(--accent-2)' : '';
    btn.style.borderColor = on ? 'var(--accent)' : '';
    fill(btn, h('svg', {
      viewBox: '0 0 24 24',
      html: on
        ? '<rect x="4" y="11" width="16" height="10" rx="2"/><path d="M8 11V8a4 4 0 018 0"/>'
        : '<rect x="4" y="11" width="16" height="10" rx="2"/><path d="M8 11V7a4 4 0 017.5-2"/>',
    }));
  };

  btn.addEventListener('click', () => (isAdmin() ? lock() : promptUnlock()));
  onAdminChange(paint);
  paint();
  return btn;
}
