import { describe, it, expect } from 'vitest';
import { env, createExecutionContext, waitOnExecutionContext } from 'cloudflare:test';
import worker from '../src/index.js';
import { sql, snapshot, stubScryfall } from './helpers.js';

// The share target posts a real multipart body, so these tests build one
// byte for byte rather than going through FormData — a share that arrives
// wrong is the whole reason this endpoint exists, and half of what has to
// be asserted here (an empty body, a part that is not UTF-8, a filename
// with a quote in it) cannot be expressed any other way.

const BOUNDARY = '----mtgapisharetest';
const enc = new TextEncoder();

/** `parts` -> the bytes of a multipart/form-data body. */
function multipart(parts, boundary = BOUNDARY) {
  const chunks = [];
  for (const p of parts) {
    let head = `--${boundary}\r\nContent-Disposition: form-data; name="${p.field}"`;
    if (p.filename !== undefined) head += `; filename="${p.filename}"`;
    head += '\r\n';
    if (p.type) head += `Content-Type: ${p.type}\r\n`;
    head += '\r\n';
    chunks.push(enc.encode(head));
    chunks.push(typeof p.value === 'string' ? enc.encode(p.value) : p.value);
    chunks.push(enc.encode('\r\n'));
  }
  chunks.push(enc.encode(`--${boundary}--\r\n`));
  const total = chunks.reduce((n, c) => n + c.length, 0);
  const out = new Uint8Array(total);
  let at = 0;
  for (const c of chunks) { out.set(c, at); at += c.length; }
  return out;
}

/** Hit the Worker with whatever bytes and headers the test wants. */
async function raw(body, { contentType, method = 'POST', path = '/share', headers = {} } = {}) {
  const request = new Request(`https://mtg-api.test${path}`, {
    method,
    headers: { ...(contentType ? { 'content-type': contentType } : {}), ...headers },
    body,
  });
  const ctx = createExecutionContext();
  const res = await worker.fetch(request, { ...env, SCRYFALL_FETCH: stubScryfall() }, ctx);
  await waitOnExecutionContext(ctx);
  const text = await res.text();
  let parsed;
  try { parsed = JSON.parse(text); } catch { parsed = text; }
  return { status: res.status, body: parsed, text, headers: res.headers };
}

/** The ordinary case: a well-formed multipart share. */
const share = (parts, opts = {}) => raw(multipart(parts), {
  contentType: `multipart/form-data; boundary=${BOUNDARY}`,
  ...opts,
});

const LIST = '1 Sol Ring (C21) 263\n1 Arcane Signet (ELD) 331';

/** The latest row this request wrote to the log. */
async function lastLog() {
  const rows = await sql("SELECT * FROM logs WHERE path = '/share' ORDER BY id DESC LIMIT 1");
  return rows[0];
}

describe('POST /share — what came out of the body', () => {
  it('a text part is the list', async () => {
    const r = await share([{ field: 'text', value: LIST }]);
    expect(r.status).toBe(200);
    expect(r.body.ok).toBe(true);
    expect(r.body.list).toBe(LIST);
    expect(r.body.names).toEqual([]);
    expect(r.body.problem).toBeNull();
  });

  it('a file part is the list, and the file is named', async () => {
    const r = await share([
      { field: 'file', filename: 'deck.txt', type: 'text/plain', value: LIST },
    ]);
    expect(r.status).toBe(200);
    expect(r.body.list).toBe(LIST);
    expect(r.body.names).toEqual(['deck.txt']);
    expect(r.body.report.files[0]).toMatchObject({ name: 'deck.txt', type: 'text/plain' });
    expect(r.body.report.files[0].size).toBe(LIST.length);
  });

  it('a file under any field name still counts — senders put it anywhere', async () => {
    const r = await share([
      { field: 'attachment-0', filename: 'list.csv', type: 'application/octet-stream', value: LIST },
    ]);
    expect(r.body.list).toBe(LIST);
    expect(r.body.names).toEqual(['list.csv']);
  });

  it('a file and a text part both land, one per line', async () => {
    const r = await share([
      { field: 'text', value: '1 Lightning Bolt' },
      { field: 'file', filename: 'deck.txt', type: 'text/plain', value: LIST },
    ]);
    expect(r.body.list).toBe(`1 Lightning Bolt\n${LIST}`);
    expect(r.body.report.files).toHaveLength(1);
    expect(r.body.report.fields).toEqual([{ field: 'text', length: '1 Lightning Bolt'.length }]);
  });

  it('neither a file nor text is a 200 that says so in words', async () => {
    const r = await share([]);
    expect(r.status).toBe(200);
    expect(r.body.ok).toBe(true);
    expect(r.body.list).toBe('');
    expect(r.body.problem).toMatch(/no file and no text/);
  });
});

