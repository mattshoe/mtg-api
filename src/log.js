// Request and event logging, into D1.
//
// Two rules this module exists to enforce:
//
//   1. Logging must never slow a request down or take one out. Every write
//      happens after the response has gone, via ctx.waitUntil, and every
//      failure here is swallowed. A log that breaks the thing it logs is
//      worse than no log.
//   2. Nothing secret is ever written. The admin password and the tokens
//      minted from it are stripped before anything reaches the table, and
//      the /admin body is never recorded at all.
//
// Rows live a week; the daily maintenance job prunes them.

export const RETENTION_DAYS = 7;

const MAX_MESSAGE = 400;
const MAX_DETAIL = 20000;

/**
 * What a row's level should be, given how the request went. The default
 * view in the UI is info and above, so ordinary reads stay out of the way
 * without being thrown away.
 */
export function levelFor(status, { write = false } = {}) {
  if (status >= 500) return 'error';
  if (status >= 400) return 'warn';
  return write ? 'info' : 'debug';
}

/** Route -> event slug, for filtering. */
export function eventFor(method, path) {
  const p = path.replace(/^\/+|\/+$/g, '') || 'root';
  return `${p.replace(/\//g, '.')}${method === 'GET' ? '' : `.${method.toLowerCase()}`}`
    .replace(/\.post$/, '')
    .slice(0, 48);
}

const clip = (v, n) => {
  if (v === null || v === undefined) return null;
  const s = typeof v === 'string' ? v : JSON.stringify(v);
  return s.length > n ? `${s.slice(0, n)}…` : s;
};

// Anything that looks like a credential, in case a detail object ever
// picks one up by accident.
const SECRET_KEYS = /^(password|token|authorization|secret|pass|pw)$/i;

function scrub(value, depth = 0) {
  if (value === null || typeof value !== 'object' || depth > 4) return value;
  if (Array.isArray(value)) return value.map((v) => scrub(v, depth + 1));
  const out = {};
  for (const [k, v] of Object.entries(value)) {
    out[k] = SECRET_KEYS.test(k) ? '[redacted]' : scrub(v, depth + 1);
  }
  return out;
}

/**
 * A per-request scratchpad. Handlers drop whatever is worth knowing onto
 * it and the shell turns that into one row.
 */
export function newEntry(request) {
  const url = new URL(request.url);
  return {
    ts: new Date().toISOString(),
    method: request.method.toUpperCase(),
    path: url.pathname.replace(/\/+$/, '') || '/',
    started: Date.now(),
    write: false,
    admin: false,
    message: null,
    detail: null,
    ip: request.headers.get('cf-connecting-ip') || null,
    country: request.cf?.country || request.headers.get('cf-ipcountry') || null,
    ray: request.headers.get('cf-ray') || null,
  };
}

/**
 * The log viewer polls, and logging those reads would bury the log in
 * itself. A refused attempt is different — someone trying to read the log
 * without a token is the one thing in here worth keeping — so only
 * successful reads are skipped.
 */
const SKIP = new Set(['/logs', '/logs/stats']);

export async function writeEntry(env, entry, status) {
  if (!env?.DB) return;
  if (SKIP.has(entry.path) && status < 400) return;
  try {
    // Date.now() barely moves between I/O in Workers, so this is a floor on
    // the real duration rather than a precise measurement. Good enough to
    // spot something taking seconds; do not read it as a benchmark.
    const ms = Math.max(0, Date.now() - entry.started);
    await env.DB.prepare(
      `INSERT INTO logs (ts, level, event, method, path, status, ms, message,
                         detail, ip, country, ray, admin)
       VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)`,
    ).bind(
      entry.ts,
      entry.level || levelFor(status, { write: entry.write }),
      eventFor(entry.method, entry.path),
      entry.method,
      entry.path,
      status,
      ms,
      clip(entry.message, MAX_MESSAGE),
      entry.detail ? clip(scrub(entry.detail), MAX_DETAIL) : null,
      entry.ip,
      entry.country,
      entry.ray,
      entry.admin ? 1 : 0,
    ).run();
  } catch {
    // Never let logging be the reason a request failed.
  }
}

/** A standalone event, not tied to a request — used by the cron job. */
export async function logEvent(env, { level = 'info', event, message, detail, ms = 0 }) {
  if (!env?.DB) return;
  try {
    await env.DB.prepare(
      `INSERT INTO logs (ts, level, event, method, path, status, ms, message, detail, admin)
       VALUES (?,?,?,?,?,?,?,?,?,?)`,
    ).bind(
      new Date().toISOString(), level, event, 'CRON', `/${event}`, 0, Math.round(ms),
      clip(message, MAX_MESSAGE), detail ? clip(scrub(detail), MAX_DETAIL) : null, 1,
    ).run();
  } catch {
    // as above
  }
}

