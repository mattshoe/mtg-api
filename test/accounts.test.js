import { describe, it, expect } from 'vitest';
import { env } from 'cloudflare:test';
import { get, postAnon, stubScryfall } from './helpers.js';
import {
  signIn, newSession, userForToken, endSession, slugFor, hashToken,
} from '../src/accounts.js';

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
 * `cards.owner` and `decks.owner` already hold a slug, so `users.slug`
 * is the join and the collection needs no migration: an account whose
 * slug is `matt` owns every row that already says `matt`.
 */
describe('accounts', () => {
  const google = { provider: 'google', subject: '1234567890' };

  it('a first sign-in creates the account and its identity', async () => {
    const user = await signIn(env.DB, {
      ...google, email: 'someone@example.com', name: 'Some One',
    });
    expect(user.id).toBeGreaterThan(0);
    expect(user.slug).toBe('some-one');
    expect(user.email).toBe('someone@example.com');
    // Every account owns its own collection and nothing else. The
    // server-wide role is a separate thing and starts off.
    expect(user.role).toBe('user');
  });

  it('signing in again finds the same account rather than making another', async () => {
    const first = await signIn(env.DB, { ...google, email: 'a@example.com', name: 'Some One' });
    const again = await signIn(env.DB, { ...google, email: 'a@example.com', name: 'Some One' });
    expect(again.id).toBe(first.id);
    const { results } = await env.DB.prepare('SELECT COUNT(*) AS n FROM users').all();
    expect(results[0].n).toBe(1);
  });

  it('two people with the same name get different slugs', async () => {
    const one = await signIn(env.DB, { provider: 'google', subject: '1', name: 'Some One' });
    const two = await signIn(env.DB, { provider: 'google', subject: '2', name: 'Some One' });
    expect(one.slug).toBe('some-one');
    expect(two.slug).toMatch(/^some-one-[0-9a-hjkmnp-tv-z]{4}$/);
  });

  it('a slug is a path segment and nothing else', () => {
    // It goes in a URL — `#/c/<slug>` — so anything that would need
    // escaping there has no business in one.
    expect(slugFor("Matt O'Shoemaker")).toBe('matt-o-shoemaker');
    expect(slugFor('  Ünïcødé  Name ')).toBe('unicode-name');
    expect(slugFor('///')).toBe('player');
    expect(slugFor('')).toBe('player');
  });

  /**
   * The slug stops counting at a collision.
   *
   * Matt: "What slug when we have thousands of users??? How are we
   * going to keep them distinct???"
   *
   * It used to try `base-2`, `base-3` … up to `base-999` and then
   * throw, which is a query per attempt on the sign-in path and a
   * hard failure for the thousandth John Smith. Four random
   * characters cost one query and have no ceiling.
   *
   * None of this is what keeps accounts apart: `users.key` is, and it
   * is eight characters of a 32-letter alphabet — a trillion of them.
   * The slug is the word in `cards.owner` and on the screen, and it
   * only has to be unique, not meaningful.
   */
  it('a crowd of the same name all get their own slug', async () => {
    const seen = new Set();
    for (let n = 0; n < 25; n += 1) {
      const u = await signIn(env.DB, { provider: 'google', subject: `crowd-${n}`, name: 'John Smith' });
      expect(seen.has(u.slug), `${u.slug} was handed out twice`).toBe(false);
      seen.add(u.slug);
    }
    expect(seen.size).toBe(25);
    // The first one gets the clean name; the rest are marked.
    expect(seen.has('john-smith')).toBe(true);
    [...seen].filter((s) => s !== 'john-smith').forEach((s) => {
      expect(s, `${s} is not the name plus a suffix`).toMatch(/^john-smith-[0-9a-hjkmnp-tv-z]{4}$/);
    });
  });

  it('and none of them is a number anybody could guess the next of', async () => {
    // `john-smith-2` tells you there is a `john-smith` and invites a
    // `john-smith-3`. A random suffix says nothing.
    await signIn(env.DB, { provider: 'google', subject: 'g1', name: 'Jane Doe' });
    const second = await signIn(env.DB, { provider: 'google', subject: 'g2', name: 'Jane Doe' });
    expect(second.slug).not.toBe('jane-doe-2');
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

  it('a slug cannot collide with a collection that already exists', async () => {
    // `cards.owner` holds slugs today and nobody has signed in yet, so
    // the names already in the collection have to be reserved or the
    // first Matthew to sign in would be handed Matt's cards.
    const user = await signIn(env.DB, { provider: 'google', subject: '9', name: 'Matt' });
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
    expect(found.slug).toBe(user.slug);
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
    expect(b.slug).toMatch(/^same-name-[0-9a-hjkmnp-tv-z]{4}$/);
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
    expect(r.body.slug).toBe(user.slug);
    expect(r.body.name).toBe('Some One');
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
      owner: user.slug, list: '1 Sol Ring (M3C) 409', dry_run: true, key: user.key,
    }, stubScryfall());
    expect(r.status).toBe(401);
  });
});
