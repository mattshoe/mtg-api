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
      'INSERT INTO task_inbox (key, title, details, created_by, created_at) VALUES (?1, ?2, ?3, ?4, ?5)',
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
    'SELECT id, key, title, details, created_at FROM task_inbox WHERE received_at IS NULL ORDER BY id',
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
 * The laptop has written these. Its files are dropped with it: they
 * are on disk now, and a blob kept here past that is only weight.
 */
export async function received(db, keys) {
  if (!Array.isArray(keys)) return { status: 400, body: { error: 'keys must be a list' } };
  const now = new Date().toISOString();
  const statements = keys.flatMap((k) => [
    db.prepare('DELETE FROM task_files WHERE task_id = (SELECT id FROM task_inbox WHERE key = ?1)').bind(String(k)),
    db.prepare('UPDATE task_inbox SET received_at = ?2 WHERE key = ?1 AND received_at IS NULL').bind(String(k), now),
  ]);
  if (statements.length) await db.batch(statements);
  return { status: 200, body: { received: keys.length } };
}
