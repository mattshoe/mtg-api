import { describe, it, expect } from 'vitest';
import { post, sql, exec, count, snapshot, stubScryfall } from './helpers.js';
import { env } from 'cloudflare:test';
import { claim } from '../src/idempotency.js';
import { MATT, KAYLA } from './helpers.js';

// A retried write must not apply twice.
//
// Every mutation is one atomic batch, so a 5xx means nothing landed and
// the client may retry freely. The case this exists for is the other one:
// the batch committed and the response never got back. From the client
// that is indistinguishable from a request that never arrived — so it
// retries, and without a key four Lightning Bolts become eight.

const KEY = 'idem-0123456789abcdef';

describe('POST with an idempotency key', () => {
  it('applies the first attempt and replays the second', async () => {
    const list = '4 Lightning Bolt (2X2) 117';
    const first = await post('/cards/add', { list, dry_run: false },
      stubScryfall(), { 'idempotency-key': KEY });
    expect(first.status).toBe(200);
    expect(first.body.applied).toBe(true);

    const after = await count('cards', "name_norm = 'lightning bolt' AND owner_id = 1");
    const held = await sql("SELECT SUM(qty) AS q FROM cards WHERE name_norm = 'lightning bolt' AND owner_id = 1");

    const second = await post('/cards/add', { list, dry_run: false },
      stubScryfall(), { 'idempotency-key': KEY });
    expect(second.status).toBe(200);
    // The same answer, and nothing else happened.
    expect(second.body).toEqual(first.body);
    expect(await count('cards', "name_norm = 'lightning bolt' AND owner_id = 1")).toBe(after);
    const now = await sql("SELECT SUM(qty) AS q FROM cards WHERE name_norm = 'lightning bolt' AND owner_id = 1");
    expect(now[0].q).toBe(held[0].q);
  });

  it('changes nothing at all on the replay', async () => {
    const list = '1 Lightning Bolt (2X2) 117';
    await post('/cards/add', { list, dry_run: false },
      stubScryfall(), { 'idempotency-key': KEY });
    const before = await snapshot();
    await post('/cards/add', { list, dry_run: false },
      stubScryfall(), { 'idempotency-key': KEY });
    expect(await snapshot()).toEqual(before);
  });

  it('lets two different keys both apply', async () => {
    // Two deliberate identical writes are two writes. Only a retry of
    // the same attempt shares a key.
    const list = '1 Lightning Bolt (2X2) 117';
    const before = await sql("SELECT COALESCE(SUM(qty),0) AS q FROM cards WHERE name_norm = 'lightning bolt' AND owner_id = 1");
    await post('/cards/add', { list, dry_run: false }, stubScryfall(), { 'idempotency-key': 'one' });
    await post('/cards/add', { list, dry_run: false }, stubScryfall(), { 'idempotency-key': 'two' });
    const after = await sql("SELECT COALESCE(SUM(qty),0) AS q FROM cards WHERE name_norm = 'lightning bolt' AND owner_id = 1");
    expect(after[0].q).toBe(before[0].q + 2);
  });

  it('refuses the same key on a different endpoint', async () => {
    await post('/cards/add', { list: '1 Lightning Bolt (2X2) 117', dry_run: false },
      stubScryfall(), { 'idempotency-key': KEY });
    const other = await post('/cards/remove', { list: '1 Lightning Bolt', dry_run: false },
      stubScryfall(), { 'idempotency-key': KEY });
    expect(other.status).toBe(409);
    expect(other.body.error).toMatch(/cards\/add/);
  });

  it('works without a key, the way it always did', async () => {
    const r = await post('/cards/add', { list: '1 Lightning Bolt (2X2) 117', dry_run: false },
      stubScryfall());
    expect(r.status).toBe(200);
    expect(r.body.applied).toBe(true);
  });

  it('does not hold the key when the attempt failed outright', async () => {
    // A refusal is not an answer worth repeating: the client should be
    // able to fix the list and send it again under a fresh key, and the
    // same key must not come back as "already in progress".
    const bad = await post('/cards/add', { list: '', dry_run: false },
      stubScryfall(), { 'idempotency-key': 'empty-list' });
    expect(bad.status).toBeGreaterThanOrEqual(400);
    const rows = await sql('SELECT status FROM idempotency WHERE key = ?', 'empty-list');
    // Either released, or remembered with its own refusal — never left
    // claimed and unanswered.
    if (rows.length) expect(rows[0].status).not.toBeNull();
  });

  it('remembers a dry run separately from the real thing', async () => {
    const list = '2 Lightning Bolt (2X2) 117';
    const dry = await post('/cards/add', { list, dry_run: true },
      stubScryfall(), { 'idempotency-key': 'dry' });
    expect(dry.body.applied).toBe(false);
    const real = await post('/cards/add', { list, dry_run: false },
      stubScryfall(), { 'idempotency-key': 'wet' });
    expect(real.body.applied).toBe(true);
  });
});

// A claim whose request died must not refuse every attempt after it.
//
// Matt's share from ManaBox looped: a `/cards/add` claimed its key and
// never came back, the client's own retry ten seconds later was refused
// with "already in progress", and the next tap did the same. The attempt
// holding the key was dead, so nothing was ever going to release it — the
// flow could not recover without somebody clearing a table by hand, and
// somebody did, three times.
describe('a claim nobody is coming back for', () => {
  const KEY = 'k-abandoned';

  it('refuses a retry while the first attempt could still be running', async () => {
    await exec(
      'INSERT INTO idempotency (key, path, ts) VALUES (?,?,?)',
      KEY, '/cards/add', new Date().toISOString(),
    );
    const r = await claim(env.DB, KEY, '/cards/add');
    expect(r.inFlight, 'it let a second attempt in while the first was live').toBe(true);
  });

  it('lets the next attempt take over once the first is plainly dead', async () => {
    const longAgo = new Date(Date.now() - 5 * 60_000).toISOString();
    await exec(
      'INSERT INTO idempotency (key, path, ts) VALUES (?,?,?)',
      KEY, '/cards/add', longAgo,
    );

    const r = await claim(env.DB, KEY, '/cards/add');

    expect(r.claimed, 'a dead claim refused the retry forever').toBe(true);
    expect(r.inFlight).toBeUndefined();
  });

  it('starts the clock again, so the attempt that took over is protected', async () => {
    const longAgo = new Date(Date.now() - 5 * 60_000).toISOString();
    await exec('INSERT INTO idempotency (key, path, ts) VALUES (?,?,?)', KEY, '/cards/add', longAgo);
    await claim(env.DB, KEY, '/cards/add');

    // A third attempt arriving straight after must be refused, not let in
    // alongside the one now doing the work.
    const r = await claim(env.DB, KEY, '/cards/add');
    expect(r.inFlight, 'two attempts were allowed to run at once').toBe(true);
  });

  it('still replays a finished answer however old it is', async () => {
    const longAgo = new Date(Date.now() - 5 * 60_000).toISOString();
    await exec(
      'INSERT INTO idempotency (key, path, ts, status, body) VALUES (?,?,?,?,?)',
      KEY, '/cards/add', longAgo, 200, '{"applied":true}',
    );
    const r = await claim(env.DB, KEY, '/cards/add');
    expect(r.replay?.status, 'an old success stopped being replayed').toBe(200);
  });
})
