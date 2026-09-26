import { describe, it, expect } from 'vitest';
import { env } from 'cloudflare:test';
import {
  get, post, postAnon, postAs, sql, count, adminToken, stubScryfall,
} from './helpers.js';
import {
  refreshPrices, prunePrices, sweepOrphans, repairSearch, healthCheck, runMaintenance,
} from '../src/maintenance.js';

/** Scryfall stand-in that prices whatever it is asked about. */
function stubPricer({ fail = false, usd = 3.5 } = {}) {
  const calls = { batches: 0, ids: [] };
  const impl = async (url, init) => {
    if (fail) throw new Error('scryfall down');
    calls.batches += 1;
    const { identifiers } = JSON.parse(init.body);
    calls.ids.push(...identifiers.map((i) => i.id));
    return Response.json({
      data: identifiers.map((i) => ({
        id: i.id,
        prices: { usd: String(usd), usd_foil: '9.00', usd_etched: null, eur: '3.00', tix: '0.10' },
        purchase_uris: { tcgplayer: `https://tcgplayer.example/${i.id}` },
      })),
      not_found: [],
    });
  };
  impl.calls = calls;
  return impl;
}

describe('refreshPrices', () => {
  it('prices every printing in the collection', async () => {
    const stub = stubPricer();
    const cards = await count('cards');
    const out = await refreshPrices(env.DB, { fetchImpl: stub });

    expect(out.priced).toBe(await count('prices'));
    expect(out.missing).toBe(0);
    const distinct = await sql('SELECT COUNT(DISTINCT scryfall_id) AS n FROM cards');
    expect(out.priced).toBe(distinct[0].n);
    expect(cards).toBeGreaterThan(0);
  });

  it('batches at 75 per Scryfall call', async () => {
    const stub = stubPricer();
    const distinct = (await sql('SELECT COUNT(DISTINCT scryfall_id) AS n FROM cards'))[0].n;
    await refreshPrices(env.DB, { fetchImpl: stub });
    expect(stub.calls.batches).toBe(Math.ceil(distinct / 75));
  });

  it('stores numbers and a TCGplayer url', async () => {
    await refreshPrices(env.DB, { fetchImpl: stubPricer({ usd: 1.25 }) });
    const row = await sql('SELECT * FROM prices LIMIT 1');
    expect(row[0].usd).toBe(1.25);
    expect(row[0].usd_foil).toBe(9);
    expect(row[0].tcg_url).toMatch(/tcgplayer/);
    expect(row[0].updated_at).toMatch(/^\d{4}-\d{2}-\d{2}T/);
  });

  it('updates in place rather than duplicating', async () => {
    await refreshPrices(env.DB, { fetchImpl: stubPricer({ usd: 1 }) });
    const first = await count('prices');
    await refreshPrices(env.DB, { fetchImpl: stubPricer({ usd: 2 }) });
    expect(await count('prices')).toBe(first);
    const row = await sql('SELECT usd FROM prices LIMIT 1');
    expect(row[0].usd).toBe(2);
  });

  it('a Scryfall failure throws rather than half-writing', async () => {
    await expect(refreshPrices(env.DB, { fetchImpl: stubPricer({ fail: true }) })).rejects.toThrow();
    expect(await count('prices')).toBe(0);
  });
});

describe('card_prices view', () => {
  it('quotes the finish you actually own', async () => {
    await refreshPrices(env.DB, { fetchImpl: stubPricer({ usd: 1.25 }) });
    const rows = await sql(`
      SELECT c.finish, cp.price FROM cards c
      JOIN card_prices cp ON cp.card_id = c.id
      WHERE c.finish IN ('nonfoil','foil') GROUP BY c.finish`);
    for (const r of rows) {
      expect(r.price, `${r.finish} priced wrong`).toBe(r.finish === 'nonfoil' ? 1.25 : 9);
    }
  });

  it('gives a null price rather than zero when unpriced', async () => {
    const rows = await sql('SELECT price FROM card_prices WHERE price IS NOT NULL');
    expect(rows).toHaveLength(0);
    const all = await count('card_prices');
    expect(all).toBe(await count('cards'));
  });
});

describe('prunePrices', () => {
  it('drops prices for printings nobody owns', async () => {
    await refreshPrices(env.DB, { fetchImpl: stubPricer() });
    await env.DB.prepare(
      "INSERT INTO prices (scryfall_id, usd) VALUES ('ghost-id-not-owned', 1.0)",
    ).run();
    const before = await count('prices');
    const out = await prunePrices(env.DB);
    expect(out.removed).toBe(1);
    expect(await count('prices')).toBe(before - 1);
  });
});

describe('sweepOrphans', () => {
  it('finds nothing on a healthy database', async () => {
    expect((await sweepOrphans(env.DB)).removed).toBe(0);
  });

  it('clears child rows pointing at a card that is gone', async () => {
    await env.DB.batch([
      env.DB.prepare("INSERT INTO card_colors VALUES (999999, 'W', 'color')"),
      env.DB.prepare("INSERT INTO card_types VALUES (999999, 'Creature', 'type')"),
      env.DB.prepare("INSERT INTO aliases VALUES ('ghost', 'Ghost Card')"),
    ]);
    const out = await sweepOrphans(env.DB);
    expect(out.removed).toBe(3);
    expect(await count('card_colors', 'card_id = 999999')).toBe(0);
    expect(await count('aliases', "canonical_name = 'Ghost Card'")).toBe(0);
  });
});

