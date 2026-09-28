// Service worker, for one job: catching shares from the Android share sheet.
//
// The manifest declares a share target, and a share target that accepts
// files has to be `method: "POST"`. GitHub Pages is static and cannot
// receive a POST, so the only thing that can answer it is a service worker
// running in the browser. It reads the multipart body and hands the text
// to the add page in the URL fragment.
//
// It caches nothing and intercepts nothing else. Every request that is not
// the share POST falls through untouched.
//
// ## Logging
//
// This runs on a phone. There is no console to read from a laptop and
// nothing to attach a debugger to, and every attempt at fixing it blind
// has been wrong. So it narrates: every stage of every share goes to the
// server log, including the raw request headers and the raw body, before
// anything has interpreted them. The page hands the worker an admin token
// so it can do that itself, rather than only being heard when a page
// happens to load afterwards.
//
// ## What has already gone wrong here, so it does not go wrong again
//
//   - Take whatever arrives. Android apps label a shared file with any
//     MIME type they like and do not always send a filename, so guessing
//     from the label threw away real decklists. Whether the bytes decode
//     as text is the only test that means anything.
//   - Hand the list over in the fragment, not through Cache Storage. The
//     cache came back empty on the phone with nothing anywhere to say why.
//   - Do not trust `formData()` on its own. When it yields nothing there
//     is no way to tell an empty POST from a body it declined to parse, so
//     the raw bytes get split by hand as well and the two are compared.

const VERSION = 'sw-8';

const API = 'https://mtg-api.mattshoe81.workers.dev';
const CACHE = 'share-inbox';
const SHARE = new URL('share', self.registration.scope).pathname;
const SLOT = new URL('share-inbox-payload', self.registration.scope).href;
const BASE = new URL('./', self.registration.scope).href;

// A collection export, not a database. Same ceiling the upload box uses.
const MAX_BYTES = 2 * 1024 * 1024;

// Chrome takes far more than this in a fragment, but past it the cache is
// the saner carrier. Around 20,000 decklist lines, so it is a formality.
const MAX_FRAGMENT = 700 * 1024;

// How much of the raw body to put in the log. Enough to see the whole
// multipart preamble and the start of the file, nowhere near enough to
// post a collection export into the log table.
const RAW_IN_LOG = 4000;

// ---------------------------------------------------------------- logging

// Handed over by the page, which is the only side that has it. Lives in
// memory for as long as this worker does; a restarted worker logs nothing
// until the next page load hands it over again, and the share payload
// carries the whole report either way.
let token = null;

// One id per share, so the lines from a single share can be picked out of
// the log even when several are interleaved.
let shareId = null;

function say(message, detail, level = 'info') {
  if (!token) return Promise.resolve();
  return fetch(`${API}/logs/client`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', authorization: `Bearer ${token}` },
    body: JSON.stringify({ level, message: `[sw ${shareId || '-'}] ${message}`, detail }),
  }).catch(() => {});
}

self.addEventListener('install', () => self.skipWaiting());
self.addEventListener('activate', (e) => e.waitUntil(self.clients.claim()));

self.addEventListener('message', (event) => {
  const data = event.data;
  if (data === 'version') {
    const port = event.ports && event.ports[0];
    if (port) port.postMessage({ sw: VERSION });
    return;
  }
  if (data && data.type === 'auth') {
    token = data.token || null;
    say('token received', { v: VERSION, had: Boolean(data.token) });
  }
});

self.addEventListener('fetch', (event) => {
  const url = new URL(event.request.url);
  if (url.pathname !== SHARE) return;
  if (event.request.method === 'POST') { event.respondWith(receive(event.request, event)); return; }
  // Nothing lives at this path, so a stray GET goes home rather than 404.
  event.respondWith(Response.redirect(`${BASE}#/add`, 303));
});

// ------------------------------------------------------------- reading

/**
 * Did this decode as text?
 *
 * Bytes that are not UTF-8 come back as replacement characters, so a few
 * of those is a file with an odd character in it and a great many is a
 * JPEG. A NUL settles it on its own — no text file has one.
 */
function textual(s) {
  if (!s) return false;
  if (/\u0000/.test(s)) return false;
  const head = s.slice(0, 4096);
  const bad = (head.match(/\uFFFD/g) || []).length;
  return bad / head.length < 0.02;
}

const size = (n) => (n >= 1e6 ? `${(n / 1e6).toFixed(1)} MB` : `${Math.round(n / 1024)} KB`);

/**
 * Split a multipart body by hand.
 *
 * `formData()` is a black box: when it comes back with nothing there is no
 * way to tell a genuinely empty POST from a body it declined to parse, and
 * that difference is the whole diagnosis. This reads the same bytes
 * directly, so the two can be compared — and if it finds parts the browser
 * did not, it is also how the share gets through.
 */
function splitMultipart(raw, boundary) {
  const out = [];
  if (!raw || !boundary) return out;
  for (const chunk of raw.split(`--${boundary}`)) {
    const gap = chunk.indexOf('\r\n\r\n');
    if (gap === -1) continue;
    const head = chunk.slice(0, gap);
    const field = /name="([^"]*)"/.exec(head)?.[1];
    if (!field) continue;
    out.push({
      field,
      filename: /filename="([^"]*)"/.exec(head)?.[1] ?? null,
      type: /content-type:\s*([^\r\n]+)/i.exec(head)?.[1]?.trim() || '',
      head,
      body: chunk.slice(gap + 4).replace(/\r\n$/, ''),
    });
  }
  return out;
}

