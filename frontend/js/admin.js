// Admin mode in the browser.
//
// The token lives in a module variable and nowhere else — not localStorage,
// not sessionStorage, not the URL. Closing the tab ends the session, which
// is the point. A reload ends it too.

import { API } from './api.js';
import { h, fill, toast } from './util.js';

let token = null;
let expiresAt = 0;
const listeners = new Set();

export const isAdmin = () => Boolean(token) && expiresAt * 1000 > Date.now();
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
  expiresAt = 0;
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
  expiresAt = body.expires_at;
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

  const close = () => { dialogOpen = false; scrim.remove(); };

  async function submit() {
    const pw = input.value;
    if (!pw) return;
    btn.disabled = true;
    btn.textContent = 'Checking…';
    err.hidden = true;
    try {
      await unlock(pw);
      close();
      toast('Admin mode on — edits enabled for this tab', 'ok');
      if (afterUnlock) afterUnlock();
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
      + 'Lasts for this tab only.'),
    h('div.field', input),
    err,
    h('div.flex', { style: { marginTop: '12px' } },
      btn,
      h('button.btn.ghost', { onclick: close }, 'Cancel'))));

  document.body.append(scrim);
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
