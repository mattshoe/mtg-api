import { describe, it, expect } from 'vitest';
import { env } from 'cloudflare:test';
import {
  postAs, post, get, call, sql, stubScryfall, MATT, KAYLA,
} from './helpers.js';
import { signIn, newSession } from '../src/accounts.js';

/**
 * Ownership is an id, addresses are keys, and no slug survives.
 *
 * Matt: "ARE YOU NOT USING A FUCKING USER ID?!?!?!" — and "USER KEY IS
 * NOT SUFFICIENT TO MUTATE DATA!!! ONLY USER ID!!"
 *
 * The seed holds two accounts: id 1 is Matt, key `m4tt0001`, and id 3
 * is Kayla, key `k4yy0003`. Every card and deck in it carries its
 * owner's id, the way the live database does after 0007.
 */
describe('a card belongs to an account id', () => {
  /** A new account, signed in. */
  async function account(name) {
    const user = await signIn(env.DB, {
      provider: 'google', subject: `sub-${name}`, name, email: `${name}@example.com`,
    });
    return { user, token: await newSession(env.DB, user.id) };
  }

  /** A session for one of the seeded accounts. */
  const sessionFor = (id) => newSession(env.DB, id);

  const list = '1 Sol Ring';

  it('every card and deck in the seed has an owner_id that is a real account', async () => {
    const orphans = await sql(
      `SELECT 'card' AS t, COUNT(*) AS n FROM cards WHERE owner_id IS NULL OR owner_id NOT IN (SELECT id FROM users)
       UNION ALL
       SELECT 'deck', COUNT(*) FROM decks WHERE owner_id IS NULL OR owner_id NOT IN (SELECT id FROM users)`,
    );
    expect(orphans.map((r) => `${r.t}:${r.n}`)).toEqual(['card:0', 'deck:0']);
  });

  it('a card added by account 3 belongs to id 3, and account 1 cannot touch it', async () => {
    const kayla = await sessionFor(3);
    const r = await postAs('/cards/add', { list, dry_run: false }, kayla, stubScryfall());
    expect(r.status, JSON.stringify(r.body)).toBe(200);
    const [row] = await sql(
      "SELECT owner_id FROM cards WHERE name = 'Sol Ring' AND owner_id = 3",
    );
    expect(row?.owner_id, 'the card account 3 added does not say owner_id = 3').toBe(3);

    // Account 1 is not an admin here: an ordinary account, whose only
    // claim is a session for id 1.
    await env.DB.prepare("UPDATE users SET role = 'user' WHERE id = 1").run();
    const matt = await sessionFor(1);
    const before = await sql('SELECT SUM(qty) AS n FROM cards WHERE owner_id = 3');
    const denied = await postAs('/cards/remove', { collection: KAYLA, list, dry_run: false }, matt, stubScryfall());
    expect(denied.status, 'account 1 removed a card that belongs to account 3').toBe(403);
    const after = await sql('SELECT SUM(qty) AS n FROM cards WHERE owner_id = 3');
    expect(after[0].n).toBe(before[0].n);

    const own = await postAs('/cards/remove', { list, dry_run: false }, kayla, stubScryfall());
    expect(own.status, JSON.stringify(own.body)).toBe(200);
    expect(own.body.applied).toBe(true);
  });

  it('a new account owns nothing, because nothing has its id', async () => {
    const { user } = await account('Matt');
    const [n] = await sql('SELECT COUNT(*) AS n FROM cards WHERE owner_id = ?', user.id);
    expect(n.n, 'a brand new account called Matt was handed cards').toBe(0);
  });

  it('a new account writes to its own id even if it is called Kayla', async () => {
    const { user, token } = await account('Kayla');
    const kaylas = () => sql('SELECT COALESCE(SUM(qty), 0) AS n FROM cards WHERE owner_id = 3');
    const before = await kaylas();
    const r = await postAs('/cards/add', { list, dry_run: false }, token, stubScryfall());
    expect(r.status, JSON.stringify(r.body)).toBe(200);
    const [mine] = await sql("SELECT SUM(qty) AS n FROM cards WHERE name = 'Sol Ring' AND owner_id = ?", user.id);
    expect(mine.n).toBe(1);
    expect(await kaylas(), 'a stranger called Kayla wrote into account 3').toEqual(before);
  });

  it('identity survives a display-name change', async () => {
    // The Kayla bug, the other way round: ownership must not hang off
    // anything a person can rename.
    const kayla = await sessionFor(3);
    const before = await sql('SELECT COUNT(*) AS n FROM cards WHERE owner_id = 3');
    await env.DB.prepare("INSERT INTO identities VALUES ('google', 'kayla-sub', 3, datetime('now'))").run();
    await signIn(env.DB, { provider: 'google', subject: 'kayla-sub', name: 'Kayla Renamed Entirely' });
    const [me] = await sql('SELECT display_name FROM users WHERE id = 3');
    expect(me.display_name).toBe('Kayla Renamed Entirely');
    const after = await sql('SELECT COUNT(*) AS n FROM cards WHERE owner_id = 3');
    expect(after[0].n).toBe(before[0].n);
    const r = await postAs('/cards/add', { list, dry_run: true }, kayla, stubScryfall());
    expect(r.status, 'a renamed account could no longer edit its own cards').toBe(200);
  });

  it('presenting somebody else\'s key is not permission to write to it', async () => {
    const { token } = await account('Stranger');
    for (const [path, body] of [
      ['/cards/add', { collection: MATT, list, dry_run: true }],
      ['/cards/remove', { collection: MATT, list, dry_run: true }],
      ['/decks/create', { collection: MATT, name: 'Mine Now', format: 'commander', list: '1 Sol Ring', dry_run: true }],
    ]) {
      const r = await postAs(path, body, token, stubScryfall());
      expect(r.status, `${path} let a stranger write with Matt's key`).toBe(403);
    }
  });

  it('a slug in the body is refused rather than read', async () => {
    const r = await post('/cards/add', { owner: 'matt', list, dry_run: true }, stubScryfall());
    expect(r.status).toBe(400);
    expect(r.body.error).toMatch(/collection/);
  });

  it('the operator has no account, so it has to name the collection by key', async () => {
    const none = await post('/cards/add', { list, dry_run: true }, stubScryfall());
    expect(none.status).toBe(400);
    const named = await post('/cards/add', { collection: KAYLA, list, dry_run: false }, stubScryfall());
    expect(named.status, JSON.stringify(named.body)).toBe(200);
    const [row] = await sql("SELECT owner_id FROM cards WHERE name = 'Sol Ring' AND owner_id = 3");
    expect(row?.owner_id).toBe(3);
  });

  it('a key nobody has is a 404, not a write to nowhere', async () => {
    const r = await post('/cards/add', { collection: 'zzzzzzzz', list, dry_run: true }, stubScryfall());
    expect(r.status).toBe(404);
  });
});

