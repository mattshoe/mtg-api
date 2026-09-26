// The daily maintenance job.
//
// Runs on a Cloudflare Cron Trigger — see [triggers] in wrangler.toml — so
// it needs nothing switched on, plugged in, or awake. Everything here is
// reachable from the Worker; the one job that cannot live here is the local
// backup, which by definition has to land on Matt's disk.
//
// Every task records a row in maintenance_log, so a task that quietly stops
// working is visible rather than merely absent.

import { makeThrottle } from './throttle.js';

const SCRYFALL = 'https://api.scryfall.com';
const UA = 'MattMTGCollectionAPI/1.0';
const CHUNK = 75;        // Scryfall's cap for /cards/collection
const WRITE_BATCH = 200; // statements per D1 batch

const money = (v) => (v === null || v === undefined ? null : Number(v));

async function log(db, task, ok, detail, ms) {
  try {
    await db.prepare(
      'INSERT INTO maintenance_log (ran_at, task, ok, detail, ms) VALUES (?,?,?,?,?)',
    ).bind(new Date().toISOString(), task, ok ? 1 : 0, String(detail).slice(0, 500), Math.round(ms)).run();
  } catch {
    // A failure to log must never be the reason the job fails.
  }
}

/**
 * Re-price every printing in the collection.
 *
 * 4,236 printings is 57 Scryfall calls, well inside the paid plan's 1,000
 * subrequests. The gap between calls is wall-clock, not CPU, so it costs
 * almost nothing against the limit that matters.
 */
export async function refreshPrices(db, { fetchImpl = fetch } = {}) {
  const rows = await db.prepare(
    'SELECT DISTINCT scryfall_id FROM cards WHERE scryfall_id IS NOT NULL',
  ).all();
  const ids = (rows.results || []).map((r) => r.scryfall_id);
  if (!ids.length) return { priced: 0, missing: 0, calls: 0 };

  const now = new Date().toISOString();
  const statements = [];
  const throttle = makeThrottle();
  let calls = 0;
  const seen = new Set();

  for (let i = 0; i < ids.length; i += CHUNK) {
    const slice = ids.slice(i, i + CHUNK);
    const res = await throttle(fetchImpl, `${SCRYFALL}/cards/collection`, {
      method: 'POST',
      headers: { 'content-type': 'application/json', 'User-Agent': UA, Accept: 'application/json' },
      body: JSON.stringify({ identifiers: slice.map((id) => ({ id })) }),
    });
    calls += 1;
    if (!res.ok) throw new Error(`scryfall ${res.status} on batch ${calls}`);
    const body = await res.json();

    for (const card of body.data || []) {
      const p = card.prices || {};
      seen.add(card.id);
      statements.push(db.prepare(
        `INSERT INTO prices (scryfall_id, usd, usd_foil, usd_etched, eur, tix, tcg_url, updated_at)
         VALUES (?,?,?,?,?,?,?,?)
         ON CONFLICT(scryfall_id) DO UPDATE SET
           usd = excluded.usd, usd_foil = excluded.usd_foil,
           usd_etched = excluded.usd_etched, eur = excluded.eur, tix = excluded.tix,
           tcg_url = excluded.tcg_url, updated_at = excluded.updated_at`,
      ).bind(
        card.id, money(p.usd), money(p.usd_foil), money(p.usd_etched),
        money(p.eur), money(p.tix), card.purchase_uris?.tcgplayer || null, now,
      ));
    }
  }

  for (let i = 0; i < statements.length; i += WRITE_BATCH) {
    await db.batch(statements.slice(i, i + WRITE_BATCH));
  }

  return { priced: seen.size, missing: ids.length - seen.size, calls };
}

/** Drop price rows for printings nobody owns any more. */
export async function prunePrices(db) {
  const r = await db.prepare(
    'DELETE FROM prices WHERE scryfall_id NOT IN (SELECT scryfall_id FROM cards WHERE scryfall_id IS NOT NULL)',
  ).run();
  return { removed: r.meta?.changes ?? 0 };
}

const CHILD_TABLES = ['card_faces', 'card_colors', 'card_types', 'card_keywords',
  'card_finishes', 'card_games', 'card_promo_types', 'card_frame_effects', 'card_tags'];

/**
 * Sweep up anything left pointing at a card that no longer exists. Remove
 * already cleans up after itself; this is the net under it, and it reports
 * what it found so a regression there shows up in the log.
 */
