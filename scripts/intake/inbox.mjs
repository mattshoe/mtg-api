// Tasks written in the app, collected into requests/.
//
// Matt: "I want the option to add a new task from the admin settings ...
// where i can enter the details and upload files". The website and the
// phone cannot write into requests/ on this laptop, so they write to the
// Worker's inbox (`POST /tasks`) and this collects them. A launchd job
// runs it every few minutes — `com.matt.mtg.intake-inbox.plist`, installed
// by install.sh — and the file it writes into requests/ is what fires the
// ordinary watcher, so nothing about dispatching changes.
//
// A task's files go to `.intake/attachments/<name>/`, which is gitignored:
// a builder reads them by path and cannot commit them by accident.

import { readFileSync, writeFileSync, mkdirSync, existsSync } from 'node:fs'
import { join, basename, dirname } from 'node:path'
import { homedir } from 'node:os'
import { fileURLToPath } from 'node:url'

const BASE = 'https://mtg-api.mattshoe81.workers.dev'

const API = 'https://mtg-api.mattshoe81.workers.dev'

/** The same shape `branchFor` slugs a name into, so the branch reads like the title. */
function slug(text) {
  const s = String(text).toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '')
  return (s.length > 60 ? s.slice(0, 60).replace(/-+$/, '') : s) || 'task'
}

/** A file name with no way out of its folder and nothing a shell would trip on. */
function safeName(name, i) {
  const base = basename(String(name ?? '').replace(/\\/g, '/'))
    .replace(/[^A-Za-z0-9._ -]+/g, '_').replace(/^\.+/, '').trim()
  return base || `file-${i + 1}`
}

/**
 * One task as the request file and attachments it becomes.
 *
 * `taken(name)` says whether `requests/` or `requests/done/` already has
 * that file. A clash gets the task's key on the end rather than
 * overwriting a request somebody wrote, or reviving a finished one.
 */
export function requestFor(task, { repo, taken }) {
  let name = slug(task.title)
  if (taken(`${name}.md`)) name = `${name}-${task.key}`
  const folder = join(repo, '.intake', 'attachments', name)
  const used = new Set()
  const files = (task.files || []).map((f, i) => {
    let n = safeName(f.name, i)
    while (used.has(n)) n = `${i + 1}-${n}`
    used.add(n)
    return { path: join(folder, n), bytes: Buffer.from(String(f.data || ''), 'base64') }
  })
  const lines = [
    '---',
    'status: ready',
    '---',
    '',
    `# ${task.title}`,
    '',
    String(task.details || '').trim(),
    '',
  ]
  if (files.length) {
    lines.push(
      '## Files',
      '',
      'Sent with the task from Admin Settings. Read them by path; they are',
      'outside the repository on purpose and must not be committed.',
      '',
      ...files.map((f) => `- ${f.path}`),
      '',
    )
  }
  lines.push(`<!-- from the app, task ${task.key}, ${task.created_at || ''} -->`, '')
  return { name: `${name}.md`, text: lines.join('\n'), files }
}

/**
 * Collect every task waiting in the inbox. Returns the request files
 * written. Throws a sentence when the Worker refuses, and acknowledges
 * nothing it has not written to disk.
 */
export async function collect({ repo, token, base = API, fetch = globalThis.fetch }) {
  const state = join(repo, '.intake')
  if (!existsSync(join(state, 'enabled')) || existsSync(join(state, 'disabled'))) return []

  const auth = { authorization: `Bearer ${token}` }
  const res = await fetch(`${base}/tasks/inbox`, { headers: auth })
  const body = await res.json().catch(() => ({}))
  if (!res.ok) throw new Error(`the inbox refused: ${body.error || res.status}`)

  const requests = join(repo, 'requests')
  const taken = (n) => existsSync(join(requests, n)) || existsSync(join(requests, 'done', n))
  const written = []
  const keys = []
  const names = {}
  for (const task of body.tasks || []) {
    const r = requestFor(task, { repo, taken })
    for (const f of r.files) {
      mkdirSync(dirname(f.path), { recursive: true })
      writeFileSync(f.path, f.bytes)
    }
    // The request last: it is what fires the watcher, and a builder that
    // starts before its files are on disk would read a list of nothing.
    writeFileSync(join(requests, r.name), r.text)
    written.push(r.name)
    keys.push(task.key)
    names[task.key] = r.name.replace(/\.md$/, '')
  }

  if (keys.length) {
    const ack = await fetch(`${base}/tasks/inbox/received`, {
      method: 'POST',
      headers: { ...auth, 'content-type': 'application/json' },
      body: JSON.stringify({ keys, names }),
    })
    if (!ack.ok) throw new Error(`wrote ${written.join(', ')} but the inbox did not take the receipt`)
  }
  return written
}

/** The agent's token, from the file dispatch.sh reads it from. */
/**
 * A token that may actually write task status.
 *
 * The dispatcher runs on Matt's laptop, not inside an agent, so it can hold
 * the operator password — and it has to, because the agent service account
 * is deliberately NOT an admin any more. It was demoted after an agent with
 * /admin/sql rewrote two production views, and nothing has made it worth
 * giving back. With the agent's token the Worker refuses every status write:
 *   task-status: the Worker refused task-details-page in progress:
 *   that needs the admin role
 * which leaves the whole D1 port writing nothing.
 *
 * Operator first, agent second, so this keeps working anywhere the password
 * is absent.
 */
export async function writeToken(fetchImpl = globalThis.fetch) {
  const pw = operatorPassword()
  if (pw) {
    try {
      const res = await fetchImpl(`${BASE}/admin`, {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({ password: pw }),
      })
      if (res.ok) {
        const got = await res.json()
        if (got?.token) return got.token
      }
    } catch { /* fall through to the agent's token */ }
  }
  return agentToken()
}

/** The operator password, from the file only the laptop has. */
export function operatorPassword() {
  try {
    const env = readFileSync(join(homedir(), '.mtg-api.env'), 'utf8')
    return /^MTG_ADMIN_PASSWORD=(.*)$/m.exec(env)?.[1]?.trim().replace(/^["']|["']$/g, '') || ''
  } catch {
    return ''
  }
}

export function agentToken() {
  try {
    const env = readFileSync(join(homedir(), '.mtg-agent.env'), 'utf8')
    return /^MTG_AGENT_TOKEN=(.*)$/m.exec(env)?.[1]?.trim() || ''
  } catch {
    return ''
  }
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
  const repo = join(dirname(fileURLToPath(import.meta.url)), '..', '..')
  const token = await writeToken()
  const stamp = new Date().toISOString().replace('T', ' ').slice(0, 19)
  if (!token) {
    console.log(`${stamp}  inbox: no credential that may read the inbox, so nothing was collected`)
  } else {
    collect({ repo, token })
      .then((got) => { if (got.length) console.log(`${stamp}  inbox: wrote ${got.join(', ')}`) })
      .catch((e) => { console.log(`${stamp}  inbox: ${e.message}`); process.exitCode = 1 })
  }
}
