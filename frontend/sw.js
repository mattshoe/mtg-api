// Service worker, for one job: catching shares from the Android share sheet.
//
// The manifest declares a share target, and a share target that accepts
// files has to be `method: "POST"`. GitHub Pages is static and cannot
// receive a POST, so the only thing that can answer it is a service worker
// running in the browser. It reads the multipart body and hands the text
// to the add page.
//
// It caches nothing and intercepts nothing else. Every request that is not
// the share POST falls through untouched.
//
// Three things learned the hard way, in the order they bit:
//
//   - Take whatever arrives. Android apps label a shared file with any
//     MIME type they feel like, `application/vnd.ms-excel` for a CSV and
//     `application/octet-stream` for anything they are unsure of, and they
//     do not always send a filename. Guessing from the label threw away
//     real decklists. Whether the bytes decode as text is the only test
//     that means anything, so that is the only test.
//
//   - Hand the list over in the URL fragment, not through Cache Storage.
//     Parking it in a cache and redirecting looked clean and failed on the
//     phone with no trace: the redirect arrived, the cache came back
//     empty. A fragment never leaves the browser, cannot be evicted, needs
//     no quota, and cannot be read back under a key that does not match.
//     The cache is still here for a list too big for a URL, nothing else.
//
//   - Never fail silently. Every share carries a report of what actually
//     turned up, whether or not it worked, and the page shows it and logs
//     it. An empty box that explains itself can be fixed.

const VERSION = 'sw-5';

const CACHE = 'share-inbox';
const SHARE = new URL('share', self.registration.scope).pathname;
const SLOT = new URL('share-inbox-payload', self.registration.scope).href;
const BASE = new URL('./', self.registration.scope).href;

// A collection export, not a database. Same ceiling the upload box uses.
const MAX_BYTES = 2 * 1024 * 1024;

// Chrome takes far more than this in a fragment, but past it the cache is
// the saner carrier. Around 20,000 decklist lines, so it is a formality.
const MAX_FRAGMENT = 700 * 1024;

self.addEventListener('install', () => self.skipWaiting());
self.addEventListener('activate', (e) => e.waitUntil(self.clients.claim()));

// So the page can say which build of this handled a share. An older
// worker has no handler here and simply never answers, which is itself
// the answer.
self.addEventListener('message', (event) => {
  if (event.data !== 'version') return;
  const port = event.ports && event.ports[0];
  if (port) port.postMessage({ sw: VERSION });
});

self.addEventListener('fetch', (event) => {
  const url = new URL(event.request.url);
  if (url.pathname !== SHARE) return;
  if (event.request.method === 'POST') { event.respondWith(receive(event.request)); return; }
  // Nothing lives at this path, so a stray GET goes home rather than 404.
  event.respondWith(Response.redirect(`${BASE}#/add`, 303));
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

/**
 * What turned up, in words, for when none of it was usable.
 *
 * The empty case has a specific cause worth naming. Android matches a
 * share against the `accept` list in the manifest and drops the file
 * before this code runs if it does not match — and that list is frozen
 * into the installed app when it is installed, so widening it here does
 * nothing for a copy already on a phone. An empty POST with no file and
 * no text is the signature of exactly that, and the only cure is to
 * remove the app from the home screen and add it again.
 */
function describe(files, fields) {
  if (!files.length && !fields.length) {
    return 'Android sent the share with nothing in it — no file, no text. That means it '
      + 'dropped the file before this app saw it, which happens when the installed copy of '
      + 'the app was installed with a narrower list of accepted file types than it has now. '
      + 'Remove it from the home screen, add it again, and share once more.';
  }
  if (!files.length) return 'the share carried text, but nothing that reads as a card list.';
  const list = files.map((f) => `${f.name || 'unnamed'} (${f.type || 'no type'}, ${size(f.size)})`);
  return `nothing in the share read as text: ${list.join(', ')}.`;
}

/** The overflow carrier, for a list too long to put in a URL. */
async function park(payload) {
  try {
    const cache = await caches.open(CACHE);
    await cache.put(SLOT, new Response(JSON.stringify(payload), {
      headers: { 'content-type': 'application/json' },
    }));
    return true;
  } catch {
    return false;
  }
}

async function receive(request) {
  const seen = [];
  const fields = [];
  const parts = [];
  const names = [];
  let failure = null;

  try {
    const form = await request.formData();

    // Every field, not just the one the manifest named. If a sender puts
    // the file somewhere unexpected it is still the only file here.
    for (const [field, value] of form.entries()) {
      if (typeof value === 'string') {
        const text = value.trim();
        if (!text) continue;
        fields.push(field);
        // A bare link is the page it came from, not a decklist.
        if (field !== 'url' && !/^https?:\/\/\S+$/i.test(text)) parts.push(text);
        continue;
      }

      seen.push({ name: value.name || '', type: value.type || '', size: value.size || 0 });
      if (!value.size || value.size > MAX_BYTES) continue;
      let text;
      try { text = await value.text(); } catch (e) { failure = `read: ${e && e.message}`; continue; }
      if (!textual(text)) continue;
      parts.push(text);
      if (value.name) names.push(value.name);
    }
  } catch (e) {
    failure = `form: ${e && e.message}`;
  }

  const list = parts.join('\n').trim();
  const problem = list
    ? null
    : (failure ? `the share could not be read (${failure}).` : describe(seen, fields));
  const payload = {
    list,
    names,
    at: Date.now(),
    problem,
    report: { v: VERSION, files: seen, fields, chars: list.length, failure },
  };

  const encoded = encodeURIComponent(JSON.stringify(payload));
  if (encoded.length <= MAX_FRAGMENT) {
    return Response.redirect(`${BASE}#/add?share=${encoded}`, 303);
  }

  // Too big for a URL. The cache is the only thing left, and if that fails
  // as well the page still gets told why its box is empty.
  if (await park(payload)) return Response.redirect(`${BASE}#/add`, 303);
  const note = encodeURIComponent(JSON.stringify({
    at: Date.now(),
    problem: `the list came to ${size(list.length)} of text, too big to hand over.`,
    report: { v: VERSION, files: seen, chars: list.length, parked: false },
  }));
  return Response.redirect(`${BASE}#/add?share=${note}`, 303);
}
