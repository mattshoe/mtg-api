import { describe, it, expect } from 'vitest';
import { env } from 'cloudflare:test';
import { get, postAnon, stubScryfall } from './helpers.js';
import {
  signIn, newSession, userForToken, endSession, hashToken,
} from '../src/accounts.js';

const KEY = /^[0-9a-hjkmnp-tv-z]{8}$/;

/**
 * Accounts, and the sessions that stand for them.
 *
 * Matt: "I want to intuitive the concept of accounts/profiles. Users
 * should be able to log in, and see their cards [...] I want this to
 * use like google/facebook/etc sign in"; "DO NOT STORE PLAIN TEXT
 * PASSWORDS ONLY HASHES".
 *
 * There are no passwords to store: Google holds the credential and
 * this never sees one. What this does hold is a session token, which
 * is a credential in every way that matters — anyone with it is you
 * until it expires — so it gets the same treatment. The token goes to
 * the browser; only its SHA-256 lands in the database.
 *
 * An account owns the rows whose `owner_id` is its `users.id`. The
 * slug that used to be the join is retired: a new account's `slug`
 * column holds its key only because the column is still NOT NULL
 * UNIQUE, and nothing reads it.
 */
describe('accounts', () => {
  const google = { provider: 'google', subject: '1234567890' };

  it('a first sign-in creates the account and its identity', async () => {
    const user = await signIn(env.DB, {
      ...google, email: 'someone@example.com', name: 'Some One',
    });
    expect(user.id).toBeGreaterThan(0);
    expect(user.key).toMatch(KEY);
    expect(user.slug, 'the retired slug column should just hold the key').toBe(user.key);
    expect(user.email).toBe('someone@example.com');
    // Every account owns its own collection and nothing else. The
    // server-wide role is a separate thing and starts off.
    expect(user.role).toBe('user');
  });

  it('signing in again finds the same account rather than making another', async () => {
    const users = async () => (await env.DB.prepare('SELECT COUNT(*) AS n FROM users').all()).results[0].n;
    const before = await users();
    const first = await signIn(env.DB, { ...google, email: 'a@example.com', name: 'Some One' });
    const again = await signIn(env.DB, { ...google, email: 'a@example.com', name: 'Some One' });
    expect(again.id).toBe(first.id);
    expect(await users()).toBe(before + 1);
  });

  it('two people with the same name get different keys', async () => {
    const one = await signIn(env.DB, { provider: 'google', subject: '1', name: 'Some One' });
    const two = await signIn(env.DB, { provider: 'google', subject: '2', name: 'Some One' });
    expect(one.id).not.toBe(two.id);
    expect(one.key).toMatch(KEY);
    expect(two.key).toMatch(KEY);
    expect(two.key).not.toBe(one.key);
  });

  it('the address is a path segment whatever the name is', async () => {
    // It goes in a URL — `#/c/<key>` — so anything that would need
    // escaping there has no business in one. It is not built from
    // the name, so no name can put anything there.
    const names = ["Matt O'Shoemaker", '  Ünïcødé  Name ', '///', '', null];
    for (const [n, name] of names.entries()) {
      const u = await signIn(env.DB, { provider: 'google', subject: `seg-${n}`, name });
      expect(u.key, `the address for ${JSON.stringify(name)}`).toMatch(KEY);
    }
  });

  /**
   * A crowd of one name is a crowd of accounts.
   *
   * Matt: "What slug when we have thousands of users??? How are we
   * going to keep them distinct???"
   *
   * The answer ended up being: not with a slug at all. `users.key` is
   * eight characters of a 32-letter alphabet — a trillion of them —
   * and the retired `slug` column, which is still UNIQUE, holds the
   * same key, so the thousandth John Smith collides with nothing.
   */
  it('a crowd of the same name all get their own key', async () => {
    const seen = new Set();
    for (let n = 0; n < 25; n += 1) {
      const u = await signIn(env.DB, { provider: 'google', subject: `crowd-${n}`, name: 'John Smith' });
      expect(seen.has(u.key), `${u.key} was handed out twice`).toBe(false);
      expect(u.slug).toBe(u.key);
      seen.add(u.key);
    }
    expect(seen.size).toBe(25);
  });

  it('and none of them is a name or a number anybody could guess the next of', async () => {
    // `jane-doe-2` tells you there is a `jane-doe` and invites a
    // `jane-doe-3`. A random key says nothing.
    await signIn(env.DB, { provider: 'google', subject: 'g1', name: 'Jane Doe' });
    const second = await signIn(env.DB, { provider: 'google', subject: 'g2', name: 'Jane Doe' });
    expect(second.key).not.toMatch(/jane|doe|-\d/i);
    expect(second.slug).not.toMatch(/jane|doe|-\d/i);
  });

  it('the key is what is actually unique, and it is long', async () => {
    const keys = new Set();
    for (let n = 0; n < 10; n += 1) {
      const u = await signIn(env.DB, { provider: 'google', subject: `k-${n}`, name: 'Same Name' });
      expect(u.key).toMatch(/^[0-9a-hjkmnp-tv-z]{8}$/);
      keys.add(u.key);
    }
    expect(keys.size).toBe(10);
  });

  it('a new account called Matt is not the account that owns Matt\'s collection', async () => {
    // Ownership is the id, and a new sign-in gets a new one, so the
    // first Matthew through the door is not handed Matt's cards.
    const user = await signIn(env.DB, { provider: 'google', subject: '9', name: 'Matt' });
    expect(user.id).not.toBe(1);
    expect(user.slug).not.toBe('matt');
  });

  // ------------------------------------------------------- sessions

  it('a session is a token the browser keeps and a hash the database keeps', async () => {
    const user = await signIn(env.DB, { ...google, name: 'Some One' });
    const token = await newSession(env.DB, user.id);
    expect(token).toMatch(/^[A-Za-z0-9_-]{43}$/);

    const { results } = await env.DB.prepare('SELECT * FROM sessions').all();
    expect(results).toHaveLength(1);
    // The row has the hash and nothing resembling the token itself.
    expect(results[0].token_hash).toBe(await hashToken(token));
    expect(JSON.stringify(results[0])).not.toContain(token);
  });

  it('the token names its account', async () => {
    const user = await signIn(env.DB, { ...google, name: 'Some One' });
    const token = await newSession(env.DB, user.id);
    const found = await userForToken(env.DB, token);
    expect(found.id).toBe(user.id);
    expect(found.key).toBe(user.key);
  });

  it('a token nobody issued names nobody', async () => {
    expect(await userForToken(env.DB, 'not-a-real-token')).toBeNull();
    expect(await userForToken(env.DB, '')).toBeNull();
    expect(await userForToken(env.DB, null)).toBeNull();
  });

  it('a stored hash is not itself a usable token', async () => {
    // The point of hashing: somebody who reads the table cannot sign
    // in with what they found there.
    const user = await signIn(env.DB, { ...google, name: 'Some One' });
    const token = await newSession(env.DB, user.id);
    expect(await userForToken(env.DB, await hashToken(token))).toBeNull();
  });

  it('an expired session names nobody', async () => {
    const user = await signIn(env.DB, { ...google, name: 'Some One' });
    const token = await newSession(env.DB, user.id);
    await env.DB.prepare("UPDATE sessions SET expires_at = datetime('now', '-1 day')").run();
    expect(await userForToken(env.DB, token)).toBeNull();
  });

  it('logging out ends that session and leaves the others alone', async () => {
    const user = await signIn(env.DB, { ...google, name: 'Some One' });
    const phone = await newSession(env.DB, user.id);
    const laptop = await newSession(env.DB, user.id);
    await endSession(env.DB, phone);
    expect(await userForToken(env.DB, phone)).toBeNull();
    expect((await userForToken(env.DB, laptop)).id).toBe(user.id);
  });

  it('two sessions are two tokens', async () => {
    const user = await signIn(env.DB, { ...google, name: 'Some One' });
    expect(await newSession(env.DB, user.id)).not.toBe(await newSession(env.DB, user.id));
  });
});