/** What turned up, in words, for when none of it was usable. */
function describe(files, fields) {
  if (!files.length && !fields.length) {
    return 'Android sent an empty share — no file, no text, nothing in the body at all. '
      + 'It dropped the file before this app saw it. The list of file types this app accepts '
      + 'is compiled into the installed app and a website update cannot change it. Dragging '
      + 'the icon off the home screen does not uninstall it either: long-press the icon, tap '
      + 'App info, and Uninstall from there, then add it to the home screen again.';
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
  } catch (e) {
    await say('cache park failed', { error: String(e && e.message) }, 'error');
    return false;
  }
}

async function receive(request, event) {
  shareId = Math.random().toString(36).slice(2, 8);
  const seen = [];
  const fields = [];
  const parts = [];
  const names = [];
  let failure = null;

  // Everything about the request, before anything interprets it. Reading
  // the body only through formData() is what made "the share arrived
  // empty" a guess about the browser rather than an observation.
  const headers = {};
  for (const [k, v] of request.headers.entries()) headers[k] = v;

  await say('share POST intercepted', {
    v: VERSION,
    url: request.url,
    method: request.method,
    mode: request.mode,
    destination: request.destination,
    headers,
    clientId: event?.clientId || null,
    resultingClientId: event?.resultingClientId || null,
  });

  let raw = '';
  try {
    raw = await request.clone().text();
  } catch (e) {
    failure = `raw: ${e && e.message}`;
  }

  const ct = headers['content-type'] || '';
  const bm = /boundary=(?:"([^"]+)"|([^;]+))/i.exec(ct);
  const boundary = (bm?.[1] || bm?.[2] || '').trim();
  const manual = splitMultipart(raw, boundary);

  await say('raw body read', {
    contentType: ct || null,
    contentLength: headers['content-length'] || null,
    boundary: boundary || null,
    rawLength: raw.length,
    rawHead: raw.slice(0, RAW_IN_LOG),
    manualParts: manual.map((p) => ({
      field: p.field, filename: p.filename, type: p.type, bytes: p.body.length, head: p.head,
    })),
    readError: failure,
  });

  try {
    const form = await request.formData();
    const entries = [];

    // Every field, not just the one the manifest named. If a sender puts
    // the file somewhere unexpected it is still the only file here.
    for (const [field, value] of form.entries()) {
      if (typeof value === 'string') {
        entries.push({ field, kind: 'string', length: value.length, sample: value.slice(0, 200) });
        const text = value.trim();
        if (!text) continue;
        fields.push(field);
        // A bare link is the page it came from, not a decklist.
        if (field !== 'url' && !/^https?:\/\/\S+$/i.test(text)) parts.push(text);
        continue;
      }

      const info = { name: value.name || '', type: value.type || '', size: value.size || 0 };
      entries.push({ field, kind: 'file', ...info });
      seen.push(info);
      if (!value.size) { await say('file part is empty', { field, ...info }, 'warn'); continue; }
      if (value.size > MAX_BYTES) { await say('file part is over the cap', { field, ...info }, 'warn'); continue; }
      let text;
      try {
        text = await value.text();
      } catch (e) {
        failure = `read: ${e && e.message}`;
        await say('file part would not read', { field, ...info, error: failure }, 'error');
        continue;
      }
      const ok = textual(text);
      await say('file part read', {
        field, ...info, chars: text.length, textual: ok, sample: text.slice(0, 300),
      });
      if (!ok) continue;
      parts.push(text);
      if (value.name) names.push(value.name);
    }

    await say('formData parsed', { entries, entryCount: entries.length });
  } catch (e) {
    failure = `form: ${e && e.message}`;
    await say('formData threw', { error: failure }, 'error');
  }

  // formData() found nothing and the raw bytes say otherwise. Use them.
  let rescued = false;
  if (!parts.length && manual.length) {
    rescued = true;
    for (const part of manual) {
      if (part.filename !== null) {
        seen.push({ name: part.filename, type: part.type, size: part.body.length, manual: true });
      } else {
        fields.push(part.field);
      }
      if (!textual(part.body)) continue;
      if (part.filename === null
        && (part.field === 'url' || /^https?:\/\/\S+$/i.test(part.body.trim()))) continue;
      parts.push(part.body);
      if (part.filename) names.push(part.filename);
    }
    await say('rescued the body by hand, formData gave nothing', {
      recovered: parts.length, chars: parts.join('\n').length,
    }, 'warn');
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
    report: {
      v: VERSION,
      id: shareId,
      files: seen,
      fields,
      chars: list.length,
      failure,
      rescued,
      req: {
        ct: ct || null,
        len: headers['content-length'] || null,
        rawLen: raw.length,
        partsSeen: manual.map((p) => ({
          field: p.field, filename: p.filename, type: p.type, bytes: p.body.length,
        })),
        head: raw.slice(0, 400),
      },
    },
  };

  const encoded = encodeURIComponent(JSON.stringify(payload));
  await say(list ? 'share resolved' : 'share produced nothing', {
    chars: list.length,
    names,
    problem,
    fragmentBytes: encoded.length,
    viaFragment: encoded.length <= MAX_FRAGMENT,
    sample: list.slice(0, 300),
  }, list ? 'info' : 'warn');

  if (encoded.length <= MAX_FRAGMENT) {
    return Response.redirect(`${BASE}#/add?share=${encoded}`, 303);
  }

  // Too big for a URL. The cache is the only thing left, and if that fails
  // as well the page still gets told why its box is empty.
  if (await park(payload)) return Response.redirect(`${BASE}#/add`, 303);
  const note = encodeURIComponent(JSON.stringify({
    at: Date.now(),
    problem: `the list came to ${size(list.length)} of text, too big to hand over.`,
    report: { v: VERSION, id: shareId, files: seen, chars: list.length, parked: false },
  }));
  return Response.redirect(`${BASE}#/add?share=${note}`, 303);
}