export async function sweepOrphans(db) {
  const statements = [];
  let found = 0;
  for (const t of CHILD_TABLES) {
    const c = await db.prepare(
      `SELECT COUNT(*) AS n FROM ${t} WHERE card_id NOT IN (SELECT id FROM cards)`,
    ).first();
    if (c?.n) {
      found += c.n;
      statements.push(db.prepare(`DELETE FROM ${t} WHERE card_id NOT IN (SELECT id FROM cards)`));
    }
  }
  const fts = await db.prepare(
    'SELECT COUNT(*) AS n FROM card_search WHERE rowid NOT IN (SELECT id FROM cards)',
  ).first();
  if (fts?.n) {
    found += fts.n;
    statements.push(db.prepare('DELETE FROM card_search WHERE rowid NOT IN (SELECT id FROM cards)'));
  }
  const alias = await db.prepare(
    'SELECT COUNT(*) AS n FROM aliases WHERE canonical_name NOT IN (SELECT name FROM cards)',
  ).first();
  if (alias?.n) {
    found += alias.n;
    statements.push(db.prepare('DELETE FROM aliases WHERE canonical_name NOT IN (SELECT name FROM cards)'));
  }

  if (statements.length) await db.batch(statements);
  return { removed: found };
}

/** Cards with no FTS row cannot be found by rules text; rebuild them. */
export async function repairSearch(db) {
  const missing = await db.prepare(
    'SELECT COUNT(*) AS n FROM cards c WHERE NOT EXISTS (SELECT 1 FROM card_search s WHERE s.rowid = c.id)',
  ).first();
  if (!missing?.n) return { reindexed: 0 };

  await db.prepare(`
    INSERT INTO card_search (rowid, name, type_line, oracle_text, flavor_text, keywords, tags)
    SELECT c.id, c.name, c.type_line, c.oracle_text, c.flavor_text,
           (SELECT GROUP_CONCAT(keyword,' ') FROM card_keywords k WHERE k.card_id = c.id),
           (SELECT GROUP_CONCAT(tag_slug,' ') FROM card_tags t WHERE t.card_id = c.id)
      FROM cards c
     WHERE NOT EXISTS (SELECT 1 FROM card_search s WHERE s.rowid = c.id)`).run();
  return { reindexed: missing.n };
}

/** A few numbers worth noticing if they ever move the wrong way. */
export async function healthCheck(db) {
  const row = await db.prepare(`SELECT
      (SELECT COUNT(*) FROM cards)                                   AS cards,
      (SELECT SUM(qty) FROM cards)                                   AS physical,
      (SELECT COUNT(*) FROM prices)                                  AS priced,
      (SELECT COUNT(*) FROM cards c LEFT JOIN prices p
                ON p.scryfall_id = c.scryfall_id WHERE p.usd IS NULL) AS unpriced,
      (SELECT ROUND(SUM(qty * price), 2) FROM card_prices
                WHERE price IS NOT NULL)                             AS value,
      (SELECT COUNT(*) FROM card_search)                             AS indexed`).first();
  if (!row || !row.cards) throw new Error('cards table is empty');
  return row;
}

/**
 * What the nightly Cron Trigger runs. Deliberately no price refresh:
 * Scryfall rate-limits Cloudflare's shared egress IPs hard — 20 batches of
 * 75 and then a 429 that backing off does not clear, reproducibly, while
 * the same 55 batches from a home IP sail through in 37 seconds. Their own
 * 429 tells you to use the bulk data offering for volume, and that file is
 * 78 MB gzipped, well past what a Worker can hold. So prices come from the
 * Mac, which already downloads that bulk file three times a day; see
 * scripts/refresh_prices.py. Everything here touches only D1 and therefore
 * cannot be rate-limited by anyone.
 */
export const CRON_TASKS = ['prune-prices', 'orphans', 'search', 'health'];

/**
 * Everything, in order, each logged separately so one failure does not
 * hide the others.
 */
export async function runMaintenance(db, { fetchImpl = fetch, only, tasks: want } = {}) {
  const tasks = [
    ['prices', () => refreshPrices(db, { fetchImpl })],
    ['prune-prices', () => prunePrices(db)],
    ['orphans', () => sweepOrphans(db)],
    ['search', () => repairSearch(db)],
    ['health', () => healthCheck(db)],
  ].filter(([name]) => (only ? only === name : (!want || want.includes(name))));

  const results = {};
  let ok = true;
  const started = Date.now();

  for (const [name, fn] of tasks) {
    const t0 = Date.now();
    try {
      const out = await fn();
      results[name] = out;
      await log(db, name, true, JSON.stringify(out), Date.now() - t0);
    } catch (e) {
      ok = false;
      results[name] = { error: String(e.message || e) };
      await log(db, name, false, String(e.message || e), Date.now() - t0);
    }
  }

  const ms = Date.now() - started;
  await log(db, 'run', ok, JSON.stringify(Object.keys(results)), ms);
  return { ok, ms, results };
}
