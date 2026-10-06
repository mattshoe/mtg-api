import { describe, it, expect } from 'vitest';
import { env } from 'cloudflare:test';
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
    expect(two.slug).toBe('some-one-2');
  });

  it('a slug is a path segment and nothing else', () => {
    // It goes in a URL — `#/c/<slug>` — so anything that would need
    // escaping there has no business in one.
    expect(slugFor("Matt O'Shoemaker")).toBe('matt-o-shoemaker');
    expect(slugFor('  Ünïcødé  Name ')).toBe('unicode-name');
    expect(slugFor('///')).toBe('player');
    expect(slugFor('')).toBe('player');
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
