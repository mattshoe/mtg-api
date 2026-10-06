// Accounts, and the sessions that stand for them.
//
// There are no passwords here. Google holds the credential and this
// never sees one, which is the strongest possible answer to "do not
// store plain text passwords". What this does hold is a session
// token, and a session token is a credential in every way that
// matters — anyone holding it is you until it expires — so it gets
// the same treatment a password would: the browser keeps the token,
// the database keeps only its SHA-256.
//
// `cards.owner` and `decks.owner` already hold a slug, so `users.slug`
// is the join between an account and its collection and nothing in
// the collection has to move. An account whose slug is `matt` owns
// every row that already says `matt`.

const enc = new TextEncoder();

/** How long a session lasts without being used again. */
const SESSION_DAYS = 90;

const b64url = (bytes) => btoa(String.fromCharCode(...new Uint8Array(bytes)))
  .replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');

/** The only form of a session token this database ever holds. */
export async function hashToken(token) {
  return b64url(await crypto.subtle.digest('SHA-256', enc.encode(String(token))));
}

const FOLD = {
  // The Latin letters NFD cannot take apart, because each is a letter
  // in its own right rather than a letter with a mark on it. Without
  // these, "Ünïcødé" comes out "unic-de" — the ø silently becoming a
  // word break rather than an o.
  'ø': 'o', 'æ': 'ae', 'œ': 'oe', 'ð': 'd', 'þ': 'th', 'ł': 'l', 'đ': 'd', 'ß': 'ss',
};

/**
 * A display name, as a path segment.
 *
 * It goes in a URL — `#/c/<slug>` — so anything that would need
 * escaping there has no business in one. Accents are folded rather
 * than dropped, because "Ünïcødé" losing four letters is worse than
 * it losing its diacritics.
 */
export function slugFor(name) {
  const flat = String(name ?? '')
    .normalize('NFD')
    .replace(/[\u0300-\u036f]/g, '')
    .toLowerCase()
    .replace(/[øæœðþłđß]/g, (c) => FOLD[c])
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '');
  return flat || 'player';
}

/**
 * Whether that slug is already spoken for.
 *
 * By an account, or by a collection that predates accounts. The
 * second half matters today: `cards.owner` has held `matt` and
 * `kayla` since long before anybody could sign in, and without this
 * the first Matt through the door would be handed Matt's cards.
 */
async function taken(db, slug) {
  const { results } = await db.prepare(
    `SELECT 1 AS n FROM users WHERE slug = ?1
      UNION ALL SELECT 1 FROM cards WHERE owner = ?1
      UNION ALL SELECT 1 FROM decks WHERE owner = ?1
      LIMIT 1`,
  ).bind(slug).all();
  return results.length > 0;
}

/** The first free slug built off that name. */
async function freeSlug(db, name) {
  const base = slugFor(name);
  if (!(await taken(db, base))) return base;
  for (let n = 2; n < 1000; n += 1) {
    const candidate = `${base}-${n}`;
    if (!(await taken(db, candidate))) return candidate;
  }
  throw new Error(`no free slug for ${base}`);
}

/**
 * Who signed in, creating the account the first time.
 *
 * Keyed on `(provider, subject)` and never on the email address: a
 * Google account's subject is stable and its email is not, and
 * trusting the email would let anybody who can get an address issued
 * to them walk into somebody else's collection.
 */
export async function signIn(db, { provider, subject, email = null, name = null, avatar = null }) {
  const existing = await db.prepare(
    `SELECT u.* FROM identities i JOIN users u ON u.id = i.user_id
      WHERE i.provider = ?1 AND i.subject = ?2`,
  ).bind(provider, String(subject)).first();
  if (existing) {
    // The profile travels with the identity, so a changed name or
    // picture follows you in. The slug does not: it is an address
    // somebody may have bookmarked.
    await db.prepare(
      'UPDATE users SET display_name = ?2, email = ?3, avatar_url = ?4 WHERE id = ?1',
    ).bind(existing.id, name ?? existing.display_name, email, avatar).run();
    return db.prepare('SELECT * FROM users WHERE id = ?1').bind(existing.id).first();
  }

  const slug = await freeSlug(db, name || email || 'player');
  const user = await db.prepare(
    `INSERT INTO users (slug, display_name, email, avatar_url, role, created_at)
     VALUES (?1, ?2, ?3, ?4, 'user', datetime('now'))
     RETURNING *`,
  ).bind(slug, name, email, avatar).first();
  await db.prepare(
    'INSERT INTO identities (provider, subject, user_id, created_at) VALUES (?1, ?2, ?3, datetime(\'now\'))',
  ).bind(provider, String(subject), user.id).run();
  return user;
}

