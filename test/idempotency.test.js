import { describe, it, expect } from 'vitest';
import { post, sql, count, snapshot, stubScryfall } from './helpers.js';

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
    const first = await post('/cards/add', { owner: 'matt', list, dry_run: false },
      stubScryfall(), { 'idempotency-key': KEY });
    expect(first.status).toBe(200);
    expect(first.body.applied).toBe(true);

    const after = await count('cards', "name_norm = 'lightning bolt' AND owner = 'matt'");
    const held = await sql("SELECT SUM(qty) AS q FROM cards WHERE name_norm = 'lightning bolt' AND owner = 'matt'");

    const second = await post('/cards/add', { owner: 'matt', list, dry_run: false },
      stubScryfall(), { 'idempotency-key': KEY });
    expect(second.status).toBe(200);
    // The same answer, and nothing else happened.
    expect(second.body).toEqual(first.body);
    expect(await count('cards', "name_norm = 'lightning bolt' AND owner = 'matt'")).toBe(after);
    const now = await sql("SELECT SUM(qty) AS q FROM cards WHERE name_norm = 'lightning bolt' AND owner = 'matt'");
    expect(now[0].q).toBe(held[0].q);
  });

  it('changes nothing at all on the replay', async () => {
    const list = '1 Lightning Bolt (2X2) 117';
    await post('/cards/add', { owner: 'matt', list, dry_run: false },
      stubScryfall(), { 'idempotency-key': KEY });
    const before = await snapshot();
    await post('/cards/add', { owner: 'matt', list, dry_run: false },
      stubScryfall(), { 'idempotency-key': KEY });
    expect(await snapshot()).toEqual(before);
  });

  it('lets two different keys both apply', async () => {
    // Two deliberate identical writes are two writes. Only a retry of
    // the same attempt shares a key.
    const list = '1 Lightning Bolt (2X2) 117';
    const before = await sql("SELECT COALESCE(SUM(qty),0) AS q FROM cards WHERE name_norm = 'lightning bolt' AND owner = 'matt'");
    await post('/cards/add', { owner: 'matt', list, dry_run: false }, stubScryfall(), { 'idempotency-key': 'one' });
    await post('/cards/add', { owner: 'matt', list, dry_run: false }, stubScryfall(), { 'idempotency-key': 'two' });
    const after = await sql("SELECT COALESCE(SUM(qty),0) AS q FROM cards WHERE name_norm = 'lightning bolt' AND owner = 'matt'");
    expect(after[0].q).toBe(before[0].q + 2);
  });

  it('refuses the same key on a different endpoint', async () => {
    await post('/cards/add', { owner: 'matt', list: '1 Lightning Bolt (2X2) 117', dry_run: false },
      stubScryfall(), { 'idempotency-key': KEY });
    const other = await post('/cards/remove', { owner: 'matt', list: '1 Lightning Bolt', dry_run: false },
      stubScryfall(), { 'idempotency-key': KEY });
    expect(other.status).toBe(409);
    expect(other.body.error).toMatch(/cards\/add/);
  });

  it('works without a key, the way it always did', async () => {
    const r = await post('/cards/add', { owner: 'matt', list: '1 Lightning Bolt (2X2) 117', dry_run: false },
      stubScryfall());
    expect(r.status).toBe(200);
    expect(r.body.applied).toBe(true);
  });

  it('does not hold the key when the attempt failed outright', async () => {
    // A refusal is not an answer worth repeating: the client should be
    // able to fix the list and send it again under a fresh key, and the
    // same key must not come back as "already in progress".
    const bad = await post('/cards/add', { owner: 'matt', list: '', dry_run: false },
      stubScryfall(), { 'idempotency-key': 'empty-list' });
    expect(bad.status).toBeGreaterThanOrEqual(400);
    const rows = await sql('SELECT status FROM idempotency WHERE key = ?', 'empty-list');
    // Either released, or remembered with its own refusal — never left
    // claimed and unanswered.
    if (rows.length) expect(rows[0].status).not.toBeNull();
  });

  it('remembers a dry run separately from the real thing', async () => {
    const list = '2 Lightning Bolt (2X2) 117';
    const dry = await post('/cards/add', { owner: 'matt', list, dry_run: true },
      stubScryfall(), { 'idempotency-key': 'dry' });
    expect(dry.body.applied).toBe(false);
    const real = await post('/cards/add', { owner: 'matt', list, dry_run: false },
      stubScryfall(), { 'idempotency-key': 'wet' });
    expect(real.body.applied).toBe(true);
  });
});
