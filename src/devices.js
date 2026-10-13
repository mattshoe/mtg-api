// Which phones to tell.
//
// A device says "this is my current Firebase token" every time the app
// starts. The token rotates on its own — a reinstall, a restore,
// sometimes nothing visible at all — so this is an upsert keyed on the
// token itself, and a device that comes back with a new one simply adds
// a row while its old one is dropped the first time Firebase says it is
// dead. There is no device id to keep in step, which is the point.

import { allUsers } from './accounts.js';

const MAX_TOKEN = 400;

/**
 * Android, and nothing else.
 *
 * Matt: "web does NOT need any pushes for any reason ever right now."
 * The website is open in front of him when he is using it and has
 * nothing to tell him that the page is not already showing. iOS does not
 * exist. Both were in this list for a few minutes because a set of three
 * looked more finished than a set of one, which is the wrong reason to
 * carry a path nothing walks — a `web` row here would be a row the send
 * loop tries, fails on, and marks dead forever.
 *
 * Adding one back is this line and a test. Until then a web registration
 * is refused, loudly, rather than accepted and silently never used.
 */
const PLATFORMS = new Set(['android']);

/** Remember where to reach somebody. */
export async function remember(db, userId, body) {
  const token = String(body?.token || '').trim();
  const platform = String(body?.platform || 'android').trim().toLowerCase();
  if (!token || token.length > MAX_TOKEN) {
    return { status: 400, body: { error: 'send the device token' } };
  }
  if (!PLATFORMS.has(platform)) {
    return { status: 400, body: { error: `platform is one of ${[...PLATFORMS].join(', ')}` } };
  }
  const label = body?.label ? String(body.label).slice(0, 80) : null;
  const now = new Date().toISOString();
  await db.prepare(
    `INSERT INTO devices (token, user_id, platform, label, created_at, seen_at, dead_at)
     VALUES (?1, ?2, ?3, ?4, ?5, ?5, NULL)
     ON CONFLICT(token) DO UPDATE SET
       user_id = excluded.user_id,
       platform = excluded.platform,
       label = COALESCE(excluded.label, devices.label),
       seen_at = excluded.seen_at,
       -- Back from the dead: the same token arriving again means the app
       -- is installed and listening, whatever Firebase said last week.
       dead_at = NULL`,
  ).bind(token, userId, platform, label, now).run();
  return { status: 200, body: { ok: true } };
}

/** Stop sending to a device, for good. */
export async function forget(db, token) {
  await db.prepare('DELETE FROM devices WHERE token = ?1').bind(String(token || '')).run();
  return { status: 200, body: { ok: true } };
}

/** Mark a token Firebase has told us is gone. */
export async function markDead(db, token) {
  await db.prepare('UPDATE devices SET dead_at = ?2 WHERE token = ?1')
    .bind(String(token || ''), new Date().toISOString()).run();
}

/**
 * Every live device belonging to somebody who should hear about tasks.
 *
 * Admins only, decided here at send time rather than frozen into the
 * row — so taking somebody's admin away stops their pushes without
 * anybody remembering to delete anything.
 */
export async function adminDevices(db) {
  const rows = await db.prepare(
    `SELECT d.token, d.user_id, d.platform
       FROM devices d
       JOIN users u ON u.id = d.user_id
      WHERE d.dead_at IS NULL AND u.role = 'admin'`,
  ).all();
  return rows.results || [];
}

/** What the admin screen shows: whose phones are registered. */
export async function listDevices(db) {
  const rows = await db.prepare(
    `SELECT d.token, d.platform, d.label, d.created_at, d.seen_at, d.dead_at,
            u.display_name AS who
       FROM devices d JOIN users u ON u.id = d.user_id
      ORDER BY d.seen_at DESC`,
  ).all();
  // The token is a credential for sending to that phone, so the list
  // shows enough to recognise a device and not enough to use one.
  return (rows.results || []).map((r) => ({
    ...r,
    token: `${String(r.token).slice(0, 8)}…`,
  }));
}

export { allUsers };
