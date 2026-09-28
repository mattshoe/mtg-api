// The page half of the Android share target.
//
// `sw.js` catches the share POST and hands the list over in the URL
// fragment — `#/add?share=<encoded>`. That is read here, synchronously, at
// module load, before a single thing has rendered. It used to come through
// Cache Storage and that is what kept failing on the phone: the redirect
// arrived on the add page and the cache read came back empty, with nothing
// anywhere to say why.
//
// A fragment cannot be evicted, needs no quota, and cannot be read back
// under a key that does not match. The cache is still read as a fallback,
// because a list too big for a URL still goes that way.

import { API } from './api.js';
import { authHeader } from './admin.js';

const CACHE = 'share-inbox';
const SLOT = new URL('share-inbox-payload', location.href).href;

// A share nobody ever picked up should not ambush the next launch by
// dragging it onto the add page days later. Only applies to the cache
// path; a fragment is by definition this navigation.
const STALE_MS = 15 * 60 * 1000;

let held = null;
let heldKey = SLOT;
let reported = false;

const usable = (p) => Boolean(p) && (Boolean(p.list) || Boolean(p.problem));

// ------------------------------------------------------- the fragment

// What the fragment looked like on arrival, reported once the token is
// available. Module load happens before anything can log.
let arrival = null;

(function fromFragment() {
  const hash = location.hash;
  const m = /[?&]share=([^&]*)/.exec(hash);
  arrival = {
    href: location.href.slice(0, 200),
    hashLength: hash.length,
    hasShareParam: Boolean(m),
    encodedLength: m ? m[1].length : 0,
  };
  if (!m) return;

  try {
    const payload = JSON.parse(decodeURIComponent(m[1]));
    arrival.decoded = true;
    arrival.keys = Object.keys(payload || {});
    arrival.listChars = payload?.list ? payload.list.length : 0;
    if (usable(payload)) held = payload;
    else arrival.rejected = 'payload had neither a list nor a problem';
  } catch (e) {
    arrival.decoded = false;
    arrival.error = e.message;
    held = { at: Date.now(), problem: `the share arrived but would not decode (${e.message}).`, report: {} };
  }

  // Take it back out of the URL. Leaving a whole decklist in the address
  // bar means a reload re-applies it, and any link copied out of here
  // carries it.
  const clean = location.hash.replace(/[?&]share=[^&]*/, '').replace(/\?$/, '') || '#/add';
  try { history.replaceState(history.state, '', clean); } catch { /* not worth failing over */ }
}());

// ----------------------------------------------------------- the cache

async function inbox() {
  if (typeof caches === 'undefined') return null;
  try {
    return await caches.open(CACHE);
  } catch {
    return null;
  }
}

/**
 * Look for a parked share under any key that could be ours.
 *
 * The exact key is derived from the worker's scope and this one from the
 * page's URL. They should agree, and a disagreement would look exactly
 * like nothing having been shared, so check every key rather than let
 * that be silent.
 */
async function find(cache) {
  const hit = await cache.match(SLOT);
  if (hit) return { key: SLOT, res: hit };
  for (const req of await cache.keys()) {
    if (req.url.endsWith('/share-inbox-payload')) return { key: req, res: await cache.match(req) };
  }
  return null;
}

/** Read the inbox into memory. Leaves the cache entry where it is. */
export async function loadShare() {
  if (held) { announce(); return held; }
  const cache = await inbox();
  if (!cache) return null;
  try {
    const found = await find(cache);
    if (!found?.res) return null;
    const payload = await found.res.json();
    if (!usable(payload) || Date.now() - (payload.at || 0) > STALE_MS) {
      await cache.delete(found.key);
      return null;
    }
    held = payload;
    heldKey = found.key;
  } catch (e) {
    reportShare('share inbox could not be read', { error: String(e && e.message) });
    return null;
  }
  announce();
  return held;
}

/**
 * The other way in: Open with, from a file manager.
 *
 * `file_handlers` in the manifest routes an opened file here through the
 * Launch Queue, which has nothing to do with the share sheet and nothing
 * to do with the service worker. If Android is mishandling one of them it
 * is unlikely to be mishandling both.
 */
