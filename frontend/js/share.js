// The page half of the Android share target.
//
// `sw.js` catches the share POST and parks the text in the cache, because
// that POST has nowhere else to go on static hosting. This reads it back.
//
// Two things make the timing awkward, and between them they decide the
// shape of this file:
//
//   - The add page is behind the password, so the list has to survive a
//     bounce to the library, a login dialog and a navigation back before
//     anything can put it in a box.
//   - The add page can be rendered more than once on the way through
//     that. Android fires a visibility change when the soft keyboard
//     closes over the password field, which is enough to route again.
//
// So the list stays in the cache until it is genuinely used, and it is
// handed out synchronously from memory in the meantime. An await in the
// middle of claiming it let one render take the payload and a later one
// paint an empty box over the top, which is exactly what happened.

import { API } from './api.js';
import { authHeader } from './admin.js';

const CACHE = 'share-inbox';

// Same URL the worker writes to: a cache key is a URL, and both sides sit
// at the site root.
const SLOT = new URL('share-inbox-payload', location.href).href;

// A share nobody ever picked up should not ambush the next launch by
// dragging it onto the add page days later.
const STALE_MS = 15 * 60 * 1000;

let held = null;
let heldKey = SLOT;

async function inbox() {
  if (typeof caches === 'undefined') return null;
  try {
    return await caches.open(CACHE);
  } catch {
    // No cache, no worker, private mode — sharing is a shortcut, not a
    // feature anything else depends on.
    return null;
  }
}

/**
 * Find the parked share.
 *
 * The exact key is what the worker writes, but it is derived from the
 * worker's scope and this from the page's URL, so a mismatch is possible
 * in a way that would look exactly like nothing having been shared. There
 * is at most a handful of keys in here — check them all rather than let
 * that be a silent failure.
 */
async function find(cache) {
  const hit = await cache.match(SLOT);
  if (hit) return { key: SLOT, res: hit };
  for (const req of await cache.keys()) {
    if (req.url.endsWith('/share-inbox-payload')) {
      return { key: req, res: await cache.match(req) };
    }
  }
  return null;
}

/** Read the inbox into memory. Leaves the cache entry where it is. */
export async function loadShare() {
  if (held) return held;
  const cache = await inbox();
  if (!cache) return null;
  try {
    const found = await find(cache);
    if (!found?.res) return null;
    const payload = await found.res.json();
    // `problem` is the worker saying it got something it could not use.
    // That is worth showing, so it counts as a share for these purposes.
    if ((!payload?.list && !payload?.problem) || Date.now() - (payload.at || 0) > STALE_MS) {
      await cache.delete(found.key);
      return null;
    }
    held = payload;
    heldKey = found.key;
    reportShare(payload.problem ? `share unusable: ${payload.problem}` : 'share received', {
      ...(payload.report || {}),
      chars: payload.list ? payload.list.length : 0,
      names: payload.names || [],
      problem: payload.problem || null,
    });
  } catch (e) {
    reportShare('share inbox could not be read', { error: String(e && e.message) });
    return null;
  }
  return held;
}

/**
 * Tell the server what happened.
 *
 * All of this runs in a service worker on a phone, where there is no
 * console to read and no way to attach a debugger from here. Without a
 * line in the log there is nothing to go on but guesswork, and guesswork
 * is what made this take four attempts. Fire and forget: a report that
 * fails must never be the reason a share fails.
 */
export function reportShare(message, detail) {
  try {
    const auth = authHeader();
    if (!auth.authorization) return;
    fetch(`${API}/logs/client`, {
      method: 'POST',
      headers: { 'content-type': 'application/json', ...auth },
      body: JSON.stringify({ level: detail?.problem ? 'warn' : 'info', message, detail }),
    }).catch(() => {});
  } catch { /* never in the way */ }
}

/** The share in hand, with no waiting. Repaint-safe because of it. */
export const sharedNow = () => held;

/** It is in the box now, and nothing else needs it. */
export async function shareUsed() {
  if (!held) return;
  const key = heldKey;
  held = null;
  heldKey = SLOT;
  const cache = await inbox();
  if (!cache) return;
  try { await cache.delete(key); } catch { /* it will go stale anyway */ }
}

export function registerWorker() {
  if (!navigator.serviceWorker) return;
  // `updateViaCache: 'none'` keeps the worker script out of the HTTP
  // cache. Pages serves it with max-age=600 like everything else, and a
  // worker that updates ten minutes late is the one file the version
  // stamping cannot reach — the browser fetches it by name, not through
  // the import map.
  navigator.serviceWorker.register('sw.js', { updateViaCache: 'none' }).catch(() => {});
}

/**
 * Call `fn` when a share turns up in an app that is already running.
 *
 * An installed app is usually resumed rather than loaded, so the share can
 * land after the boot-time read has already come back empty.
 */
export function watchShares(fn) {
  const check = async () => { if (await loadShare()) fn(); };
  // Restores from the back/forward cache only. A plain pageshow is the
  // load boot() has already dealt with, and acting on it again put a
  // second navigation in the air against the first.
  addEventListener('pageshow', (e) => { if (e.persisted) check(); });
  document.addEventListener('visibilitychange', () => { if (!document.hidden) check(); });
}
