import { describe, it, expect } from 'vitest';
import { post, postAnon, get, sql, count, snapshot, stubScryfall } from './helpers.js';
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

describe('GET /query', () => {
  // Claude desktop and mobile can fetch a URL but cannot POST, so the whole
  // collection would be unreachable from there without this.
  it('runs a SELECT from the query string', async () => {
    const r = await get('/query?sql=' + encodeURIComponent(
      "SELECT name, qty FROM cards WHERE name_norm = 'arcane signet' ORDER BY id LIMIT 2",
    ));
    expect(r.status).toBe(200);
    expect(r.body.cols).toEqual(['name', 'qty']);
    expect(r.body.rows.length).toBeGreaterThan(0);
  });

  it('honours fmt and limit', async () => {
    const tsv = await get('/query?fmt=tsv&sql=' + encodeURIComponent('SELECT name FROM cards LIMIT 2'));
    expect(tsv.headers.get('content-type')).toMatch(/tab-separated/);
    expect(tsv.text.split('\n')).toHaveLength(3);

    const capped = await get('/query?limit=3&sql=' + encodeURIComponent('SELECT id FROM cards'));
    expect(capped.body.n).toBe(3);
    expect(capped.body.truncated).toBe(3);
  });

  it('accepts bound params as a JSON array', async () => {
    const r = await get('/query?params=' + encodeURIComponent('["kayla"]')
      + '&sql=' + encodeURIComponent('SELECT COUNT(*) FROM cards WHERE owner = ?'));
    expect(r.status).toBe(200);
    expect(r.body.rows[0][0]).toBe(await count('cards', 'owner = ?', 'kayla'));
  });

  it('rejects params that are not a JSON array', async () => {
    const r = await get('/query?params=nope&sql=' + encodeURIComponent('SELECT 1'));
    expect(r.status).toBe(400);
  });

  it('refuses to write, and the write does not happen', async () => {
    const before = await count('deck_notes');
    for (const sql of [
      "INSERT INTO deck_notes (deck_id, section, body) VALUES (1,'x','y')",
      'DELETE FROM deck_notes',
      'UPDATE cards SET qty = 0',
      'DROP TABLE cards',
      'CREATE TABLE evil (a)',
      "ATTACH DATABASE 'x' AS y",
    ]) {
      const r = await get(`/query?sql=${encodeURIComponent(sql)}`);
      expect(r.status, `${sql} was not refused`).toBe(405);
      expect(r.body.error).toMatch(/read-only/);
    }
    expect(await count('deck_notes')).toBe(before);
    expect(await count('cards', 'qty = 0')).toBe(0);
  });

  it('is not fooled by a mutating word inside a string literal', async () => {
    // A card actually named "Delete the Evidence" must still be searchable.
    const r = await get('/query?sql=' + encodeURIComponent(
      "SELECT COUNT(*) FROM cards WHERE name = 'Delete the Evidence'",
    ));
    expect(r.status).toBe(200);
    expect(r.body.rows[0][0]).toBe(0);
  });

  it('allows a CTE and an EXPLAIN', async () => {
    const cte = await get('/query?sql=' + encodeURIComponent(
      'WITH x AS (SELECT id FROM cards LIMIT 2) SELECT * FROM x',
    ));
    expect(cte.status).toBe(200);
    expect(cte.body.n).toBe(2);
  });

  it('still rejects two statements', async () => {
    const r = await get('/query?sql=' + encodeURIComponent('SELECT 1; SELECT 2'));
    expect([400, 405]).toContain(r.status);
  });

  it('POST is still allowed to write', async () => {
    const r = await post('/query', { sql: 'DELETE FROM deck_notes' });
    expect(r.status).toBe(200);
    expect(await count('deck_notes')).toBe(0);
  });
});

// ------------------------------------------------- read-only over GET
//
// The guard used to be a keyword blocklist over the raw statement, which got
// it wrong in both directions: it refused reads that merely contained one of
// the words, and it only caught a write hidden behind a CTE by accident. It
// asks SQLite now — EXPLAIN compiles the statement and hands back its
// program without running it, and a program that opens a cursor for writing
// is not a read.

const getSql = (sql, extra = '') =>
  get(`/query?sql=${encodeURIComponent(sql)}${extra}`);

describe('GET /query — reads that must be allowed', () => {
  const reads = [
    ['a join',
      "SELECT c.name, u.free FROM cards c JOIN card_usage u ON u.name_norm = c.name_norm"
      + " AND u.owner = c.owner WHERE c.owner = 'matt' LIMIT 5"],
    ['a subquery',
      "SELECT name FROM cards WHERE owner = 'matt' AND name_norm IN"
      + " (SELECT name_norm FROM deck_cards) LIMIT 5"],
    ['a scalar function',
      "SELECT name FROM cards WHERE substr(type_line, 1, 8) = 'Artifact' LIMIT 5"],
    // The one the blocklist actually broke: replace() is a string function,
    // and `\breplace\b` matched it.
    ['replace(), which is a function and not a statement',
      "SELECT replace(name, 'a', 'b') AS x FROM cards LIMIT 2"],
    ['a CTE', 'WITH t AS (SELECT name FROM cards LIMIT 3) SELECT * FROM t'],
    ['a nested CTE feeding a join',
      'WITH t AS (SELECT name_norm FROM cards LIMIT 5)'
      + ' SELECT c.name FROM cards c JOIN t ON t.name_norm = c.name_norm LIMIT 5'],
    ['GROUP BY and DISTINCT, which fill ephemeral tables internally',
      'SELECT DISTINCT rarity, COUNT(*) AS n FROM cards GROUP BY rarity ORDER BY n DESC'],
    ['EXPLAIN QUERY PLAN', 'EXPLAIN QUERY PLAN SELECT name FROM cards'],
    ['VALUES', 'VALUES (1, 2)'],
    ['a literal containing the word delete',
      "SELECT COUNT(*) AS n FROM cards WHERE name = 'Delete the Evidence'"],
    ['a compound select',
      'SELECT name FROM cards UNION SELECT name FROM deck_cards LIMIT 5'],
  ];

  for (const [label, sql] of reads) {
    it(`allows ${label}`, async () => {
      const r = await getSql(sql);
      expect(r.status, JSON.stringify(r.body)).toBe(200);
    });
  }
});

