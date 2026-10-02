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
import { runQuery, mayWrite, stripLiterals, isOverloaded } from './query.js';
import { validateNames } from './validate.js';
import { addCards, removeCards } from './cards.js';
import { disassembleDeck, editDeckList, createDeck, FORMATS } from './decks.js';
import { mintToken, verifyToken, bearer } from './admin.js';
import { lookupPrices } from './prices.js';
import { runMaintenance, CRON_TASKS } from './maintenance.js';
import { newEntry, writeEntry, buildLogQuery, logStats } from './log.js';
import { keyFrom, claim, remember, release } from './idempotency.js';

/**
 * On every response, the error ones included — a CORS failure is invisible
 * in the browser's network tab and the frontend only sees "failed to fetch".
 *
 * `expose-headers` is there because `retry-after` is not on the browser's
 * safelist: without it a 503 arrives at the client with the one piece of
 * information it needs to back off correctly stripped out.
 */
const CORS = {
  'access-control-allow-origin': '*',
  'access-control-expose-headers': 'retry-after',
};

const JSON_HEADERS = {
  'content-type': 'application/json; charset=utf-8',
  ...CORS,
};

const json = (body, status = 200, extra) => new Response(JSON.stringify(body), {
  status,
  headers: extra ? { ...JSON_HEADERS, ...extra } : JSON_HEADERS,
});

const text = (body, contentType, status = 200, extra) => new Response(body, {
  status,
  headers: { 'content-type': `${contentType}; charset=utf-8`, ...CORS, ...extra },
});

/** How long to tell a client to wait before trying a busy database again. */
const RETRY_AFTER = { 'retry-after': '1' };

/**
 * A handler result -> a Response.
 *
 * A 503 from a handler means the same thing as a 503 from the catch in
 * `fetch` — D1 is busy — so it carries the same retry hint. Sending it from
 * one path and not the other left half the API's 503s unhintable.
 */
function send(r) {
  const extra = r.status === 503 ? RETRY_AFTER : undefined;
  if (r.text !== undefined) return text(r.text, r.contentType || 'text/plain', r.status, extra);
  return json(r.body, r.status, extra);
}

/** 405 with the Allow header, because "use GET" is only half an answer. */
const notAllowed = (...methods) => json(
  { error: `use ${methods.join(' or ')}` },
  405,
  { allow: [...methods, 'OPTIONS'].join(', ') },
);

/**
 * The path a request routes as.
 *
 * `/query`, `/query/` and `//query` are one endpoint — a caller that joined
 * a base URL and a path and got a double slash is not asking for something
 * else. Both the router and the idempotency wrapper normalise through here,
 * so a mutation cannot route as a write but be guarded as nothing.
 */
const routePath = (pathname) => pathname.replace(/\/{2,}/g, '/').replace(/\/+$/, '') || '/';

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
    'POST /share': 'a share-target body in, what the server actually received back out',
    'POST /prices': '{"ids":["<scryfall id>",...]} -> {"prices":{id:{usd,foil,etched,eur,tix,tcg}}}',
    'POST /admin': '{"password":"..."} -> {"token":"...","expires_at":null}',
    'GET /logs': '?min=info&q=&event=&status=error&since=24&limit=100 — admin only',
    'POST /logs/client': '{"level":"info","message":"...","detail":{...}} — admin only',
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
      res = await guarded(request, env, ctx, entry);
    } catch (e) {
      // Nothing here is secret, so the real message is more useful than a
      // generic 500 — this is a private card database, not a public service.
      entry.level = 'error';
      entry.message = String(e.cause?.message || e.message || e);
      entry.detail = { stack: String(e.stack || '').split('\n').slice(0, 4).join(' | ') };
      // D1 saying it is busy is not the request's fault and not a
      // permanent answer. As a 500 the client retried it; as a 400 it
      // reached the screen. 503 is what it is, and what the client's
      // backoff is for.
      res = overloaded(entry.message)
        ? json({ error: entry.message }, 503, RETRY_AFTER)
        : json({ error: entry.message }, 500);
    }
    // After the response, never in front of it.
    ctx.waitUntil(writeEntry(env, entry, res.status));
    return res;
  },
};

