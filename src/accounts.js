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
// An account owns the rows whose `owner_id` is its `users.id`. The id
// is private: it is never in a URL, a response body or a log, and it
// is only ever reached by resolving a session. `users.key` is the
// public address of a collection, which lets you read it and nothing
// else.

const enc = new TextEncoder();

/**
 * Crockford's alphabet, less the letters people mistype.
 *
 * No i, l, o or u: the first three read as 1, 1 and 0 off a screen,
 * and leaving out u means a random key cannot spell anything
 * unfortunate. Eight characters of this is 32^8 — a thousand billion
 * — which is not a thing anybody enumerates.
 */
const KEY_ALPHABET = '0123456789abcdefghjkmnpqrstvwxyz';
const KEY_LENGTH = 8;

/**
 * The public identifier a collection is shared by.
 *
 * In the address, so a link somebody pastes into a chat opens *their*
 * collection rather than whatever the reader's own happens to be.
 * Not derived from the name: two people called Matt would collide,
 * and a guessable address is a poor thing to hand out.
 *
 * It identifies and never authorises. Nothing anywhere asks whether a
 * request carries the right key — editing asks who the session says
 * you are, which an address cannot say.
 */
export function newKey() {
  const bytes = crypto.getRandomValues(new Uint8Array(KEY_LENGTH));
  return [...bytes].map((b) => KEY_ALPHABET[b % KEY_ALPHABET.length]).join('');
}

/** A key nobody is using yet. */
async function freeKey(db) {
  for (let tries = 0; tries < 10; tries += 1) {
    const key = newKey();
    const taken = await db.prepare('SELECT 1 FROM users WHERE key = ?1').bind(key).first();
    if (!taken) return key;
  }
  throw new Error('could not find a free collection key');
}

/** How long a session lasts without being used again. */
const SESSION_DAYS = 90;

const b64url = (bytes) => btoa(String.fromCharCode(...new Uint8Array(bytes)))
  .replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');

/** The only form of a session token this database ever holds. */
export async function hashToken(token) {
  return b64url(await crypto.subtle.digest('SHA-256', enc.encode(String(token))));
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
    // picture follows you in. Nothing it owns hangs off the name.
    await db.prepare(
      'UPDATE users SET display_name = ?2, email = ?3, avatar_url = ?4 WHERE id = ?1',
    ).bind(existing.id, name ?? existing.display_name, email, avatar).run();
    return db.prepare('SELECT * FROM users WHERE id = ?1').bind(existing.id).first();
  }

  // `slug` is retired and read by nothing, but the live column is
  // NOT NULL UNIQUE until a later migration drops it, so it is given
  // the key, which is both. A clash on the key is the only UNIQUE
  // this can lose, and the loser simply draws another.
  let user = null;
  for (let tries = 0; tries < 5 && !user; tries += 1) {
    const key = await freeKey(db);
    try {
      user = await db.prepare(
        `INSERT INTO users (key, slug, display_name, email, avatar_url, role, created_at)
         VALUES (?1, ?1, ?2, ?3, ?4, 'user', datetime('now'))
         RETURNING *`,
      ).bind(key, name, email, avatar).first();
    } catch (e) {
      if (!/UNIQUE/i.test(String(e?.message ?? e))) throw e;
    }
  }
  if (!user) throw new Error('could not create an account');
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
export function canEdit(who, ownerId) {
  // The operator's password, which is a machine: `scripts/backup.py`,
  // `refresh_prices.py` and `tags.mjs` have no account to sign
  // into. A machine credential is not a login.
  if (who?.operator) return true;
  // The admin role, which Matt hands out by name: "Anyone with the
  // admin role will be able to do whatever they want, from modify
  // others cards to giving other users admin etc etc."
  //
  // This line came out for an hour this morning, on "NOBODY GETS
  // FUCKING ADMIN PERMISSIONS!!!!!! YOU JUST GET TO MODIFY YOUR OWN
  // FUCKING CARDS BY DEFAULT!!!!!" — which is about the default, not
  // about what the role means once it is granted. Both hold at once:
  // every new account is a `user` and a `user` owns only its own
  // cards, and nobody is an `admin` unless Matt says so.
  if (who?.user?.role === ADMIN) return true;
  // Ids, both of them: the owner's from the row, and the caller's from
  // the session. Nothing in the request body can reach this line.
  return Number.isInteger(ownerId) && Number.isInteger(who?.user?.id) && who.user.id === ownerId;
}

