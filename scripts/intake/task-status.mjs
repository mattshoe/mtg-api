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
//   node scripts/intake/task-status.mjs cancel
//   node scripts/intake/task-status.mjs reconcile
//
// `building` writes `in progress` before the builder starts. `settle`
// after it exits: what its pull request came to, asked of `gh` on this
// laptop, which is signed in and has no sixty-an-hour ceiling.
// `cancel` on every run, before reconcile: a task cancelled in the app
// has its request file marked `status: cancelled` and its agent killed.
// `reconcile` on every run: what the laptop can see, held against what D1
// says, so a dead agent reads paused and a withdrawn request cancelled.
// None may stop a build: a refusal is a line in the log and the
// dispatcher carries on.

import { readFileSync, readdirSync, existsSync, writeFileSync } from 'node:fs'
import { join, basename, dirname } from 'node:path'
import { execFileSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import { writeToken } from './inbox.mjs'
import { pending, buildable, branchFor, statusOf } from '../intake.mjs'

const API = 'https://mtg-api.mattshoe81.workers.dev'

/** Furthest first: a request built twice is at the furthest any of its pull requests got. */
const ORDER = [
  ['MERGED', { status: 'merged' }],
  ['OPEN', { status: 'in review' }],
  ['CLOSED', { status: 'paused', note: 'pull request closed without merging' }],
]

const STOPPED = 'the agent stopped; its work is kept in the worktree'
const WITHDRAWN = 'request withdrawn'
const CANCELLED = 'cancelled; the agent was stopped'
const FINISHED = new Set(['merged', 'deployed', 'cancelled'])

/** The workflows a merge to main deploys with, by path, and what each puts live. */
const DEPLOYS = { pages: 'the site', release: 'the APK', worker: 'the API' }
/** How long a merge may go with no deploy run before nothing is coming. */
const SETTLE_MS = 15 * 60 * 1000

/**
 * Where a merge's deploy is, from the `gh run list` runs on main: every
 * deploy run of its merge commit green is `deployed`, and anything else
 * stays `merged` with a note saying which is still going or which
 * failed. A run cancelled for a newer one of the same workflow that went
 * green is live all the same, since that one carried it. A merge more
 * than fifteen minutes old that set off no deploy at all says so. Null
 * where the runs cannot tell: none were asked for, the merge is older
 * than every run seen, or it is too new for its runs to have started.
 */
export function deployment(pr, { runs, now, contains } = {}) {
  const sha = pr?.mergeCommit?.oid
  if (!runs || !sha) return null
  const mine = runs.filter((r) => r.headSha === sha && DEPLOYS[r.workflowName])
  if (!mine.length) {
    const oldest = runs.reduce((a, r) => (r.createdAt < a ? r.createdAt : a), '9999')
    const age = Date.parse(now ?? new Date().toISOString()) - Date.parse(pr.mergedAt)
    if (!(pr.mergedAt >= oldest) || !(age > SETTLE_MS)) return null
    return { status: 'merged', note: 'nothing to deploy: no deploy ran for this merge' }
  }
  // A later green run of the same workflow, from a commit that has this
  // merge behind it, means this merge is live. A deploy publishes main
  // whole — it is not a per-commit artefact — so once the site has gone
  // out from anything containing the change, the change is on the site.
  //
  // Without this, six guide tasks sat at `merged` saying "deploy failed:
  // the site" for a day while being live. GitHub Pages had been switched
  // off on the repo for eight hours; when it came back, a later run
  // published main and those merges with it, and nothing here could see
  // that because it only ever matched the exact merge commit. One bad
  // afternoon of CI pinned them permanently, and `deployed` is one of
  // the two statuses that buzzes Matt's phone, so they never did.
  const carried = (r) => Boolean(contains) && runs.some((n) => (
    n.workflowName === r.workflowName
    && n.createdAt > r.createdAt
    && (n.conclusion === 'success' || n.conclusion === 'skipped')
    && contains(sha, n.headSha)
  ))
  const live = (r) => r.conclusion === 'success' || r.conclusion === 'skipped'
    || carried(r)
    || (r.conclusion === 'cancelled'
      && runs.some((n) => n.workflowName === r.workflowName && n.createdAt > r.createdAt && live(n)))
  const what = (rs) => Object.keys(DEPLOYS).filter((w) => rs.some((r) => r.workflowName === w))
    .map((w) => DEPLOYS[w]).join(', ')
  const going = mine.filter((r) => r.status !== 'completed')
  const failed = mine.filter((r) => r.status === 'completed' && !live(r))
  if (failed.length) return { status: 'merged', note: `deploy failed: ${what(failed)}` }
  if (going.length) return { status: 'merged', note: `deploying: ${what(going)}` }
  return { status: 'deployed', note: `live: ${what(mine)}` }
}

/**
 * `gh pr list --json state,url` for one branch in, the status to write
 * out. A builder that ended with nothing to show is paused, not
 * cancelled: its worktree is kept and the next run resumes it. One whose
 * request was taken back while it ran is cancelled.
 */
export function outcome(prs, { withdrawn = false, cancelled = false } = {}) {
  // Cancelled in the app: the pull request stays, named, and nothing but
  // a merge outranks the cancel.
  if (cancelled && !(prs || []).some((p) => p.state === 'MERGED')) {
    const kept = (prs || []).find((p) => p.state === 'OPEN') ?? (prs || [])[0]
    return { status: 'cancelled', pr: kept?.url ?? null, note: CANCELLED }
  }
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
  rows = [], ready = [], held = [], filed = [], stalled = [], alive = [], prs = {}, branches = [], runs, now, cancelled = [],
  // Whether one commit has another behind it. Supplied by the caller so
  // this stays a pure function; git answers it for real.
  contains,
}) {
  const byName = new Map(rows.filter((r) => r.name).map((r) => [r.name, r]))
  const names = new Set([
    ...ready, ...held, ...filed, ...alive, ...stalled, ...cancelled, ...Object.keys(prs), ...branches, ...byName.keys(),
  ])
  const out = []
  for (const name of names) {
    const row = byName.get(name)
    const found = truth(name, row?.status, { ready, held, filed, stalled, alive, cancelled, prs: prs[name] || [], runs, at: now, contains })
    if (!found) continue
    const { deploy, ...t } = found
    const backfill = t.started_at && !row?.started_at
    // Where the deploy is lives in the note, so a changed note is a change.
    const moved = deploy && (row?.note ?? undefined) !== t.note
    if (row?.status === t.status && !backfill && !moved) continue
    out.push({ name, ...t })
  }
  return out
}