export function watchLaunches(fn) {
  const queue = window.launchQueue;
  if (!queue || typeof queue.setConsumer !== 'function') return;
  queue.setConsumer(async (params) => {
    const handles = params?.files || [];
    reportShare('page: launched with files', { count: handles.length });
    if (!handles.length) return;
    const texts = [];
    const names = [];
    for (const handle of handles) {
      try {
        const file = await handle.getFile();
        const text = await file.text();
        reportShare('page: launch file read', {
          name: file.name, type: file.type, size: file.size, chars: text.length,
        });
        if (text.trim()) { texts.push(text); names.push(file.name); }
      } catch (e) {
        reportShare('page: launch file would not read', { error: String(e && e.message) }, 'error');
      }
    }
    const list = texts.join('\n').trim();
    held = list
      ? { list, names, at: Date.now(), report: { v: 'launch-queue', files: names } }
      : { at: Date.now(), problem: 'the file opened, but there was no text in it.', report: { v: 'launch-queue' } };
    reported = false;
    announce();
    fn();
  });
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

// ------------------------------------------------------------ logging

/**
 * Tell the server what happened.
 *
 * All of this runs in a service worker on a phone, where there is no
 * console to read from a laptop and nothing to attach a debugger to.
 * Without a line in the log there is nothing to go on but guesswork, and
 * guesswork is what made this take five attempts. Fire and forget: a
 * report that fails must never be the reason a share fails.
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

function announce() {
  if (reported || !held) return;
  reported = true;
  reportShare(held.problem ? `page: share unusable — ${held.problem}` : 'page: share received', {
    ...(held.report || {}),
    arrival,
    via: heldKey === SLOT && held.report?.parked !== false ? 'fragment' : 'cache',
    chars: held.list ? held.list.length : 0,
    names: held.names || [],
    problem: held.problem || null,
  });
}

/** Everything the page knows about how it got here, share or not. */
export function reportArrival(extra) {
  reportShare('page: boot', {
    ...arrival,
    ...extra,
    held: held ? { chars: held.list?.length || 0, problem: held.problem || null } : null,
  });
}

// ------------------------------------------------------------- worker

export function registerWorker() {
  if (!navigator.serviceWorker) return;
  // `updateViaCache: 'none'` keeps the worker script out of the HTTP
  // cache, and the explicit update() makes every launch check for a new
  // one. Pages serves it with max-age=600 like everything else, and it is
  // the one file the version stamping cannot reach — the browser fetches
  // it by name, not through the import map. Without this, a fix to the
  // worker can sit unused for ten minutes after it ships.
  navigator.serviceWorker.register('sw.js', { updateViaCache: 'none' })
    .then((reg) => reg.update())
    .catch(() => {});

  // Hand the worker a token so it can log on its own. It handles the share
  // in a context the page never sees — if it goes wrong before the
  // redirect, the page is not there to report it, and for five attempts
  // that was a blind spot.
  const give = () => {
    const sw = navigator.serviceWorker.controller;
    const auth = authHeader();
    if (sw && auth.authorization) sw.postMessage({ type: 'auth', token: auth.authorization.slice(7) });
  };
  give();
  navigator.serviceWorker.addEventListener('controllerchange', give);
  navigator.serviceWorker.ready.then(give).catch(() => {});
}

/**
 * Which build of the worker is actually running.
 *
 * Pages caches sw.js for ten minutes and a worker can go on serving long
 * after a fix ships, so "did my change even reach the phone" is a real
 * question and this is the only way to answer it. An older worker has no
 * message handler and never replies, which says what it needs to say.
 */
export function workerVersion() {
  const sw = navigator.serviceWorker?.controller;
  if (!sw) return Promise.resolve('none');
  return new Promise((resolve) => {
    const ch = new MessageChannel();
    const done = setTimeout(() => resolve('no answer — older worker'), 800);
    ch.port1.onmessage = (e) => { clearTimeout(done); resolve(e.data?.sw || 'unknown'); };
    try { sw.postMessage('version', [ch.port2]); } catch { clearTimeout(done); resolve('unreachable'); }
  });
}

/**
 * Call `fn` when a share turns up in an app that is already running.
 *
 * Only the cache path can arrive that way — a fragment comes in on a
 * navigation, which is a fresh load.
 */
export function watchShares(fn) {
  const check = async () => { if (await loadShare()) fn(); };
  // Restores from the back/forward cache only. A plain pageshow is the
  // load boot() has already dealt with, and acting on it again put a
  // second navigation in the air against the first.
  addEventListener('pageshow', (e) => { if (e.persisted) check(); });
  document.addEventListener('visibilitychange', () => { if (!document.hidden) check(); });
}
