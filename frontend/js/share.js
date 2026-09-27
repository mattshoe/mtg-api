// The page half of the Android share target.
//
// `sw.js` catches the share POST and parks the text in the cache, because
// that POST has nowhere else to go on static hosting. This reads it back,
// clears it, and holds it until the add page mounts and claims it.
//
// The service worker exists only for that. It is registered here rather
// than in sw.js's own right so the one place that depends on it is the one
// place that sets it up.

const CACHE = 'share-inbox';

// Same URL the worker writes to: a cache key is a URL, and both sides sit
// at the site root.
const SLOT = new URL('share-inbox-payload', location.href).href;

let pending = null;

/** Claim whatever was shared, if anything. Only returns it once. */
export function takeShared() {
  const p = pending;
  pending = null;
  return p;
}

async function drain() {
  if (typeof caches === 'undefined') return null;
  try {
    const cache = await caches.open(CACHE);
    const res = await cache.match(SLOT);
    if (!res) return null;
    await cache.delete(SLOT);
    const payload = await res.json();
    return payload?.list ? payload : null;
  } catch {
    // No cache, no worker, private mode — sharing is a shortcut, not a
    // feature anything else depends on.
    return null;
  }
}

/**
 * Register the worker and watch for shares.
 *
 * `onShared` fires once per share, after the payload is in hand, and is
 * expected to send the app to the add page.
 */
export function installShareTarget(onShared) {
  if (navigator.serviceWorker) {
    // `updateViaCache: 'none'` keeps the worker script out of the HTTP
    // cache. Pages serves it with max-age=600 like everything else, and a
    // worker that updates ten minutes late is the one file the version
    // stamping cannot reach — the browser fetches it by name, not through
    // the import map.
    navigator.serviceWorker.register('sw.js', { updateViaCache: 'none' }).catch(() => {});
  }

  const check = async () => {
    const payload = await drain();
    if (!payload) return;
    pending = payload;
    onShared(payload);
  };

  check();
  // An installed app is often resumed rather than loaded, so the first
  // check can have already happened by the time the share lands.
  addEventListener('pageshow', check);
  document.addEventListener('visibilitychange', () => { if (!document.hidden) check(); });
}
