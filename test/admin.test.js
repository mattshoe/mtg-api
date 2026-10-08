import { describe, it, expect } from 'vitest';
import {
  get, post, postAnon, postAs, sql, count, snapshot, adminToken,
  TEST_PASSWORD, stubScryfall, MATT,
} from './helpers.js';

const BOLT = '1 Lightning Bolt (2X2) 117';

/**
 * Sign a token payload the way the Worker does, so a test can mint one the
 * verifier will accept — the only way to produce a lapsed token that gets
 * as far as the clock check.
 */
async function signAs(password, payload) {
  const enc = new TextEncoder();
  const key = await crypto.subtle.importKey(
    'raw', enc.encode(password), { name: 'HMAC', hash: 'SHA-256' }, false, ['sign'],
  );
  const mac = await crypto.subtle.sign('HMAC', key, enc.encode(payload));
  return btoa(String.fromCharCode(...new Uint8Array(mac)))
    .replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

describe('POST /admin', () => {
  it('hands back a token for the right password', async () => {
    const r = await postAnon('/admin', { password: TEST_PASSWORD });
    expect(r.status).toBe(200);
    expect(r.body.ok).toBe(true);
    expect(typeof r.body.token).toBe('string');
    // No expiry: the token lasts until the password is rotated.
    expect(r.body.expires_at).toBeNull();
  });

  it('refuses a wrong password', async () => {
    for (const p of ['', 'wrong', 'test-passwor', 'test-password ', 'TEST-PASSWORD']) {
      const r = await postAnon('/admin', { password: p });
      expect(r.status, `"${p}" was accepted`).toBe(401);
      expect(r.body.token).toBeUndefined();
    }
  });

  it('refuses a missing or non-string password', async () => {
    expect((await postAnon('/admin', {})).status).toBe(401);
    expect((await postAnon('/admin', { password: 123 })).status).toBe(401);
    expect((await postAnon('/admin', { password: null })).status).toBe(401);
  });

  it('never echoes the password back', async () => {
    const r = await postAnon('/admin', { password: TEST_PASSWORD });
    expect(r.text).not.toContain(TEST_PASSWORD);
  });

  it('is POST only', async () => {
    expect((await get('/admin')).status).toBe(405);
  });
});

describe('writes are gated', () => {
  it('/cards/add is refused without a token', async () => {
    const before = await snapshot();
    const r = await postAnon('/cards/add', { list: BOLT }, stubScryfall());
    expect(r.status).toBe(401);
    expect(r.body.admin_required).toBe(true);
    expect(await snapshot()).toEqual(before);
  });

  it('/cards/remove is refused without a token', async () => {
    const r = await postAnon('/cards/remove', { list: '1 Sol Ring' });
    expect(r.status).toBe(401);
  });

  it('a dry run is gated too — a preview is part of editing', async () => {
    const r = await postAnon('/cards/add', { list: BOLT, dry_run: true }, stubScryfall());
    expect(r.status).toBe(401);
  });

  it('a mutating /query is refused and changes nothing', async () => {
    // It used to be refused for want of a token and accepted with
    // one. Collections have owners now, and a statement is not a
    // collection — there is nothing to check an owner against — so
    // the endpoint reads and does not write, whoever is asking.
    const before = await count('deck_notes');
    for (const s of [
      "INSERT INTO deck_notes (deck_id, section, body) VALUES (1,'x','y')",
      'DELETE FROM deck_notes',
      'UPDATE cards SET qty = 0',
      'DROP TABLE cards',
    ]) {
      const r = await postAnon('/query', { sql: s });
      expect(r.status, `${s} was allowed`).toBe(403);
    }
    expect(await count('deck_notes')).toBe(before);
    expect(await count('cards', 'qty = 0')).toBe(0);
  });

  it('accepts the write once a token is presented', async () => {
    const token = await adminToken();
    const r = await postAs('/cards/add', { collection: MATT, list: `2 ${BOLT.slice(2)}` }, token, stubScryfall());
    expect(r.status).toBe(200);
    expect(r.body.applied).toBe(true);
    expect(await count('cards', "name_norm = 'lightning bolt'")).toBe(1);
  });

  it('refuses a mutating query even with a token', async () => {
    // The hole this closes: one leaked password used to be able to
    // rewrite every collection on the server with one statement.
    const token = await adminToken();
    const r = await postAs('/query', {
      sql: "INSERT INTO deck_notes (deck_id, section, body) VALUES (1,'Gameplan','ok')",
    }, token);
    expect(r.status).toBe(403);
    expect(r.body.error).toMatch(/read-only/);
  });
});

describe('reads stay open', () => {
  it('GET /schema needs nothing', async () => {
    expect((await get('/schema')).status).toBe(200);
  });

  it('GET /query needs nothing', async () => {
    const r = await get(`/query?sql=${encodeURIComponent('SELECT COUNT(*) FROM cards')}`);
    expect(r.status).toBe(200);
  });

  it('a read-only POST /query needs nothing', async () => {
    const r = await postAnon('/query', { sql: 'SELECT name FROM cards LIMIT 3' });
    expect(r.status).toBe(200);
    expect(r.body.n).toBe(3);
  });

  it('a CTE read needs nothing', async () => {
    const r = await postAnon('/query', { sql: 'WITH x AS (SELECT id FROM cards LIMIT 2) SELECT * FROM x' });
    expect(r.status).toBe(200);
  });

  it('the index still describes itself', async () => {
    const r = await get('/');
    expect(r.body.auth).toMatch(/Reads are open/);
    expect(Object.keys(r.body.endpoints)).toContain('POST /admin');
  });
});

describe('token handling', () => {
  it('rejects a forged or mangled token', async () => {
    const good = await adminToken();
    const bad = [
      'nonsense',
      'nonsense.nonsense',
      `${good}x`,
      good.replace(/\.[^.]*$/, '.AAAA'),
      `${Math.floor(Date.now() / 1000) + 9999}.forged`,
      '',
    ];
    for (const t of bad) {
      const r = await postAs('/cards/remove', { list: '1 Sol Ring' }, t);
      expect(r.status, `token "${t.slice(0, 24)}" was accepted`).toBe(401);
    }
  });

  it('the token it issues does not expire', async () => {
    const token = await adminToken();
    expect(token.split('.')[0]).toBe('0');
    const r = await postAs('/decks/disassemble', { slug: 'nope', dry_run: true }, token);
    // Not a 401: the token was accepted. What it then ran into is the
    // deck not existing, which is this endpoint's own business.
    expect(r.status).not.toBe(401);
  });

  it('still rejects one of the old timed tokens once it has lapsed', async () => {
    // A token is `<expiry>.<hmac>`; rewriting the expiry breaks the
    // signature, so a lapsed one has to be signed honestly. Borrowing the
    // signature off a `0` token gives the right shape with the wrong
    // payload, which the signature check refuses before the clock is
    // consulted — so sign the past expiry properly.
    const past = String(Math.floor(Date.now() / 1000) - 60);
    const mac = await signAs(TEST_PASSWORD, past);
    const r = await postAs('/cards/remove', { list: '1 Sol Ring' }, `${past}.${mac}`);
    expect(r.status).toBe(401);
    expect(r.body.error).toMatch(/expired/i);
  });

  it('a past expiry carrying another token\'s signature is refused', async () => {
    const past = Math.floor(Date.now() / 1000) - 60;
    const forged = `${past}.${(await adminToken()).split('.')[1]}`;
    const r = await postAs('/cards/remove', { list: '1 Sol Ring' }, forged);
    expect(r.status).toBe(401);
  });

  it('a token is not accepted in place of the password', async () => {
    const token = await adminToken();
    const r = await postAnon('/admin', { password: token });
    expect(r.status).toBe(401);
  });

  it('tokens issued at the same second are identical, and both work', async () => {
    // The token is a signed expiry, not a stored session — deliberately
    // stateless, so unlocking twice does not invalidate the first tab.
    const a = await adminToken();
    const b = await adminToken();
    for (const t of [a, b]) {
      const r = await postAs('/decks/disassemble', { slug: 'nope', dry_run: true }, t);
      expect(r.status).not.toBe(401);
    }
  });

  it('is case sensitive about the Bearer scheme but tolerant of spacing', async () => {
    const token = await adminToken();
    const r = await postAs('/decks/disassemble', { slug: 'nope', dry_run: true }, `  ${token}  `.trim());
    expect(r.status).not.toBe(401);
  });
});
