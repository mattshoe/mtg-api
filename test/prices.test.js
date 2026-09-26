import { describe, it, expect } from 'vitest';
import { get, postAnon, sql } from './helpers.js';

const ID_A = '2d47121d-8b90-4d28-9ffa-0a640b9dd611';
const ID_B = 'f29ba16f-c8fb-42fe-aabf-87089cb214a7';

/** A Scryfall stand-in that only knows about prices. */
function stubPrices({ fail = false, status = 0 } = {}) {
  const calls = { collection: 0, ids: [] };
  const impl = async (url, init) => {
    if (fail) throw new Error('network down');
    if (status) return new Response('{}', { status });
    calls.collection += 1;
    const { identifiers } = JSON.parse(init.body);
    calls.ids.push(...identifiers.map((i) => i.id));
    return Response.json({
      data: identifiers
        .filter((i) => i.id !== 'ffffffff-0000-0000-0000-000000000000')
        .map((i) => ({
          id: i.id,
          prices: {
            usd: i.id === ID_A ? '1.97' : '2.11',
            usd_foil: i.id === ID_A ? null : '2.31',
            usd_etched: null,
            eur: '1.30',
            tix: '0.04',
          },
          purchase_uris: { tcgplayer: `https://tcgplayer.example/${i.id}` },
        })),
      not_found: [],
    });
  };
  impl.calls = calls;
  return impl;
}

/**
 * The cache is off in tests. Cloudflare's Cache API is a real edge cache
 * and leaving it on would make one test's fetch satisfy the next test's,
 * which would hide exactly the bugs these tests are for.
 */
const priceReq = (ids, scryfall) => postAnon('/prices', { ids }, scryfall);

describe('POST /prices', () => {
  it('returns prices keyed by scryfall id', async () => {
    const r = await priceReq([ID_A, ID_B], stubPrices());
    expect(r.status).toBe(200);
    expect(r.body.prices[ID_A]).toEqual({
      usd: 1.97, foil: null, etched: null, eur: 1.3, tix: 0.04,
      tcg: `https://tcgplayer.example/${ID_A}`,
    });
    expect(r.body.prices[ID_B].foil).toBe(2.31);
  });

  it('gives numbers, not the strings Scryfall sends', async () => {
    const r = await priceReq([ID_A], stubPrices());
    expect(typeof r.body.prices[ID_A].usd).toBe('number');
  });

  it('carries a TCGplayer link through', async () => {
    const r = await priceReq([ID_A], stubPrices());
    expect(r.body.prices[ID_A].tcg).toMatch(/tcgplayer/);
  });

  it('needs no admin token — it is a read', async () => {
    const r = await priceReq([ID_A], stubPrices());
    expect(r.status).toBe(200);
  });

  it('batches at 75 per Scryfall call', async () => {
    const stub = stubPrices();
    const ids = Array.from({ length: 160 }, (_, i) => `00000000-0000-0000-0000-${String(i).padStart(12, '0')}`);
    await priceReq(ids, stub);
    expect(stub.calls.collection).toBe(3);
    expect(stub.calls.ids).toHaveLength(160);
  });

  it('deduplicates before asking Scryfall', async () => {
    const stub = stubPrices();
    await priceReq([ID_A, ID_A, ID_A, ID_B], stub);
    expect(stub.calls.ids).toHaveLength(2);
  });

  it('reports ids Scryfall did not return rather than leaving them pending', async () => {
    const ghost = 'ffffffff-0000-0000-0000-000000000000';
    const r = await priceReq([ID_A, ghost], stubPrices());
    expect(r.body.missing).toEqual([ghost]);
    expect(r.body.prices[ghost]).toBeUndefined();
  });

  it('refuses a silly number of ids', async () => {
    const ids = Array.from({ length: 1501 }, (_, i) => `00000000-0000-0000-0000-${String(i).padStart(12, '0')}`);
    const r = await priceReq(ids, stubPrices());
    expect(r.status).toBe(400);
    expect(r.body.error).toMatch(/too many/);
  });

  it('rejects a body without an ids array', async () => {
    expect((await postAnon('/prices', {}, stubPrices())).status).toBe(400);
    expect((await postAnon('/prices', { ids: 'nope' }, stubPrices())).status).toBe(400);
  });

  it('ignores junk entries instead of asking Scryfall about them', async () => {
    const stub = stubPrices();
    const r = await priceReq([ID_A, '', null, 123, 'short'], stub);
    expect(stub.calls.ids).toEqual([ID_A]);
    expect(r.status).toBe(200);
  });

  it('an empty list is a valid, empty answer', async () => {
    const stub = stubPrices();
    const r = await priceReq([], stub);
    expect(r.status).toBe(200);
    expect(r.body.prices).toEqual({});
    expect(stub.calls.collection).toBe(0);
  });

  it('a Scryfall outage is a 502, not a 500', async () => {
    const r = await priceReq([ID_A], stubPrices({ fail: true }));
    expect(r.status).toBe(502);
  });

  it('a Scryfall error status surfaces as a 502', async () => {
    const r = await priceReq([ID_A], stubPrices({ status: 503 }));
    expect(r.status).toBe(502);
  });

  it('is POST only', async () => {
    expect((await get('/prices')).status).toBe(405);
  });

  it('writes nothing to the database', async () => {
    const before = await sql("SELECT name FROM sqlite_master WHERE type='table' ORDER BY 1");
    await priceReq([ID_A, ID_B], stubPrices());
    const after = await sql("SELECT name FROM sqlite_master WHERE type='table' ORDER BY 1");
    expect(after).toEqual(before);
    // and no money column crept into cards. `mana_cost` is a mana cost,
    // not a price, so the pattern has to be about currency.
    const cols = await sql('PRAGMA table_info(cards)');
    expect(cols.map((c) => c.name).filter((n) => /price|usd|eur|tix|\bcost_/.test(n))).toEqual([]);
  });

  it('every scryfall_id in the collection is the shape this endpoint expects', async () => {
    const bad = await sql(
      "SELECT COUNT(*) AS n FROM cards WHERE scryfall_id IS NULL OR length(scryfall_id) < 30",
    );
    expect(bad[0].n).toBe(0);
  });
});
