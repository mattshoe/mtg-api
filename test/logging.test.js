import { describe, it, expect } from 'vitest';
import { env } from 'cloudflare:test';
import {
  get, post, postAnon, postAs, call, sql, count, adminToken,
  TEST_PASSWORD, stubScryfall,
} from './helpers.js';
import {
  levelFor, eventFor, buildLogQuery, pruneLogs, logStats, RETENTION_DAYS,
} from '../src/log.js';

/** Logs are written after the response, so give waitUntil a moment. */
const settle = () => new Promise((r) => setTimeout(r, 60));

const authed = (path, token) => call(path, { method: 'GET', token });

describe('requests are logged', () => {
  it('records one row per request with the useful fields', async () => {
    await get('/schema');
    await settle();
    const rows = await sql("SELECT * FROM logs WHERE path = '/schema' ORDER BY id DESC LIMIT 1");
    expect(rows).toHaveLength(1);
    const r = rows[0];
    expect(r.method).toBe('GET');
    expect(r.status).toBe(200);
    expect(r.event).toBe('schema');
    expect(r.ts).toMatch(/^\d{4}-\d{2}-\d{2}T/);
    expect(r.ms).toBeGreaterThanOrEqual(0);
  });

  it('classifies levels by outcome', async () => {
    await get('/schema');                                            // read -> debug
    await postAnon('/query', { sql: 'SELECT * FROM nope' });          // 400 -> warn
    await post('/query', { sql: 'DELETE FROM deck_notes WHERE 0' });  // write -> info
    await settle();
    const byPath = async (p, status) => (await sql(
      'SELECT level FROM logs WHERE path = ? AND status = ? ORDER BY id DESC LIMIT 1', p, status,
    ))[0]?.level;
    expect(await byPath('/schema', 200)).toBe('debug');
    expect(await byPath('/query', 400)).toBe('warn');
    expect(await byPath('/query', 200)).toBe('info');
  });

  it('records what a query did without the whole result', async () => {
    await postAnon('/query', { sql: 'SELECT name FROM cards LIMIT 3' });
    await settle();
    const [r] = await sql("SELECT detail FROM logs WHERE path='/query' ORDER BY id DESC LIMIT 1");
    const d = JSON.parse(r.detail);
    expect(d.sql).toContain('SELECT name FROM cards');
    expect(d.n).toBe(3);
  });

  it('records an add with its counts', async () => {
    await post('/cards/add', { list: '2 Lightning Bolt (2X2) 117' }, stubScryfall());
    await settle();
    const [r] = await sql("SELECT * FROM logs WHERE path='/cards/add' ORDER BY id DESC LIMIT 1");
    const d = JSON.parse(r.detail);
    expect(d.resolved).toBe(1);
    expect(d.applied).toBe(true);
    expect(d.lines).toBe(1);
    expect(r.admin).toBe(1);
    expect(r.level).toBe('info');
  });

  it('does not log successful reads of the log itself', async () => {
    const token = await adminToken();
    await authed('/logs?limit=5', token);
    await authed('/logs/stats', token);
    await settle();
    expect(await count('logs', "path LIKE '/logs%' AND status < 400")).toBe(0);
  });

  it('but does log a refused attempt to read it', async () => {
    // Someone trying the log without a token is the one thing in here
    // genuinely worth keeping.
    await get('/logs');
    await settle();
    const [r] = await sql("SELECT * FROM logs WHERE path='/logs' ORDER BY id DESC LIMIT 1");
    expect(r.status).toBe(401);
    expect(r.level).toBe('warn');
  });
});