/**
 * The public key a collection is shared by.
 *
 * Matt: "I need these to be scoped in the url [...] It's important
 * that you can share your own collection with other people.
 * Otherwise if i copy the url then they'll just go to their own
 * fucking collection"; "these slugs must be VERY unique. Probably a
 * small like 8 digit hash or something. Like a user key. BUT the
 * user key should NEVER be sufficient for edit privileges. It's just
 * a public user identifier."
 *
 * So the address carries a key and not a name. A name-derived slug
 * would have collided — two Matts, and the second one's URL is
 * `matt-2` — and worse, it is guessable, which matters for a thing
 * that is going to be pasted into chats.
 *
 * The key identifies and never authorises. Nothing anywhere asks
 * "does this request carry the right key"; editing asks who the
 * *session* says you are, which a URL cannot say.
 */
describe('a collection key', () => {
  const google = { provider: 'google', subject: 'k-1', name: 'Some One' };

  it('every account gets one', async () => {
    const user = await signIn(env.DB, google);
    expect(user.key).toMatch(/^[0-9a-hjkmnp-tv-z]{8}$/);
  });

  it('two accounts never share one', async () => {
    const a = await signIn(env.DB, { provider: 'google', subject: '1', name: 'Same Name' });
    const b = await signIn(env.DB, { provider: 'google', subject: '2', name: 'Same Name' });
    expect(a.key).not.toBe(b.key);
    // And the names collided, which is the thing a key is for.
    expect(b.display_name).toBe(a.display_name);
  });

  it('it is not a name, so it gives nothing away', async () => {
    const user = await signIn(env.DB, { ...google, name: 'Matthew Shoemaker' });
    expect(user.key).not.toMatch(/matt|shoe/i);
  });

  it('it leaves out the letters people mistype', async () => {
    // Crockford's alphabet: no i, l, o or u. A key gets read off a
    // screen and typed into another one.
    const keys = [];
    for (let i = 0; i < 40; i += 1) {
      keys.push((await signIn(env.DB, { provider: 'google', subject: `n-${i}`, name: 'X' })).key);
    }
    expect(keys.join('')).not.toMatch(/[ilou]/);
  });

  it('it never changes, because people paste it places', async () => {
    const first = await signIn(env.DB, google);
    const again = await signIn(env.DB, { ...google, name: 'Renamed Entirely' });
    expect(again.key).toBe(first.key);
  });

  it('a collection can be looked up by it, and says nothing private', async () => {
    const user = await signIn(env.DB, { ...google, email: 'private@example.com' });
    const r = await get(`/c/${user.key}`);
    expect(r.status).toBe(200);
    expect(r.body.key).toBe(user.key);
    expect(r.body.name).toBe('Some One');
    expect(r.body).not.toHaveProperty('slug');
    expect(r.body).not.toHaveProperty('id');
    // Somebody else's address is not their inbox.
    expect(JSON.stringify(r.body)).not.toContain('private@example.com');
  });

  it('a key nobody has is a 404', async () => {
    expect((await get('/c/zzzzzzzz')).status).toBe(404);
  });

  it('holding the key is not permission to edit', async () => {
    // The whole point. Anyone can read a collection by its key and
    // nobody can write to one by holding it.
    const user = await signIn(env.DB, google);
    const r = await postAnon('/cards/add', {
      collection: user.key, list: '1 Sol Ring (M3C) 409', dry_run: true,
    }, stubScryfall());
    expect(r.status).toBe(401);
  });
});
