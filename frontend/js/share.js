// The page half of the Android share target.
//
// `sw.js` catches the share POST and parks the text in the cache, because
// that POST has nowhere else to go on static hosting. This reads it back.
//
// The payload stays in the cache until the add page actually has it. That
// is the whole trick: the add page is behind the password, so between the
// share landing and the list reaching the box there is a bounce to the
// library, a login dialog and a navigation back. Holding the payload in a
// variable across all that meant one stray reload threw the file away.

const CACHE = 'share-inbox';

// Same URL the worker writes to: a cache key is a URL, and both sides sit
// at the site root.
const SLOT = new URL('share-inbox-payload', location.href).href;

// A share nobody ever picked up should not ambush the next launch by
// dragging it onto the add page days later.
const STALE_MS = 15 * 60 * 1000;

async function read(consume) {
  if (typeof caches === 'undefined') return null;
  try {
    const cache = await caches.open(CACHE);
    const res = await cache.match(SLOT);
    if (!res) return null;
    const payload = await res.json();
    const stale = !payload?.list || Date.now() - (payload.at || 0) > STALE_MS;
    if (stale || consume) await cache.delete(SLOT);
    return stale ? null : payload;
  } catch {
    // No cache, no worker, private mode — sharing is a shortcut, not a
    // feature anything else depends on.
    return null;
  }
}

/** Is something sitting in the inbox? Leaves it there. */
export const sharedWaiting = () => read(false).then(Boolean);

/** Take it. This is the only thing that empties the inbox. */
export const takeShared = () => read(true);

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
 * land after the boot-time check has already come back empty.
 */
export function watchShares(fn) {
  const check = async () => { if (await sharedWaiting()) fn(); };
  addEventListener('pageshow', check);
  document.addEventListener('visibilitychange', () => { if (!document.hidden) check(); });
}
