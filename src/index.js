// mtg-api — Matt's Magic: The Gathering collection behind a REST API.
//
//   GET  /            what this is
//   GET  /schema      tables, views, columns, row counts
//   GET  /query       read-only SQL via query string, for GET-only callers
//   POST /query       arbitrary SQL, compact JSON out
//   POST /cards/add   a decklist in, Scryfall-enriched rows out
//   POST /cards/remove
//
// No auth. It is a card database.

import { getSchema } from './schema.js';
import { runQuery } from './query.js';
import { addCards, removeCards } from './cards.js';

const JSON_HEADERS = {
  'content-type': 'application/json; charset=utf-8',
  'access-control-allow-origin': '*',
};

const json = (body, status = 200) => new Response(JSON.stringify(body), { status, headers: JSON_HEADERS });

const text = (body, contentType, status = 200) => new Response(body, {
  status,
  headers: { 'content-type': `${contentType}; charset=utf-8`, 'access-control-allow-origin': '*' },
});

/** A handler result -> a Response. */
function send(r) {
  if (r.text !== undefined) return text(r.text, r.contentType || 'text/plain', r.status);
  return json(r.body, r.status);
}

/** `?params=[1,"x"]` -> an array, or null if it is not one. */
function safeParams(raw) {
  try {
    const v = JSON.parse(raw);
    return Array.isArray(v) ? v : null;
  } catch {
    return null;
  }
}

async function readJson(request) {
  try {
    const body = await request.json();
    if (body === null || typeof body !== 'object' || Array.isArray(body)) {
      return { error: 'body must be a JSON object' };
    }
    return { body };
  } catch {
    return { error: 'body must be valid JSON' };
  }
}

const INDEX = {
  service: 'mtg-api',
  endpoints: {
    'GET /schema': 'tables, views, columns, row counts',
    'GET /query': '?sql=SELECT+...&fmt=rows|objects|tsv&limit=5000 (read-only)',
    'POST /query': '{"sql":"SELECT ...","params":[],"fmt":"rows|objects|tsv","limit":5000}',
    'POST /cards/add': '{"owner":"matt","list":"4 Lightning Bolt (2X2) 117","dry_run":false}',
    'POST /cards/remove': '{"owner":"matt","list":"1 Sol Ring","dry_run":false}',
  },
};

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const path = url.pathname.replace(/\/+$/, '') || '/';
    const method = request.method.toUpperCase();

    if (method === 'OPTIONS') {
      return new Response(null, {
        status: 204,
        headers: {
          'access-control-allow-origin': '*',
          'access-control-allow-methods': 'GET, POST, OPTIONS',
          'access-control-allow-headers': 'content-type',
        },
      });
    }

    try {
      if (path === '/' && method === 'GET') return json(INDEX);

      if (path === '/schema') {
        if (method !== 'GET') return json({ error: 'use GET' }, 405);
        return json(await getSchema(env.DB));
      }

      if (path === '/query') {
        // GET exists for callers that can only fetch a URL — Claude desktop
        // and mobile among them, whose web fetch is GET-only. It is
        // read-only: a URL that can delete rows is one link preview away
        // from doing it. POST is unrestricted.
        if (method === 'GET') {
          const q = url.searchParams;
          const body = {
            sql: q.get('sql') || '',
            fmt: q.get('fmt') || 'rows',
            ...(q.has('limit') ? { limit: Number(q.get('limit')) } : {}),
            ...(q.has('params') ? { params: safeParams(q.get('params')) } : {}),
          };
          if (body.params === null) return json({ error: 'params must be a JSON array' }, 400);
          return send(await runQuery(env.DB, body, { readOnly: true }));
        }
        if (method !== 'POST') return json({ error: 'use GET or POST' }, 405);
        const { body, error } = await readJson(request);
        if (error) return json({ error }, 400);
        return send(await runQuery(env.DB, body));
      }

      if (path === '/cards/add' || path === '/cards/remove') {
        if (method !== 'POST') return json({ error: 'use POST' }, 405);
        const { body, error } = await readJson(request);
        if (error) return json({ error }, 400);
        const r = path === '/cards/add'
          ? await addCards(env.DB, body, env.SCRYFALL_FETCH || fetch)
          : await removeCards(env.DB, body);
        return send(r);
      }

      return json({ error: `no route for ${method} ${path}`, see: INDEX.endpoints }, 404);
    } catch (e) {
      // Nothing here is secret, so the real message is more useful than a
      // generic 500 — this is a private card database, not a public service.
      return json({ error: String(e.cause?.message || e.message || e) }, 500);
    }
  },
};
