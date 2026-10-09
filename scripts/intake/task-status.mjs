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
//
// `building` before the builder starts. `settle` after it exits: what its
// pull request came to, asked of `gh` on this laptop, which is signed in
// and has no sixty-an-hour ceiling. Neither may stop a build: a refusal is
// a line in the log and the dispatcher carries on.

import { readFileSync } from 'node:fs'
import { join, basename, dirname } from 'node:path'
import { execFileSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import { agentToken } from './inbox.mjs'

const API = 'https://mtg-api.mattshoe81.workers.dev'

/** Furthest first: a request built twice is at the furthest any of its pull requests got. */
const ORDER = [['MERGED', 'done'], ['OPEN', 'in review'], ['CLOSED', 'closed']]

/** `gh pr list --json state,url` for one branch in, the status to write out. */
export function outcome(prs) {
  for (const [state, status] of ORDER) {
    const hit = (prs || []).find((p) => p.state === state)
    if (hit) return { status, pr: hit.url }
  }
  return { status: 'stopped', pr: null }
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

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
  const [cmd, file, branch] = process.argv.slice(2)
  const repo = join(dirname(fileURLToPath(import.meta.url)), '..', '..')
  const name = basename(String(file || ''), '.md')
  const stamp = new Date().toTimeString().slice(0, 8)
  const say = (line) => console.log(`${stamp}  task-status: ${line}`)
  const token = agentToken()

  let transition
  if (cmd === 'building') {
    let text = ''
    try { text = readFileSync(join(repo, 'requests', `${name}.md`), 'utf8') } catch { /* gone */ }
    transition = { name, status: 'building', title: titleOf(text) || undefined }
  } else if (cmd === 'settle') {
    let prs = []
    try {
      prs = JSON.parse(execFileSync('gh', ['pr', 'list', '--head', branch, '--state', 'all', '--json', 'state,url'], {
        cwd: repo, encoding: 'utf8',
      }))
      transition = { name, ...outcome(prs) }
    } catch (e) {
      say(`could not ask gh about ${branch}: ${e.message}`)
    }
  } else {
    console.error('usage: task-status.mjs building <file> | settle <file> <branch>')
    process.exit(2)
  }

  if (transition && !token) say(`no ~/.mtg-agent.env, so ${name} ${transition.status} was not written`)
  else if (transition) {
    report(transition, { token })
      .then(() => say(`${name} ${transition.status}`))
      .catch((e) => say(e.message))
  }
}
