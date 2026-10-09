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
import { pending, buildable, branchFor } from '../intake.mjs'

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
    if (hit) {
      // A merged pull request's own times, for a row that never saw its
      // start: the Worker fills them only where there is none.
      const times = state === 'MERGED' && hit.createdAt && hit.mergedAt
        && { started_at: hit.createdAt, finished_at: hit.mergedAt }
      return { status: to.status, pr: hit.url, ...(to.note && { note: to.note }), ...times }
    }
  }
  if (withdrawn) return { status: 'cancelled', pr: null, note: WITHDRAWN }
  return { status: 'paused', pr: null, note: 'the agent stopped with no pull request; its work is kept in the worktree' }
}

/**
 * What every row should say, from everything this laptop can see:
 * `ready` and `held` request files, `filed` ones under done/ (on main or
 * here), `alive` ones with an agent running in their worktree, `stalled`
 * ones whose worktree has none, `branches` named for a request, and
 * `prs`, each request's pull requests by name. Every task found in any of
 * those has a row; only the rows that are wrong come back.
 *
 * Matt, after #73 and #74: "Both of those are done already??!! And what
 * hairbrush to the task details page?! And the fucking elapsed time is
 * gone from the completed ones!!!" Each of those was this guessing from
 * less than it could see. So: filed under done/ is finished and never
 * cancelled; an agent alive is in progress; a worktree, a branch or a
 * pull request is a row; a merged one carries its pull request's times.
 *
 * A row with no name is a task sent from the app that the laptop has not
 * collected yet, and is left alone.
 */
export function reconcile({
  rows = [], ready = [], held = [], filed = [], stalled = [], alive = [], prs = {}, branches = [],
}) {
  const byName = new Map(rows.filter((r) => r.name).map((r) => [r.name, r]))
  const names = new Set([
    ...ready, ...held, ...filed, ...alive, ...stalled, ...Object.keys(prs), ...branches, ...byName.keys(),
  ])
  const out = []
  for (const name of names) {
    const row = byName.get(name)
    const t = truth(name, row?.status, { ready, held, filed, stalled, alive, prs: prs[name] || [] })
    if (!t) continue
    const backfill = t.started_at && !row?.started_at
    if (row?.status === t.status && !backfill) continue
    out.push({ name, ...t })
  }
  return out
}

/** What one task's row should say, or null to leave it as it is. */
function truth(name, now, { ready, held, filed, stalled, alive, prs }) {
  const merged = prs.find((p) => p.state === 'MERGED')
  const landed = now === 'merged' || now === 'deployed'
  // Filed under done/ is the strongest evidence there is that it finished.
  if (filed.includes(name)) {
    if (merged) return { ...outcome([merged]), status: landed ? now : 'merged' }
    return landed ? null : { status: 'merged' }
  }
  if (alive.includes(name)) return now === 'blocked' ? null : { status: 'in progress' }
  const isReady = ready.includes(name)
  const isHeld = held.includes(name)
  if (merged || prs.some((p) => p.state === 'OPEN')) {
    const o = outcome(prs, { withdrawn: !isReady && !isHeld })
    if (landed) return o.status === 'merged' ? { ...o, status: now } : null
    if (now === 'blocked' || now === 'cancelled') return null
    return o
  }
  if (isHeld) return FINISHED.has(now) ? null : { status: 'paused', note: 'held by Matt' }
  if (isReady) {
    if (FINISHED.has(now) || now === 'in review' || now === 'blocked') return null
    if (stalled.includes(name)) return now === 'paused' ? null : { status: 'paused', note: STOPPED }
    if (prs.length) return now === 'paused' ? null : outcome(prs)
    return now === 'in progress' ? null : { status: 'pending' }
  }
  // A worktree, a branch or a row with no request file anywhere.
  if (FINISHED.has(now)) return null
  return { status: 'cancelled', note: WITHDRAWN }
}

/** The request a dispatcher branch was made for, or null for any other branch. */
export function requestOfBranch(ref) {
  const m = /^request\/(.+)-[0-9a-f]{7}$/.exec(String(ref ?? ''))
  return m && branchFor(`${m[1]}.md`) === ref ? m[1] : null
}

/** The request file's `# heading`, or '' when it has none. */
export function titleOf(text) {
  return /^#\s+(.+)$/m.exec(String(text ?? ''))?.[1]?.trim() ?? ''
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
  // folder lags it whenever an agent merges its own work. And what this
  // folder has filed, because a request done by hand is moved here and
  // may never reach main as a move: it must still read as finished.
  let filed = ls(join(dir, 'done'))
  try {
    filed = filed.concat(execFileSync('git', ['ls-tree', '--name-only', 'origin/main', 'requests/done/'], { cwd: repo, encoding: 'utf8' })
      .split('\n'))
  } catch { /* the local folder will do */ }
  filed = [...new Set(filed.filter((f) => f.endsWith('.md')).map(strip))]
  // A worktree is a task whatever its request file says, and an agent is
  // alive in it when a process names its request.
  const worktrees = ls(join(repo, '.intake', 'wt')).filter((n) => !n.startsWith('.'))
  const running = (name) => {
    try { execFileSync('pgrep', ['-f', `requests/${name}.md`]); return true } catch { return false }
  }
  const alive = worktrees.filter(running)
  const stalled = worktrees.filter((n) => !alive.includes(n))
  let branches = []
  try {
    branches = execFileSync('git', ['for-each-ref', '--format=%(refname:short)', 'refs/heads/request/'], { cwd: repo, encoding: 'utf8' })
      .split('\n').map(requestOfBranch).filter(Boolean)
  } catch { /* no branches to add */ }
  const prs = {}
  try {
    const all = JSON.parse(execFileSync('gh', ['pr', 'list', '--state', 'all', '--limit', '500',
      '--json', 'headRefName,state,url,createdAt,mergedAt'], { cwd: repo, encoding: 'utf8' }))
    for (const p of all) {
      const n = requestOfBranch(p.headRefName)
      if (n) (prs[n] ||= []).push(p)
    }
  } catch { /* gh unavailable: the rest still stands */ }
  const wrote = reconcile({ rows, ready, held, filed, stalled, alive, prs, branches })
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
    transition = { name, status: 'in progress', title: titleOf(text) || undefined }
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