/**
 * The account a public key names, as the private id a write is
 * checked against. Null for a key nobody has. Never handed to a
 * client: it goes straight into `canEdit` and an INSERT.
 */
export async function ownerOfKey(db, key) {
  if (!key) return null;
  const row = await db.prepare('SELECT id FROM users WHERE key = ?1').bind(String(key)).first();
  return row?.id ?? null;
}

/** The only two roles there are. `user` is the floor, `admin` the ceiling. */
export const USER = 'user';
export const ADMIN = 'admin';
export const ROLES = [USER, ADMIN];

/**
 * Everybody, for the admin screen's list.
 *
 * No email and no id. A role list is not a mailing list, and the page
 * exists to answer "who is there and what are they": a name, the key
 * their collection is shared by, and the role. A row with no display
 * name shows its email's local part rather than something invented.
 */
export async function allUsers(db) {
  const r = await db.prepare(
    `SELECT key, display_name, email, avatar_url, role, created_at
       FROM users ORDER BY role DESC, COALESCE(display_name, email, key) COLLATE NOCASE`,
  ).all();
  return (r.results || []).map((u) => ({
    key: u.key,
    name: u.display_name || String(u.email || '').split('@')[0] || null,
    avatar: u.avatar_url || null,
    role: u.role,
    since: u.created_at,
  }));
}

/**
 * Hand a role out, or take it back.
 *
 * Returns `{ ok }` or `{ error, status }` — the refusals are the
 * interesting part:
 *
 * - a role that is not one of the two, because a typo that lands in
 *   the column is an account nobody can classify
 * - a key nobody has
 *
 * The last admin demoting themselves is allowed. It leaves a database
 * no browser can promote anybody from — `ADMIN_PASSWORD` is the way
 * back — but it is Matt's database and his decision to make.
 */
export async function setRole(db, key, role) {
  if (!ROLES.includes(role)) {
    return { error: `a role is ${ROLES.join(' or ')}, not ${JSON.stringify(role)}`, status: 400 };
  }
  const row = await db.prepare('SELECT id, role FROM users WHERE key = ?1').bind(String(key ?? '')).first();
  if (!row) return { error: `there is no account at ${key}`, status: 404 };
  // The last admin demoting themselves used to be a 409 here. Matt:
  // "I want to be able to assign and remove roles at will!!!! I don't
  // want to need you for it!!!" — and a refusal is the shape of
  // needing somebody. It is still the one change nothing in a browser
  // can undo, so the screen says so on the row; the decision is not
  // taken away from the person making it.
  await db.prepare('UPDATE users SET role = ?2 WHERE id = ?1').bind(row.id, role).run();
  return { ok: true, key: String(key), role };
}

/**
 * What anybody is allowed to know about a collection they found the
 * address of. A public address is not an inbox: no email.
 */
export async function collectionByKey(db, key) {
  if (!key) return null;
  const row = await db.prepare(
    'SELECT key, display_name, avatar_url FROM users WHERE key = ?1',
  ).bind(String(key)).first();
  if (!row) return null;
  return {
    key: row.key,
    name: row.display_name,
    avatar: row.avatar_url,
  };
}

/** What a caller is allowed to know about themselves. */
export function profileOf(user) {
  if (!user) {
    return { key: null, name: null, email: null, avatar: null, role: null };
  }
  return {
    key: user.key,
    name: user.display_name,
    email: user.email,
    avatar: user.avatar_url,
    role: user.role,
  };
}
