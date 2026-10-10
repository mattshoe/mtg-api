import { describe, it, expect } from 'vitest';
import { post, count, stubScryfall } from './helpers.js';

/** N distinct printings a wide stub will resolve. */
function many(n) {
  return Array.from({ length: n }, (_, i) => ({
    id: `00000000-0000-4000-8000-${String(i).padStart(12, '0')}`,
    name: `Bulk Card ${i}`,
    set: 'tst',
    collector_number: String(i),
    type_line: 'Artifact',
    oracle_id: `11111111-0000-4000-8000-${String(i).padStart(12, '0')}`,
    finishes: ['nonfoil'],
    colors: [], color_identity: [], keywords: [], games: ['paper'],
    legalities: { commander: 'legal' },
    prices: { usd: '0.10' },
  }));
}

function wide(cards) {
  const stub = stubScryfall();
  const byName = new Map(cards.map((c) => [c.name.toLowerCase(), c]));
  const seen = { collection: 0 };
  const f = async (url, init) => {
    const u = new URL(url);
    if (u.pathname === '/cards/collection') {
      seen.collection += 1;
      const { identifiers } = JSON.parse(init.body);
      return Response.json({
        data: identifiers.map((i) => byName.get(String(i.name || '').toLowerCase())).filter(Boolean),
        not_found: [],
      });
    }
    return stub(url, init);
  };
  f.seen = seen;
  return f;
}

// There is no line limit on an import, and this is what makes that true
// rather than a claim.
//
// There used to be `MAX_LINES = 1000`, from the first commit, with no
// reason written beside it. What it hid was a real bug: the id lookups
// pasted every UUID into one `IN (...)` and past roughly 4,000 ids that is
// `SQLITE_TOOBIG — statement too long`. So the cap was not a policy, it was
// a bug nobody could reach.
//
// 4,000 lines is bigger than Matt's whole collection and twice the size
// that used to fail outright. 10,000 was measured by hand and lands too,
// in 134 chunked Scryfall calls and 88 seconds; it is not run here because
// 88 seconds in CI for one assertion is not worth it.
describe('an import has no line limit', () => {
  for (const n of [1500, 4000]) {
    it(`imports ${n} lines, which the old cap refused`, { timeout: 300000 }, async () => {
      const cards = many(n);
      const f = wide(cards);
      const list = cards.map((c) => `1 ${c.name}`).join('\n');
      const t0 = Date.now();
      const r = await post('/cards/add', { list }, f);
      const ms = Date.now() - t0;
      console.log(`${n} lines -> status ${r.status} applied=${r.body?.applied} `
        + `resolved=${r.body?.resolved} subrequests=${f.seen.collection} ${ms}ms `
        + `err=${r.body?.error || '-'}`);
      expect(r.status).toBe(200);
      expect(r.body.applied).toBe(true);
      expect(await count('cards', "setcode = 'tst'")).toBe(n);
    });
  }
});