describe('POST /share — a shared page is not a decklist', () => {
  it('the url field never becomes the list', async () => {
    const r = await share([
      { field: 'title', value: 'Mono-Red Aggro | Moxfield' },
      { field: 'url', value: 'https://moxfield.com/decks/abc123' },
    ]);
    expect(r.status).toBe(200);
    expect(r.body.list).toBe('');
    expect(r.body.url).toBe('https://moxfield.com/decks/abc123');
    expect(r.body.title).toBe('Mono-Red Aggro | Moxfield');
    expect(r.body.problem).toMatch(/nothing that reads as a card list/);
  });

  it('a title on its own is never the list', async () => {
    const r = await share([{ field: 'title', value: 'Sol Ring' }]);
    expect(r.body.list).toBe('');
    expect(r.body.title).toBe('Sol Ring');
  });

  it('a text part that is only a URL is reported as the url, not as cards', async () => {
    const r = await share([{ field: 'text', value: '  https://scryfall.com/card/c21/263  ' }]);
    expect(r.body.list).toBe('');
    expect(r.body.url).toBe('https://scryfall.com/card/c21/263');
  });

  it('a list that merely mentions a URL still counts as a list', async () => {
    const value = `${LIST}\nsee https://scryfall.com`;
    const r = await share([{ field: 'text', value }]);
    expect(r.body.list).toBe(value);
  });

  it('url and title are null when nothing shared them', async () => {
    const r = await share([{ field: 'text', value: LIST }]);
    expect(r.body.url).toBeNull();
    expect(r.body.title).toBeNull();
  });
});

describe('POST /share — bytes that are not text', () => {
  it('a part with a NUL byte is refused and reported', async () => {
    const value = new Uint8Array([...enc.encode('1 Sol Ring'), 0, ...enc.encode('more')]);
    const r = await share([{ field: 'file', filename: 'deck.bin', type: 'text/plain', value }]);
    expect(r.status).toBe(200);
    expect(r.body.list).toBe('');
    expect(r.body.report.files[0].skipped).toBe('not text');
    expect(r.body.problem).toMatch(/deck\.bin/);
  });

  it('an image does not come back as a decklist of mojibake', async () => {
    // JPEG-ish: high bytes that are not valid UTF-8 sequences.
    const value = new Uint8Array(600).map((_, i) => 0x80 + (i % 0x40));
    const r = await share([{ field: 'file', filename: 'photo.jpg', type: 'image/jpeg', value }]);
    expect(r.body.list).toBe('');
    expect(r.body.report.files[0].skipped).toBe('not text');
  });

  it('a stray control character is stripped, the list survives', async () => {
    const value = `1 Sol Ring\n\t1 Arcane Signet`;
    const r = await share([{ field: 'file', filename: 'deck.txt', value }]);
    expect(r.body.list).toBe('1 Sol Ring\n\t1 Arcane Signet');
  });

  it('a zero-length file part is reported rather than counted', async () => {
    const r = await share([{ field: 'file', filename: 'empty.txt', type: 'text/plain', value: '' }]);
    expect(r.body.list).toBe('');
    expect(r.body.report.files[0]).toMatchObject({ name: 'empty.txt', size: 0, skipped: 'empty' });
  });

  it('an awkward filename comes back intact', async () => {
    const name = "Matt's deck (2024) — 日本語.txt";
    const r = await share([{ field: 'file', filename: name, value: LIST }]);
    expect(r.body.names).toEqual([name]);
    expect(r.body.report.files[0].name).toBe(name);
  });
});