describe('GET /query — writes that must stay refused', () => {
  const writes = [
    ['INSERT', "INSERT INTO tags (slug, kind, label) VALUES ('x', 'y', 'z')"],
    ['UPDATE', "UPDATE cards SET qty = 99 WHERE owner = 'matt'"],
    ['DELETE', 'DELETE FROM cards'],
    ['DROP', 'DROP TABLE cards'],
    ['CREATE', 'CREATE TABLE evil (a TEXT)'],
    ['ALTER', 'ALTER TABLE cards ADD COLUMN evil TEXT'],
    ['ATTACH', "ATTACH DATABASE 'x.db' AS x"],
    ['REINDEX', 'REINDEX'],
    ['a PRAGMA write', 'PRAGMA writable_schema = 1'],
    // The one a word list only catches by luck: it opens with WITH.
    ['a write hidden behind a CTE',
      "WITH t AS (SELECT 'x' AS s) INSERT INTO tags (slug, kind, label)"
      + " SELECT s, 'k', 'l' FROM t"],
    ['a delete hidden behind a CTE',
      'WITH t AS (SELECT id FROM cards LIMIT 1) DELETE FROM cards WHERE id IN (SELECT id FROM t)'],
  ];

  for (const [label, sql] of writes) {
    it(`refuses ${label}, and changes nothing`, async () => {
      const before = await snapshot();
      const r = await getSql(sql);
      expect(r.status, JSON.stringify(r.body)).toBe(405);
      expect(r.body.error).toMatch(/read-only/);
      expect(await snapshot()).toEqual(before);
    });
  }

  it('says why, not just no', async () => {
    const r = await getSql('DELETE FROM cards');
    expect(r.body.error).toMatch(/only SELECT, WITH, VALUES and EXPLAIN/);
  });

  it('names the write it found when the shape looked like a read', async () => {
    const r = await getSql(
      "WITH t AS (SELECT 'x' AS s) INSERT INTO tags (slug, kind, label) SELECT s, 'k', 'l' FROM t",
    );
    expect(r.body.error).toMatch(/writes to the database/);
    expect(r.body.error).toMatch(/OpenWrite/);
  });
});

describe('GET /query — errors that explain themselves', () => {
  it('reports a syntax error against the caller\'s own SQL, not the wrapper', async () => {
    // The limit wrap made this come back as `near ")": syntax error at
    // offset 23` — a bracket the caller never typed.
    const r = await getSql('SELECT');
    expect(r.status).toBe(400);
    expect(r.body.error).not.toMatch(/near "\)"/);
    expect(r.body.error).toMatch(/incomplete input|syntax error/i);
  });

  it('spots a query truncated by an unencoded space and says so', async () => {
    const r = await getSql('SELECT');
    expect(r.body.error).toMatch(/truncated/);
    expect(r.body.error).toMatch(/URL-encode/);
  });

  it('still says when the sql parameter is missing entirely', async () => {
    const r = await get('/query');
    expect(r.status).toBe(400);
    expect(r.body.error).toBe('sql is required');
  });

  it('reports an unknown column against the real statement', async () => {
    const r = await getSql('SELECT nope FROM cards');
    expect(r.status).toBe(400);
    expect(r.body.error).toMatch(/no such column: nope/);
  });
});

describe('POST /query — the auth gate follows the same truth', () => {
  it('lets an anonymous read use replace() without demanding a token', async () => {
    const r = await postAnon('/query', { sql: "SELECT replace(name,'a','b') AS x FROM cards LIMIT 1" });
    expect(r.status, JSON.stringify(r.body)).toBe(200);
  });

  it('still demands a token for a write hidden behind a CTE', async () => {
    const before = await snapshot();
    const r = await postAnon('/query', {
      sql: "WITH t AS (SELECT 'x' AS s) INSERT INTO tags (slug, kind, label) SELECT s, 'k', 'l' FROM t",
    });
    expect(r.status).toBe(401);
    expect(await snapshot()).toEqual(before);
  });

  it('still demands a token for a plain write', async () => {
    const r = await postAnon('/query', { sql: 'DELETE FROM cards' });
    expect(r.status).toBe(401);
  });
});

describe('the test harness itself', () => {
  it('hands the Worker a stub, never the real fetch', async () => {
    // Guards the thing that actually broke: a call with no stub used to get
    // real network access. Now the default is the stub, so an add that the
    // stub cannot resolve fails on the card, not on a timeout.
    const r = await post('/cards/add', { list: '1 Definitely Not A Real Card', dry_run: true });
    expect(r.status).toBe(200);
    expect(r.body.errors.join(' ')).toMatch(/no card named/);
  });

  it('a deliberately offline stub fails fast and writes nothing', async () => {
    const before = await count('cards', "name_norm = 'sol ring'");
    const r = await post('/cards/add', { list: '1 Sol Ring' }, stubScryfall({ fail: true }));
    expect(r.status).toBeGreaterThanOrEqual(400);
    expect(await count('cards', "name_norm = 'sol ring'")).toBe(before);
  });
});