/**
 * What a query did, for the log.
 *
 * The statement, how long SQLite itself took, how many rows came back,
 * and the error when there was one — the four things needed to answer
 * "why was the app slow at 8pm" without guessing. `entry.ms` is the
 * whole request; `db_ms` is the database's share of it.
 */
function queryDetail(body, out) {
  const d = {
    sql: String(body.sql || '').replace(/\s+/g, ' ').slice(0, 400),
    params: Array.isArray(body.params) ? body.params.length : 0,
    db_ms: out.ms ?? null,
    n: out.body?.n ?? null,
  };
  if (out.body?.changes !== undefined) d.changes = out.body.changes;
  if (out.body?.truncated) d.truncated = out.body.truncated;
  if (out.failed) d.error = String(out.failed).slice(0, 300);
  return d;
}

/** Worth a warning rather than a debug line. */
const SLOW_MS = 1000;
const slow = (out) => (out.ms ?? 0) >= SLOW_MS;

/**
 * D1 under load, rather than anything wrong with the request. The same
 * judgement a failing statement gets inside /query — two copies of this
 * regex is one copy too many, and they had already started to differ in
 * nothing but comments.
 */
export const overloaded = isOverloaded;

/** Paths where sending the same request twice must not do the thing twice. */
const MUTATIONS = new Set([
  '/cards/add', '/cards/remove',
  '/decks/list', '/decks/create', '/decks/disassemble',
]);

/**
 * The idempotency wrapper.
 *
 * A retried write is only safe if the second attempt can be recognised as
 * the same write. The client sends one key per logical mutation and keeps
 * it across its own retries; the first attempt claims it and stores its
 * reply, and anything after that gets the reply back without running.
 *
 * No key means no protection, which is the old behaviour — it is opt-in by
 * the caller rather than something the server can invent.
 */
async function guarded(request, env, ctx, entry) {
  const url = new URL(request.url);
  const path = routePath(url.pathname);
  const key = request.method.toUpperCase() === 'POST' && MUTATIONS.has(path)
    ? keyFrom(request)
    : null;

  if (!key) return route(request, env, ctx, entry);

  const held = await claim(env.DB, key, path);
  if (held.conflict) {
    entry.message = held.conflict;
    return json({ error: held.conflict }, 409);
  }
  if (held.inFlight) {
    entry.message = 'that write is already running';
    return json({ error: 'that write is already in progress' }, 409, { 'retry-after': '1' });
  }
  if (held.replay) {
    // Not counted as a write: nothing happened this time.
    entry.detail = { ...(entry.detail || {}), idempotent_replay: true };
    entry.message = 'replayed an earlier answer';
    return json(held.replay.body, held.replay.status);
  }

  let res;
  try {
    res = await route(request, env, ctx, entry);
  } catch (e) {
    await release(env.DB, key);
    throw e;
  }

  // Only an answer worth repeating is kept. A 5xx means try again for
  // real, and holding it would turn one bad minute into a permanent one.
  if (res.status < 500) {
    const copy = res.clone();
    ctx.waitUntil((async () => {
      let body = null;
      try { body = await copy.json(); } catch { body = null; }
      await remember(env.DB, key, res.status, body);
    })());
  } else {
    await release(env.DB, key);
  }
  return res;
}