describe('repairSearch', () => {
  it('does nothing when every card is indexed', async () => {
    expect((await repairSearch(env.DB)).reindexed).toBe(0);
  });

  it('reindexes a card whose FTS row went missing', async () => {
    const [card] = await sql('SELECT id, name FROM cards LIMIT 1');
    await env.DB.prepare('DELETE FROM card_search WHERE rowid = ?').bind(card.id).run();
    expect(await count('card_search', 'rowid = ?', card.id)).toBe(0);

    const out = await repairSearch(env.DB);
    expect(out.reindexed).toBe(1);
    expect(await count('card_search', 'rowid = ?', card.id)).toBe(1);
  });
});

describe('healthCheck', () => {
  it('reports the numbers worth watching', async () => {
    await refreshPrices(env.DB, { fetchImpl: stubPricer({ usd: 2 }) });
    const h = await healthCheck(env.DB);
    expect(h.cards).toBe(await count('cards'));
    expect(h.priced).toBeGreaterThan(0);
    expect(h.unpriced).toBe(0);
    expect(h.value).toBeGreaterThan(0);
    expect(h.indexed).toBe(await count('card_search'));
  });
});

describe('runMaintenance', () => {
  it('runs every task and logs each one', async () => {
    const out = await runMaintenance(env.DB, { fetchImpl: stubPricer() });
    expect(out.ok).toBe(true);
    expect(Object.keys(out.results)).toEqual(
      ['prices', 'prune-prices', 'prune-logs', 'orphans', 'search', 'health'],
    );

    const logged = await sql('SELECT task, ok FROM maintenance_log ORDER BY id');
    expect(logged.map((l) => l.task)).toEqual(
      ['prices', 'prune-prices', 'prune-logs', 'orphans', 'search', 'health', 'run'],
    );
    expect(logged.every((l) => l.ok === 1)).toBe(true);
  });

  it('one failing task does not stop the others, and is logged as failed', async () => {
    const out = await runMaintenance(env.DB, { fetchImpl: stubPricer({ fail: true }) });
    expect(out.ok).toBe(false);
    expect(out.results.prices.error).toBeTruthy();
    // the rest still ran
    expect(out.results.orphans).toEqual({ removed: 0 });
    expect(out.results.health.cards).toBeGreaterThan(0);

    const failed = await sql("SELECT task FROM maintenance_log WHERE ok = 0");
    expect(failed.map((f) => f.task)).toContain('prices');
  });

  it('can be narrowed to one task', async () => {
    const out = await runMaintenance(env.DB, { fetchImpl: stubPricer(), only: 'orphans' });
    expect(Object.keys(out.results)).toEqual(['orphans']);
  });
});

describe('/maintenance endpoint', () => {
  it('GET reports the log without a token', async () => {
    await runMaintenance(env.DB, { fetchImpl: stubPricer() });
    const r = await get('/maintenance');
    expect(r.status).toBe(200);
    expect(r.body.runs.length).toBeGreaterThan(0);
    expect(r.body.runs[0]).toHaveProperty('task');
  });

  it('POST needs admin, because it writes', async () => {
    const r = await postAnon('/maintenance', {});
    expect(r.status).toBe(401);
    expect(r.body.admin_required).toBe(true);
  });

  it('POST with a token and wait:true runs it and reports', async () => {
    const token = await adminToken();
    const r = await postAs('/maintenance', { only: 'orphans', wait: true }, token, stubPricer());
    expect(r.status).toBe(200);
    expect(r.body.ok).toBe(true);
    expect(r.body.results.orphans).toEqual({ removed: 0 });
  });

  it('POST without wait returns straight away and runs in the background', async () => {
    // Re-pricing is a minute of waiting on Scryfall; holding the response
    // open for it would just time out.
    const token = await adminToken();
    const r = await postAs('/maintenance', { only: 'orphans' }, token, stubPricer());
    expect(r.status).toBe(202);
    expect(r.body.started).toBe(true);
    expect(r.body.check).toMatch(/GET \/maintenance/);
  });
});

describe('adding a card prices it immediately', () => {
  it('writes a price row as part of the add', async () => {
    const r = await post('/cards/add', { list: '1 Lightning Bolt (2X2) 117' }, stubScryfall());
    expect(r.body.applied).toBe(true);
    const [card] = await sql("SELECT scryfall_id FROM cards WHERE name_norm = 'lightning bolt'");
    const priced = await sql('SELECT usd FROM prices WHERE scryfall_id = ?', card.scryfall_id);
    // The recorded fixture has real prices on it.
    expect(priced).toHaveLength(1);
    expect(priced[0].usd).toBeGreaterThan(0);
  });
});

