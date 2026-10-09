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

import { readFileSync, readdirSync, writeFileSync, rmSync } from 'node:fs'
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
 * The `status:` field, or '' when it set none.
 *
 * Anything the frontmatter did not say exactly was treated as ready to
 * build: `status: blocked`, `status: done`, `Needs-Matt`, a value with a
 * trailing comment, and a file with no `status:` key at all were all
 * buildable. There is to be no fourth, implicit state.
 */
export function statusOf(text) {
  return field(text, 'status')
}

/**
 * Whether an agent may be spun up on this.
 *
 * `status: ready` and nothing else. There used to be a separate triage
 * agent that had to write a `## Plan` first, and a request without one
 * was held — but triage was invented, not asked for, and the agent that
 * builds the request can plan it perfectly well itself. Matt writing
 * `ready` is the whole gate; anything else is held, never built, so a
 * request he is still typing is safe.
 */
export function buildable(text) {
  return statusOf(text) === 'ready'
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
  const s = statusOf(text)
  if (s === 'ready') return 'ready'
  // There was a `waiting()` export beside this that did nothing but
  // `statusOf(text) === 'needs-matt'`. Nothing called it — the dispatcher
  // asks `state` for this and matches on the word — so it had five tests
  // and no caller, which is what `builderDone` and `afterCi` were removed
  // for. This line is the only place the question is answered now.
  if (s === 'needs-matt') return 'needs-matt'
  return s ? `held: status ${s}` : 'held: no status'
}

/**
 * How a request should be merged.
 *
 * Absent means `auto`. The agent merges its own work on green CI — that is
 * the rule, and a missing field is not a reason to break it. Only an explicit
 * `merge: ask` stops at a green pull request, and Matt is the only one who
 * writes that, when he says he wants to look first.
 *
 * This was the other way round for about an hour, on the reasoning that
 * not-saying is not consent. That was wrong: it meant every request written
 * without the field sat unmerged forever waiting on a human, which is exactly
 * what green CI exists to replace.
 */
export function mergeMode(text) {
  return field(text, 'merge') === 'ask' ? 'ask' : 'auto'
}

/**
 * The branch a builder works on. Git refuses a lot of names.
 *
 * The slug alone collapsed distinct filenames onto one branch —
 * `deck_page.md` and `deck-page.md` both gave `request/deck-page`, and
 * every all-non-ASCII name gave `request/unnamed`. Two requests sharing a
 * branch meant two builders committing to one ref with neither blocking
 * the other, and `gh pr list --head` then answered about the wrong pull
 * request. The digest is what makes the ref a function of the exact name.
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
  // The Part D enforcement mechanism. Without these three a builder runs
  // `--permission-mode bypassPermissions` with the deny hook silently
  // absent, which is the whole of Part D not happening — and nothing
  // fails, which is what makes it the same bug as the blind builder.
  '.claude/settings.json',
  'scripts/bash-deny.mjs',
  'scripts/intake/deny-bash.mjs',
]

/**
 * How many agents one request may consume before it needs Matt.
 *
 * `remove-task-title` ran SEVEN times in four hours. Each run read the
 * whole worktree from cold, did a little, backgrounded an Android suite it
 * could not outlive, and died — and because a stopped agent leaves a
 * worktree behind, the next tick resumed it and did the same. Nothing in
 * the system had any notion of "this has been tried enough".
 *
 * Six is not a tuned number, it is a ceiling: a request that six separate
 * agent lifetimes have not finished is not going to be finished by a
 * seventh, and Matt would rather be told than keep paying for it.
 */
export const MAX_ATTEMPTS = 6

/**
 * How many attempts in a row may end with the branch exactly where it
 * started.
 *
 * This is the faster trip-wire, and it is the one that catches a true
 * wedge — an agent that cannot even reach a commit. Two is enough: one
 * agent committing nothing is a bad run, two in a row is the system.
 */
export const MAX_STALE = 2