describe('nothing secret reaches the log', () => {
  it('a successful unlock records the event, never the password', async () => {
    await postAnon('/admin', { password: TEST_PASSWORD });
    await settle();
    const [r] = await sql("SELECT * FROM logs WHERE path='/admin' ORDER BY id DESC LIMIT 1");
    expect(r.message).toBe('admin unlocked');
    expect(`${r.detail} ${r.message}`).not.toContain(TEST_PASSWORD);
  });

  it('a failed unlock is a warning worth seeing', async () => {
    await postAnon('/admin', { password: 'guessing' });
    await settle();
    const [r] = await sql("SELECT * FROM logs WHERE path='/admin' ORDER BY id DESC LIMIT 1");
    expect(r.level).toBe('warn');
    expect(r.status).toBe(401);
    expect(`${r.detail} ${r.message}`).not.toContain('guessing');
  });

  it('no row anywhere contains the password or a bearer token', async () => {
    const token = await adminToken();
    await post('/cards/add', { list: '1 Lightning Bolt (2X2) 117' }, stubScryfall());
    await postAnon('/admin', { password: TEST_PASSWORD });
    await authed('/schema', token);
    await settle();
    const all = await sql('SELECT ts, message, detail, path FROM logs');
    const blob = JSON.stringify(all);
    expect(blob).not.toContain(TEST_PASSWORD);
    expect(blob).not.toContain(token);
  });

  it('redacts a credential-shaped key if one ever reaches detail', async () => {
    const { writeEntry, newEntry } = await import('../src/log.js');
    const entry = newEntry(new Request('https://x.test/probe'));
    entry.detail = { password: 'hunter2', nested: { token: 'abc' }, safe: 'keep' };
    await writeEntry(env, entry, 200);
    const [r] = await sql("SELECT detail FROM logs WHERE path='/probe' ORDER BY id DESC LIMIT 1");
    const d = JSON.parse(r.detail);
    expect(d.password).toBe('[redacted]');
    expect(d.nested.token).toBe('[redacted]');
    expect(d.safe).toBe('keep');
  });
});

describe('the log is admin-only', () => {
  it('GET /logs needs a token', async () => {
    const r = await get('/logs');
    expect(r.status).toBe(401);
    expect(r.body.admin_required).toBe(true);
  });

  it('GET /logs/stats needs a token', async () => {
    expect((await get('/logs/stats')).status).toBe(401);
  });

  it('reading the logs table through /query needs a token too', async () => {
    // Gating the endpoint but not the table would leave the data one
    // SELECT away, since ordinary reads are open.
    for (const sqlText of [
      'SELECT * FROM logs',
      'select ip from LOGS limit 1',
      'SELECT COUNT(*) FROM logs',
      'WITH x AS (SELECT * FROM logs) SELECT * FROM x',
    ]) {
      const r = await postAnon('/query', { sql: sqlText });
      expect(r.status, `${sqlText} was allowed`).toBe(401);
    }
    const g = await get(`/query?sql=${encodeURIComponent('SELECT * FROM logs')}`);
    expect(g.status).toBe(401);
  });

  it('SQL it cannot prove is read-only is treated as a write', async () => {
    // Fail-safe: a statement the parser cannot classify asks for a token
    // rather than being waved through.
    const r = await postAnon('/query', { sql: 'SELEC 1' });
    expect(r.status).toBe(401);
  });

  it('a card called "logs" does not trip the gate', async () => {
    const r = await postAnon('/query', {
      sql: "SELECT COUNT(*) FROM cards WHERE name = 'logs'",
    });
    expect(r.status).toBe(200);
  });

  it('with a token the log reads fine', async () => {
    const token = await adminToken();
    const r = await authed('/logs?limit=5', token);
    expect(r.status).toBe(200);
    expect(Array.isArray(r.body.rows)).toBe(true);
    expect(r.body).toHaveProperty('total');
  });
});