describe('POST /share — bodies it should refuse', () => {
  it('an empty body is a 400 that says the body was empty', async () => {
    const r = await raw(new Uint8Array(0), {
      contentType: `multipart/form-data; boundary=${BOUNDARY}`,
    });
    expect(r.status).toBe(400);
    expect(r.body.ok).toBe(false);
    expect(r.body.error).toMatch(/empty/);
    expect(r.body.report.bytes).toBe(0);
  });

  it('a body that is not a form at all is a 400, not a 200 claiming ok', async () => {
    const r = await raw(JSON.stringify({ text: LIST }), { contentType: 'application/json' });
    expect(r.status).toBe(400);
    expect(r.body.ok).toBe(false);
    expect(r.body.error).toMatch(/could not be read as a form/);
    expect(r.body.report.ct).toBe('application/json');
  });

  it('no content-type at all is a 400', async () => {
    const r = await raw('1 Sol Ring');
    expect(r.status).toBe(400);
    expect(r.body.ok).toBe(false);
  });

  it('an error is a readable sentence, never a stack trace', async () => {
    const r = await raw(JSON.stringify({ text: LIST }), { contentType: 'application/json' });
    expect(r.body.error).not.toMatch(/\n\s+at /);
    expect(r.body.error).not.toMatch(/index\.js:\d/);
    expect(r.body.error.length).toBeLessThan(300);
  });

  it('a body over the cap is a 413 and is not parsed', async () => {
    const value = 'x'.repeat(9 * 1024 * 1024);
    const r = await share([{ field: 'file', filename: 'huge.txt', value }]);
    expect(r.status).toBe(413);
    expect(r.body.ok).toBe(false);
    expect(r.body.error).toMatch(/over the/);
    expect(r.body.list).toBe('');
  });

  it('a file part over the per-part cap is skipped, the rest still arrives', async () => {
    const r = await share([
      { field: 'text', value: LIST },
      { field: 'file', filename: 'huge.txt', value: 'x'.repeat(2 * 1024 * 1024 + 1) },
    ]);
    expect(r.status).toBe(200);
    expect(r.body.list).toBe(LIST);
    expect(r.body.names).toEqual([]);
    expect(r.body.report.files[0].skipped).toBe('over the size cap');
  });
});

describe('POST /share — the route itself', () => {
  it('GET is a 405', async () => {
    const r = await raw(null, { method: 'GET' });
    expect(r.status).toBe(405);
    expect(r.body.error).toBe('use POST');
  });

  it('PUT is a 405', async () => {
    const r = await raw('x', { method: 'PUT', contentType: 'text/plain' });
    expect(r.status).toBe(405);
  });

  it('a trailing slash is the same route', async () => {
    const r = await share([{ field: 'text', value: LIST }], { path: '/share/' });
    expect(r.status).toBe(200);
    expect(r.body.list).toBe(LIST);
  });

  it('takes no admin token — the share sheet has no credentials', async () => {
    const r = await share([{ field: 'text', value: LIST }]);
    expect(r.status).toBe(200);
  });

  it('sends CORS headers, because the phone calls it cross-origin', async () => {
    const r = await share([{ field: 'text', value: LIST }]);
    expect(r.headers.get('access-control-allow-origin')).toBe('*');
    expect(r.headers.get('content-type')).toMatch(/application\/json/);
  });

  it('a urlencoded share — the shape with no files — still works', async () => {
    const r = await raw('text=1+Sol+Ring&title=Notes', {
      contentType: 'application/x-www-form-urlencoded',
    });
    expect(r.status).toBe(200);
    expect(r.body.list).toBe('1 Sol Ring');
    expect(r.body.title).toBe('Notes');
  });

  it('reads the body and writes nothing to the collection', async () => {
    const before = await snapshot();
    await share([{ field: 'file', filename: 'deck.txt', value: LIST }]);
    expect(await snapshot()).toEqual(before);
  });
});

describe('POST /share — what it leaves in the log', () => {
  it('a share that worked is an info line naming the file', async () => {
    await share([{ field: 'file', filename: 'deck.txt', value: LIST }]);
    const row = await lastLog();
    expect(row.event).toBe('share');
    expect(row.level).toBe('info');
    expect(row.message).toMatch(/deck\.txt/);
    expect(JSON.parse(row.detail).bytes).toBeGreaterThan(0);
  });

  it('a share that produced nothing is a warning that says why', async () => {
    await share([{ field: 'url', value: 'https://moxfield.com/decks/abc123' }]);
    const row = await lastLog();
    expect(row.level).toBe('warn');
    expect(row.message).toMatch(/nothing that reads as a card list/);
  });

  it('the log keeps the head of the raw body, for diagnosing the next one', async () => {
    await share([{ field: 'file', filename: 'deck.txt', value: LIST }]);
    const detail = JSON.parse((await lastLog()).detail);
    expect(detail.head).toContain('deck.txt');
    expect(detail.contentType).toMatch(/multipart\/form-data/);
  });
});
