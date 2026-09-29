// Making a retried write safe to send twice.
//
// Every mutation the Worker applies is a single `batch()`, so a 5xx means
// nothing landed and the client may retry freely. The case that needs this
// table is the other one: the batch committed and the response never got
// back. From the client that is indistinguishable from a request that never
// arrived, so it retries — and without a key, four copies of Lightning Bolt
// become eight.
//
// The client sends the same `Idempotency-Key` on every attempt of one
// logical write. The first attempt claims the key, runs, and stores its
// reply. Any later attempt gets that reply back without touching the
// database.

export const HEADER = 'idempotency-key';

/** Long enough to be unguessable, short enough to index. */
const MAX_KEY = 200;

export function keyFrom(request) {
  const raw = request.headers.get(HEADER);
  if (!raw) return null;
  const key = raw.trim();
  if (!key || key.length > MAX_KEY) return null;
  return key;
}

/**
 * Claim the key, or say what already happened under it.
 *
 * The claim is an INSERT on a primary key, so two requests racing with the
 * same key cannot both win — SQLite decides, not a read-then-write that has
 * a gap in the middle.
 *
 * Returns `{ claimed: true }` when this is the first attempt and the caller
 * should go ahead, `{ replay }` when there is an answer to hand back, or
 * `{ inFlight: true }` when another attempt holds the key and has not
 * finished.
 */
export async function claim(db, key, path) {
  try {
    await db.prepare(
      'INSERT INTO idempotency (key, path, ts) VALUES (?, ?, ?)',
    ).bind(key, path, new Date().toISOString()).run();
    return { claimed: true };
  } catch (e) {
    // Anything other than the key already existing is a real fault and
    // must not be mistaken for a duplicate.
    if (!/UNIQUE|PRIMARY KEY|constraint/i.test(String(e.cause?.message || e.message || e))) {
      throw e;
    }
  }

  const row = await db.prepare(
    'SELECT path, status, body FROM idempotency WHERE key = ?',
  ).bind(key).first();

  if (!row) return { claimed: true };

  // The same key on a different endpoint is a client bug, and replaying
  // one endpoint's answer for another would be worse than refusing.
  if (row.path !== path) {
    return {
      conflict: `that idempotency key was used for ${row.path}`,
    };
  }
  if (row.status === null || row.status === undefined) return { inFlight: true };

  let body;
  try {
    body = JSON.parse(row.body);
  } catch {
    return { inFlight: true };
  }
  return { replay: { status: row.status, body } };
}

/** Remember what the first attempt answered. */
export async function remember(db, key, status, body) {
  if (!key) return;
  try {
    await db.prepare(
      'UPDATE idempotency SET status = ?, body = ? WHERE key = ?',
    ).bind(status, JSON.stringify(body ?? null), key).run();
  } catch {
    // A write that worked must not fail because the note about it did.
    // The cost is that one retry could apply twice, which is exactly
    // where this started — but failing a committed write is worse.
  }
}

/**
 * Let go of a key whose attempt failed, so the client's own retry is a
 * fresh attempt rather than a permanent "already in progress".
 */
export async function release(db, key) {
  if (!key) return;
  try {
    await db.prepare('DELETE FROM idempotency WHERE key = ? AND status IS NULL').bind(key).run();
  } catch {
    // Nothing to do about it. The row expires with the daily prune.
  }
}
