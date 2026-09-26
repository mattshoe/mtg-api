// Price lookups, through the Worker's /prices endpoint.
//
// Prices are not in the database and never will be — they go stale in a
// day. They are fetched on demand, cached at the edge for 12 hours, and
// cached again here in memory for the life of the tab.

import { API } from './api.js';

const CHUNK = 1000;   // the endpoint caps a single request at 1500
const mem = new Map(); // scryfall id -> price row, or null when unknown

/** Already-known prices, without a round trip. */
export const known = (id) => mem.get(id);

/**
 * Fetch prices for these ids, skipping anything already in memory.
 * @param {string[]} ids
 * @param {(done:number,total:number)=>void} [onProgress]
 */
export async function fetchPrices(ids, onProgress) {
  const wanted = [...new Set(ids)].filter((id) => id && !mem.has(id));
  if (!wanted.length) {
    if (onProgress) onProgress(ids.length, ids.length);
    return mem;
  }

  let done = 0;
  for (let i = 0; i < wanted.length; i += CHUNK) {
    const slice = wanted.slice(i, i + CHUNK);
    try {
      const res = await fetch(`${API}/prices`, {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({ ids: slice }),
      });
      const body = await res.json();
      if (body.prices) for (const [id, row] of Object.entries(body.prices)) mem.set(id, row);
      // Remember the blanks too, so a card Scryfall has no price for is
      // not re-requested on every page turn.
      for (const id of slice) if (!mem.has(id)) mem.set(id, null);
    } catch {
      for (const id of slice) if (!mem.has(id)) mem.set(id, null);
    }
    done += slice.length;
    if (onProgress) onProgress(done, wanted.length);
  }
  return mem;
}

/**
 * The price that matches what you actually own — a foil row should not
 * quote the nonfoil price. Falls back to the plain USD figure when the
 * specific finish has none.
 */
export function priceOf(row) {
  const p = mem.get(row.scryfall_id);
  if (!p) return null;
  if (row.finish === 'foil') return p.foil ?? p.usd ?? null;
  if (row.finish === 'etched') return p.etched ?? p.foil ?? p.usd ?? null;
  return p.usd ?? null;
}

export const tcgLink = (id) => mem.get(id)?.tcg || null;

export function money(v, { dash = '—' } = {}) {
  if (v === null || v === undefined) return dash;
  return `$${v < 10 ? v.toFixed(2) : Math.round(v).toLocaleString()}`;
}

export function exact(v) {
  if (v === null || v === undefined) return '—';
  return `$${v.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
}