describe('decks are addressed by a key', () => {
  const create = (token, name) => postAs('/decks/create', {
    name, format: 'casual', list: '1 Sol Ring', dry_run: false,
  }, token, stubScryfall());

  it('two accounts can each own a deck called Milly Moth', async () => {
    const matt = await newSession(env.DB, 1);
    const kayla = await newSession(env.DB, 3);
    const a = await create(matt, 'Milly Moth');
    const b = await create(kayla, 'Milly Moth');
    expect(a.status, JSON.stringify(a.body)).toBe(201);
    expect(b.status, `the second Milly Moth was refused: ${JSON.stringify(b.body)}`).toBe(201);
    expect(a.body.key).toMatch(/^[0-9a-hjkmnp-tv-z]{8}$/);
    expect(b.body.key).toMatch(/^[0-9a-hjkmnp-tv-z]{8}$/);
    expect(a.body.key).not.toBe(b.body.key);

    const mine = await sql("SELECT key, owner_id FROM decks WHERE name = 'Milly Moth' ORDER BY owner_id");
    expect(mine).toEqual([{ key: a.body.key, owner_id: 1 }, { key: b.body.key, owner_id: 3 }]);
  });

  it('renaming a deck changes its name and not its address', async () => {
    const [deck] = await sql('SELECT key, name FROM decks WHERE id = 1');
    const r = await post('/decks/rename', { key: deck.key, name: 'Something Else Entirely' });
    expect(r.status, JSON.stringify(r.body)).toBe(200);
    expect(r.body.key).toBe(deck.key);
    const [after] = await sql('SELECT key, name FROM decks WHERE id = 1');
    expect(after).toEqual({ key: deck.key, name: 'Something Else Entirely' });
  });

  it('a deck is only edited by the account whose id owns it', async () => {
    await env.DB.prepare("UPDATE users SET role = 'user' WHERE id = 1").run();
    const matt = await newSession(env.DB, 1);
    const [kaylas] = await sql('SELECT key FROM decks WHERE owner_id = 3 LIMIT 1');
    for (const [path, body] of [
      ['/decks/rename', { key: kaylas.key, name: 'Taken' }],
      ['/decks/list', { key: kaylas.key, list: '1 Sol Ring', dry_run: true }],
      ['/decks/disassemble', { key: kaylas.key, dry_run: true }],
    ]) {
      const r = await postAs(path, body, matt, stubScryfall());
      expect(r.status, `${path} let account 1 edit account 3's deck`).toBe(403);
    }
  });
});

describe('no response carries a user id or a slug', () => {
  it('/c/:key, /auth/me and /admin/users say key, name and avatar only', async () => {
    const page = await get(`/c/${KAYLA}`);
    expect(page.status).toBe(200);
    expect(Object.keys(page.body).sort()).toEqual(['avatar', 'key', 'name']);

    const token = await newSession(env.DB, 3);
    const me = await call('/auth/me', { method: 'GET', token });
    expect(me.body).not.toHaveProperty('id');
    expect(me.body).not.toHaveProperty('slug');
    expect(me.body.key).toBe(KAYLA);

    const admin = await newSession(env.DB, 1);
    const users = await call('/admin/users', { method: 'GET', token: admin });
    for (const u of users.body.users) {
      expect(u).not.toHaveProperty('id');
      expect(u).not.toHaveProperty('slug');
    }
  });

  it('a role is handed out by key', async () => {
    const r = await post('/admin/role', { key: KAYLA, role: 'admin' });
    expect(r.status, JSON.stringify(r.body)).toBe(200);
    const [row] = await sql('SELECT role FROM users WHERE id = 3');
    expect(row.role).toBe('admin');
  });
});
