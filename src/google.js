// Signing in with Google.
//
// The flow is `arctic`'s and the token verification is `jose`'s. The
// half of this nobody should hand-roll is checking a signature
// against a key set that rotates, and getting the issuer, audience
// and expiry checks all right while doing it — so neither is written
// here. What is written here is which questions to ask.
//
// Two things this does not do, deliberately. It never trusts the
// email address as an identity: Google's `sub` is stable and an email
// is not, and an address somebody else is later issued would
// otherwise walk into their collection. And it never accepts a token
// without checking `aud`, which is the difference between "a valid
// Google token" and "a token meant for this application".

// `arctic` builds the authorization URL and the PKCE pair; the
// exchange below is written out because it takes no fetch of its own.
import { Google, generateState, generateCodeVerifier } from 'arctic';
import * as jose from 'jose';

export const ISSUER = 'https://accounts.google.com';
const JWKS_URL = 'https://www.googleapis.com/oauth2/v3/certs';
const TOKEN_URL = 'https://oauth2.googleapis.com/token';

/** The scopes. The least that identifies a person and names them. */
const SCOPES = ['openid', 'email', 'profile'];

/**
 * Where the browser is sent back to.
 *
 * Part of the signature Google checks, so it has to match what is
 * registered in the console exactly.
 */
export const callbackUrl = (env) => `${apiOrigin(env)}/auth/callback/google`;

const apiOrigin = (env) => env.API_URL || 'https://mtg-api.mattshoe81.workers.dev';

function client(env) {
  if (!env.GOOGLE_CLIENT_ID || !env.GOOGLE_CLIENT_SECRET) {
    throw new Error('GOOGLE_CLIENT_ID and GOOGLE_CLIENT_SECRET are not configured');
  }
  return new Google(env.GOOGLE_CLIENT_ID, env.GOOGLE_CLIENT_SECRET, callbackUrl(env));
}

/** The URL to send somebody to, and the two secrets that go with it. */
export function startSignIn(env) {
  const state = generateState();
  const verifier = generateCodeVerifier();
  return {
    url: client(env).createAuthorizationURL(state, verifier, SCOPES).toString(),
    state,
    verifier,
  };
}

/**
 * The code, exchanged for a verified identity.
 *
 * Throws when anything about the token is wrong, which is every case
 * where signing somebody in would be a mistake.
 */
export async function finishSignIn(env, code, verifier, fetchImpl) {
  const http = fetchImpl || fetch;
  // The exchange is written out rather than taken from `arctic`,
  // which uses the global `fetch` and gives no way to pass another
  // one — so the only way to test this half would have been to swap
  // `globalThis.fetch` out from under it. It is a documented form
  // POST and the part that must not be hand-rolled is the signature
  // check below, which is not.
  const res = await http(TOKEN_URL, {
    method: 'POST',
    headers: { 'content-type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({
      grant_type: 'authorization_code',
      code,
      code_verifier: verifier,
      client_id: env.GOOGLE_CLIENT_ID,
      client_secret: env.GOOGLE_CLIENT_SECRET,
      redirect_uri: callbackUrl(env),
    }),
  });
  const tokens = await res.json().catch(() => null);
  if (!res.ok || !tokens?.id_token) {
    throw new Error(`google refused the code: ${tokens?.error || res.status}`);
  }
  const idToken = tokens.id_token;

  return verifyIdToken(env, idToken, fetchImpl);
}

/**
 * A Google ID token, checked and turned into an identity.
 *
 * Shared by the two ways in. The browser gets here after exchanging a
 * code; the phone gets here with a token Credential Manager handed
 * it directly and no code at all — and the checks have to be the
 * same either way, because a token is a token however it arrived.
 */
export async function verifyIdToken(env, idToken, fetchImpl) {
  const keys = jose.createRemoteJWKSet(new URL(JWKS_URL), {
    ...(fetchImpl ? { [jose.customFetch]: fetchImpl } : {}),
  });
  const { payload } = await jose.jwtVerify(idToken, keys, {
    issuer: [ISSUER, 'accounts.google.com'],
    // The check that separates "a valid Google token" from "a token
    // for this application". Without it, anybody with a token from
    // any Google-signed app could present it here.
    //
    // The phone's own OAuth clients are not in this list on purpose:
    // Credential Manager is given the *web* client id as its
    // `serverClientId`, so the token it produces is audienced to the
    // same client the browser's is.
    audience: env.GOOGLE_CLIENT_ID,
  });

  return {
    provider: 'google',
    subject: payload.sub,
    email: payload.email ?? null,
    name: payload.name ?? payload.email ?? null,
    avatar: payload.picture ?? null,
  };
}
