// Prices, from Scryfall, cached at the edge — never written to D1.
//
// Scryfall's `usd` figures are TCGplayer market prices, and every card
// object carries a TCGplayer purchase link, so this gets the data the ask
// wanted without an OAuth dance or a developer account.
//
// Deliberately not in the database: prices go stale within a day, and a
// column that is wrong most of the time is worse than no column. This
// caches in the Cloudflare Cache API instead, which is storage the
// collection does not have to own or back up.

import { makeThrottle } from './throttle.js';

const SCRYFALL = 'https://api.scryfall.com';
const UA = 'MattMTGCollectionAPI/1.0';
const CHUNK = 75;             // Scryfall's cap for /cards/collection
const TTL_SECONDS = 12 * 3600;
const MAX_IDS = 1500;         // one request should not become 20 batches often

const cacheKey = (id) => new Request(`https://prices.mtg.internal/v1/${id}`);

const money = (v) => (v === null || v === undefined ? null : Number(v));

/** The slice of a Scryfall card this endpoint actually returns. */
function extract(card) {
  const p = card.prices || {};
  return {
    usd: money(p.usd),
    foil: money(p.usd_foil),
    etched: money(p.usd_etched),
    eur: money(p.eur),
    tix: money(p.tix),
    tcg: card.purchase_uris?.tcgplayer || null,
  };
}

/**
 * @param {string[]} ids scryfall ids
 * @returns {Promise<{prices: Record<string, object>, fetched: number, cached: number, missing: string[]}>}
 */
export async function lookupPrices(ids, { fetchImpl = fetch, cache, waitUntil } = {}) {
  const unique = [...new Set(ids.filter((x) => typeof x === 'string' && x.length > 8))];
  if (unique.length > MAX_IDS) {
    const err = new Error(`too many ids (${unique.length} > ${MAX_IDS})`);
    err.status = 400;
    throw err;
  }

  const prices = {};
  const misses = [];

  // Cache reads are not subrequests, so this is cheap even for 1500 ids.
  if (cache) {
    await Promise.all(unique.map(async (id) => {
      try {
        const hit = await cache.match(cacheKey(id));
        if (hit) prices[id] = await hit.json();
        else misses.push(id);
      } catch {
        misses.push(id);
      }
    }));
  } else {
    misses.push(...unique);
  }

  const cachedCount = unique.length - misses.length;
  const throttle = makeThrottle();
  let fetched = 0;

  for (let i = 0; i < misses.length; i += CHUNK) {
    const slice = misses.slice(i, i + CHUNK);
    const res = await throttle(fetchImpl, `${SCRYFALL}/cards/collection`, {
      method: 'POST',
      headers: { 'content-type': 'application/json', 'User-Agent': UA, Accept: 'application/json' },
      body: JSON.stringify({ identifiers: slice.map((id) => ({ id })) }),
    });
    if (!res.ok) {
      const err = new Error(`scryfall: ${res.status} ${res.statusText}`);
      err.status = 502;
      throw err;
    }
    const body = await res.json();

    for (const card of body.data || []) {
      const row = extract(card);
      prices[card.id] = row;
      fetched += 1;
      if (cache) {
        const store = cache.put(cacheKey(card.id), new Response(JSON.stringify(row), {
          headers: {
            'content-type': 'application/json',
            'cache-control': `max-age=${TTL_SECONDS}`,
          },
        }));
        // Writing the cache should not hold the response up.
        if (waitUntil) waitUntil(store); else await store;
      }
    }
  }

  // A card Scryfall no longer knows about should not look like a pending
  // lookup forever, so say which ids came back with nothing.
  const missing = unique.filter((id) => !prices[id]);
  return { prices, fetched, cached: cachedCount, missing };
}