/** A new session. Returns the token; the database never sees it again. */
export async function newSession(db, userId) {
  const token = b64url(crypto.getRandomValues(new Uint8Array(32)));
  await db.prepare(
    `INSERT INTO sessions (token_hash, user_id, created_at, expires_at)
     VALUES (?1, ?2, datetime('now'), datetime('now', ?3))`,
  ).bind(await hashToken(token), userId, `+${SESSION_DAYS} days`).run();
  return token;
}

/** The account that token stands for, or null. */
export async function userForToken(db, token) {
  if (!token || typeof token !== 'string') return null;
  const row = await db.prepare(
    `SELECT u.* FROM sessions s JOIN users u ON u.id = s.user_id
      WHERE s.token_hash = ?1 AND s.expires_at > datetime('now')`,
  ).bind(await hashToken(token)).first();
  return row ?? null;
}

/** Log out — this session only, not every session you have open. */
export async function endSession(db, token) {
  if (!token) return;
  await db.prepare('DELETE FROM sessions WHERE token_hash = ?1')
    .bind(await hashToken(token)).run();
}

/**
 * Who is making this request.
 *
 * Two kinds of bearer token arrive here and they are told apart by
 * asking, not by their shape: a session token, which names an
 * account, and a password-derived admin token, which names the
 * server's operator and no account at all. The second is what the
 * nightly job and the seeding scripts hold.
 *
 * The cookie is for the website, which has one; the header is for
 * the phone, which would rather not.
 */
export async function whoAmI(env, request, verifyAdminToken) {
  const header = request.headers.get('authorization') || '';
  const bearerToken = header.toLowerCase().startsWith('bearer ') ? header.slice(7).trim() : '';
  const cookie = cookieValue(request.headers.get('cookie'), SESSION_COOKIE);

  for (const token of [bearerToken, cookie]) {
    if (!token) continue;
    const user = await userForToken(env.DB, token);
    if (user) return { user, operator: false, token, bearer: bearerToken };
  }
  if (bearerToken && verifyAdminToken) {
    const v = await verifyAdminToken(env, bearerToken);
    if (v.ok) return { user: null, operator: true, token: bearerToken, bearer: bearerToken };
  }
  // `bearer` is kept whether or not it worked, so a caller can be
  // told *how* its token failed rather than only that it did.
  return { user: null, operator: false, token: null, bearer: bearerToken };
}

export const SESSION_COOKIE = 'mtg_session';

/** One cookie out of the header, without pulling in a parser for it. */
export function cookieValue(header, name) {
  if (!header) return '';
  for (const part of header.split(';')) {
    const at = part.indexOf('=');
    if (at < 0) continue;
    if (part.slice(0, at).trim() === name) return decodeURIComponent(part.slice(at + 1).trim());
  }
  return '';
}

/**
 * May this caller change that collection?
 *
 * Owning it is the whole of the ordinary answer. Matt: "accounts have
 * admin rights by default for their own cards and only their own
 * cards" — so there is no flag that lets one account edit the
 * collection next door, only the account that owns it.
 *
 * The two exceptions are both about running the server rather than
 * owning a collection: the password the scripts hold, and the
 * server-wide `role` that will replace it.
 */
export function canEdit(who, ownerSlug) {
  if (who?.operator) return true;
  if (who?.user?.role === 'admin') return true;
  return Boolean(ownerSlug) && who?.user?.slug === ownerSlug;
}

/** What a caller is allowed to know about themselves. */
export function profileOf(user) {
  if (!user) return { slug: null, name: null, email: null, avatar: null, role: null };
  return {
    slug: user.slug,
    name: user.display_name,
    email: user.email,
    avatar: user.avatar_url,
    role: user.role,
  };
}
