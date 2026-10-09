// New tasks, written in the app and collected by the laptop.
//
// Matt: "I want the option to add a new task from the admin settings ...
// where i can enter the details and upload files". A request is built
// once it is a file in `requests/` on the laptop that dispatches, and
// neither the website nor the phone can write there. So a task waits
// here, in `task_inbox`, until `scripts/intake/inbox.mjs` on the laptop
// collects it, writes the request file and says it has it.

import { newKey } from './accounts.js';

/** Per file. A D1 row stops at 2 MB, and a screenshot fits well under this. */
export const MAX_FILE_BYTES = 1_500_000;
export const MAX_FILES = 5;
const MAX_TITLE = 120;
const MAX_DETAILS = 20_000;

const fromBase64 = (s) => Uint8Array.from(atob(s), (c) => c.charCodeAt(0));

function toBase64(bytes) {
  const b = new Uint8Array(bytes);
  let out = '';
  for (let i = 0; i < b.length; i += 0x8000) out += String.fromCharCode(...b.subarray(i, i + 0x8000));
  return btoa(out);
}

/** The body checked and decoded, or the sentence that says what is wrong with it. */
function read(body) {
  const title = String(body.title ?? '').trim();
  const details = String(body.details ?? '').trim();
  if (!title) return { error: 'a task needs a title' };
  if (title.length > MAX_TITLE) return { error: `a title is ${MAX_TITLE} characters at most` };
  if (!details) return { error: 'say what the task is' };
  if (details.length > MAX_DETAILS) return { error: `the details are ${MAX_DETAILS} characters at most` };
  const raw = body.files ?? [];
  if (!Array.isArray(raw)) return { error: 'files must be a list' };
  if (raw.length > MAX_FILES) return { error: `${MAX_FILES} files at most` };
  const files = [];
  for (const f of raw) {
    const name = String(f?.name ?? '').trim();
    if (!name) return { error: 'every file needs a name' };
    let bytes;
    try {
      bytes = fromBase64(String(f.data ?? ''));
    } catch {
      return { error: `${name} did not arrive whole` };
    }
    if (bytes.length > MAX_FILE_BYTES) return { error: `${name} is over 1.5 MB` };
    files.push({ name, type: String(f.type || 'application/octet-stream'), bytes });
  }
  return { title, details, files };
}

/** Write one task and its files, all or nothing. */
export async function newTask(db, userId, body) {
  const t = read(body);
  if (t.error) return { status: 400, body: { error: t.error } };
  const key = newKey();
  const now = new Date().toISOString();
  const statements = [
    db.prepare(
      'INSERT INTO task_inbox (key, title, details, created_by, created_at, status_at) VALUES (?1, ?2, ?3, ?4, ?5, ?5)',
    ).bind(key, t.title, t.details, userId, now),
    ...t.files.map((f, i) => db.prepare(
      `INSERT INTO task_files (task_id, position, name, type, bytes)
         VALUES ((SELECT id FROM task_inbox WHERE key = ?1), ?2, ?3, ?4, ?5)`,
    ).bind(key, i, f.name, f.type, f.bytes)),
  ];
  await db.batch(statements);
  return { status: 200, body: { key, files: t.files.length } };
}

/** Every task the laptop has not said it has, oldest first, with its files. */
export async function inbox(db) {
  const { results: tasks = [] } = await db.prepare(
    "SELECT id, key, title, details, created_at FROM task_inbox WHERE received_at IS NULL AND status = 'pending' ORDER BY id",
  ).all();
  const out = [];
  for (const t of tasks) {
    const { results: files = [] } = await db.prepare(
      'SELECT name, type, bytes FROM task_files WHERE task_id = ?1 ORDER BY position',
    ).bind(t.id).all();
    out.push({
      key: t.key,
      title: t.title,
      details: t.details,
      created_at: t.created_at,
      files: files.map((f) => ({ name: f.name, type: f.type, data: toBase64(f.bytes) })),
    });
  }
  return out;
}

/**
 * One task by its key, everything D1 knows about it and its files,
 * whether or not the laptop has collected it. Its own page in the app
 * reads this. Null when there is no such task.
 */
export async function task(db, key) {
  const t = await db.prepare(
    `SELECT id, key, name, title, details, status, status_at, note, pr, created_at, received_at, started_at, finished_at
       FROM task_inbox WHERE key = ?1`,
  ).bind(String(key)).first();
  if (!t) return null;
  const { results: files = [] } = await db.prepare(
    'SELECT name, type, bytes FROM task_files WHERE task_id = ?1 ORDER BY position',
  ).bind(t.id).all();
  const { id, ...row } = t;
  return { ...row, files: files.map((f) => ({ name: f.name, type: f.type, data: toBase64(f.bytes) })) };
}

