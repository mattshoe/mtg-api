import { describe, it, expect } from 'vitest';
import { env } from 'cloudflare:test';
import cases from './fixtures/core-sql.json';
import { sql } from './helpers.js';

// The Fetch list's query, as the Kotlin core sends it (see CoreSqlDump.kt),
// against the seed. Matt: "if there are no entries in the bulk for a card,
// then show which deck it belongs to". The query answers how many copies
// real decks hold and which decks they are, and it has to count the way
// `card_usage` does or the list names a deck that bulk does not agree with.

async function rows() {
  const c = cases.find((x) => x.name === 'fetch list');
  expect(c, 'core-sql.json has no case "fetch list"').toBeTruthy();
  return (await env.DB.prepare(c.sql).bind(...c.params).all()).results;
}

describe('the Fetch list query', () => {
  it('counts the copies in decks the same as card_usage does', async () => {
    const found = await rows();
    expect(found.length).toBeGreaterThan(0);
    for (const r of found) {
      const [u] = await sql('SELECT in_decks FROM card_usage WHERE owner_id = 1 AND name_norm = ?', r.name_norm);
      expect(r.in_decks, `${r.name}: the list and card_usage disagree on what decks hold`).toBe(u?.in_decks ?? 0);
    }
  });

  it('names every real deck a card is in, and no proxy deck', async () => {
    const found = await rows();
    // Arcane Signet is in both of his precons, and in deck 4 as well,
    // a proxy build. A proxy holds no real card, so it is not named.
    const signet = found.find((r) => r.name_norm === 'arcane signet');
    expect(signet, 'Arcane Signet did not come back').toBeTruthy();
    const names = JSON.parse(signet.decks);
    const [chaos] = await sql('SELECT name FROM decks WHERE id = 1');
    const [elven] = await sql('SELECT name FROM decks WHERE id = 2');
    const [proxy] = await sql('SELECT name FROM decks WHERE id = 4');
    expect(names).toContain(chaos.name);
    expect(names).toContain(elven.name);
    expect(names, 'a proxy deck is named as holding a real card').not.toContain(proxy.name);
  });

  it('names no deck for a card no deck holds', async () => {
    const free = (await rows()).filter((r) => r.in_decks === 0);
    for (const r of free) expect(JSON.parse(r.decks), r.name).toEqual([]);
  });
});
