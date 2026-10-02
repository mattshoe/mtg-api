// The outside edge of the Worker: what a caller sees when it asks for
// something that is not there, asks the wrong way, sends a body that is not
// a body, or arrives while D1 is busy.
//
// None of this is about cards. It is about the two or three lines of
// routing every other endpoint sits behind, which is exactly the code
// nobody notices is wrong until a browser silently drops a response or a
// client retries a permanent failure forever.

import { describe, it, expect } from 'vitest';
import { env, createExecutionContext, waitOnExecutionContext } from 'cloudflare:test';
import worker from '../src/index.js';
import source from '../src/index.js?raw';
import { call, get, postAnon, adminToken } from './helpers.js';
import { overloaded } from '../src/index.js';

/** The methods each route actually answers to. */
const ROUTES = {
  '/': ['GET'],
  '/schema': ['GET'],
  '/query': ['GET', 'POST'],
  '/maintenance': ['GET', 'POST'],
  '/decks/formats': ['GET'],
  '/logs': ['GET'],
  '/logs/stats': ['GET'],
  '/logs/client': ['POST'],
  '/prices': ['POST'],
  '/admin': ['POST'],
  '/cards/add': ['POST'],
  '/cards/remove': ['POST'],
  '/cards/validate': ['POST'],
  '/decks/create': ['POST'],
  '/decks/list': ['POST'],
  '/decks/disassemble': ['POST'],
  // /share belongs to the share-target work and is left to it.
};

const OTHER = (allowed) => ['DELETE', 'PUT', 'PATCH'].find((m) => !allowed.includes(m));