/**
 * Whether another agent may be spent on this request.
 *
 * `line` is the request's ledger — `<attempts> <head> <stale>`, or '' the
 * first time. `head` is the worktree's HEAD **right now**, before this
 * attempt runs, so it is the previous attempt's result.
 *
 * Progress is a CHANGED HEAD SHA and nothing else. My first attempt at
 * this measured `git rev-list --count origin/main..HEAD` instead, which
 * three reviewers rejected for the same reason: that count goes to zero
 * the moment an agent merges its own work, so the most successful run
 * possible scored as no progress at all. A sha either moved or it did not,
 * whatever main has since done.
 */
export function attempt(line, head, { max = MAX_ATTEMPTS, maxStale = MAX_STALE } = {}) {
  const [n0, last = '', s0] = String(line ?? '').trim().split(/\s+/)
  const n = (Number(n0) || 0) + 1
  const sha = String(head ?? '').trim()
  // No ledger means nothing has been tried, which is not a stale attempt.
  const stale = (!last || last !== sha) ? 0 : (Number(s0) || 0) + 1
  if (stale >= maxStale) {
    return { stop: true, n, stale, why: `${stale} agents in a row left it at ${sha.slice(0, 7) || 'no commit'}` }
  }
  if (n > max) {
    return { stop: true, n, stale, why: `${n - 1} agents have worked on this and it is not finished` }
  }
  return { stop: false, n, stale, line: `${n} ${sha} ${stale}` }
}

/**
 * The request file, held, with the reason where the next reader sees it.
 *
 * The dispatcher cannot simply stop spending agents on a wedged request:
 * the next timer firing would claim it again, because the queue is decided
 * from `status: ready` in this very file. Holding the file is the only
 * thing that actually ends the loop — and the reason goes in the body
 * because the body is what Admin Settings shows on the task's own page, so
 * Matt reads why without opening anything.
 *
 * Done in node rather than `sed -i` on purpose: BSD sed wants `-i ''` and
 * GNU sed treats that as a filename, and dispatch.sh is exercised on both.
 */
export function heldText(text, reason) {
  const t = normalise(text)
  const note = `\n\n> Held by the dispatcher: ${reason}. Nothing is running on it and\n> its work is kept. Set \`status: ready\` to let agents pick it up again.\n`
  const fm = frontmatter(t)
  if (!fm) return `---\nstatus: hold\n---\n\n${t.replace(/^\n+/, '')}${note}`
  const held = /^status:.*$/m.test(fm)
    ? fm.replace(/^status:.*$/m, 'status: hold')
    : `status: hold\n${fm}`
  return `---\n${held}\n---${t.slice(4 + fm.length + 4)}${note}`
}

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
  const [cmd, arg] = process.argv.slice(2)
  const dir = process.env.INTAKE_DIR || 'requests'
  const list = () => pending(listing(dir), { done: listing(join(dir, 'done')) })

  if (cmd === 'pending') {
    console.log(list().join('\n'))
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
  } else if (cmd === 'attempt') {
    // `arg` is the ledger file and the next argument the worktree's HEAD.
    // Exits 0 to go ahead, having recorded this attempt, and 3 to stop,
    // having removed the ledger so that un-holding the request starts it
    // over with a full allowance.
    const ledger = arg || ''
    const head = process.argv[4] || ''
    let line = ''
    try { line = readFileSync(ledger, 'utf8') } catch { /* the first attempt */ }
    const v = attempt(line, head)
    if (v.stop) {
      console.error(v.why)
      try { rmSync(ledger) } catch { /* never existed */ }
      process.exit(3)
    }
    writeFileSync(ledger, `${v.line}\n`)
    console.log(`attempt ${v.n} of ${MAX_ATTEMPTS}`)
  } else if (cmd === 'hold') {
    // `arg` is the request file and the rest of the argv the reason.
    const reason = process.argv.slice(4).join(' ') || 'it needs you'
    const path = join(dir, arg || '')
    writeFileSync(path, heldText(readFileSync(path, 'utf8'), reason))
    console.log(`held ${arg}: ${reason}`)
  } else if (cmd === 'fires') {
    process.exit(hookFires(arg) ? 0 : 1)
  } else {
    console.error(
      'usage: intake.mjs pending|buildable|held|state [f]|'
      + 'branch <f>|merge <f>|equipped <tree>|attempt <ledger> <head>|'
      + 'hold <f> <reason>|fires <path>',
    )
    process.exit(2)
  }
}
