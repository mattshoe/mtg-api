// mtg-api — Matt's Magic: The Gathering collection behind a REST API.
//
//   GET  /            what this is
//   GET  /schema      tables, views, columns, row counts
//   GET  /query       read-only SQL via query string, for GET-only callers
//   POST /query       arbitrary SQL, compact JSON out
//   POST /cards/add   a decklist in, Scryfall-enriched rows out
//   POST /cards/remove
//   POST /prices      scryfall ids in, TCGplayer-derived prices out
//   POST /admin       password in, admin token out
//   GET  /maintenance last run of the daily job
//   POST /maintenance run it now (admin)
//
// Reading is open. Anything that writes needs an admin token — see admin.js.

import { getSchema } from './schema.js';
import { runQuery, isReadOnly } from './query.js';
import { addCards, removeCards } from './cards.js';
import { mintToken, verifyToken, bearer } from './admin.js';
import { lookupPrices } from './prices.js';
import { runMaintenance, CRON_TASKS } from './maintenance.js';

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
    'POST /prices': '{"ids":["<scryfall id>",...]} -> {"prices":{id:{usd,foil,etched,eur,tix,tcg}}}',
    'POST /admin': '{"password":"..."} -> {"token":"...","expires_at":<unix>}',
    'GET /maintenance': 'what the daily job did last',
    'POST /maintenance': 'run it now — needs admin; {"only":"orphans"} or {"all":true,"wait":true}',
  },
  auth: 'Reads are open. Writes need Authorization: Bearer <token> from POST /admin.',
};

/** 401 with the reason, in the shape every other error uses. */
const denied = (reason) => json({ error: reason, admin_required: true }, 401);

export default {
  /** Cloudflare Cron Trigger. Nothing has to be awake for this to run. */
  async scheduled(event, env, ctx) {
    ctx.waitUntil(runMaintenance(env.DB, {
      fetchImpl: env.SCRYFALL_FETCH || fetch,
      tasks: CRON_TASKS,
    }));
  },

  async fetch(request, env, ctx) {
    const url = new URL(request.url);
    const path = url.pathname.replace(/\/+$/, '') || '/';
    const method = request.method.toUpperCase();

    if (method === 'OPTIONS') {
      return new Response(null, {
        status: 204,
        headers: {
          'access-control-allow-origin': '*',
          'access-control-allow-methods': 'GET, POST, OPTIONS',
          'access-control-allow-headers': 'content-type, authorization',
        },
      });
    }

    try {
      if (path === '/' && method === 'GET') return json(INDEX);

      if (path === '/schema') {
        if (method !== 'GET') return json({ error: 'use GET' }, 405);
        return json(await getSchema(env.DB));
      }

      if (path === '/maintenance') {
        if (method === 'GET') {
          const runs = await env.DB.prepare(
            'SELECT ran_at, task, ok, detail, ms FROM maintenance_log ORDER BY id DESC LIMIT 25',
          ).all();
          return json({ runs: runs.results || [] });
        }
        if (method !== 'POST') return json({ error: 'use GET or POST' }, 405);
        // It writes, so it is gated like any other write.
        const v = await verifyToken(env, bearer(request));
        if (!v.ok) return denied(v.reason);
        const { body } = await readJson(request);
        const job = runMaintenance(env.DB, {
          fetchImpl: env.SCRYFALL_FETCH || fetch,
          only: body?.only,
          tasks: body?.all ? undefined : CRON_TASKS,
        });
        // Re-pricing the whole collection takes about a minute of mostly
        // waiting on Scryfall. Returning immediately and letting it finish
        // in the background is the honest shape; GET /maintenance says how
        // it went. `wait: true` blocks, for tests and for a quick task.
        if (body?.wait) {
          const out = await job;
          return json(out, out.ok ? 200 : 500);
        }
        ctx.waitUntil(job);
        return json({ started: true, only: body?.only || 'all', check: 'GET /maintenance' }, 202);
      }

      if (path === '/prices') {
        // A read, so no admin token. Prices are public information and
        // this only ever touches the cache and Scryfall, never D1.
        if (method !== 'POST') return json({ error: 'use POST' }, 405);
        const { body, error } = await readJson(request);
        if (error) return json({ error }, 400);
        if (!Array.isArray(body.ids)) return json({ error: 'ids must be an array' }, 400);
        try {
          const out = await lookupPrices(body.ids, {
            fetchImpl: env.SCRYFALL_FETCH || fetch,
            cache: env.DISABLE_PRICE_CACHE ? null : caches.default,
            waitUntil: (pr) => ctx.waitUntil(pr),
          });
          return json(out);
        } catch (e) {
          return json({ error: String(e.message || e) }, e.status || 502);
        }
      }

      if (path === '/admin') {
        if (method !== 'POST') return json({ error: 'use POST' }, 405);
        const { body, error } = await readJson(request);
        if (error) return json({ error }, 400);
        const issued = await mintToken(env, body.password);
        // Same shape and timing whether or not the password was close.
        if (!issued) return json({ error: 'wrong password' }, 401);
        return json({ ok: true, ...issued });
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
        // A SELECT needs nothing. Anything that could change a row does.
        if (typeof body.sql === 'string' && !isReadOnly(body.sql)) {
          const v = await verifyToken(env, bearer(request));
          if (!v.ok) return denied(v.reason);
        }
        return send(await runQuery(env.DB, body));
      }

      if (path === '/cards/add' || path === '/cards/remove') {
        if (method !== 'POST') return json({ error: 'use POST' }, 405);
        // Gated whole, dry runs included: a preview is part of editing, and
        // one rule is easier to trust than a carve-out.
        const v = await verifyToken(env, bearer(request));
        if (!v.ok) return denied(v.reason);
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