/**
 * The laptop has written these. Their files stay: the task's own page
 * shows them, and the laptop's copy is on a machine the app cannot reach.
 */
export async function received(db, keys, names = {}) {
  if (!Array.isArray(keys)) return { status: 400, body: { error: 'keys must be a list' } };
  const now = new Date().toISOString();
  // `names` is the request file each became, so the dispatcher's
  // transitions, which only know the file, land on this same row.
  const statements = keys.map((k) => db.prepare(
    'UPDATE task_inbox SET received_at = ?2, name = COALESCE(?3, name) WHERE key = ?1 AND received_at IS NULL',
  ).bind(String(k), now, names?.[k] ? String(names[k]) : null));
  if (statements.length) await db.batch(statements);
  return { status: 200, body: { received: keys.length } };
}

/**
 * Every status a task can have, in the words the app shows, which are
 * Matt's. `in progress` is an agent alive on it this second and nothing
 * else; `blocked` needs Matt or something it depends on; `paused` is
 * nothing running with the work kept, whether held by Matt, rate limited,
 * crashed or killed. The note says which. There is no `failing`: red CI
 * mid-run is still `in review`. Each is written by whatever caused it,
 * and a cancelled task is never collected.
 */
export const STATUSES = [
  'pending', 'in progress', 'blocked', 'paused', 'in review', 'merged', 'deployed', 'cancelled',
];
const FINISHED = new Set(['merged', 'deployed', 'cancelled']);

/** Every task, newest first, without its files. */
export async function list(db) {
  const { results = [] } = await db.prepare(
    `SELECT key, name, title, status, status_at, note, pr, created_at, started_at, finished_at
       FROM task_inbox ORDER BY id DESC`,
  ).all();
  return results;
}

/**
 * One transition, written as it happens. A task is named by its `key`
 * (sent from the app) or its request file's `name` (what the dispatcher
 * knows). A name with no row yet is a request written straight into
 * requests/, and gets one. The first `in progress` is the start, and a
 * resumed build keeps it. `status_at` moves only when the status does;
 * the note is the detail of this status and goes with it.
 */
export async function setStatus(db, body) {
  const status = String(body.status ?? '');
  if (!STATUSES.includes(status)) {
    return { status: 400, body: { error: `no such status: ${status}; one of ${STATUSES.join(', ')}` } };
  }
  const key = body.key ? String(body.key) : null;
  const name = body.name ? String(body.name) : null;
  if (!key && !name) return { status: 400, body: { error: 'which task: a key or a name' } };
  const now = new Date().toISOString();
  const where = key ? 'key = ?1' : 'name = ?1';
  const found = await db.prepare(`SELECT 1 FROM task_inbox WHERE ${where}`).bind(key ?? name).first();
  if (!found) {
    if (!name) return { status: 404, body: { error: `no task ${key}` } };
    const title = String(body.title ?? '').trim() || name.replace(/-/g, ' ');
    await db.prepare(
      "INSERT INTO task_inbox (key, title, details, created_at, received_at, name, status_at) VALUES (?1, ?2, '', ?3, ?3, ?4, ?3)",
    ).bind(newKey(), title, now, name).run();
  }
  const note = body.note ? String(body.note).slice(0, 500) : null;
  // The request file's text, for a task written on the laptop. One sent
  // from the app keeps what was typed into it.
  const details = body.details ? String(body.details).slice(0, MAX_DETAILS) : null;
  // A task that was never seen starting (filed straight into done/, or
  // finished before status lived here) may be given its pull request's
  // own times, so a done row can say how long it took. Only where it has
  // no start: a start the dispatcher wrote is never replaced.
  const iso = (v) => (/^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(\.\d+)?Z$/.test(String(v ?? '')) ? String(v) : null);
  await db.prepare(
    `UPDATE task_inbox SET
            details = CASE WHEN details = '' AND ?9 IS NOT NULL THEN ?9 ELSE details END,
            status_at = CASE WHEN status = ?2 THEN COALESCE(status_at, ?4) ELSE ?4 END,
            status = ?2,
            note = ?6,
            pr = COALESCE(?3, pr),
            started_at = CASE WHEN ?2 = 'in progress' THEN COALESCE(started_at, ?4) ELSE COALESCE(started_at, ?7) END,
            finished_at = CASE WHEN NOT ?5 THEN NULL
                               WHEN started_at IS NULL AND ?7 IS NOT NULL AND ?8 IS NOT NULL THEN ?8
                               ELSE COALESCE(finished_at, ?4) END
      WHERE ${where}`,
  ).bind(
    key ?? name, status, body.pr ? String(body.pr) : null, now, FINISHED.has(status) ? 1 : 0, note,
    iso(body.started_at), iso(body.finished_at), details,
  ).run();
  return { status: 200, body: { status } };
}
