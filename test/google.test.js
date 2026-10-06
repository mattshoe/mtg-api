import { describe, it, expect } from 'vitest';
import { env } from 'cloudflare:test';
import * as jose from 'jose';
import { call, sql } from './helpers.js';
import { ISSUER } from '../src/google.js';

/**
 * Signing in with Google.
 *
 * The flow is `arctic`'s and the token verification is `jose`'s,
 * because the half of this that must not be hand-rolled is checking
 * a signature against a key set that rotates — Matt: "leverage this
 * party libraries where you can, and don't rebuild this from the
 * ground up."
 *
 * What is worth testing is not that the libraries work. It is that
 * this asks them the right questions: a token signed by the wrong
 * key, issued by the wrong party, meant for somebody else's client,
 * or long expired, all have to be refused — and a sign-in that
 * succeeds has to leave a session and nothing else.
 */
describe('sign in with Google', () => {
  /** A key pair standing in for Google's, and the JWKS it publishes. */
  async function googleKeys() {
    const { publicKey, privateKey } = await jose.generateKeyPair('RS256', { extractable: true });
    const jwk = await jose.exportJWK(publicKey);
    return { privateKey, jwks: { keys: [{ ...jwk, kid: 'test-key', alg: 'RS256', use: 'sig' }] } };
  }

  async function idToken(privateKey, claims = {}, { kid = 'test-key' } = {}) {
    return new jose.SignJWT({
      email: 'someone@example.com',
      name: 'Some One',
      picture: 'https://example.com/a.png',
      ...claims,
    })
      .setProtectedHeader({ alg: 'RS256', kid })
      .setIssuer(claims.iss ?? ISSUER)
      .setAudience(claims.aud ?? env.GOOGLE_CLIENT_ID)
      .setSubject(claims.sub ?? '1234567890')
      .setIssuedAt()
      .setExpirationTime(claims.exp ?? '5m')
      .sign(privateKey);
  }

  /**
   * Google, as far as this Worker can tell: the JWKS it publishes and
   * the token endpoint it answers on. Everything else 404s, so a call
   * this test did not anticipate fails loudly.
   */
  function stubGoogle(token) {
    return async (input) => {
      const u = typeof input === 'string' ? input : input.url;
      if (u.includes('/oauth2/v3/certs') || u.includes('jwks')) {
        return new Response(JSON.stringify(token.jwks), {
          headers: { 'content-type': 'application/json' },
        });
      }
      if (u.includes('/token')) {
        return new Response(JSON.stringify({
          access_token: 'at', token_type: 'Bearer', expires_in: 3599, id_token: token.idToken,
        }), { headers: { 'content-type': 'application/json' } });
      }
      return new Response('no', { status: 404 });
    };
  }

  /** Walk the whole flow: start it, then come back with the code. */
  async function signInWith(claims = {}, opts = {}) {
    const { privateKey, jwks } = await googleKeys();
    const token = { jwks, idToken: await idToken(privateKey, claims, opts) };

    const start = await call('/auth/google', { method: 'GET' });
    expect(start.status).toBe(302);
    const to = new URL(start.headers.get('location'));
    const state = to.searchParams.get('state');
    const cookie = start.headers.get('set-cookie');

    return call(`/auth/callback/google?code=abc&state=${state}`, {
      method: 'GET',
      headers: { cookie: cookie.split(';')[0] },
      fetchImpl: stubGoogle(token),
    });
  }

  // --------------------------------------------------------- starting

  it('sends you to Google, with a state and a challenge', async () => {
    const r = await call('/auth/google', { method: 'GET' });
    expect(r.status).toBe(302);
    const to = new URL(r.headers.get('location'));
    expect(to.origin + to.pathname).toBe('https://accounts.google.com/o/oauth2/v2/auth');
    expect(to.searchParams.get('client_id')).toBe(env.GOOGLE_CLIENT_ID);
    expect(to.searchParams.get('state')).toBeTruthy();
    // PKCE. Without it a stolen code is enough on its own.
    expect(to.searchParams.get('code_challenge')).toBeTruthy();
    expect(to.searchParams.get('code_challenge_method')).toBe('S256');
    expect(to.searchParams.get('scope')).toMatch(/openid/);
  });

  it('remembers the state and the verifier somewhere the browser cannot read', async () => {
    const r = await call('/auth/google', { method: 'GET' });
    const cookie = r.headers.get('set-cookie');
    expect(cookie).toMatch(/HttpOnly/i);
    expect(cookie).toMatch(/SameSite=Lax/i);
  });

  // --------------------------------------------------------- coming back

  it('a good token signs you in and leaves a session', async () => {
    const r = await signInWith();
    expect(r.status).toBe(302);
    expect(r.headers.get('set-cookie')).toMatch(/mtg_session=/);
    const users = await sql('SELECT slug, email FROM users');
    expect(users).toHaveLength(1);
    expect(users[0].email).toBe('someone@example.com');
    expect(await sql('SELECT 1 FROM sessions')).toHaveLength(1);
  });

  it('signing in twice is one account and two sessions', async () => {
    await signInWith();
    await signInWith();
    expect(await sql('SELECT 1 FROM users')).toHaveLength(1);
    expect(await sql('SELECT 1 FROM sessions')).toHaveLength(2);
  });

  it('a token signed by the wrong key is refused', async () => {
    const stranger = await jose.generateKeyPair('RS256', { extractable: true });
    const { jwks } = await googleKeys();
    const forged = await idToken(stranger.privateKey);
    const start = await call('/auth/google', { method: 'GET' });
    const state = new URL(start.headers.get('location')).searchParams.get('state');
    const r = await call(`/auth/callback/google?code=abc&state=${state}`, {
      method: 'GET',
      headers: { cookie: start.headers.get('set-cookie').split(';')[0] },
      fetchImpl: stubGoogle({ jwks, idToken: forged }),
    });
    expect(r.status).toBe(401);
    expect(await sql('SELECT 1 FROM users')).toHaveLength(0);
  });

  it('a token from the wrong issuer is refused', async () => {
    const r = await signInWith({ iss: 'https://evil.example.com' });
    expect(r.status).toBe(401);
    expect(await sql('SELECT 1 FROM users')).toHaveLength(0);
  });

  it('a token meant for somebody else\'s client is refused', async () => {
    // The one that matters most: a valid Google token, correctly
    // signed, issued for a different application entirely.
    const r = await signInWith({ aud: 'someone-elses-client.apps.googleusercontent.com' });
    expect(r.status).toBe(401);
    expect(await sql('SELECT 1 FROM users')).toHaveLength(0);
  });

  it('an expired token is refused', async () => {
    const r = await signInWith({ exp: Math.floor(Date.now() / 1000) - 60 });
    expect(r.status).toBe(401);
    expect(await sql('SELECT 1 FROM users')).toHaveLength(0);
  });

  it('a callback with the wrong state is refused', async () => {
    const start = await call('/auth/google', { method: 'GET' });
    const r = await call('/auth/callback/google?code=abc&state=not-the-state', {
      method: 'GET',
      headers: { cookie: start.headers.get('set-cookie').split(';')[0] },
    });
    expect(r.status).toBe(400);
  });

  it('a callback with no state at all is refused', async () => {
    const r = await call('/auth/callback/google?code=abc', { method: 'GET' });
    expect(r.status).toBe(400);
  });

  it('the session cookie is one a script cannot read', async () => {
    const r = await signInWith();
    const cookie = r.headers.get('set-cookie');
    expect(cookie).toMatch(/HttpOnly/i);
    expect(cookie).toMatch(/Secure/i);
    // `None`, not `Lax`: the site and the API are on different
    // registrable domains, so a Lax cookie would never be sent at
    // all. CORS is what keeps it to the sites we know.
    expect(cookie).toMatch(/SameSite=None/i);
  });

  it('a known site gets its own origin back, so the cookie can travel', async () => {
    const r = await call('/auth/me', {
      method: 'GET',
      headers: { origin: 'https://mtg.mattshoe.org' },
    });
    expect(r.headers.get('access-control-allow-origin')).toBe('https://mtg.mattshoe.org');
    expect(r.headers.get('access-control-allow-credentials')).toBe('true');
    expect(r.headers.get('vary')).toMatch(/origin/i);
  });

  it('anywhere else reads as a stranger and sends no cookie', async () => {
    // Still allowed to read — every collection is public — but with
    // `*` and no credentials, which is a browser's own refusal to
    // attach one.
    const r = await call('/auth/me', {
      method: 'GET',
      headers: { origin: 'https://evil.example.com' },
    });
    expect(r.headers.get('access-control-allow-origin')).toBe('*');
    expect(r.headers.get('access-control-allow-credentials')).toBeNull();
  });
});
