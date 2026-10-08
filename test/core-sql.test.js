import { describe, it, expect } from 'vitest';
import { env } from 'cloudflare:test';
import cases from './fixtures/core-sql.json';

// Every statement the Kotlin core can send, run against the real schema.
//
// The Kotlin suite asserts on SQL *text*, which is blind to the one thing
// only a database knows: whether the columns exist. `DeckQueries.cards`
// selected `SUM(t.qty)` from `totals` — whose column is `total_qty`, and
// which is already grouped — and 500-odd tests agreed it was fine while
// opening any deck in the app answered "no such column: t.qty".
//
// `apps/core/src/jvmTest/.../CoreSqlDump.kt` writes the fixture and fails if
// it has drifted, so the two halves stay in step without either build having
// to run the other.

describe('every statement the core emits', () => {
  it('has statements to run at all', () => {
    expect(cases.length).toBeGreaterThan(30);
  });

  // One test per statement, so a failure names the query rather than the file.
  for (const c of cases) {
    it(`runs: ${c.name}`, async () => {
      const r = await env.DB.prepare(c.sql).bind(...c.params).all();
      // A statement that runs returns results, even if empty. What is
      // being asserted is that SQLite accepted it.
      expect(Array.isArray(r.results), `${c.name} returned no result set`).toBe(true);
    });
  }

  it('binds exactly as many values as each statement has holes', () => {
    // The other half of the same failure: a statement can be valid SQL and
    // still be sent the wrong number of parameters. SQLite says so at bind
    // time, which is after every Kotlin assertion has passed.
    const wrong = [];
    for (const c of cases) {
      const holes = (c.sql.match(/\?/g) || []).length;
      if (holes !== c.params.length) {
        wrong.push(`${c.name}: ${holes} holes, ${c.params.length} params`);
      }
    }
    expect(wrong).toEqual([]);
  });
});

describe('the card page\'s facts, against the seed', () => {
  // `CardFacts.query` reads six child tables. Joined rather than
  // subqueried, a card with two keywords and two finishes comes back
  // as four rows and every list on the page doubles. Aetherblade Agent
  // has two of each in the seed.
  const c = cases.find((x) => x.name === 'card facts, a transform card with two keywords and two finishes');

  it('is in the dump', () => {
    expect(c, 'CoreSqlDump never wrote the facts case').toBeTruthy();
  });

  it('is one row, with every list said once', async () => {
    const r = await env.DB.prepare(c.sql).bind(...c.params).all();
    expect(r.results.length).toBe(1);
    const row = r.results[0];
    expect(row.keywords).toBe('Deathtouch, Transform');
    expect(row.finishes).toBe('foil, nonfoil');
    expect(row.games).toBe('arena, mtgo, paper');
    expect(row.artist).toBe('Alexander Mokhov');
    expect(row.layout).toBe('transform');
    expect(row.edhrec_rank).toBe(20135);
    expect(row.tags).toContain('draw engine');
  });

  it('carries every column of cards', async () => {
    const r = await env.DB.prepare(c.sql).bind(...c.params).all();
    const cols = (await env.DB.prepare('PRAGMA table_info(cards)').all()).results.map((x) => x.name);
    const missing = cols.filter((n) => !(n in r.results[0]));
    expect(missing).toEqual([]);
  });
});
