// mtg-api — Matt's Magic: The Gathering collection behind a REST API.
//
//   GET  /            what this is
//   GET  /schema      tables, views, columns, row counts
//   GET  /query       read-only SQL via query string, for GET-only callers
//   POST /query       arbitrary SQL, compact JSON out
//   POST /cards/add   a decklist in, Scryfall-enriched rows out
//   POST /cards/remove
//   POST /decks/disassemble  delete a deck, freeing its cards (admin)
//   POST /decks/list         replace a deck's list (admin)
//   POST /decks/create       new deck, from the wizard (admin)
//   POST /cards/validate     do these card names exist?
//   GET  /decks/formats      the formats the wizard offers
//   POST /prices      scryfall ids in, TCGplayer-derived prices out
//   POST /admin       password in, admin token out
//   GET  /maintenance last run of the daily job
//   POST /maintenance run it now (admin)
//   GET  /logs        the request log (admin)
//   GET  /logs/stats  headline numbers for it (admin)
//
// Reading is open. Anything that writes needs an admin token — see admin.js.

import { getSchema } from './schema.js';
import { runQuery, mayWrite, stripLiterals } from './query.js';
import { validateNames } from './validate.js';
import { addCards, removeCards } from './cards.js';
import { disassembleDeck, editDeckList, createDeck, FORMATS } from './decks.js';
import { mintToken, verifyToken, bearer } from './admin.js';
import { lookupPrices } from './prices.js';
import { runMaintenance, CRON_TASKS } from './maintenance.js';
import { newEntry, writeEntry, buildLogQuery, logStats } from './log.js';

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
    'POST /decks/disassemble': '{"slug":"...","dry_run":false} — deletes the deck, its cards go back to bulk; needs admin',
    'POST /cards/validate': '{"list":"1 Sol Ring\\n..."} or {"names":[...]} -> which names are real, with suggestions',
    'GET /decks/formats': 'the deck formats the wizard offers',
    'POST /decks/create': '{"name":"...","format":"commander","owner":"matt","commander":"...","list":"..."} — needs admin',
    'POST /decks/list': '{"slug":"...","list":"1 Sol Ring\\n...","dry_run":false} — replaces the deck list; needs admin',
    'POST /prices': '{"ids":["<scryfall id>",...]} -> {"prices":{id:{usd,foil,etched,eur,tix,tcg}}}',
    'POST /admin': '{"password":"..."} -> {"token":"...","expires_at":null}',
    'GET /logs': '?min=info&q=&event=&status=error&since=24&limit=100 — admin only',
    'GET /logs/stats': 'counts, slowest routes, retention — admin only',
    'GET /maintenance': 'what the daily job did last',
    'POST /maintenance': 'run it now — needs admin; {"only":"orphans"} or {"all":true,"wait":true}',
  },
  auth: 'Reads are open. Writes need Authorization: Bearer <token> from POST /admin.',
};

/** 401 with the reason, in the shape every other error uses. */
const denied = (reason) => json({ error: reason, admin_required: true }, 401);

/**
 * Does this statement go near the log table? Checked against the SQL with
 * string literals blanked, so a card named "Logs" cannot trip it. A false
 * positive only means someone is asked for a token they already have.
 */
const touchesLogs = (sql) => typeof sql === 'string' && /\blogs\b/i.test(stripLiterals(sql));

export default {
  /** Cloudflare Cron Trigger. Nothing has to be awake for this to run. */
  async scheduled(event, env, ctx) {
    ctx.waitUntil(runMaintenance(env.DB, {
      fetchImpl: env.SCRYFALL_FETCH || fetch,
      tasks: CRON_TASKS,
    }));
  },

  async fetch(request, env, ctx) {
    const entry = newEntry(request);
    let res;
    try {
      res = await route(request, env, ctx, entry);
    } catch (e) {
      // Nothing here is secret, so the real message is more useful than a
      // generic 500 — this is a private card database, not a public service.
      entry.level = 'error';
      entry.message = String(e.cause?.message || e.message || e);
      entry.detail = { stack: String(e.stack || '').split('\n').slice(0, 4).join(' | ') };
      res = json({ error: entry.message }, 500);
    }
    // After the response, never in front of it.
    ctx.waitUntil(writeEntry(env, entry, res.status));
    return res;
  },
};

