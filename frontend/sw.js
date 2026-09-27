// Service worker, for one job: catching shares from the Android share sheet.
//
// The manifest declares a share target, and a share target that accepts
// files has to be `method: "POST"`. GitHub Pages is static and cannot
// receive a POST, so the only thing that can answer it is a service worker
// running in the browser. It reads the multipart body, parks the text in
// the cache, and redirects to the add page, which picks it up.
//
// It deliberately caches nothing else and intercepts nothing else. Every
// request that is not the share POST falls through untouched, so this
// cannot serve a stale page — which matters here, because Pages already
// caches for ten minutes and the asset stamping exists to work around it.

const CACHE = 'share-inbox';
const SHARE = new URL('share', self.registration.scope).pathname;
const SLOT = new URL('share-inbox-payload', self.registration.scope).href;
const HOME = new URL('./#/add', self.registration.scope).href;

// A collection export, not a database. Same ceiling the upload box uses.
const MAX_BYTES = 2 * 1024 * 1024;

// Android is loose about honouring the manifest's accept list, so a file
// manager can hand over anything. Only text is any use to a decklist
// parser, and a binary read as UTF-8 is worse than nothing.
const TEXT_TYPE = /^(text\/|application\/(csv|json|octet-stream)$|$)/i;
const TEXT_NAME = /\.(txt|csv|dek|md|mwdeck|cod)$/i;

self.addEventListener('install', () => self.skipWaiting());
self.addEventListener('activate', (e) => e.waitUntil(self.clients.claim()));

self.addEventListener('fetch', (event) => {
  const url = new URL(event.request.url);
  if (url.pathname !== SHARE) return;
  if (event.request.method === 'POST') { event.respondWith(receive(event.request)); return; }
  // Nothing lives at this path, so a stray GET goes home rather than 404.
  event.respondWith(Response.redirect(HOME, 303));
});

const usable = (f) => f && typeof f.text === 'function' && f.size > 0
  && f.size <= MAX_BYTES && (TEXT_TYPE.test(f.type || '') || TEXT_NAME.test(f.name || ''));

async function receive(request) {
  try {
    const form = await request.formData();

    const parts = [];
    const names = [];
    for (const file of form.getAll('file')) {
      if (!usable(file)) continue;
      const text = await file.text();
      // A NUL byte means this was not text, whatever it claimed to be.
      if (text.indexOf(String.fromCharCode(0)) !== -1) continue;
      parts.push(text);
      if (file.name) names.push(file.name);
    }

    // Sharing selected text rather than a file — a decklist off a web page.
    const typed = String(form.get('text') || '').trim();
    if (typed && !/^https?:\/\/\S+$/i.test(typed)) parts.push(typed);

    const list = parts.join('\n').trim();
    if (list) {
      const cache = await caches.open(CACHE);
      await cache.put(SLOT, new Response(JSON.stringify({ list, names, at: Date.now() }), {
        headers: { 'content-type': 'application/json' },
      }));
    }
  } catch {
    // A share that cannot be read just opens an empty add page. Throwing
    // here would show the user a browser error instead of the app.
  }
  return Response.redirect(HOME, 303);
}