/**
 * A merged pull request's row: how far its deploy got where the runs can
 * tell, and otherwise merged, or deployed if somebody already said so.
 */
function shipped(pr, now, deploys) {
  const d = deployment(pr, deploys)
  if (d) return { ...outcome([pr]), ...d, deploy: true }
  return { ...outcome([pr]), status: now === 'deployed' ? now : 'merged' }
}

/** What one task's row should say, or null to leave it as it is. */
function truth(name, now, { ready, held, filed, stalled, alive, cancelled = [], prs, runs, at, contains }) {
  const merged = prs.find((p) => p.state === 'MERGED')
  const landed = now === 'merged' || now === 'deployed'
  const deploys = { runs, now: at, contains }
  // Filed under done/ is the strongest evidence there is that it finished.
  if (filed.includes(name)) {
    if (merged) return shipped(merged, now, deploys)
    return landed ? null : { status: 'merged' }
  }
  // A request file that says cancelled is cancelled, agent or no agent:
  // the next tick kills one still running. Only a merge outranks it.
  if (cancelled.includes(name)) {
    if (merged) return shipped(merged, now, deploys)
    return now === 'cancelled' ? null : { status: 'cancelled', note: CANCELLED }
  }
  // An agent working on it beats `blocked`, always. `blocked` used to win
  // here, on the reasoning that a builder which stopped to ask should not
  // have its question overwritten — but the dispatcher writes `blocked`
  // for a stalled worktree too, and that one is answered by somebody
  // picking the work up, which is exactly what a live agent is. Two tasks
  // read `blocked` while one was merged and the other had an agent on it.
  // `blocked` is terminal. Matt: "A task should NEVER enter blocked
  // unless the process is COMPLETELY unrecoverable and the agent can no
  // longer function. It is NOT a temporary status, it is a PERMANENT,
  // COMPLETELY UNRESOLVABLE status."
  //
  // So nothing here takes it back. It used to be cleared by a live
  // agent, which is how a task could flap into it and out of it once a
  // minute — the dispatcher wrote it for a stalled worktree, this
  // cleared it, the next tick wrote it again. That source is gone
  // (a stalled worktree is `paused` now) and this is the other half:
  // once a task is genuinely unrecoverable, it stays said until a
  // person decides otherwise.
  //
  // A merge still outranks it, below, because work that shipped plainly
  // was not unrecoverable and a row saying otherwise is just wrong.
  if (now === 'blocked' && !merged) return null
  if (alive.includes(name)) return now === 'in progress' ? null : { status: 'in progress' }
  const isReady = ready.includes(name)
  const isHeld = held.includes(name)
  if (merged || prs.some((p) => p.state === 'OPEN')) {
    const o = outcome(prs, { withdrawn: !isReady && !isHeld })
    // A merge answers everything, `blocked` included.
    if (o.status === 'merged' || merged) return shipped(merged, now, deploys)
    if (now === 'blocked' || now === 'cancelled') return null
    if (o.status === 'merged') return shipped(merged, now, deploys)
    if (landed) return null
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

/**
 * What a cancel in the app asks of this laptop. Matt: "i want the
 * ability to cancel/abort tasks and any running agent should be
 * immediately killed. prs, branches etc can stay just need to kill the
 * agent". `files` are the request files to mark cancelled, so the
 * dispatcher never builds them again; `kill` the ones with an agent
 * alive on them. `files` is each live request file's text by name.
 */
export function cancels({ rows = [], files = {}, alive = [] }) {
  const out = rows.filter((r) => r.name && r.status === 'cancelled').map((r) => r.name)
  return {
    files: out.filter((n) => files[n] !== undefined && statusOf(files[n]) !== 'cancelled'),
    kill: out.filter((n) => alive.includes(n)),
  }
}

/** A request file's text with its frontmatter saying `status: cancelled`, and nothing else touched. */
export function cancelFile(text) {
  const t = String(text ?? '').replace(/\r\n/g, '\n')
  if (!t.startsWith('---\n') || t.indexOf('\n---', 4) === -1) return `---\nstatus: cancelled\n---\n\n${t}`
  const end = t.indexOf('\n---', 4)
  const head = t.slice(4, end)
  const next = /^status:.*$/m.test(head) ? head.replace(/^status:.*$/m, 'status: cancelled') : `status: cancelled\n${head}`
  return `---\n${next}${t.slice(end)}`
}

/**
 * Kill the agent working on a request, and everything it started. An
 * agent is a process whose command line names `requests/<name>.md`,
 * which is how `reconcile` already tells one is alive; what it started
 * (a Bash tool call, a test run) does not name it, so its children are
 * found first, while they are still its children, and killed with it.
 * The pids killed come back.
 */
export function stop(name) {
  const pids = (args) => {
    try {
      return execFileSync('pgrep', args, { encoding: 'utf8' }).split('\n').map(Number).filter(Boolean)
    } catch { return [] }
  }
  const pattern = `requests/${name.replace(/[.*+?^$()[\]{}|\\]/g, '\\$&')}\\.md( |$)`
  const all = []
  const walk = (pid) => {
    if (pid === process.pid || all.includes(pid)) return
    all.push(pid)
    pids(['-P', String(pid)]).forEach(walk)
  }
  pids(['-f', pattern]).forEach(walk)
  for (const pid of all) {
    try { process.kill(pid, 'SIGKILL') } catch { /* already gone */ }
  }
  return all
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

/**
 * Every task cancelled in the app, carried out on this laptop: its
 * request file marked so it is never built again, and its agent killed.
 */
async function cancelNow({ repo, token, base = API }) {
  const res = await fetch(`${base}/tasks`, { headers: { authorization: `Bearer ${token}` } })
  if (!res.ok) throw new Error(`the Worker would not list tasks: ${res.status}`)
  const { tasks: rows = [] } = await res.json()
  const dir = join(repo, 'requests')
  const files = {}
  for (const f of readdirSync(dir).filter((e) => e.endsWith('.md') && e !== 'README.md')) {
    files[basename(f, '.md')] = readFileSync(join(dir, f), 'utf8')
  }
  const running = (name) => {
    try { execFileSync('pgrep', ['-f', `requests/${name}.md`]); return true } catch { return false }
  }
  const todo = cancels({ rows, files, alive: Object.keys(files).filter(running) })
  const done = []
  for (const name of todo.files) {
    writeFileSync(join(dir, `${name}.md`), cancelFile(files[name]))
    done.push(`${name}: request file marked cancelled`)
  }
  for (const name of todo.kill) done.push(`${name}: agent killed (${stop(name).length} processes)`)
  return done
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
  const said = (f) => statusOf(readFileSync(join(dir, f), 'utf8'))
  const cancelled = live.filter((f) => said(f) === 'cancelled').map(strip)
  const held = live.map(strip).filter((n) => !ready.includes(n) && !cancelled.includes(n))
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
      '--json', 'headRefName,state,url,createdAt,mergedAt,mergeCommit'], { cwd: repo, encoding: 'utf8' }))
    for (const p of all) {
      const n = requestOfBranch(p.headRefName)
      if (n) (prs[n] ||= []).push(p)
    }
  } catch { /* gh unavailable: the rest still stands */ }
  // What every merge to main set off, so a merged row can say whether it
  // went live. Left undefined when gh cannot say, which leaves them be.
  let runs
  try {
    runs = JSON.parse(execFileSync('gh', ['run', 'list', '--branch', 'main', '--event', 'push', '--limit', '300',
      '--json', 'headSha,workflowName,status,conclusion,createdAt'], { cwd: repo, encoding: 'utf8' }))
  } catch { /* no deploy runs to read */ }
  // Does one commit have another behind it. Answered by git, cached,
  // and false whenever git cannot say — a commit this clone has never
  // fetched must not be read as "contains", or a task goes `deployed`
  // on no evidence.
  const seen = new Map()
  const contains = (ancestor, descendant) => {
    if (!ancestor || !descendant) return false
    const key = `${ancestor} ${descendant}`
    if (seen.has(key)) return seen.get(key)
    let yes = false
    try {
      execFileSync('git', ['merge-base', '--is-ancestor', ancestor, descendant],
        { cwd: repo, stdio: 'ignore' })
      yes = true
    } catch { yes = false }
    seen.set(key, yes)
    return yes
  }
  const wrote = reconcile({ rows, ready, held, filed, stalled, alive, prs, branches, runs, cancelled, contains })
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
  } else if (cmd === 'stalled') {
    // `paused`, NOT `blocked`.
    //
    // A worktree with no agent in it is the definition of recoverable:
    // the work is sitting there and starting another agent picks it up.
    // It was writing `blocked` on every timer firing, reconcile was
    // clearing it, and the next tick wrote it again — so a task flapped
    // in and out of `blocked` once a minute, and with push notifications
    // on that is a phone buzzing once a minute. Matt: "The tasks are
    // constantly flopping in and out of blocked."
    //
    // `paused` is the word the vocabulary already has for this: nothing
    // is running, the work is kept, the reason says why. It is also not
    // a status anything pushes for.
    //
    // The agent's own last words go in the note when the shell found
    // them. "Not logged in · Please run /login" is a different problem
    // from "it crashed", and a row that says the first is a row Matt can
    // act on in ten seconds.
    // And nothing at all when the agent left a pull request behind.
    //
    // This wrote `paused` for any worktree with nobody in it, without
    // ever looking at what the agent had achieved. Reconcile reads an
    // open pull request and writes `in review`; this then wrote `paused`
    // straight over the top, and the next tick did both again.
    // `the-ui-retry-policy-has-never-worked` went round that loop once a
    // minute for hours, so the row was wrong half the time. It is the
    // same shape as the `blocked` flap above, one layer down.
    //
    // A worktree with no agent and a pull request open is not stalled:
    // the agent built it, pushed it and stopped, which is exactly what
    // `in review` means. Reconcile owns that row. Only a worktree with
    // nothing to show is this path's business.
    let shown = [];
    try {
      shown = JSON.parse(execFileSync('gh', [
        'pr', 'list', '--state', 'all', '--limit', '100', '--json', 'state,headRefName',
      ], { cwd: repo, encoding: 'utf8' }) || '[]');
    } catch { /* no gh, no network: fall through and say what we know */ }
    const mine = shown.filter((p) => String(p.headRefName || '').startsWith(`request/${name}-`));
    if (mine.some((p) => p.state === 'OPEN' || p.state === 'MERGED')) {
      say(`${name} has a pull request open; reconcile owns that row, not this one`);
      transition = null;
    } else {
      const said = process.argv.slice(4).join(' ').trim();
      transition = {
        name,
        status: 'paused',
        note: said
          ? `stopped: ${said.slice(0, 160)}`
          : 'stopped with work left in its worktree; it needs a person to pick it up',
      };
    }
  } else if (cmd === 'settle') {
    let prs = []
    try {
      prs = JSON.parse(execFileSync('gh', ['pr', 'list', '--head', branch, '--state', 'all', '--json', 'state,url'], {
        cwd: repo, encoding: 'utf8',
      }))
      const file = join(repo, 'requests', `${name}.md`)
      const withdrawn = !existsSync(file) && !existsSync(join(repo, 'requests', 'done', `${name}.md`))
      const cancelled = existsSync(file) && statusOf(readFileSync(file, 'utf8')) === 'cancelled'
      transition = { name, ...outcome(prs, { withdrawn, cancelled }) }
    } catch (e) {
      say(`could not ask gh about ${branch}: ${e.message}`)
    }
  } else if (cmd === 'cancel') {
    if (!token) say('no credential that may read task status, so nothing was cancelled')
    else {
      await cancelNow({ repo, token })
        .then((done) => done.forEach((line) => say(`cancel: ${line}`)))
        .catch((e) => say(e.message))
    }
  } else if (cmd === 'reconcile') {
    if (!token) say('no credential that may write task status, so nothing was reconciled')
    else {
      reconcileNow({ repo, token })
        .then((wrote) => wrote.forEach((t) => say(`${t.name} ${t.status}${t.note ? ` (${t.note})` : ''}`)))
        .catch((e) => say(e.message))
    }
  } else {
    console.error('usage: task-status.mjs building <file> | settle <file> <branch> | cancel | reconcile')
    process.exit(2)
  }

  // The note goes in the message too: when there is no credential the log
  // is the only place the reason survives, and the reason is the point.
  if (transition && !token) {
    say(`no credential that may write task status, so ${name} ${transition.status}`
      + `${transition.note ? ` (${transition.note})` : ''} was not written`)
  }
  else if (transition) {
    report(transition, { token })
      .then(() => say(`${name} ${transition.status}`))
      .catch((e) => say(e.message))
  }
}
