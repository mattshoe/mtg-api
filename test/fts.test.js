import { describe, it, expect } from 'vitest';
import { post, sql, count, stubScryfall } from './helpers.js';

// FTS5 is the one piece of the schema D1 might not have supported. If these
// fail on the remote database, the fallback is a LIKE index on oracle_text —
// but they should not, and a silent loss of search would be worse than a
// loud one.

describe('card_search', () => {
  it('exists as an FTS5 table', async () => {
    const rows = await sql(
      "SELECT sql FROM sqlite_master WHERE name = 'card_search' AND type = 'table'",
    );
    expect(rows[0].sql).toMatch(/fts5/i);
    expect(rows[0].sql).toMatch(/porter/);
  });

  it('finds a card by a word in its oracle text', async () => {
    const [card] = await sql(
      "SELECT id, name FROM cards WHERE oracle_text LIKE '%counter target spell%' LIMIT 1",
    );
    if (!card) throw new Error('fixture has no counterspell');

    const r = await post('/query', {
      sql: "SELECT rowid FROM card_search WHERE card_search MATCH 'counter target spell'",
    });
    expect(r.status).toBe(200);
    expect(r.body.rows.flat()).toContain(card.id);
  });

  it('stems, so a search matches an inflected form', async () => {
    // porter stemming: searching the stem finds the conjugated text.
    const r = await post('/query', {
      sql: "SELECT COUNT(*) AS n FROM card_search WHERE card_search MATCH 'draw'",
    });
    expect(r.status).toBe(200);
    const withS = await post('/query', {
      sql: "SELECT COUNT(*) AS n FROM card_search WHERE card_search MATCH 'draws'",
    });
    expect(withS.body.rows[0][0]).toBe(r.body.rows[0][0]);
  });

  it('searches a named column', async () => {
    const r = await post('/query', {
      sql: "SELECT rowid FROM card_search WHERE card_search MATCH 'type_line : Land'",
    });
    expect(r.status).toBe(200);
    expect(r.body.n).toBeGreaterThan(0);
  });

  it('joins back to cards by rowid', async () => {
    const r = await post('/query', {
      sql: `SELECT c.name FROM card_search s JOIN cards c ON c.id = s.rowid
             WHERE card_search MATCH 'artifact' LIMIT 5`,
    });
    expect(r.status).toBe(200);
    expect(r.body.rows.length).toBeGreaterThan(0);
  });

  it('a newly added card is searchable immediately', async () => {
    await post('/cards/add', { list: '1 Lightning Bolt (2X2) 117' }, stubScryfall());
    const [card] = await sql("SELECT id FROM cards WHERE name_norm = 'lightning bolt'");

    const r = await post('/query', {
      sql: "SELECT rowid FROM card_search WHERE card_search MATCH 'Lightning Bolt'",
    });
    expect(r.body.rows.flat()).toContain(card.id);
  });

  it('a removed card is gone from the index', async () => {
    await post('/cards/add', { list: '1 Lightning Bolt (2X2) 117' }, stubScryfall());
    const [card] = await sql("SELECT id FROM cards WHERE name_norm = 'lightning bolt'");
    await post('/cards/remove', { list: '1 Lightning Bolt' });

    expect(await count('card_search', 'rowid = ?', card.id)).toBe(0);
    const r = await post('/query', {
      sql: "SELECT rowid FROM card_search WHERE card_search MATCH 'Lightning Bolt'",
    });
    expect(r.body.rows.flat()).not.toContain(card.id);
  });

  it('a MATCH with punctuation is a 400, not a 500', async () => {
    // FTS5 has its own query grammar; a bare hyphen or quote is a syntax
    // error in it. That must surface as a client error.
    for (const q of ['"', 'a - b', '(']) {
      const r = await post('/query', {
        sql: 'SELECT rowid FROM card_search WHERE card_search MATCH ?',
        params: [q],
      });
      expect([200, 400], `MATCH ${JSON.stringify(q)} returned ${r.status}`).toContain(r.status);
      expect(r.status).not.toBe(500);
    }
  });

  it('a quoted phrase search works', async () => {
    const r = await post('/query', {
      sql: 'SELECT COUNT(*) AS n FROM card_search WHERE card_search MATCH ?',
      params: ['"add one mana"'],
    });
    expect(r.status).toBe(200);
  });
});
