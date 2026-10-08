// What the request watcher decides.
//
// `scripts/intake/dispatch.sh` is a shell script because launchd is what
// runs it, but none of the thinking happens in bash. The first draft did
// it with grep and got two things wrong in ten minutes: `grep -L` with no
// file arguments reads stdin and hangs forever, and a file triage had
// never touched was handed to a builder with no plan in it.
//
// So the decisions live here, where `test/intake-dispatch.test.js` can
// hold them to account, and the shell asks this file. `status.sh` asks it
// too — it used to re-implement `triaged` and `needs-matt` with grep, and
// the two implementations disagreed.

import { readFileSync, readdirSync } from 'node:fs'
import { createHash } from 'node:crypto'
import { join, basename } from 'node:path'

/**
 * Line endings a parser can rely on.
 *
 * The frontmatter parser required `---\n` at byte zero, so a request file
 * saved by a Windows editor lost its whole frontmatter — while the old
 * `triaged()` still matched, because its `\s*` ate the `\r`. The one
 * request triage had flagged as needing Matt was therefore the one that
 * got built.
 */
function normalise(text) {
  return String(text ?? '').replace(/\r\n/g, '\n').replace(/\r/g, '\n')
}

/**
 * The request files in a listing of `requests/`.
 *
 * `done` is the listing of `requests/done/`. A finished request was never
 * removed from the live folder: the builder moved its own copy inside its
 * worktree, and the real repo is on another branch and never pulls. So
 * `edhrec-sort-backwards.md` and `kayla-account-owns-her-cards.md` were
 * built hours apart and still read as buildable — each rebuild starting
 * from a base that already contained the feature, so the TDD red could
 * not reproduce and every pass opened another pull request.
 */
export function pending(entries, { done = [] } = {}) {
  const finished = new Set(done)
  return entries.filter((e) => (
    e.endsWith('.md') && e !== 'README.md' && !e.startsWith('.') && !finished.has(e)
  ))
}

/** The sections triage promises to leave behind. */
export const SECTIONS = ['## Plan', '## Tests', '## Done when']

/**
 * The text with fenced code blocks taken out.
 *
 * `request-triage.md` documents the shape it writes by showing it inside
 * a ```markdown fence, and triage has copied that fence into a request
 * file. A `## Plan` inside a code block is an example, not a plan.
 */
