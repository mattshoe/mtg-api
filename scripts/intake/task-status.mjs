// Where a task is, written to D1 as it happens.
//
// Matt: "The system is just looking at fucking branch names?!?!" The app
// used to work every status out of GitHub's branches and pull requests,
// which could not carry anything GitHub does not know about — a task sent
// from the app and not yet built, or one cancelled — and was asked
// unauthenticated from the phone. Now the dispatcher writes each
// transition to the task's row in D1 (`POST /tasks/status`) and the app
// reads that.
//
//   node scripts/intake/task-status.mjs building <file>
//   node scripts/intake/task-status.mjs settle <file> <branch>
//   node scripts/intake/task-status.mjs reconcile
//
// `building` writes `in progress` before the builder starts. `settle`
// after it exits: what its pull request came to, asked of `gh` on this
// laptop, which is signed in and has no sixty-an-hour ceiling.
// `reconcile` on every run: what the laptop can see, held against what D1
// says, so a dead agent reads paused and a withdrawn request cancelled.
// None may stop a build: a refusal is a line in the log and the
// dispatcher carries on.

import { readFileSync, readdirSync, existsSync } from 'node:fs'
import { join, basename, dirname } from 'node:path'
import { execFileSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import { writeToken } from './inbox.mjs'
import { pending, buildable } from '../intake.mjs'

const API = 'https://mtg-api.mattshoe81.workers.dev'

/** Furthest first: a request built twice is at the furthest any of its pull requests got. */
const ORDER = [
  ['MERGED', { status: 'merged' }],
  ['OPEN', { status: 'in review' }],
  ['CLOSED', { status: 'paused', note: 'pull request closed without merging' }],
]

const STOPPED = 'the agent stopped; its work is kept in the worktree'
const WITHDRAWN = 'request withdrawn'
const FINISHED = new Set(['merged', 'deployed', 'cancelled'])

/**
 * `gh pr list --json state,url` for one branch in, the status to write
 * out. A builder that ended with nothing to show is paused, not
 * cancelled: its worktree is kept and the next run resumes it. One whose
 * request was taken back while it ran is cancelled.
 */
export function outcome(prs, { withdrawn = false } = {}) {
  for (const [state, to] of ORDER) {
    if (withdrawn && state === 'CLOSED') continue
    const hit = (prs || []).find((p) => p.state === state)
    if (hit) return { status: to.status, pr: hit.url, ...(to.note && { note: to.note }) }
  }
  if (withdrawn) return { status: 'cancelled', pr: null, note: WITHDRAWN }
  return { status: 'paused', pr: null, note: 'the agent stopped with no pull request; its work is kept in the worktree' }
}

/**
 * What every row should say, from what this laptop can see: `ready` and
 * `held` request files, `filed` ones on main, and `stalled` ones whose
 * worktree exists with no agent alive in it. Only the rows that are wrong
 * come back. A finished row is left alone, and so is a row with no name:
 * a task sent from the app that the laptop has not collected yet.
 */
export function reconcile({ rows = [], ready = [], held = [], filed = [], stalled = [] }) {
  const byName = new Map(rows.filter((r) => r.name).map((r) => [r.name, r]))
  const out = []
  const want = (name, status, note) => {
    if (byName.get(name)?.status === status) return
    out.push({ name, status, ...(note && { note }) })
  }
  for (const name of ready) {
    const now = byName.get(name)?.status
    if (FINISHED.has(now) || now === 'in review' || now === 'blocked') continue
    if (stalled.includes(name)) {
      if (now === 'in progress') want(name, 'paused', STOPPED)
    } else if (now !== 'in progress') want(name, 'pending')
  }
  for (const name of held) {
    if (!FINISHED.has(byName.get(name)?.status)) want(name, 'paused', 'held by Matt')
  }
  for (const name of filed) if (!byName.has(name)) want(name, 'merged')
  const seen = new Set([...ready, ...held, ...filed])
  for (const r of rows) {
    if (r.name && !seen.has(r.name) && !FINISHED.has(r.status)) want(r.name, 'cancelled', WITHDRAWN)
  }
  return out
}

/** The request file's `# heading`, or '' when it has none. */
export function titleOf(text) {
  return /^#\s+(.+)$/m.exec(String(text ?? ''))?.[1]?.trim() ?? ''
}

/**
 * `in progress`, with the request file's heading and its text whole: a
 * request written straight into requests/ is on this laptop and nowhere
 * else, and the task's own page in the app shows it. The Worker keeps the
 * text only for a task that has none, so one sent from the app keeps
 * what was typed into it.
 */
export function started(name, text) {
  return {
    name,
    status: 'in progress',
    ...(titleOf(text) && { title: titleOf(text) }),
    ...(text && { details: text }),
  }
}

/** One transition to the Worker. Throws a sentence when it refuses. */
export async function report(transition, { token, base = API, fetch = globalThis.fetch }) {
  const res = await fetch(`${base}/tasks/status`, {
    method: 'POST',
    headers: { authorization: `Bearer ${token}`, 'content-type': 'application/json' },
    body: JSON.stringify(transition),
  })
  if (!res.ok) {
    const body = await res.json().catch(() => ({}))
    throw new Error(`the Worker refused ${transition.name} ${transition.status}: ${body.error || res.status}`)
  }
}

/** Everything `reconcile` needs, gathered off this laptop and the Worker, and the fixes written. */
async function reconcileNow({ repo, token, base = API }) {
  const res = await fetch(`${base}/tasks`, { headers: { authorization: `Bearer ${token}` } })
  if (!res.ok) throw new Error(`the Worker would not list tasks: ${res.status}`)
  const { tasks: rows = [] } = await res.json()
  const dir = join(repo, 'requests')
  const ls = (d) => { try { return readdirSync(d) } catch { return [] } }
  const strip = (f) => basename(f, '.md')
  const live = pending(ls(dir), { done: ls(join(dir, 'done')) })
  const ready = live.filter((f) => buildable(readFileSync(join(dir, f), 'utf8'))).map(strip)
  const held = live.map(strip).filter((n) => !ready.includes(n))
  // What main has filed, for the reason dispatch.sh asks main: the local
  // folder lags it whenever an agent merges its own work.
  let filed = ls(join(dir, 'done'))
  try {
    filed = execFileSync('git', ['ls-tree', '--name-only', 'origin/main', 'requests/done/'], { cwd: repo, encoding: 'utf8' })
      .split('\n')
  } catch { /* the local folder will do */ }
  filed = filed.filter((f) => f.endsWith('.md')).map(strip)
  const alive = (name) => {
    try { execFileSync('pgrep', ['-f', `requests/${name}.md`]); return true } catch { return false }
  }
  const stalled = ready.filter((n) => existsSync(join(repo, '.intake', 'wt', n)) && !alive(n))
  const wrote = reconcile({ rows, ready, held, filed, stalled })
  for (const t of wrote) await report(t, { token, base })
  return wrote
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
  const [cmd, file, branch] = process.argv.slice(2)
  const repo = join(dirname(fileURLToPath(import.meta.url)), '..', '..')
  const name = basename(String(file || ''), '.md')
  const stamp = new Date().toTimeString().slice(0, 8)
  const say = (line) => console.log(`${stamp}  task-status: ${line}`)
  const token = await writeToken()

  let transition
  if (cmd === 'building') {
    let text = ''
    try { text = readFileSync(join(repo, 'requests', `${name}.md`), 'utf8') } catch { /* gone */ }
    transition = started(name, text)
  } else if (cmd === 'settle') {
    let prs = []
    try {
      prs = JSON.parse(execFileSync('gh', ['pr', 'list', '--head', branch, '--state', 'all', '--json', 'state,url'], {
        cwd: repo, encoding: 'utf8',
      }))
      const withdrawn = !existsSync(join(repo, 'requests', `${name}.md`))
        && !existsSync(join(repo, 'requests', 'done', `${name}.md`))
      transition = { name, ...outcome(prs, { withdrawn }) }
    } catch (e) {
      say(`could not ask gh about ${branch}: ${e.message}`)
    }
  } else if (cmd === 'reconcile') {
    if (!token) say('no credential that may write task status, so nothing was reconciled')
    else {
      reconcileNow({ repo, token })
        .then((wrote) => wrote.forEach((t) => say(`${t.name} ${t.status}${t.note ? ` (${t.note})` : ''}`)))
        .catch((e) => say(e.message))
    }
  } else {
    console.error('usage: task-status.mjs building <file> | settle <file> <branch> | reconcile')
    process.exit(2)
  }

  if (transition && !token) say(`no credential that may write task status, so ${name} ${transition.status} was not written`)
  else if (transition) {
    report(transition, { token })
      .then(() => say(`${name} ${transition.status}`))
      .catch((e) => say(e.message))
  }
}
