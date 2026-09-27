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

const CACHE = 'share-inbox';

// Same URL the worker writes to: a cache key is a URL, and both sides sit
// at the site root.
const SLOT = new URL('share-inbox-payload', location.href).href;

// A share nobody ever picked up should not ambush the next launch by
// dragging it onto the add page days later.
const STALE_MS = 15 * 60 * 1000;

let held = null;

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

/** Read the inbox into memory. Leaves the cache entry where it is. */
export async function loadShare() {
  if (held) return held;
  const cache = await inbox();
  if (!cache) return null;
  try {
    const res = await cache.match(SLOT);
    if (!res) return null;
    const payload = await res.json();
    if (!payload?.list || Date.now() - (payload.at || 0) > STALE_MS) {
      await cache.delete(SLOT);
      return null;
    }
    held = payload;
  } catch {
    return null;
  }
  return held;
}

/** The share in hand, with no waiting. Repaint-safe because of it. */
export const sharedNow = () => held;

/** It is in the box now, and nothing else needs it. */
export async function shareUsed() {
  if (!held) return;
  held = null;
  const cache = await inbox();
  if (!cache) return;
  try { await cache.delete(SLOT); } catch { /* it will go stale anyway */ }
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