function withoutFences(text) {
  const out = []
  let fence = null
  for (const line of normalise(text).split('\n')) {
    const m = /^\s{0,3}(```+|~~~+)/.exec(line)
    if (m) {
      if (fence === null) { fence = m[1][0]; continue }
      if (m[1][0] === fence) { fence = null; continue }
    }
    if (fence === null) out.push(line)
  }
  return out.join('\n')
}

/**
 * Triage rewrites every file it handles with a plan, tests and a
 * done-when.
 *
 * Testing only for `## Plan` let triage write a complete-looking file
 * that was still "untriaged", so it got a full opus triage run on every
 * dispatch forever while being reported as ready at the same time.
 * `requests/release-notes-in-admin-settings.md` was the live instance.
 */
export function triaged(text) {
  const body = withoutFences(text)
  return SECTIONS.every((h) => new RegExp(`^${h}\\s*$`, 'm').test(body))
}

/** The frontmatter block, or '' when there is none. */
function frontmatter(text) {
  const t = normalise(text)
  if (!t.startsWith('---\n')) return ''
  const end = t.indexOf('\n---', 4)
  return end === -1 ? '' : t.slice(4, end)
}

/** One frontmatter value, lowercased, without quotes or a trailing comment. */
function field(text, key) {
  const m = new RegExp(`^${key}:(.*)$`, 'm').exec(frontmatter(text))
  if (!m) return ''
  return m[1]
    .replace(/#.*$/, '')
    .trim()
    .replace(/^["']|["']$/g, '')
    .trim()
    .toLowerCase()
}

/**
 * The `status:` triage set, or '' when it set none.
 *
 * Anything the frontmatter did not say exactly was treated as ready to
 * build: `status: blocked`, `status: done`, `Needs-Matt`, a value with a
 * trailing comment, and a file with no `status:` key at all were all
 * buildable. There is to be no fourth, implicit state.
 */
export function statusOf(text) {
  return field(text, 'status')
}

/** Triage sets this when it could not tell what was wanted. */
export function waiting(text) {
  return statusOf(text) === 'needs-matt'
}

/**
 * Whether a builder may be spun up on this.
 *
 * No plan means triage never got to it, and a builder with no plan
 * invents a smaller problem and solves that. Anything but exactly
 * `ready` is held, never built.
 */
export function buildable(text) {
  return triaged(text) && statusOf(text) === 'ready'
}

/**
 * Why a request is not buildable, in one word for a status line.
 *
 * `status.sh` used to work this out with its own greps, and they
 * disagreed with the predicates here: `grep -qF '## Plan'` is an
 * unanchored substring and `grep -q '^status: needs-matt'` matches the
 * body outside the frontmatter. So status said "ready" for files the
 * dispatcher held, and the other way round.
 */
export function state(text) {
  if (!triaged(text)) return 'untriaged'
  const s = statusOf(text)
  if (s === 'ready') return 'ready'
  if (s === 'needs-matt') return 'needs-matt'
  return s ? `held: status ${s}` : 'held: no status'
}

/**
 * Whether the dispatcher merges this one when CI goes green.
 *
 * Read from the file and tested here, never decided by the model.
 * Unknown means ask, which is the safe way to be wrong.
 */
export function mergeMode(text) {
  const v = field(text, 'merge')
  if (v === 'ask') return 'ask'
  if (v === 'auto' || v === '') return 'auto'
  return 'ask'
}

/**
 * The branch a builder works on. Git refuses a lot of names.
 *
 * The slug alone collapsed distinct filenames onto one branch —
 * `deck_page.md` and `deck-page.md` both gave `request/deck-page`, and
 * every all-non-ASCII name gave `request/unnamed`. The `.building`
 * claims are keyed on the branch, so two requests sharing a branch
 * meant two builders committing to one ref with neither blocking the
 * other. The digest is what makes the ref a function of the exact name.
 */
export function branchFor(file) {
  const name = basename(String(file ?? ''), '.md')
  const digest = createHash('sha1').update(name).digest('hex').slice(0, 7)
  let slug = name
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
  // `request/` is 8, the digest 7, the separator 1. Git's own limit is
  // far higher but a 400-character ref is unusable in a log line.
  if (slug.length > 84) slug = slug.slice(0, 84).replace(/-+$/g, '')
  return `request/${slug ? `${slug}-` : ''}${digest}`
}

/**
 * Whether a worktree carries the instructions a builder needs.
 *
 * A builder works in a worktree branched off main, and for a while
 * `.claude/` existed only on the branch that introduced it. Two builders
 * started in worktrees with neither the skill nor their own definition,
 * were told to invoke the `mtg` skill, found nothing, and built without
 * the parity rule or the TDD discipline. Nothing failed — they worked
 * blind and looked exactly like builders that had read everything.
 *
 * So the worktree is asked before anything runs in it.
 */
export function equipped(present) {
  const have = new Set(present)
  const missing = REQUIRED.filter((f) => !have.has(f))
  return { ok: missing.length === 0, missing }
}

/**
 * Whether a builder actually finished, which its exit code does not say.
 *
 * `claude -p` ends when the model stops producing text. One builder
 * started the core and web suites, wrote "Red runs for core and web are
 * in progress", and ended its turn — exit 0, nothing committed, no pull
 * request. The dispatcher read 0 as success and removed the worktree,
 * throwing the work away.
 *
 * What "done" means changed with the division of labour. The builder no
 * longer waits on CI and no longer merges: the `apps` job takes 13-17
 * minutes and three of four builders ended their turn rather than sit
 * through it — one called ScheduleWakeup and stopped, another's last
 * line was "I'll pick up from that notification", and in headless
 * `claude -p` there is no next turn. 154 minutes of agent wall-clock
 * across four builders produced zero merged pull requests.
 *
 * So done is: commits on the branch, the branch pushed, a pull request
 * that exists. `prMerged` is the fourth signal, because
 * `gh pr list --state open` cannot see a merged pull request — a builder
 * that did exactly what it was told was judged unfinished every time.
 */
export function builderDone({
  exitCode, prOpen, prMerged = false, commits = 0, pushed = false,
}) {
  const why = []
  if (exitCode !== 0) why.push(`exited ${exitCode}`)
  if (!prOpen && !prMerged) why.push('no pull request for its branch')
  if (!commits) why.push('nothing committed on its branch')
  else if (!pushed) why.push('its commits are not pushed')
  const ok = why.length === 0
  return {
    ok,
    keep: !ok,
    why: why.join('; '),
    state: ok ? (prMerged ? 'merged' : 'open') : 'unfinished',
  }
}

/**
 * What the dispatcher does once CI has reported.
 *
 * Green CI is still the gate. It just stops being a thing an agent has
 * to sit through.
 */
export function afterCi({ green, merge }) {
  if (!green) return { action: 'fix' }
  return { action: merge === 'auto' ? 'merge' : 'notify' }
}

/**
 * What a builder cannot work without.
 *
 * This named the skill and the agent definition and stopped there —
 * while the builder is told CLAUDE.md is the law that overrides the
 * skill, told to run every suite through `scripts/guard.mjs`, told
 * `check-test-count.mjs` is what proves a suite ran, and told never to
 * let a floor in `suite-floors.json` drop. A worktree missing any of
 * those produces a builder that works blind in exactly the way this
 * check was written to prevent.
 */
export const REQUIRED = [
  '.claude/skills/mtg/SKILL.md',
  '.claude/agents/request-builder.md',
  'CLAUDE.md',
  'scripts/guard.mjs',
  'scripts/check-test-count.mjs',
  'test/suite-floors.json',
]

/** Whether a changed path is a request, for the PostToolUse hook. */
export function hookFires(path) {
  if (!path) return false
  const parts = path.split('/')
  const i = parts.lastIndexOf('requests')
  if (i === -1) return false
  // Exactly one segment after `requests/`, so `done/old.md` is out.
  if (parts.length - i !== 2) return false
  return pending([parts[parts.length - 1]]).length === 1
}

// ---- the bit the shell calls ----

function read(dir, file) {
  try { return readFileSync(join(dir, file), 'utf8') } catch { return '' }
}

function listing(dir) {
  try { return readdirSync(dir) } catch { return [] }
}

if (process.argv[1] && process.argv[1].endsWith('intake.mjs')) {
  const [cmd, arg, ...rest] = process.argv.slice(2)
  const dir = process.env.INTAKE_DIR || 'requests'
  const list = () => pending(listing(dir), { done: listing(join(dir, 'done')) })

  if (cmd === 'pending') {
    console.log(list().join('\n'))
  } else if (cmd === 'untriaged') {
    console.log(list().filter((f) => !triaged(read(dir, f))).join('\n'))
  } else if (cmd === 'buildable') {
    console.log(list().filter((f) => buildable(read(dir, f))).join('\n'))
  } else if (cmd === 'held') {
    console.log(list().filter((f) => !buildable(read(dir, f))).join('\n'))
  } else if (cmd === 'state') {
    // One word per request, `<file>\t<state>`, for status.sh. One
    // implementation of the predicates, not two that disagree.
    const names = arg ? [arg] : list()
    console.log(names.map((f) => `${f}\t${state(read(dir, f))}`).join('\n'))
  } else if (cmd === 'branch') {
    console.log(branchFor(arg || ''))
  } else if (cmd === 'merge') {
    console.log(mergeMode(read(dir, arg || '')))
  } else if (cmd === 'done-verdict') {
    // done-verdict <exitCode> <prOpen> <prMerged> <commits> <pushed>
    const [prOpen, prMerged, commits, pushed] = rest
    const v = builderDone({
      exitCode: Number(arg),
      prOpen: prOpen === 'true',
      prMerged: prMerged === 'true',
      commits: Number(commits || 0),
      pushed: pushed === 'true',
    })
    console.log(v.ok ? v.state : `keep:${v.why}`)
  } else if (cmd === 'after-ci') {
    // after-ci <green> <mergeMode>
    console.log(afterCi({ green: arg === 'true', merge: rest[0] }).action)
  } else if (cmd === 'equipped') {
    // `arg` is the worktree. Exits 0 when equipped, 1 and names what is
    // missing otherwise.
    const root = arg || '.'
    const present = REQUIRED.filter((f) => {
      try { readFileSync(join(root, f), 'utf8'); return true } catch { return false }
    })
    const v = equipped(present)
    if (!v.ok) console.error(`missing: ${v.missing.join(', ')}`)
    process.exit(v.ok ? 0 : 1)
  } else if (cmd === 'fires') {
    process.exit(hookFires(arg) ? 0 : 1)
  } else {
    console.error(
      'usage: intake.mjs pending|untriaged|buildable|held|state [f]|branch <f>|'
      + 'merge <f>|done-verdict <code> <open> <merged> <commits> <pushed>|'
      + 'after-ci <green> <mode>|equipped <tree>|fires <path>',
    )
    process.exit(2)
  }
}
