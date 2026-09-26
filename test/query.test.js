import { describe, it, expect } from 'vitest';
import { post, sql, count } from './helpers.js';
import { isSingleStatement } from '../src/query.js';

describe('POST /query response shapes', () => {
  it('rows: columns once, rows as arrays', async () => {
    const r = await post('/query', {
      sql: "SELECT name, qty FROM cards WHERE name_norm = 'arcane signet' ORDER BY id",
    });
    expect(r.status).toBe(200);
    expect(r.body.cols).toEqual(['name', 'qty']);
    expect(Array.isArray(r.body.rows[0])).toBe(true);
    expect(r.body.n).toBe(r.body.rows.length);
  });

  it('objects: keyed objects', async () => {
    const r = await post('/query', {
      sql: "SELECT name, qty FROM cards WHERE name_norm = 'arcane signet' LIMIT 1",
      fmt: 'objects',
    });
    expect(r.body.rows[0]).toHaveProperty('name');
    expect(r.body.rows[0]).toHaveProperty('qty');
    expect(r.body.cols).toBeUndefined();
  });

  it('tsv: a header line plus one line per row', async () => {
    const r = await post('/query', {
      sql: "SELECT name, qty FROM cards WHERE name_norm = 'arcane signet' ORDER BY id",
      fmt: 'tsv',
    });
    expect(r.headers.get('content-type')).toMatch(/tab-separated/);
    const lines = r.text.split('\n');
    expect(lines[0]).toBe('name\tqty');
    expect(lines).toHaveLength(1 + (await count('cards', "name_norm = 'arcane signet'")));
  });

  it('tsv escapes tabs and newlines so a row stays one line', async () => {
    const r = await post('/query', {
      sql: "SELECT 'a\tb' AS x, 'c" + String.fromCharCode(10) + "d' AS y",
      fmt: 'tsv',
    });
    expect(r.text.split('\n')).toHaveLength(2);
    expect(r.text).toContain('a\\tb');
    expect(r.text).toContain('c\\nd');
  });

  it('rejects an unknown fmt', async () => {
    const r = await post('/query', { sql: 'SELECT 1', fmt: 'xml' });
    expect(r.status).toBe(400);
    expect(r.body.error).toMatch(/unknown fmt/);
  });
});

describe('POST /query limits', () => {
  it('caps a bare SELECT at the default and says it truncated', async () => {
    const r = await post('/query', { sql: 'SELECT id FROM cards', limit: 3 });
    expect(r.body.n).toBe(3);
    expect(r.body.truncated).toBe(3);
  });

  it('does not flag truncation when everything fit', async () => {
    const r = await post('/query', { sql: 'SELECT id FROM cards LIMIT 2' });
    expect(r.body.n).toBe(2);
    expect(r.body.truncated).toBeUndefined();
  });

  it("respects the statement's own LIMIT", async () => {
    const r = await post('/query', { sql: 'SELECT id FROM cards LIMIT 2', limit: 100 });
    expect(r.body.n).toBe(2);
  });

  it('rejects a nonsense limit', async () => {
    expect((await post('/query', { sql: 'SELECT 1', limit: 0 })).status).toBe(400);
    expect((await post('/query', { sql: 'SELECT 1', limit: -5 })).status).toBe(400);
    expect((await post('/query', { sql: 'SELECT 1', limit: 1.5 })).status).toBe(400);
  });

  it('does not wrap a write in a LIMIT', async () => {
    const r = await post('/query', {
      sql: "UPDATE cards SET qty = qty WHERE name_norm = 'arcane signet'",
    });
    expect(r.status).toBe(200);
    expect(r.body.changes).toBeGreaterThan(0);
  });
});

describe('POST /query writes', () => {
  it('inserts and the row is there afterwards', async () => {
    const r = await post('/query', {
      sql: "INSERT INTO deck_notes (deck_id, section, body) VALUES (?, ?, ?)",
      params: [1, 'Gameplan', 'beat down'],
    });
    expect(r.status).toBe(200);
    expect(r.body.changes).toBe(1);
    const rows = await sql("SELECT body FROM deck_notes WHERE section = 'Gameplan' AND body = 'beat down'");
    expect(rows).toHaveLength(1);
  });

  it('deletes', async () => {
    const before = await count('deck_notes');
    await post('/query', { sql: 'DELETE FROM deck_notes' });
    expect(await count('deck_notes')).toBe(0);
    expect(before).toBeGreaterThanOrEqual(0);
  });
});

