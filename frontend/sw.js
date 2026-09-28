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
//
// Two rules learned the hard way:
//
//   - Take whatever arrives. Android apps label a shared file with any
//     MIME type they feel like, `application/vnd.ms-excel` for a CSV and
//     `application/octet-stream` for anything they are unsure of, and they
//     do not always send a filename. Guessing from the label threw away
//     real decklists. Whether the bytes decode as text is the only test
//     that means anything, so that is the only test.
//   - Never fail silently. If nothing usable came through, park a note
//     saying what did arrive. An empty box that explains itself can be
//     fixed; an empty box that says nothing cannot.

const CACHE = 'share-inbox';
const SHARE = new URL('share', self.registration.scope).pathname;
const SLOT = new URL('share-inbox-payload', self.registration.scope).href;
const HOME = new URL('./#/add', self.registration.scope).href;

// A collection export, not a database. Same ceiling the upload box uses.
const MAX_BYTES = 2 * 1024 * 1024;

self.addEventListener('install', () => self.skipWaiting());
self.addEventListener('activate', (e) => e.waitUntil(self.clients.claim()));

self.addEventListener('fetch', (event) => {
  const url = new URL(event.request.url);
  if (url.pathname !== SHARE) return;
  if (event.request.method === 'POST') { event.respondWith(receive(event.request)); return; }
  // Nothing lives at this path, so a stray GET goes home rather than 404.
  event.respondWith(Response.redirect(HOME, 303));
});

/**
 * Did this decode as text?
 *
 * Bytes that are not UTF-8 come back as replacement characters, so a few
 * of those is a file with an odd character in it and a great many is a
 * JPEG. A NUL settles it on its own — no text file has one.
 */
function textual(s) {
  if (!s || /\u0000/.test(s)) return false;
  const head = s.slice(0, 4096);
  const bad = (head.match(/\uFFFD/g) || []).length;
  return bad / head.length < 0.02;
}

const size = (n) => (n >= 1e6 ? `${(n / 1e6).toFixed(1)} MB` : `${Math.round(n / 1024)} KB`);

/** What turned up, in words, for when none of it was usable. */
function describe(files, strings) {
  if (!files.length && !strings.length) return 'the share arrived empty — no file and no text.';
  if (!files.length) return 'the share carried text but no card names in it.';
  const list = files.map((f) => `${f.name || 'unnamed'} (${f.type || 'no type'}, ${size(f.size)})`);
  return `nothing in the share read as text: ${list.join(', ')}.`;
}

async function park(payload) {
  try {
    const cache = await caches.open(CACHE);
    await cache.put(SLOT, new Response(JSON.stringify(payload), {
      headers: { 'content-type': 'application/json' },
    }));
  } catch {
    // Out of quota, or no cache at all. The add page opens empty, which is
    // the same place throwing would land, minus a browser error screen.
  }
}

async function receive(request) {
  const seen = [];
  const strings = [];
  const parts = [];
  const names = [];

  try {
    const form = await request.formData();

    // Every field, not just the one the manifest named. If a sender puts
    // the file somewhere unexpected it is still the only file here.
    for (const [field, value] of form.entries()) {
      if (typeof value === 'string') {
        const text = value.trim();
        if (!text) continue;
        strings.push(text);
        // A bare link is the page it came from, not a decklist.
        if (field !== 'url' && !/^https?:\/\/\S+$/i.test(text)) parts.push(text);
        continue;
      }

      seen.push({ name: value.name || '', type: value.type || '', size: value.size || 0 });
      if (!value.size || value.size > MAX_BYTES) continue;
      let text;
      try { text = await value.text(); } catch { continue; }
      if (!textual(text)) continue;
      parts.push(text);
      if (value.name) names.push(value.name);
    }

    const list = parts.join('\n').trim();
    // `report` rides along even on success. The page forwards it to the
    // server log, which is the only way to see from a laptop what a phone
    // actually put in the share.
    const report = { files: seen, strings: strings.length, chars: list.length };
    await park(list
      ? { list, names, at: Date.now(), report }
      : { at: Date.now(), problem: describe(seen, strings), report });
  } catch (e) {
    await park({
      at: Date.now(),
      problem: `the share could not be read: ${e && e.message}`,
      report: { files: seen, strings: strings.length, failed: true },
    });
  }

  return Response.redirect(HOME, 303);
}
