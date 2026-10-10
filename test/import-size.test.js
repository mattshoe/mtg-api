import { describe, it, expect } from 'vitest';
import { post, count, stubScryfall } from './helpers.js';
import { allInChunks, ID_CHUNK } from '../src/cards.js';

// An import has no line limit, and the id lookups behind it do not build
// one enormous statement.
//
// There used to be `MAX_LINES = 1000` in src/cards.js and `MAX_NAMES = 1000`
// in src/validate.js, both from the first commit with no reason written
// beside either. What they hid was a real bug: `currentStacks` and
// `knownOracleIds` paste every UUID into one `IN (...)` — deliberately, to
// dodge D1's bound-parameter ceiling — and past roughly 4,000 ids that is
// `SQLITE_TOOBIG: statement too long`. So the cap was not a policy, it was
// a crash nobody could reach.
//
// The first version of this file proved it by importing 4,000 and 10,000
// lines. Both passed locally and both failed in CI with "table cards
// already exists" — writing that much left the per-test storage unable to
// roll back. A test that only passes on my laptop is worse than no test,
// so the two halves are proved separately and cheaply: the cap is gone at
// 1,100 lines, and the chunking is checked directly.

describe('an import has no line limit', () => {
  // A DRY RUN, deliberately, and this is the third attempt at this test.
  //
  // It first imported 4,000 and 10,000 lines for real. Both passed here and
  // both failed in CI with "table cards already exists" — the per-test
  // isolated storage could not roll back that many writes. Cut to 1,100
  // real writes, CI failed differently: "Network connection lost" out of
  // `updateStackedStorage`. The pool simply will not carry thousands of
  // written rows on that runner, and the suite's other large test writes
  // 250.
  //
  // Writing them was never the point. The question is whether 2,500 lines
  // are REFUSED for being 2,500 lines, and the cap that used to do that sat
  // in front of everything, dry run included. So this resolves and plans
  // 2,500 lines and writes nothing — which also puts more than ID_CHUNK ids
  // through the real lookups, where the giant-statement crash lived.
  it('plans a list far longer than the old cap, rather than refusing it', { timeout: 120000 }, async () => {
    const cards = Array.from({ length: 2500 }, (_, i) => ({
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
    const stub = stubScryfall();
    const byName = new Map(cards.map((c) => [c.name.toLowerCase(), c]));
    const wide = async (url, init) => {
      if (new URL(url).pathname === '/cards/collection') {
        const { identifiers } = JSON.parse(init.body);
        return Response.json({
          data: identifiers.map((i) => byName.get(String(i.name || '').toLowerCase())).filter(Boolean),
          not_found: [],
        });
      }
      return stub(url, init);
    };

    const before = await count('cards');
    const list = cards.map((c) => `1 ${c.name}`).join('\n');
    const r = await post('/cards/add', { list, dry_run: true }, wide);

    expect(r.status, r.body?.error).toBe(200);
    expect(r.body.dry_run).toBe(true);
    expect(r.body.resolved).toBe(2500);
    expect(r.body.failed).toBe(0);
    // A dry run that wrote something would make the whole thing a lie.
    expect(await count('cards')).toBe(before);
  });
});

// The other half, without importing a collection to get at it.
describe('allInChunks', () => {
  const uuid = (i) => `00000000-0000-4000-8000-${String(i).padStart(12, '0')}`;
  const ids = (n) => Array.from({ length: n }, (_, i) => uuid(i));

  /** Records the SQL fragment each chunk produced. */
  const spy = () => {
    const sql = [];
    return { sql, run: async (frag) => { sql.push(frag); return { results: [] }; } };
  };

  it('asks once when everything fits in one statement', async () => {
    const s = spy();
    await allInChunks(ids(10), s.run);
    expect(s.sql.length).toBe(1);
  });

  it('splits past the chunk size rather than building one giant statement', async () => {
    const s = spy();
    await allInChunks(ids(ID_CHUNK * 2 + 1), s.run);
    expect(s.sql.length).toBe(3);
  });

  it('never puts more than a chunk of ids in one statement', async () => {
    const s = spy();
    await allInChunks(ids(4500), s.run);
    for (const frag of s.sql) {
      expect(frag.split(',').length).toBeLessThanOrEqual(ID_CHUNK);
    }
  });

  // 4,500 UUIDs inlined as literals is about 171 KB, which is what blew
  // SQLITE_MAX_SQL_LENGTH. Chunked, no single statement comes near it.
  //
  // This is the one that catches a bad ID_CHUNK, and the only one: the two
  // above are written in terms of ID_CHUNK, so raising the constant makes
  // them pass vacuously. They still guard the loop's arithmetic, which is
  // a different mistake. Mutation-checked both ways — ID_CHUNK at a
  // million fails only this test.
  it('keeps every statement well under SQLite\'s length limit', async () => {
    const s = spy();
    await allInChunks(ids(4500), s.run);
    for (const frag of s.sql) expect(frag.length).toBeLessThan 
      (200_000);
    expect(Math.max(...s.sql.map((f) => f.length))).toBeLessThan(100_000);
  });

  it('returns every chunk\'s rows as one list', async () => {
    let n = 0;
    const rows = await allInChunks(ids(ID_CHUNK * 2), async () => ({ results: [{ i: n++ }] }));
    expect(rows).toEqual([{ i: 0 }, { i: 1 }]);
  });

  it('asks nothing at all for no ids', async () => {
    const s = spy();
    expect(await allInChunks([], s.run)).toEqual([]);
    expect(s.sql).toEqual([]);
  });
});
