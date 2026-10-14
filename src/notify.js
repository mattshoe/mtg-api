// Saying something happened, to every phone that should hear it.
//
// The one entry point the Worker calls. It is deliberately the only
// place that knows pushes exist: `setStatus` reports a transition and
// this decides whether it is worth a buzz, who gets it, and what it
// says.
//
// Nothing here may fail a request. A task that merged has merged whether
// or not anybody's phone found out, so every path returns rather than
// throws and the caller does not have to care.

import { adminDevices, markDead } from './devices.js';
import { sendTo, noteFor } from './push.js';

/** The service account, or null when this deployment cannot send. */
export function credsFrom(env) {
  const raw = env?.FCM_SERVICE_ACCOUNT;
  if (!raw) return null;
  try {
    const creds = typeof raw === 'string' ? JSON.parse(raw) : raw;
    return creds.private_key && creds.client_email && creds.project_id ? creds : null;
  } catch {
    return null;
  }
}

/**
 * Tell every admin's phone about a task, if this one is worth telling.
 *
 * Returns what happened, which the log keeps: how many were sent, how
 * many devices turned out to be gone. A deployment with no credentials
 * answers `{ skipped: 'no credentials' }` and does nothing, which is
 * what every test and every local run gets.
 */
export async function tell(env, task, { fetch: f = fetch } = {}) {
  const note = noteFor(task);
  if (!note) return { skipped: 'not worth a push' };
  const creds = credsFrom(env);
  if (!creds) return { skipped: 'no credentials' };

  let devices;
  try {
    devices = await adminDevices(env.DB);
  } catch (e) {
    return { failed: String(e.message || e) };
  }
  if (!devices.length) return { skipped: 'nobody to tell' };

  note.data = { key: String(task.key || ''), name: String(task.name || ''), status: String(task.status || '') };

  let sent = 0;
  let dead = 0;
  const problems = [];
  for (const d of devices) {
    try {
      const r = await sendTo(d.token, note, { creds, fetch: f });
      if (r.ok) sent += 1;
      else if (r.dead) { dead += 1; await markDead(env.DB, d.token); }
      else problems.push(r.error);
    } catch (e) {
      // One phone's problem is one phone's problem.
      problems.push(String(e.message || e));
    }
  }
  return { sent, dead, ...(problems.length && { problems: problems.slice(0, 3) }) };
}