describe('filtering', () => {
  const params = (s) => new URLSearchParams(s);

  it('min level includes that level and above', () => {
    const q = buildLogQuery(params('min=warn'));
    expect(q.rows.args).toEqual(['warn', 'error']);
  });

  it('explicit levels win over min', () => {
    const q = buildLogQuery(params('level=debug&min=error'));
    expect(q.rows.args).toEqual(['debug']);
  });

  it('status=error means any 4xx or 5xx', () => {
    expect(buildLogQuery(params('status=error')).rows.sql).toContain('status >= 400');
  });

  it('status=5xx is a range', () => {
    const q = buildLogQuery(params('status=5xx'));
    expect(q.rows.args).toEqual([500, 600]);
  });

  it('since takes hours', () => {
    const q = buildLogQuery(params('since=24'));
    expect(q.rows.args[0]).toMatch(/^\d{4}-\d{2}-\d{2}T/);
  });

  it('free text searches the fields worth searching', () => {
    const q = buildLogQuery(params('q=bolt'));
    expect(q.rows.sql).toMatch(/path LIKE .*message LIKE .*detail LIKE/s);
    expect(q.rows.args).toEqual(['%bolt%', '%bolt%', '%bolt%', '%bolt%', '%bolt%']);
  });

  it('caps the page size', () => {
    expect(buildLogQuery(params('limit=99999')).limit).toBe(500);
    expect(buildLogQuery(params('limit=-4')).limit).toBe(1);
  });

  it('actually filters end to end', async () => {
    const token = await adminToken();
    await postAnon('/query', { sql: 'SELECT * FROM nope' });   // a 400
    await get('/schema');                             // a 200
    await settle();

    const errs = await authed('/logs?status=error', token);
    expect(errs.body.rows.length).toBeGreaterThan(0);
    expect(errs.body.rows.every((r) => r.status >= 400)).toBe(true);

    const byEvent = await authed('/logs?event=schema', token);
    expect(byEvent.body.rows.every((r) => r.event === 'schema')).toBe(true);

    const search = await authed(`/logs?q=${encodeURIComponent('FROM nope')}`, token);
    expect(search.body.rows.length).toBeGreaterThan(0);
  });
});

describe('stats', () => {
  it('summarises the window', async () => {
    await get('/schema');
    await postAnon('/query', { sql: 'SELECT * FROM nope' });
    await settle();
    const s = await logStats(env.DB, 24);
    expect(s.retention_days).toBe(RETENTION_DAYS);
    expect(s.total).toBeGreaterThan(0);
    expect(s.by_level.map((l) => l.level)).toContain('warn');
    expect(s.by_event.length).toBeGreaterThan(0);
    expect(s.slowest.length).toBeGreaterThan(0);
  });
});

describe('retention', () => {
  it('prunes rows past the window and keeps the rest', async () => {
    const old = new Date(Date.now() - 9 * 86400_000).toISOString();
    const fresh = new Date().toISOString();
    await env.DB.batch([
      env.DB.prepare("INSERT INTO logs (ts, level, event) VALUES (?, 'info', 'old')").bind(old),
      env.DB.prepare("INSERT INTO logs (ts, level, event) VALUES (?, 'info', 'fresh')").bind(fresh),
    ]);
    const out = await pruneLogs(env.DB, RETENTION_DAYS);
    expect(out.removed).toBe(1);
    expect(await count('logs', "event = 'old'")).toBe(0);
    expect(await count('logs', "event = 'fresh'")).toBe(1);
  });

  it('a week is the window', () => {
    expect(RETENTION_DAYS).toBe(7);
  });
});

describe('logging never breaks the request', () => {
  it('a broken log table does not fail the response', async () => {
    await env.DB.prepare('DROP TABLE logs').run();
    const r = await get('/schema');
    expect(r.status).toBe(200);
    // put it back for whatever runs next in this test's transaction
    await env.DB.prepare(
      `CREATE TABLE logs (id INTEGER PRIMARY KEY AUTOINCREMENT, ts TEXT NOT NULL,
        level TEXT NOT NULL, event TEXT, method TEXT, path TEXT, status INTEGER,
        ms INTEGER, message TEXT, detail TEXT, ip TEXT, country TEXT, ray TEXT, admin INTEGER)`,
    ).run();
  });
});

describe('helpers', () => {
  it('levelFor maps outcome to level', () => {
    expect(levelFor(200)).toBe('debug');
    expect(levelFor(200, { write: true })).toBe('info');
    expect(levelFor(404)).toBe('warn');
    expect(levelFor(500)).toBe('error');
  });

  it('eventFor makes a stable slug', () => {
    expect(eventFor('GET', '/schema')).toBe('schema');
    expect(eventFor('POST', '/cards/add')).toBe('cards.add');
    expect(eventFor('GET', '/')).toBe('root');
  });
});