/** Nothing in an error body may look like a stack frame or a path on disk. */
function leaksNothing(text, where) {
  expect(text, `${where} leaked a stack frame`).not.toMatch(/\bat \S+ \(/);
  expect(text, `${where} leaked a source location`).not.toMatch(/\.js:\d+/);
  expect(text, `${where} leaked a path on disk`).not.toMatch(/\/Users\/|\/src\/|node_modules/);
  expect(text, `${where} leaked a stack`).not.toMatch(/"stack"/);
}

/**
 * A D1 that fails the way the real one fails. Every statement throws
 * `message`; batches still go to the real database so the fixture and the
 * log can look after themselves.
 */
function brokenDb(message) {
  const fail = () => { throw new Error(message); };
  const stmt = { bind: () => stmt, all: fail, first: fail, run: fail, raw: fail };
  return {
    prepare: () => stmt,
    batch: (...a) => env.DB.batch(...a),
    exec: (...a) => env.DB.exec(...a),
  };
}

/** Call the Worker against a substitute database. */
async function callWith(db, path, init = {}) {
  const request = new Request(`https://mtg-api.test${path}`, {
    ...init,
    body: init.body !== undefined && typeof init.body !== 'string'
      ? JSON.stringify(init.body)
      : init.body,
    headers: { 'content-type': 'application/json', ...(init.headers || {}) },
  });
  const ctx = createExecutionContext();
  const res = await worker.fetch(request, { ...env, DB: db }, ctx);
  await waitOnExecutionContext(ctx);
  const text = await res.text();
  let body;
  try { body = JSON.parse(text); } catch { body = text; }
  return { status: res.status, body, text, headers: res.headers };
}

describe('the CORS preflight', () => {
  it('answers a known path with 204 and no body', async () => {
    const r = await call('/query', { method: 'OPTIONS' });
    expect(r.status).toBe(204);
    expect(r.text).toBe('');
  });

  it('names the methods and the headers the API actually uses', async () => {
    const r = await call('/cards/add', { method: 'OPTIONS' });
    expect(r.headers.get('access-control-allow-origin')).toBe('*');
    const methods = (r.headers.get('access-control-allow-methods') || '').split(/,\s*/);
    expect(methods).toEqual(expect.arrayContaining(['GET', 'POST', 'OPTIONS']));
    const headers = (r.headers.get('access-control-allow-headers') || '').toLowerCase();
    for (const h of ['content-type', 'authorization', 'idempotency-key']) {
      expect(headers, `preflight does not allow ${h}`).toContain(h);
    }
  });

  it('lets the browser cache the preflight instead of paying for one per write', async () => {
    const r = await call('/query', { method: 'OPTIONS' });
    const age = Number(r.headers.get('access-control-max-age'));
    expect(age).toBeGreaterThan(0);
  });

  it('never asks a preflight for a token — it cannot carry one', async () => {
    // A browser strips Authorization from the preflight, so gating it would
    // make every admin write impossible from the frontend.
    const r = await call('/decks/disassemble', { method: 'OPTIONS' });
    expect(r.status).toBe(204);
  });

  it('answers for a path that does not exist, and the real request still 404s', async () => {
    // A preflight is a question about the origin, not about the resource.
    expect((await call('/nope', { method: 'OPTIONS' })).status).toBe(204);
    expect((await get('/nope')).status).toBe(404);
  });

  it('puts the origin header on every answer, errors included', async () => {
    const cases = [
      await get('/'),
      await get('/nope'),
      await call('/schema', { method: 'DELETE' }),
      await postAnon('/prices', {}),
      await get('/logs'),
      await call('/query', { method: 'POST', body: '{' }),
    ];
    for (const r of cases) {
      expect(r.headers.get('access-control-allow-origin'), `${r.status} has no CORS header`).toBe('*');
    }
    // Five different failure kinds, so this is not one branch passing for all.
    expect(cases.map((r) => r.status)).toEqual([200, 404, 405, 400, 401, 400]);
  });

  it('exposes retry-after, which is not on the browser safelist', async () => {
    const r = await get('/');
    expect((r.headers.get('access-control-expose-headers') || '').toLowerCase())
      .toContain('retry-after');
  });
});

describe('a path that does not exist', () => {
  it('is a 404 that says what was asked for', async () => {
    const r = await get('/nope');
    expect(r.status).toBe(404);
    expect(r.body.error).toBe('no route for GET /nope');
    expect(typeof r.body.see).toBe('object');
    leaksNothing(r.text, 'the 404');
  });

  it('reports the normalised path, not the one with the slashes', async () => {
    const r = await get('/nope///');
    expect(r.body.error).toBe('no route for GET /nope');
  });

  it('is a 404 for an unknown method too, not a 405', async () => {
    // There is no method that would have worked, so there is nothing to put
    // in an Allow header and nothing to tell the caller to try.
    const r = await call('/nope', { method: 'DELETE' });
    expect(r.status).toBe(404);
    expect(r.body.error).toBe('no route for DELETE /nope');
  });

  it('lists only endpoints that are really there', async () => {
    const listing = (await get('/nope')).body.see;
    for (const key of Object.keys(listing)) {
      const [method, path] = key.split(' ');
      const r = method === 'GET' ? await get(path) : await postAnon(path, {});
      expect(r.status, `${key} is advertised but answers ${r.status}`).not.toBe(404);
      expect(r.status, `${key} is advertised with the wrong method`).not.toBe(405);
    }
  });

  it('lists every route the router knows about', async () => {
    const known = [...source.matchAll(/path === '([^']+)'/g)].map((m) => m[1]);
    expect(known.length).toBeGreaterThan(10);
    const listed = Object.keys((await get('/nope')).body.see).map((k) => k.split(' ')[1]);
    for (const path of new Set(known)) {
      // '/' is the listing itself.
      if (path === '/') continue;
      expect(listed, `${path} exists but the 404 does not mention it`).toContain(path);
    }
  });
});

describe('slashes', () => {
  it('a trailing slash is the same route', async () => {
    const plain = await get('/query?sql=SELECT%201%20AS%20n');
    const slashed = await get('/query/?sql=SELECT%201%20AS%20n');
    expect(slashed.status).toBe(200);
    expect(slashed.text).toBe(plain.text);
  });

  it('so is a pile of them', async () => {
    expect((await get('/schema///')).status).toBe(200);
  });

  it('a doubled slash is the same route', async () => {
    expect((await get('//schema')).status).toBe(200);
    expect((await get('/decks//formats')).status).toBe(200);
  });

  it('the bare root survives being written as //', async () => {
    expect((await get('//')).body.service).toBe('mtg-api');
    expect((await get('///')).body.service).toBe('mtg-api');
  });

  it('a slashed write is still protected against being replayed', async () => {
    // The idempotency wrapper matches the path against its own list. If it
    // normalises differently from the router, '/cards/add/' routes as a
    // write and is guarded as nothing.
    const token = await adminToken();
    const init = {
      method: 'POST',
      token,
      body: { list: '1 Lightning Bolt (2X2) 117' },
      headers: { 'idempotency-key': 'envelope-slash-test' },
    };
    const first = await call('/cards/add/', init);
    expect(first.status).toBe(200);
    const second = await call('/cards/add/', init);
    expect(second.status).toBe(200);
    expect(second.body).toEqual(first.body);
  });
});

describe('the wrong method on a route that exists', () => {
  it('is a 405 that says which methods would have worked', async () => {
    for (const [path, allowed] of Object.entries(ROUTES)) {
      const r = await call(path, { method: OTHER(allowed) });
      expect(r.status, `${path} answered ${r.status}`).toBe(405);
      expect(r.body.error).toMatch(/^use /);
      const allow = (r.headers.get('allow') || '').split(/,\s*/).filter(Boolean);
      expect(allow.sort(), `${path} Allow header`).toEqual([...allowed, 'OPTIONS'].sort());
    }
  });

  it('405, not 404 — the path is real and the caller only picked wrong', async () => {
    expect((await postAnon('/schema', {})).status).toBe(405);
    expect((await get('/cards/add')).status).toBe(405);
    expect((await call('/', { method: 'POST' })).status).toBe(405);
  });

  it('comes before the token demand, so a wrong method is never a 401', async () => {
    // GET /cards/add answered 401 once, which sent people looking for a
    // password problem on a request that had no password problem.
    for (const path of ['/cards/add', '/cards/remove', '/decks/create', '/decks/list', '/logs/client']) {
      expect((await get(path)).status, path).toBe(405);
    }
    expect((await postAnon('/logs', {})).status).toBe(405);
  });
});

describe('a body that is not a JSON object', () => {
  const bad = [
    ['truncated JSON', '{"sql":', /valid JSON/],
    ['something that is not JSON at all', 'SELECT 1', /valid JSON/],
    ['an empty body', '', /valid JSON/],
    ['an array', '[1,2]', /JSON object/],
    ['a bare null', 'null', /JSON object/],
    ['a bare string', '"hello"', /JSON object/],
  ];

  for (const [what, body, expected] of bad) {
    it(`refuses ${what} with 400`, async () => {
      const r = await call('/query', { method: 'POST', body });
      expect(r.status).toBe(400);
      expect(r.body.error).toMatch(expected);
      leaksNothing(r.text, what);
    });
  }

  it('refuses a missing body rather than throwing', async () => {
    const r = await call('/query', { method: 'POST' });
    expect(r.status).toBe(400);
    expect(r.body.error).toMatch(/valid JSON/);
  });

  it('does not care what the content-type claims', async () => {
    const r = await call('/prices', {
      method: 'POST',
      body: 'ids=1,2,3',
      headers: { 'content-type': 'application/x-www-form-urlencoded' },
    });
    expect(r.status).toBe(400);
    expect(r.body.error).toMatch(/valid JSON/);
  });
});

describe('a busy database is not a bug', () => {
  const BUSY = 'D1 DB is overloaded. Requests queued for too long.';
  const BUG = "Cannot read properties of null (reading 'results')";

  it('knows the difference', () => {
    expect(overloaded(BUSY)).toBe(true);
    expect(overloaded('Network connection lost.')).toBe(true);
    expect(overloaded(BUG)).toBe(false);
    expect(overloaded('no such column: t.qty')).toBe(false);
    expect(overloaded(undefined)).toBe(false);
  });

  it('answers 503 with a retry hint when D1 says it is busy', async () => {
    const r = await callWith(brokenDb(BUSY), '/maintenance', { method: 'GET' });
    expect(r.status).toBe(503);
    const wait = Number(r.headers.get('retry-after'));
    expect(Number.isInteger(wait)).toBe(true);
    expect(wait).toBeGreaterThan(0);
    expect(wait).toBeLessThanOrEqual(120);
    expect(r.body.error).toMatch(/overload/i);
  });

  it('answers 500 with no retry hint for a genuine bug', async () => {
    const r = await callWith(brokenDb(BUG), '/maintenance', { method: 'GET' });
    expect(r.status).toBe(500);
    expect(r.headers.get('retry-after')).toBe(null);
  });

  it('carries the retry hint on a busy statement too, not only a thrown one', async () => {
    // /query turns an overloaded D1 into a 503 of its own. Without the hint
    // there, half the 503s this API can emit tell the client nothing.
    const r = await callWith(brokenDb(BUSY), '/query', {
      method: 'POST',
      body: { sql: 'SELECT 1 AS n' },
    });
    expect(r.status).toBe(503);
    expect(Number(r.headers.get('retry-after'))).toBeGreaterThan(0);
  });

  it('keeps the CORS header on a 500 and a 503', async () => {
    for (const message of [BUSY, BUG]) {
      const r = await callWith(brokenDb(message), '/maintenance', { method: 'GET' });
      expect(r.headers.get('access-control-allow-origin'), message).toBe('*');
    }
  });

  it('tells the caller what broke without handing over the stack', async () => {
    for (const message of [BUSY, BUG]) {
      const r = await callWith(brokenDb(message), '/maintenance', { method: 'GET' });
      expect(Object.keys(r.body)).toEqual(['error']);
      expect(r.body.error).toBe(message);
      leaksNothing(r.text, `the ${r.status}`);
    }
  });
});
