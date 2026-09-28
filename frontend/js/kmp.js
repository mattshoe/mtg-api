// Mounting the multiplatform build of the mass entry wizard.
//
// The wizard's rules — which step is next, whether a write may be
// offered at all — live in Kotlin now, shared with the Android app, so
// the two cannot drift. This is the seam between that and the rest of
// the site, which is unchanged plain JavaScript.
//
// The bundle is around 260 KB gzipped against 66 KB for the whole
// hand-written app, so it is fetched on this route and no other. Seventeen
// other screens never touch it. Most of that weight is Ktor doing what
// fetch already does, and dropping it is the next thing worth doing.
//
// If it will not load, the caller falls back to the hand-written wizard,
// which is still there.

import { $ } from './util.js';

const BUNDLE = 'kmp/mtg.js';

let loading = null;

/** Fetch the bundle once. Resolves with the bridge it hangs off window. */
function load() {
  if (window.mtgEntry) return Promise.resolve(window.mtgEntry);
  if (loading) return loading;

  loading = new Promise((resolve, reject) => {
    const tag = document.createElement('script');
    // Same version stamp the modules get, for the same reason: Pages
    // caches for ten minutes and a half-updated app is worse than an old
    // one.
    tag.src = new URL(BUNDLE, document.baseURI).href + versionQuery();
    tag.onload = () => (window.mtgEntry
      ? resolve(window.mtgEntry)
      : reject(new Error('the bundle loaded but exported nothing')));
    tag.onerror = () => reject(new Error('the bundle could not be fetched'));
    document.head.append(tag);
  }).catch((e) => {
    loading = null;     // let a later route try again
    throw e;
  });

  return loading;
}

/** Whatever the deploy stamped onto the entry script, if anything. */
function versionQuery() {
  const src = document.querySelector('script[src*="js/app.js"]')?.getAttribute('src') || '';
  const v = /[?&]v=([^&]+)/.exec(src);
  return v ? `?v=${v[1]}` : '';
}

/**
 * Render the wizard into the view.
 *
 * `sharedList` is a decklist that arrived from the Android share sheet,
 * already read out of the fragment by share.js. Returns false if the
 * bundle would not load, so the caller can fall back rather than leaving
 * an empty page.
 */
export async function mountEntry(sharedList, token) {
  const bridge = await load();
  const host = document.createElement('div');
  $('#view').replaceChildren(host);
  bridge.mount(host, sharedList || null, token || '');
  return true;
}

/** Dispose it on the way out, or the next route renders over a live one. */
export function unmountEntry() {
  try {
    window.mtgEntry?.unmount();
  } catch {
    // Already gone, or never arrived. Either way there is nothing to do.
  }
}
