// Admin mode — the password gate in front of anything that writes.
//
// Reads stay open. Writes need a token, and the only way to get one is to
// present the password to POST /admin.
//
// The token is a signed expiry, not a stored session: `<exp>.<hmac>`, where
// the HMAC key is the password itself. Nothing to persist, nothing to clean
// up, and revoking everything outstanding is one `wrangler secret put`.
//
// Worth being clear about what this is and is not. It stops a stray curl, a
// bookmarked page left open, and an agent that wandered off its instructions.
// It is not protection against someone who has the password, and the token
// travels in a header over TLS like any bearer token.

const TTL_SECONDS = 12 * 60 * 60;

const enc = new TextEncoder();

const b64url = (bytes) => btoa(String.fromCharCode(...new Uint8Array(bytes)))
  .replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');

async function sign(password, payload) {
  const key = await crypto.subtle.importKey(
    'raw', enc.encode(password), { name: 'HMAC', hash: 'SHA-256' }, false, ['sign'],
  );
  return b64url(await crypto.subtle.sign('HMAC', key, enc.encode(payload)));
}

/** Constant-time compare, so a wrong guess leaks nothing through timing. */
function equal(a, b) {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i += 1) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

/** Password in, token out. Null when the password is wrong. */
export async function mintToken(env, password) {
  const secret = env.ADMIN_PASSWORD;
  if (!secret) throw new Error('ADMIN_PASSWORD is not configured on this Worker');
  if (typeof password !== 'string' || !equal(password, secret)) return null;

  const exp = Math.floor(Date.now() / 1000) + TTL_SECONDS;
  return { token: `${exp}.${await sign(secret, String(exp))}`, expires_at: exp };
}

/** -> { ok } | { ok: false, reason } */
export async function verifyToken(env, token) {
  const secret = env.ADMIN_PASSWORD;
  if (!secret) return { ok: false, reason: 'ADMIN_PASSWORD is not configured on this Worker' };
  if (!token) return { ok: false, reason: 'admin mode required' };

  const [expPart, mac] = String(token).split('.');
  if (!expPart || !mac) return { ok: false, reason: 'malformed admin token' };

  const expected = await sign(secret, expPart);
  if (!equal(mac, expected)) return { ok: false, reason: 'invalid admin token' };

  const exp = Number(expPart);
  if (!Number.isFinite(exp) || exp * 1000 < Date.now()) {
    return { ok: false, reason: 'admin session expired — unlock again' };
  }
  return { ok: true };
}

/** The bearer token on a request, if there is one. */
export function bearer(request) {
  const h = request.headers.get('authorization') || '';
  const m = /^Bearer\s+(.+)$/i.exec(h.trim());
  return m ? m[1] : null;
}
