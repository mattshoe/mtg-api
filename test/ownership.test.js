import { describe, it, expect } from 'vitest';
import { env } from 'cloudflare:test';
import {
  postAs, postAnon, post, postOperator, sql, stubScryfall, MATT,
} from './helpers.js';
import { signIn, newSession } from '../src/accounts.js';

/**
 * Who is allowed to change whose collection.
 *
 * Matt: "Configure it so that accounts have admin rights by default
 * for their own cards and only their own cards"; "Anyone should be
 * able to browse anybody's collection. But only the collection's
 * owner should be allowed to edit that collection."
 *
 * So reading stays open — every collection is public — and every
 * write is checked against the account id that owns it. The check is
 * on the owner and not on a flag: there is no such thing as an account that
 * can edit the collection next door, only an account that owns it.
 *
 * The old password still works, because the nightly job and the
 * seeding scripts hold one and because a server-wide `role` is the
 * thing that will replace it later.
 */
describe('owning a collection', () => {
  /** An account, and a session token for it. */
  async function account(name) {
    const user = await signIn(env.DB, {
      provider: 'google', subject: `sub-${name}`, name, email: `${name}@example.com`,
    });
    return { user, token: await newSession(env.DB, user.id) };
  }

  const list = '1 Sol Ring (M3C) 409';

  // ------------------------------------------------------------ reads

  it('anybody can read anybody\'s collection', async () => {
    const r = await postAnon('/query', {
      sql: "SELECT name FROM cards WHERE owner_id = 1 LIMIT 1",
    });
    expect(r.status).toBe(200);
  });

  // ----------------------------------------------------------- writes

  it('an account can add to its own collection', async () => {
    const { token } = await account('Someone');
    const r = await postAs('/cards/add', { list, dry_run: true }, token, stubScryfall());
    expect(r.status).toBe(200);
    expect(r.body.resolved).toBe(1);
  });

  it('an account cannot add to somebody else\'s', async () => {
    const { token } = await account('Someone');
    const r = await postAs('/cards/add', { collection: MATT, list, dry_run: true }, token, stubScryfall());
    expect(r.status).toBe(403);
    expect(r.body.error).toMatch(/Matt Shoemaker/);
  });

  it('an account cannot remove from somebody else\'s either', async () => {
    const { token } = await account('Someone');
    const before = await sql("SELECT SUM(qty) AS n FROM cards WHERE owner_id = 1");
    const r = await postAs('/cards/remove', { collection: MATT, list, dry_run: false }, token, stubScryfall());
    expect(r.status).toBe(403);
    const after = await sql("SELECT SUM(qty) AS n FROM cards WHERE owner_id = 1");
    expect(after[0].n).toBe(before[0].n);
  });

  it('a dry run is gated like the write it previews', async () => {
    // A preview that said what it would do to a collection you cannot
    // touch is still telling you about a change you are not allowed
    // to make, and it is the same button.
    const { token } = await account('Someone');
    const r = await postAs('/cards/add', { collection: MATT, list, dry_run: true }, token, stubScryfall());
    expect(r.status).toBe(403);
  });

  it('nobody signed in cannot write at all', async () => {
    const r = await postAnon('/cards/add', { collection: MATT, list, dry_run: true }, stubScryfall());
    expect(r.status).toBe(401);
  });

  it('an expired session cannot write', async () => {
    const { token } = await account('Someone');
    await env.DB.prepare("UPDATE sessions SET expires_at = datetime('now', '-1 day')").run();
    const r = await postAs('/cards/add', { list, dry_run: true }, token, stubScryfall());
    expect(r.status).toBe(401);
  });

  it('the server\'s own password still edits anything', async () => {
    // The nightly job and the seeding scripts hold one, and a
    // server-wide role is what will replace it.
    const r = await postOperator('/cards/add', { collection: MATT, list, dry_run: true }, stubScryfall());
    expect(r.status).toBe(200);
  });

  it('and an account with the admin role edits anybody\'s', async () => {
    // Matt: "Anyone with the admin role will be able to do whatever
    // they want, from modify others cards to giving other users admin
    // etc etc." Nobody has the role unless he hands it out —
    // `test/roles.test.js` is where that half lives.
    const { user, token } = await account('Ops');
    await env.DB.prepare("UPDATE users SET role = 'admin' WHERE id = ?1").bind(user.id).run();
    const r = await postAs('/cards/add', { collection: MATT, list, dry_run: true }, token, stubScryfall());
    expect(r.status).toBe(200);
  });

  it('and still edits its own', async () => {
    // Its own collection, named by its own key.
    const { user, token } = await account('Ops');
    await env.DB.prepare("UPDATE users SET role = 'admin' WHERE id = ?1").bind(user.id).run();
    const r = await postAs('/cards/add', { collection: user.key, list, dry_run: true }, token, stubScryfall());
    expect(r.status).toBe(200);
  });

  // ------------------------------------------------------------ decks

  it('an account cannot replace somebody else\'s deck list', async () => {
    const { token } = await account('Someone');
    const deck = (await sql('SELECT key FROM decks LIMIT 1'))[0];
    const r = await postAs(
      '/decks/list',
      { key: deck.key, commander: '', list, dry_run: true },
      token,
      stubScryfall(),
    );
    expect(r.status).toBe(403);
  });

  it('an account cannot disassemble somebody else\'s deck', async () => {
    const { token } = await account('Someone');
    const deck = (await sql('SELECT key FROM decks LIMIT 1'))[0];
    const r = await postAs('/decks/disassemble', { key: deck.key }, token);
    expect(r.status).toBe(403);
    expect((await sql('SELECT key FROM decks WHERE key = ?1', deck.key))).toHaveLength(1);
  });

  it('an account cannot rename somebody else\'s deck', async () => {
    const { token } = await account('Someone');
    const deck = (await sql('SELECT key, name FROM decks LIMIT 1'))[0];
    const r = await postAs('/decks/rename', { key: deck.key, name: 'Mine Now' }, token);
    expect(r.status).toBe(403);
    expect((await sql('SELECT name FROM decks WHERE key = ?1', deck.key))[0].name).toBe(deck.name);
  });

  it('an account cannot create a deck in somebody else\'s collection', async () => {
    const { token } = await account('Someone');
    const r = await postAs('/decks/create', {
      name: 'A New Deck', collection: MATT, format: 'commander', list,
    }, token, stubScryfall());
    expect(r.status).toBe(403);
  });

  it('an account can create a deck in its own', async () => {
    const { user, token } = await account('Someone');
    const r = await postAs('/decks/create', {
      // `modern` rather than `commander`, which would be refused for
      // naming no commander — a different refusal from the one this
      // test is about.
      name: 'A New Deck', format: 'modern', list,
    }, token, stubScryfall());
    expect(r.status).toBeLessThan(400);
    const [deck] = await sql('SELECT owner_id FROM decks WHERE key = ?1', r.body.key);
    expect(deck?.owner_id, 'the new deck is not owned by the account that made it').toBe(user.id);
  });

  // ---------------------------------------------------------- /query

  it('arbitrary SQL cannot write any more, whoever you are', async () => {
    // The one thing ownership cannot be checked on. A statement is
    // not a collection, so there is nothing to compare an owner to —
    // and every write the apps make already has an endpoint of its
    // own that does check.
    const r = await post('/query', { sql: "UPDATE cards SET qty = 99 WHERE owner_id = 1" });
    expect(r.status).toBe(403);
    expect(r.body.error).toMatch(/read/i);
    const rows = await sql("SELECT qty FROM cards WHERE owner_id = 1 AND qty = 99");
    expect(rows).toHaveLength(0);
  });

  it('and a session cannot write arbitrary SQL either', async () => {
    const { token } = await account('Someone');
    const r = await postAs('/query', { sql: 'DELETE FROM cards' }, token);
    expect(r.status).toBe(403);
  });

  // ------------------------------------------------------------- who

  it('/auth/me says who you are', async () => {
    const { user, token } = await account('Someone');
    const r = await postAs('/auth/me', {}, token);
    expect(r.status).toBe(200);
    expect(r.body.key).toBe(user.key);
    expect(r.body.name).toBe('Someone');
    expect(r.body.role).toBe('user');
    // Nothing a browser has no business holding.
    expect(JSON.stringify(r.body)).not.toContain(token);
  });

  it('/auth/me says nobody when nobody is signed in', async () => {
    const r = await postAnon('/auth/me', {});
    expect(r.status).toBe(200);
    expect(r.body.key).toBeNull();
  });

  it('logging out ends the session', async () => {
    const { token } = await account('Someone');
    expect((await postAs('/auth/logout', {}, token)).status).toBe(200);
    expect((await postAs('/auth/me', {}, token)).body.key).toBeNull();
  });
});

