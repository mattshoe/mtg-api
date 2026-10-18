// Saved Fetch lists, kept against the account.
//
// Matt: "making a fetch list on web does not show on android and vice
// versa". The apps hold the whole set of saved lists and write it whole
// on every change, so this is one row per account: read it, or replace
// it. What a list contains is the apps' business — `SavedFetch` in
// :core — and is kept as sent.

/** Comfortably more than a few hundred filed decklists, and well under D1's row limit. */
const MAX_BYTES = 900_000;

/** Every list this account has saved, newest first as the app ordered them. */
export async function read(db, userId) {
  const row = await db.prepare('SELECT lists FROM fetch_lists WHERE user_id = ?1').bind(userId).first();
  let lists = [];
  try {
    lists = row ? JSON.parse(row.lists) : [];
  } catch {
    lists = [];
  }
  return { status: 200, body: { lists: Array.isArray(lists) ? lists : [] } };
}

/** The whole set, replacing what was kept. */
export async function write(db, userId, body) {
  const lists = body?.lists;
  if (!Array.isArray(lists)) {
    return { status: 400, body: { error: 'send the saved lists as `lists`, an array' } };
  }
  const bad = lists.find((l) => !l || typeof l !== 'object'
    || typeof l.id !== 'string' || !l.id
    || typeof l.title !== 'string' || !l.title.trim());
  if (bad !== undefined) {
    return { status: 400, body: { error: 'every saved list needs an `id` and a `title`' } };
  }
  const text = JSON.stringify(lists);
  if (text.length > MAX_BYTES) {
    return { status: 413, body: { error: 'too many saved lists to keep; delete some first' } };
  }
  await db.prepare(
    `INSERT INTO fetch_lists (user_id, lists, updated_at) VALUES (?1, ?2, ?3)
     ON CONFLICT(user_id) DO UPDATE SET lists = excluded.lists, updated_at = excluded.updated_at`,
  ).bind(userId, text, new Date().toISOString()).run();
  return { status: 200, body: { ok: true, lists: lists.length } };
}