async function route(request, env, ctx, entry) {
  const url = new URL(request.url);
  const path = routePath(url.pathname);
  const method = request.method.toUpperCase();

  if (method === 'OPTIONS') {
    return new Response(null, {
      status: 204,
      headers: {
        ...CORS,
        'access-control-allow-methods': 'GET, POST, OPTIONS',
        'access-control-allow-headers': 'content-type, authorization, idempotency-key',
        // Without this the browser pays for a preflight before every single
        // write. A day is what the headers above are worth: they never vary.
        'access-control-max-age': '86400',
      },
    });
  }

  if (path === '/') {
    if (method !== 'GET') return notAllowed('GET');
    return json(INDEX);
  }

    if (path === '/schema') {
      if (method !== 'GET') return notAllowed('GET');
      return json(await getSchema(env.DB));
    }

  // The share body, parsed somewhere other than the phone.
  //
  // Chromium has a long-standing problem where reading a navigation POST
  // inside a service worker — formData(), text(), any of it — can come
  // back empty even though the request carried a file. Both reads on the
  // phone go through the same request object, so both would be empty for
  // the same reason and agreeing with each other proves nothing. This is
  // the control: the worker forwards the body here untouched and a real
  // server says what is actually in it.
  if (path === '/share') {
    if (method !== 'POST') return json({ error: 'use POST' }, 405);
    const ct = request.headers.get('content-type') || '';
    const raw = await request.arrayBuffer();

    const files = [];
    const fields = [];
    const parts = [];
    const names = [];
    let err = null;

    try {
      const form = await new Response(raw, { headers: { 'content-type': ct } }).formData();
      for (const [field, value] of form.entries()) {
        if (typeof value === 'string') {
          fields.push({ field, length: value.length });
          const t = value.trim();
          if (t && field !== 'url' && !/^https?:\/\/\S+$/i.test(t)) parts.push(t);
          continue;
        }
        files.push({ field, name: value.name || '', type: value.type || '', size: value.size || 0 });
        const text = await value.text();
        if (text && !text.includes('\u0000')) { parts.push(text); if (value.name) names.push(value.name); }
      }
    } catch (e) {
      err = String(e.message || e);
    }

    const list = parts.join('\n').trim();
    const decoder = new TextDecoder();
    entry.event = 'share';
    entry.level = list ? 'info' : 'warn';
    entry.message = list
      ? `share: ${list.length} chars from ${names.join(', ') || 'text'}`
      : 'share: the body reached the server with nothing usable in it';
    entry.detail = {
      contentType: ct,
      bytes: raw.byteLength,
      files,
      fields,
      chars: list.length,
      error: err,
      head: decoder.decode(raw.slice(0, 1500)),
    };
    return json({ ok: true, list, names, report: { bytes: raw.byteLength, ct, files, fields, error: err } });
  }

  // The browser reporting something the server cannot see for itself.
  // The share target runs entirely in a service worker on the phone, so
  // without this there is no way to find out what Android actually handed
  // over when a share comes out wrong.
  if (path === '/logs/client') {
    if (method !== 'POST') return notAllowed('POST');
    const v = await verifyToken(env, bearer(request));
    if (!v.ok) return denied(v.reason);
    entry.admin = true;
    const { body } = await readJson(request);
    entry.level = ['debug', 'info', 'warn', 'error'].includes(body?.level) ? body.level : 'info';
    entry.message = String(body?.message || 'client report').slice(0, 300);
    entry.detail = body?.detail ?? null;
    return json({ ok: true });
  }

    if (path === '/logs' || path === '/logs/stats') {
    if (method !== 'GET') return notAllowed('GET');
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
      if (method !== 'POST') return notAllowed('GET', 'POST');
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
      if (method !== 'POST') return notAllowed('POST');
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
      if (method !== 'POST') return notAllowed('POST');
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
        entry.detail = queryDetail(body, out);
        if (out.status >= 500) entry.level = 'error';
        else if (slow(out)) entry.level = 'warn';
        // Without this a rejected GET logged no reason at all, which is how
        // two 400s went unexplained.
        if (out.status >= 400) entry.message = out.body?.error;
        return send(out);
      }
      if (method !== 'POST') return notAllowed('GET', 'POST');
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
    entry.detail = queryDetail(body, out);
    if (out.status >= 500) entry.level = 'error';
    else if (slow(out)) entry.level = 'warn';
    if (out.status >= 400) entry.message = out.body?.error;
    return send(out);
    }

    if (path === '/cards/add' || path === '/cards/remove') {
      if (method !== 'POST') return notAllowed('POST');
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
      if (method !== 'POST') return notAllowed('POST');
      const { body, error } = await readJson(request);
      if (error) return json({ error }, 400);
      const r = await validateNames(env.DB, body, env.SCRYFALL_FETCH || fetch);
      entry.detail = { checked: r.body?.checked, unknown: r.body?.unknown };
      if (r.status >= 400) entry.message = r.body?.error;
      return send(r);
    }

    if (path === '/decks/formats') {
      if (method !== 'GET') return notAllowed('GET');
      return json({ formats: FORMATS });
    }

    if (path === '/decks/create') {
      if (method !== 'POST') return notAllowed('POST');
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
      if (method !== 'POST') return notAllowed('POST');
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
      if (method !== 'POST') return notAllowed('POST');
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
