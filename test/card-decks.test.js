import { describe, it, expect, beforeEach } from 'vitest';
import { env } from 'cloudflare:test';
import cases from './fixtures/core-sql.json';
import { sql } from './helpers.js';

// The card page's "In decks", as the Kotlin core sends it (see
// CoreSqlDump.kt), against the seed. Matt: "the 'in decks' section keeps
// showing decks from other peoples fucking collection". The query had no
// owner at all, so Matt's Sol Ring listed Kayla's decks beside his own.
//
// CoreSqlDump writes the card page's queries for m4tt0001, so this is his
// page. Kayla's Valgavoth deck (id 20) gets a Sol Ring here, because the
// seed gives her none and a leak needs something to leak.

async function inDecks(name) {
  const c = cases.find((x) => x.name === name);
  expect(c, `core-sql.json has no case "${name}"`).toBeTruthy();
  expect(c.sql, `"${name}" is not the In decks query`).toContain('FROM deck_cards dc');
  return (await env.DB.prepare(c.sql).bind(...c.params).all()).results;
}

describe("the card page's In decks", () => {
  beforeEach(async () => {
    await sql(
      `INSERT INTO deck_cards (deck_id, qty, name, name_norm, raw_name, role, section, in_collection)
       VALUES (20, 1, 'Sol Ring', 'sol ring', 'Sol Ring', 'spell', 'Artifacts', 1)`,
    );
  });

  it("names Matt's decks on Matt's page and none of Kayla's", async () => {
    const [kayla] = await sql('SELECT name FROM decks WHERE id = 20');
    const rows = await inDecks('card detail 2');
    const names = rows.map((r) => r.name);
    expect(rows.length, 'none of his own decks came back').toBeGreaterThan(0);
    expect(names, "Kayla's deck is listed on Matt's card page").not.toContain(kayla.name);
    expect(new Set(rows.map((r) => r.owner)), 'a deck that is not his came back').toEqual(new Set(['m4tt0001']));
  });

  it('names nobody\'s decks while nobody has said whose collection it is', async () => {
    expect(await inDecks('card detail decks, collection not known yet')).toEqual([]);
  });

  it('names every collection\'s decks for a visitor the server has said is nobody', async () => {
    const owners = new Set((await inDecks('card detail decks, every collection')).map((r) => r.owner));
    expect(owners).toEqual(new Set(['m4tt0001', 'k4yy0003']));
  });
});