describe('POST /query errors', () => {
  it('a syntax error is a 400 carrying the SQLite message', async () => {
    const r = await post('/query', { sql: 'SELEC 1' });
    expect(r.status).toBe(400);
    expect(r.body.error).toMatch(/SELEC|syntax/i);
    expect(r.body.error).not.toMatch(/^D1_/);
  });

  it('an unknown table is a 400, not a 500', async () => {
    const r = await post('/query', { sql: 'SELECT * FROM nope' });
    expect(r.status).toBe(400);
    expect(r.body.error).toMatch(/nope/);
  });

  it('missing sql is a 400', async () => {
    expect((await post('/query', {})).status).toBe(400);
    expect((await post('/query', { sql: '   ' })).status).toBe(400);
  });

  it('a malformed body is a 400', async () => {
    const r = await post('/query', undefined, undefined);
    expect(r.status).toBe(400);
  });

  it('a parameter count mismatch is a 400', async () => {
    const r = await post('/query', { sql: 'SELECT * FROM cards WHERE id = ? AND qty = ?', params: [1] });
    expect(r.status).toBe(400);
  });
});

describe('single-statement enforcement', () => {
  it('accepts one statement, with or without a trailing semicolon', () => {
    expect(isSingleStatement('SELECT 1')).toBe(true);
    expect(isSingleStatement('SELECT 1;')).toBe(true);
    expect(isSingleStatement('SELECT 1;  \n ')).toBe(true);
  });

  it('accepts a semicolon inside a string or a comment', () => {
    expect(isSingleStatement("SELECT ';' AS x")).toBe(true);
    expect(isSingleStatement("SELECT 1 -- ; not a statement")).toBe(true);
    expect(isSingleStatement('SELECT 1 /* ; */')).toBe(true);
    expect(isSingleStatement("SELECT 'it''s; fine'")).toBe(true);
  });

  it('rejects two statements', () => {
    expect(isSingleStatement('SELECT 1; DROP TABLE cards')).toBe(false);
    expect(isSingleStatement('SELECT 1;SELECT 2')).toBe(false);
  });

  it('the endpoint refuses them', async () => {
    const r = await post('/query', { sql: 'SELECT 1; SELECT 2' });
    expect(r.status).toBe(400);
    expect(r.body.error).toMatch(/one statement/);
  });
});

describe('POST /query value fidelity', () => {
  it('an empty result is an empty rows array, never null', async () => {
    const r = await post('/query', { sql: "SELECT name FROM cards WHERE name = 'nonexistent'" });
    expect(r.body.rows).toEqual([]);
    expect(r.body.n).toBe(0);
    expect(r.body.cols).toEqual([]);
  });

  it('NULL survives as JSON null', async () => {
    const r = await post('/query', { sql: 'SELECT NULL AS x' });
    expect(r.body.rows[0][0]).toBeNull();
  });

  it('cmc stays a number', async () => {
    const r = await post('/query', {
      sql: "SELECT cmc FROM cards WHERE name_norm = 'arcane signet' LIMIT 1",
    });
    expect(typeof r.body.rows[0][0]).toBe('number');
  });

  it('an apostrophe round-trips intact', async () => {
    const r = await post('/query', {
      sql: 'SELECT name FROM cards WHERE name_norm = ?',
      params: ["alchemist's refuge"],
    });
    expect(r.body.rows[0][0]).toBe("Alchemist's Refuge");
  });

  it('bound parameters work', async () => {
    const r = await post('/query', {
      sql: 'SELECT COUNT(*) AS n FROM cards WHERE owner = ?',
      params: ['kayla'],
    });
    expect(r.body.rows[0][0]).toBe(await count('cards', 'owner = ?', 'kayla'));
  });

  it('a CTE is treated as row-returning and still capped', async () => {
    const r = await post('/query', {
      sql: 'WITH x AS (SELECT id FROM cards) SELECT * FROM x',
      limit: 2,
    });
    expect(r.body.n).toBe(2);
    expect(r.body.truncated).toBe(2);
  });
});