/**
 * The operator's own way in.
 *
 * `/query` reads and nothing else now, which is right for a surface
 * the apps and the public share — and wrong for the nightly job,
 * which exists to write rows no endpoint models: a tag backfill and
 * a price refresh.
 *
 * So arbitrary SQL keeps a door, and the door is the server
 * operator's rather than any account's. The difference from what was
 * here before is the whole point: a signed-in account can never
 * reach this, however many collections it owns.
 */
describe('POST /admin/sql', () => {
  async function account(name, role = 'user') {
    const user = await signIn(env.DB, { provider: 'google', subject: `s-${name}`, name });
    await env.DB.prepare('UPDATE users SET role = ?2 WHERE id = ?1').bind(user.id, role).run();
    return newSession(env.DB, user.id);
  }

  it('the operator can write', async () => {
    const r = await postOperator('/admin/sql', {
      sql: "INSERT INTO deck_notes (deck_id, section, body) VALUES (1,'Gameplan','ok')",
    });
    expect(r.status).toBe(200);
    expect(r.body.changes).toBe(1);
  });

  it('an ordinary account cannot, however many collections it owns', async () => {
    const token = await account('Someone');
    const r = await postAs('/admin/sql', { sql: 'DELETE FROM cards' }, token);
    expect(r.status).toBe(403);
    expect(await sql('SELECT 1 FROM cards LIMIT 1')).toHaveLength(1);
  });

  it('an account with the server role can', async () => {
    const token = await account('Ops', 'admin');
    const r = await postAs('/admin/sql', { sql: 'DELETE FROM deck_notes WHERE 0' }, token);
    expect(r.status).toBe(200);
  });

  it('nobody at all cannot', async () => {
    const r = await postAnon('/admin/sql', { sql: 'DELETE FROM cards' });
    expect(r.status).toBe(401);
    expect(await sql('SELECT 1 FROM cards LIMIT 1')).toHaveLength(1);
  });

  it('it reads too, so a script needs one door and not two', async () => {
    const r = await postOperator('/admin/sql', { sql: 'SELECT COUNT(*) AS n FROM cards' });
    expect(r.status).toBe(200);
    expect(r.body.rows[0][0]).toBeGreaterThan(0);
  });
});
