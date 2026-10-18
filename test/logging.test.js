import { describe, it, expect } from 'vitest';
import { env, createExecutionContext, waitOnExecutionContext } from 'cloudflare:test';
import worker from '../src/index.js';
import {
  get, post, postAnon, postAs, call, sql, count, adminToken,
  TEST_PASSWORD, stubScryfall, mattSession,
} from './helpers.js';
import { newSession } from '../src/accounts.js';
import {
  levelFor, eventFor, buildLogQuery, pruneLogs, logStats, RETENTION_HOURS, LOG_PRUNE_CRON,
  newEntry, startEntry, writeEntry, IN_FLIGHT,
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
    await post('/query', { sql: 'SELECT 1 AS n' });                   // read -> debug
    await settle();
    const byPath = async (p, status) => (await sql(
      'SELECT level FROM logs WHERE path = ? AND status = ? ORDER BY id DESC LIMIT 1', p, status,
    ))[0]?.level;
    expect(await byPath('/schema', 200)).toBe('debug');
    expect(await byPath('/query', 400)).toBe('warn');
    // A read, which is all this endpoint does now. It was a write
    // here, and a write logged `info`.
    expect(await byPath('/query', 200)).toBe('debug');
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

  it('SQL it cannot prove is read-only is refused rather than waved through', async () => {
    // Fail-safe. It used to ask for a token; nothing writes here any
    // more, so what it asks for is a different statement — and a
    // statement that does not compile is told so.
    const r = await postAnon('/query', { sql: 'SELEC 1' });
    expect(r.status).toBe(400);
    expect(r.body.error).toMatch(/SELEC/);
  });

  it('a card called "logs" does not trip the gate', async () => {
    const r = await postAnon('/query', {
      sql: "SELECT COUNT(*) FROM cards WHERE name = 'logs'",
    });
    expect(r.status).toBe(200);
  });

  // Matt: "the server logs page shows no logs". The app holds a session,
  // never the operator's password, so a gate that only knew the password
  // refused every admin who opened the page.
  it('an account with the admin role reads the log with its session', async () => {
    const session = await mattSession();
    const r = await authed('/logs?limit=5', session);
    expect(r.status, `an admin's session was refused: ${JSON.stringify(r.body)}`).toBe(200);
    expect(Array.isArray(r.body.rows)).toBe(true);
    expect((await authed('/logs/stats', session)).status).toBe(200);
  });

  it('an account without it is told no, by name rather than as a stranger', async () => {
    const session = await newSession(env.DB, 3);
    const r = await authed('/logs?limit=5', session);
    expect(r.status).toBe(403);
    expect(r.body.error).toMatch(/admin/);
    expect(r.body.rows).toBeUndefined();
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
    expect(s.retention_hours).toBe(RETENTION_HOURS);
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
    const out = await pruneLogs(env.DB);
    expect(out.removed).toBe(1);
    expect(await count('logs', "event = 'old'")).toBe(0);
    expect(await count('logs', "event = 'fresh'")).toBe(1);
  });

  it('a log over a day old is deleted, one under a day is kept', async () => {
    const hoursAgo = (h) => new Date(Date.now() - h * 3600_000).toISOString();
    await env.DB.batch([
      env.DB.prepare("INSERT INTO logs (ts, level, event) VALUES (?, 'info', 'day-old')").bind(hoursAgo(25)),
      env.DB.prepare("INSERT INTO logs (ts, level, event) VALUES (?, 'info', 'hours-old')").bind(hoursAgo(23)),
    ]);
    await pruneLogs(env.DB);
    expect(await count('logs', "event = 'day-old'"), 'a 25-hour-old log survived the prune').toBe(0);
    expect(await count('logs', "event = 'hours-old'"), 'a 23-hour-old log was deleted').toBe(1);
  });

  it('a day is the window', () => {
    expect(RETENTION_HOURS).toBe(24);
  });

  it('the hourly cron deletes logs over a day old, and only that', async () => {
    const old = new Date(Date.now() - 30 * 3600_000).toISOString();
    await env.DB.prepare("INSERT INTO logs (ts, level, event) VALUES (?, 'info', 'cron-old')").bind(old).run();
    const runs = await count('maintenance_log');
    const ctx = createExecutionContext();
    await worker.scheduled({ cron: LOG_PRUNE_CRON, scheduledTime: Date.now() }, env, ctx);
    await waitOnExecutionContext(ctx);
    expect(await count('logs', "event = 'cron-old'"), 'the hourly cron left a 30-hour-old log').toBe(0);
    expect(await count('maintenance_log'), 'the hourly cron ran the whole daily job').toBe(runs);
  });

  it('the server log never shows a row over a day old, even before any cron ran', async () => {
    const old = new Date(Date.now() - 30 * 3600_000).toISOString();
    await env.DB.prepare("INSERT INTO logs (ts, level, event, message) VALUES (?, 'error', 'stale', 'from yesterday')").bind(old).run();
    const token = await adminToken();
    const r = await authed('/logs?min=info&limit=500', token);
    expect(r.status).toBe(200);
    expect(r.body.rows.map((x) => x.event), 'GET /logs returned a 30-hour-old row').not.toContain('stale');
    expect(await count('logs', "event = 'stale'"), 'reading the log left a 30-hour-old row in the table').toBe(0);
  });

  it('stats never count past a day either', async () => {
    const old = new Date(Date.now() - 30 * 3600_000).toISOString();
    await env.DB.prepare("INSERT INTO logs (ts, level, event) VALUES (?, 'error', 'stats-stale')").bind(old).run();
    const token = await adminToken();
    const r = await authed('/logs/stats?since=168', token);
    expect(r.status).toBe(200);
    expect(r.body.hours, 'stats looked back past the retention window').toBe(24);
    expect(r.body.retention_hours).toBe(24);
    expect(await count('logs', "event = 'stats-stale'"), 'stats left a 30-hour-old row in the table').toBe(0);
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

// A request that never comes back still leaves a row.
//
// The log was written in `ctx.waitUntil`, after the response, so the only
// requests it never recorded were the ones that died — and those are the
// only ones worth reading about. Three separate production failures left
// nothing in `logs` at all: a `/cards/add` claimed its idempotency key,
// was killed, and the only evidence was a leftover row in `idempotency`
// and a 409 from the client's own retry ten seconds later. Each one was
// diagnosed by inference. Matt: "Then fix the fucking observability."
describe('a write says so before it runs', () => {
  const asPost = (path = '/cards/add') => newEntry(new Request(`https://x.test${path}`, { method: 'POST' }));

  it('leaves a row the moment it starts, before anything can kill it', async () => {
    const entry = await startEntry(env, asPost());

    const [row] = await sql('SELECT id, path, status, message FROM logs WHERE id = ?', entry.id);
    expect(row, 'nothing was written before the request ran').toBeTruthy();
    expect(row.status, 'an unfinished request must not look finished').toBeNull();
    expect(row.message).toBe(IN_FLIGHT);
  });

  it('is still there, unfinished, when the request never comes back', async () => {
    // Exactly the production shape: started, killed, no completion.
    const entry = await startEntry(env, asPost());

    const [row] = await sql("SELECT status, message FROM logs WHERE id = ?", entry.id);
    expect(row.status).toBeNull();
    expect(row.message).toBe(IN_FLIGHT);
    // And it is findable as a class, which is what makes it useful.
    const [{ stuck }] = await sql('SELECT COUNT(*) AS stuck FROM logs WHERE status IS NULL');
    expect(stuck).toBeGreaterThan(0);
  });

  it('fills in the row it already put down rather than writing a second', async () => {
    const before = await count('logs');
    const entry = await startEntry(env, asPost());
    entry.message = 'all good';
    await writeEntry(env, entry, 200);

    expect(await count('logs'), 'one request wrote two rows').toBe(before + 1);
    const [row] = await sql('SELECT status, message, ms FROM logs WHERE id = ?', entry.id);
    expect(row.status).toBe(200);
    expect(row.message).toBe('all good');
  });

  it('still records a request nobody started a row for', async () => {
    // Reads take this path: no start row, one row at the end, as before.
    const before = await count('logs');
    const entry = newEntry(new Request('https://x.test/query', { method: 'POST' }));
    await writeEntry(env, entry, 200);
    expect(await count('logs')).toBe(before + 1);
  });

  // The one that actually proves it, by looking while the request is
  // still running. Asserting afterwards proves nothing: a finished POST
  // leaves exactly one finished row either way, which is why the first
  // version of this test passed with the feature taken out.
  it('is visible as in flight WHILE a real POST is still running', async () => {
    let release;
    const held = new Promise((r) => { release = r; });
    const stub = stubScryfall();
    const slow = async (url, init) => {
      if (new URL(url).pathname === '/cards/collection') await held;
      return stub(url, init);
    };

    const inFlight = post('/cards/add', { list: '1 Sol Ring' }, slow);

    // The row has to be there before the request can possibly finish.
    let seen = null;
    for (let i = 0; i < 100 && !seen; i += 1) {
      [seen] = await sql(
        "SELECT id, path, status, message FROM logs WHERE path = '/cards/add' AND status IS NULL",
      );
      if (!seen) await new Promise((r) => setTimeout(r, 10));
    }
    expect(seen, 'a running POST was invisible in the log').toBeTruthy();
    expect(seen.message).toBe(IN_FLIGHT);

    release();
    await inFlight;
    await settle();

    // And the same row is filled in, not joined by a second.
    const [done] = await sql('SELECT status, message FROM logs WHERE id = ?', seen.id);
    expect(done.status, 'the row was left unfinished').not.toBeNull();
    const [{ n }] = await sql("SELECT COUNT(*) AS n FROM logs WHERE path = '/cards/add'");
    expect(n, 'one request wrote more than one row').toBe(1);
  });
});

// Why a push did not arrive has to be readable afterwards.
//
// Matt: "I haven't been getting push notifications for deployed tasks."
// Six tasks reached `deployed`, two admin phones were registered and
// alive, `FCM_SERVICE_ACCOUNT` was set — and there was nothing anywhere
// saying what happened when the Worker tried to send. `wrangler tail`
// showed the POST as Ok and no more, because `tell` returned its result
// and logged nothing.
//
// The result vanished in a race: `tell` ran in one `ctx.waitUntil` and
// set `entry.detail.push` when it finished, while `writeEntry` ran in
// another registered at the end of the request. Both start at once and
// `writeEntry` wins, because `tell` is waiting on Firebase. The field
// was always written before the thing that fills it had finished.
//
// Writing it as its own row is what makes that untimeable. These tests
// assert the row exists and says what happened, which has nothing to do
// with how slow Firebase is on the day.
describe('a push leaves a trace', () => {
  const settled = () => new Promise((r) => setTimeout(r, 400));

  const pushRow = async () => {
    const [r] = await sql(
      "SELECT message, status, detail FROM logs WHERE message LIKE 'push %' ORDER BY id DESC LIMIT 1",
    );
    return r;
  };

  it('writes its own row saying what the attempt came to', async () => {
    await post('/tasks/status', { name: 'push-trace', title: 'Push trace', status: 'deployed' });
    await settled();
    const r = await pushRow();
    expect(r, 'the push attempt left no row at all').toBeTruthy();
    // No FCM credentials in the test environment, so the honest answer is
    // that it was skipped — and saying so is the whole point. A
    // deployment that cannot send must not look like one that did.
    expect(r.message).toBe('push skipped: no credentials');
    expect(JSON.parse(r.detail).push.skipped).toBe('no credentials');
    expect(JSON.parse(r.detail).name).toBe('push-trace');
  });

  it('says so for a status not worth a push, rather than going silent', async () => {
    await post('/tasks/status', { name: 'push-quiet', title: 'Push quiet', status: 'in progress' });
    await settled();
    const r = await pushRow();
    expect(r.message).toBe('push skipped: not worth a push');
  });

  it('does not hang the row on the request, which is written before the push finishes', async () => {
    // The old shape in one assertion: the request's own row carries no
    // push field, because it cannot. Anything reading it for one is
    // reading a field that was never filled.
    await post('/tasks/status', { name: 'push-race', title: 'Push race', status: 'deployed' });
    await settled();
    const [req] = await sql(
      "SELECT detail FROM logs WHERE path = '/tasks/status' AND message IS NULL ORDER BY id DESC LIMIT 1",
    );
    expect(req, 'no row for the status write itself').toBeTruthy();
    expect(JSON.parse(req.detail).push, 'the request row is carrying a push result again').toBeUndefined();
  });
});
