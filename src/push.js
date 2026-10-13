// Telling a phone something happened.
//
// Firebase Cloud Messaging, the HTTP v1 API. There is no API key for
// this: every send is authorised by an OAuth2 access token, and the only
// way to get one is to sign a JWT with the service account's private key
// and trade it in. That is the whole of the complexity here, and it is
// about thirty lines.
//
// The alternative, the legacy server key, was turned off by Google in
// 2024. There is no simpler supported path.

const TOKEN_URL = 'https://oauth2.googleapis.com/token';
const SCOPE = 'https://www.googleapis.com/auth/firebase.messaging';

/** base64url, which JWTs use and `btoa` does not produce. */
function b64url(bytes) {
  const s = typeof bytes === 'string' ? bytes : String.fromCharCode(...new Uint8Array(bytes));
  return btoa(s).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

/** A PEM private key, as `crypto.subtle` wants it. */
async function importKey(pem) {
  const body = String(pem)
    .replace(/-----[A-Z ]+-----/g, '')
    .replace(/\s+/g, '');
  const der = Uint8Array.from(atob(body), (c) => c.charCodeAt(0));
  return crypto.subtle.importKey(
    'pkcs8',
    der,
    { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' },
    false,
    ['sign'],
  );
}

/**
 * An access token good for an hour, bought with a signed assertion.
 *
 * Cached on the isolate: a token lasts an hour and a send that minted a
 * fresh one every time would spend more time at Google's token endpoint
 * than at Firebase. `skew` is 60s of slack so a token is never used in
 * the last minute of its life.
 */
let cached = null;
export async function accessToken(creds, { now = Date.now, fetch: f = fetch } = {}) {
  const skew = 60_000;
  if (cached && cached.until - skew > now()) return cached.token;

  const iat = Math.floor(now() / 1000);
  const claim = {
    iss: creds.client_email,
    scope: SCOPE,
    aud: TOKEN_URL,
    iat,
    exp: iat + 3600,
  };
  const head = b64url(JSON.stringify({ alg: 'RS256', typ: 'JWT' }));
  const body = b64url(JSON.stringify(claim));
  const key = await importKey(creds.private_key);
  const sig = await crypto.subtle.sign(
    'RSASSA-PKCS1-v1_5',
    key,
    new TextEncoder().encode(`${head}.${body}`),
  );
  const assertion = `${head}.${body}.${b64url(sig)}`;

  const res = await f(TOKEN_URL, {
    method: 'POST',
    headers: { 'content-type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({
      grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer',
      assertion,
    }),
  });
  const json = await res.json().catch(() => ({}));
  if (!res.ok || !json.access_token) {
    throw new Error(`could not get a push token: ${json.error_description || json.error || res.status}`);
  }
  cached = { token: json.access_token, until: now() + (json.expires_in || 3600) * 1000 };
  return cached.token;
}

/** Forget the cached token. Tests, and anything that rotates the key. */
export function forgetToken() { cached = null; }

/**
 * Whether Firebase is saying this device is gone for good.
 *
 * A token dies when the app is uninstalled, and Firebase answers
 * `UNREGISTERED` or a 404 for it forever after. Those rows are marked
 * dead rather than retried on every task; anything else — a timeout,
 * a 500, a quota — is this minute's problem and the row is left alone.
 */
export function isDead(status, body) {
  if (status === 404) return true;
  const code = body?.error?.details?.find?.((d) => d.errorCode)?.errorCode
    || body?.error?.status;
  return code === 'UNREGISTERED' || code === 'INVALID_ARGUMENT' || code === 'NOT_FOUND';
}

/**
 * Send one notification to one device.
 *
 * Returns `{ ok }` or `{ dead }`, and never throws for a single bad
 * token — one uninstalled phone must not stop the others being told.
 */
export async function sendTo(token, note, { creds, fetch: f = fetch } = {}) {
  const at = await accessToken(creds, { fetch: f });
  const url = `https://fcm.googleapis.com/v1/projects/${creds.project_id}/messages:send`;
  const res = await f(url, {
    method: 'POST',
    headers: { authorization: `Bearer ${at}`, 'content-type': 'application/json' },
    body: JSON.stringify({
      message: {
        token,
        notification: { title: note.title, body: note.body },
        // What the app opens when it is tapped. The key is the task's,
        // so the phone lands on the task rather than the list.
        data: note.data || {},
        android: { priority: 'high' },
      },
    }),
  });
  if (res.ok) return { ok: true };
  const body = await res.json().catch(() => ({}));
  if (isDead(res.status, body)) return { dead: true };
  return { ok: false, error: body?.error?.message || `HTTP ${res.status}` };
}

/**
 * What a task transition says on a phone, or null for one not worth
 * waking anybody for.
 *
 * Two things only. Matt: "I only want to be notified when a task CANNOT
 * be recovered, or when a task is deployed."
 *
 * - `blocked` is the one that cannot recover. Something needs him, and
 *   until he does it nothing else will happen — which is the whole
 *   reason a phone is involved.
 * - `deployed` is the one he is waiting for: it is live on the site and
 *   the phone, and he can go and look at it.
 *
 * Everything else is noise, and noise is how notifications get turned
 * off. `merged` was buzzing a few minutes before `deployed` said the
 * same thing properly. `cancelled` only ever happens because he
 * cancelled it. `in progress` and `in review` fire several times an
 * hour. The first version of this sent all of them and he had it for
 * about an hour before saying so.
 */
export function noteFor(task) {
  const title = task.title || task.name || 'A task';
  if (task.status === 'deployed') {
    return { title: `${title} is live`, body: task.note || 'Deployed to the site and the app.' };
  }
  if (task.status === 'blocked') {
    return { title: `${title} needs you`, body: task.note || 'It stopped and cannot carry on by itself.' };
  }
  return null;
}