// ------------------------------------------------------------- reading

const LEVELS = ['debug', 'info', 'warn', 'error'];

/**
 * The log query behind GET /logs. Every filter is optional and everything
 * user-supplied is bound.
 */
export function buildLogQuery(params) {
  const where = [];
  const args = [];

  const levels = (params.get('level') || '').split(',').filter((l) => LEVELS.includes(l));
  if (levels.length) {
    where.push(`level IN (${levels.map(() => '?').join(',')})`);
    args.push(...levels);
  }

  // `min` is the more useful everyday control: "info and above".
  const min = params.get('min');
  if (min && LEVELS.includes(min) && !levels.length) {
    const allowed = LEVELS.slice(LEVELS.indexOf(min));
    where.push(`level IN (${allowed.map(() => '?').join(',')})`);
    args.push(...allowed);
  }

  const event = params.get('event');
  if (event) { where.push('event = ?'); args.push(event); }

  const method = params.get('method');
  if (method) { where.push('method = ?'); args.push(method.toUpperCase()); }

  const status = params.get('status');
  if (status === 'error') where.push('status >= 400');
  else if (status && /^\d{3}$/.test(status)) { where.push('status = ?'); args.push(Number(status)); }
  else if (status && /^\dxx$/i.test(status)) {
    where.push('status >= ? AND status < ?');
    args.push(Number(status[0]) * 100, (Number(status[0]) + 1) * 100);
  }

  const since = params.get('since');
  if (since) {
    const hours = Number(since);
    if (Number.isFinite(hours) && hours > 0) {
      where.push('ts >= ?');
      args.push(new Date(Date.now() - hours * 3600_000).toISOString());
    } else {
      where.push('ts >= ?');
      args.push(since);
    }
  }

  const slow = Number(params.get('slow'));
  if (Number.isFinite(slow) && slow > 0) { where.push('ms >= ?'); args.push(slow); }

  const q = (params.get('q') || '').trim();
  if (q) {
    where.push('(path LIKE ? OR message LIKE ? OR detail LIKE ? OR event LIKE ? OR ip LIKE ?)');
    const like = `%${q}%`;
    args.push(like, like, like, like, like);
  }

  const clause = where.length ? `WHERE ${where.join(' AND ')}` : '';
  let limit = Number(params.get('limit')) || 100;
  limit = Math.min(Math.max(limit, 1), 500);
  const offset = Math.max(Number(params.get('offset')) || 0, 0);

  return {
    rows: {
      sql: `SELECT id, ts, level, event, method, path, status, ms, message, detail, ip, country, ray, admin
              FROM logs ${clause} ORDER BY id DESC LIMIT ${limit} OFFSET ${offset}`,
      args,
    },
    total: { sql: `SELECT COUNT(*) AS n FROM logs ${clause}`, args },
    limit,
    offset,
  };
}

/** Headline numbers for the top of the log page. */
export async function logStats(db, hours = 24) {
  const since = new Date(Date.now() - hours * 3600_000).toISOString();
  const [byLevel, byEvent, slowest, recentErrors] = await Promise.all([
    db.prepare('SELECT level, COUNT(*) AS n FROM logs WHERE ts >= ? GROUP BY level').bind(since).all(),
    db.prepare('SELECT event, COUNT(*) AS n, ROUND(AVG(ms)) AS avg_ms FROM logs WHERE ts >= ? GROUP BY event ORDER BY n DESC LIMIT 12').bind(since).all(),
    db.prepare('SELECT event, path, ms, ts FROM logs WHERE ts >= ? ORDER BY ms DESC LIMIT 8').bind(since).all(),
    db.prepare("SELECT COUNT(*) AS n FROM logs WHERE ts >= ? AND level = 'error'").bind(since).first(),
  ]);
  const oldest = await db.prepare('SELECT MIN(ts) AS t, COUNT(*) AS n FROM logs').first();
  return {
    hours,
    by_level: byLevel.results || [],
    by_event: byEvent.results || [],
    slowest: slowest.results || [],
    errors: recentErrors?.n ?? 0,
    oldest: oldest?.t || null,
    total: oldest?.n ?? 0,
    retention_days: RETENTION_DAYS,
  };
}

/** Delete anything past the retention window. Called by the daily job. */
export async function pruneLogs(db, days = RETENTION_DAYS) {
  const cutoff = new Date(Date.now() - days * 86400_000).toISOString();
  const r = await db.prepare('DELETE FROM logs WHERE ts < ?').bind(cutoff).run();
  return { removed: r.meta?.changes ?? 0, cutoff };
}