async function route(request, env, ctx, entry) {
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

  if (path === '/' && method === 'GET') return json(INDEX);

    if (path === '/schema') {
      if (method !== 'GET') return json({ error: 'use GET' }, 405);
      return json(await getSchema(env.DB));
    }

    if (path === '/logs' || path === '/logs/stats') {
    if (method !== 'GET') return json({ error: 'use GET' }, 405);
    // The log carries IP addresses and the SQL people ran, so unlike every
    // other read here it needs a token.
    const v = await verifyToken(env, bearer(request));
    if (!v.ok) return denied(v.reason);
    entry.admin = true;

    if (path === '/logs/stats') {
      const hours = Number(url.searchParams.get('since')) || 24;
      return json(await logStats(env.DB, hours));
    }
    const q = buildLogQuery(url.searchParams);
    const [rows, total] = await Promise.all([
      env.DB.prepare(q.rows.sql).bind(...q.rows.args).all(),
      env.DB.prepare(q.total.sql).bind(...q.total.args).first(),
    ]);
    return json({
      rows: rows.results || [],
      total: total?.n ?? 0,
      limit: q.limit,
      offset: q.offset,
    });
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
      entry.admin = true;
      entry.write = true;
      const { body } = await readJson(request);
      entry.detail = { only: body?.only || null, all: Boolean(body?.all) };
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
        entry.detail = { ids: body.ids.length };
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
      entry.write = true;
      // The body is never logged. A failed unlock is worth seeing, though —
      // it is the one thing here that looks like someone trying doors.
      if (!issued) {
        entry.level = 'warn';
        entry.message = 'failed admin unlock';
        return json({ error: 'wrong password' }, 401);
      }
      entry.admin = true;
      entry.message = 'admin unlocked';
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
        if (touchesLogs(body.sql)) {
          const v = await verifyToken(env, bearer(request));
          if (!v.ok) return denied(v.reason);
          entry.admin = true;
        }
        const out = await runQuery(env.DB, body, { readOnly: true });
        entry.detail = { sql: String(body.sql || '').slice(0, 300), n: out.body?.n };
        // Without this a rejected GET logged no reason at all, which is how
        // two 400s went unexplained.
        if (out.status >= 400) entry.message = out.body?.error;
        return send(out);
      }
      if (method !== 'POST') return json({ error: 'use GET or POST' }, 405);
    const { body, error } = await readJson(request);
    if (error) return json({ error }, 400);
    const writes = await mayWrite(env.DB, body.sql);
    // Reads are open, except the log — see /logs. Gating the endpoint but
    // not the table would leave the data one SELECT away.
    if (writes || touchesLogs(body.sql)) {
      const v = await verifyToken(env, bearer(request));
      if (!v.ok) return denied(v.reason);
      entry.admin = true;
    }
    entry.write = writes;
    const out = await runQuery(env.DB, body);
    entry.detail = { sql: String(body.sql || '').slice(0, 300), n: out.body?.n, changes: out.body?.changes };
    if (out.status >= 400) entry.message = out.body?.error;
    return send(out);
    }

    if (path === '/cards/add' || path === '/cards/remove') {
      if (method !== 'POST') return json({ error: 'use POST' }, 405);
      // Gated whole, dry runs included: a preview is part of editing, and
      // one rule is easier to trust than a carve-out.
      const v = await verifyToken(env, bearer(request));
      if (!v.ok) return denied(v.reason);
      const { body, error } = await readJson(request);
      if (error) return json({ error }, 400);
      entry.admin = true;
      entry.write = true;
      const r = path === '/cards/add'
        ? await addCards(env.DB, body, env.SCRYFALL_FETCH || fetch)
        : await removeCards(env.DB, body);
      entry.detail = {
        owner: body.owner || 'matt',
        dry_run: Boolean(body.dry_run),
        lines: String(body.list || '').split('\n').filter((l) => l.trim()).length,
        applied: r.body?.applied,
        resolved: r.body?.resolved,
        failed: r.body?.failed,
        errors: r.body?.errors?.slice(0, 5),
      };
      if (r.body?.failed) entry.level = 'warn';
      return send(r);
    }

    if (path === '/cards/validate') {
      // A read: it checks names against the collection and Scryfall and
      // writes nothing, so it needs no token.
      if (method !== 'POST') return json({ error: 'use POST' }, 405);
      const { body, error } = await readJson(request);
      if (error) return json({ error }, 400);
      const r = await validateNames(env.DB, body, env.SCRYFALL_FETCH || fetch);
      entry.detail = { checked: r.body?.checked, unknown: r.body?.unknown };
      if (r.status >= 400) entry.message = r.body?.error;
      return send(r);
    }

    if (path === '/decks/formats') {
      if (method !== 'GET') return json({ error: 'use GET' }, 405);
      return json({ formats: FORMATS });
    }

    if (path === '/decks/create') {
      if (method !== 'POST') return json({ error: 'use POST' }, 405);
      const v = await verifyToken(env, bearer(request));
      if (!v.ok) return denied(v.reason);
      const { body, error } = await readJson(request);
      if (error) return json({ error }, 400);
      entry.admin = true;
      entry.write = true;
      const r = await createDeck(env.DB, body, env.SCRYFALL_FETCH || fetch);
      entry.detail = {
        slug: r.body?.slug || null,
        format: body?.format || null,
        owner: body?.owner || null,
        dry_run: Boolean(body?.dry_run),
        created: r.body?.created,
        rows: r.body?.rows,
        acquired: r.body?.acquired?.length,
      };
      if (r.status >= 400) entry.message = r.body?.error;
      return send(r);
    }

    if (path === '/decks/list') {
      if (method !== 'POST') return json({ error: 'use POST' }, 405);
      const v = await verifyToken(env, bearer(request));
      if (!v.ok) return denied(v.reason);
      const { body, error } = await readJson(request);
      if (error) return json({ error }, 400);
      entry.admin = true;
      entry.write = true;
      const r = await editDeckList(env.DB, body, env.SCRYFALL_FETCH || fetch);
      entry.detail = {
        slug: body?.slug || null,
        dry_run: Boolean(body?.dry_run),
        applied: r.body?.applied,
        rows: r.body?.rows,
        added: r.body?.added?.length,
        removed: r.body?.removed?.length,
        changed: r.body?.changed?.length,
        acquired: r.body?.acquired?.length,
      };
      if (r.status >= 400) entry.message = r.body?.error;
      return send(r);
    }

    if (path === '/decks/disassemble') {
      if (method !== 'POST') return json({ error: 'use POST' }, 405);
      // Gated whole, dry runs included, the same as add and remove.
      const v = await verifyToken(env, bearer(request));
      if (!v.ok) return denied(v.reason);
      const { body, error } = await readJson(request);
      if (error) return json({ error }, 400);
      entry.admin = true;
      entry.write = true;
      const r = await disassembleDeck(env.DB, body);
      entry.detail = {
        slug: body?.slug || null,
        dry_run: Boolean(body?.dry_run),
        applied: r.body?.applied,
        freed: r.body?.freed,
      };
      if (r.status >= 400) entry.message = r.body?.error;
      return send(r);
    }

  return json({ error: `no route for ${method} ${path}`, see: INDEX.endpoints }, 404);
}
