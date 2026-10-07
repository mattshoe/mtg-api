// What the request watcher decides.
//
// `scripts/intake/dispatch.sh` is a shell script because launchd is what
// runs it, but none of the thinking happens in bash. The first draft did
// it with grep and got two things wrong in ten minutes: `grep -L` with no
// file arguments reads stdin and hangs forever, and a file triage had
// never touched was handed to a builder with no plan in it.
//
// So the decisions live here, where `test/intake-dispatch.test.js` can
// hold them to account, and the shell asks this file.

import { readFileSync, readdirSync } from 'node:fs'
import { join, basename } from 'node:path'

/** The request files in a listing of `requests/`. */
export function pending(entries) {
  return entries.filter((e) => (
    e.endsWith('.md') && e !== 'README.md' && !e.startsWith('.')
  ))
}

/** Triage rewrites every file it handles with a plan. */
export function triaged(text) {
  return /^## Plan\s*$/m.test(text)
}

/** Triage sets this when it could not tell what was wanted. */
export function waiting(text) {
  const front = frontmatter(text)
  return /^status:\s*needs-matt\s*$/m.test(front)
}

/**
 * Whether a builder may be spun up on this.
 *
 * No plan means triage never got to it, and a builder with no plan
 * invents a smaller problem and solves that.
 */
export function buildable(text) {
  return triaged(text) && !waiting(text)
}

/** The branch a builder works on. Git refuses a lot of names. */
export function branchFor(file) {
  const slug = basename(file, '.md')
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
  return `request/${slug || 'unnamed'}`
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

/** What a builder cannot work without. */
export const REQUIRED = [
  '.claude/skills/mtg/SKILL.md',
  '.claude/agents/request-builder.md',
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

/** The frontmatter block, or '' when there is none. */
function frontmatter(text) {
  if (!text.startsWith('---\n')) return ''
  const end = text.indexOf('\n---', 4)
  return end === -1 ? '' : text.slice(4, end)
}

// ---- the bit the shell calls ----

function read(dir, file) {
  try { return readFileSync(join(dir, file), 'utf8') } catch { return '' }
}

if (process.argv[1] && process.argv[1].endsWith('intake.mjs')) {
  const [cmd, arg] = process.argv.slice(2)
  const dir = process.env.INTAKE_DIR || 'requests'
  const list = () => { try { return pending(readdirSync(dir)) } catch { return [] } }

  if (cmd === 'pending') {
    console.log(list().join('\n'))
  } else if (cmd === 'untriaged') {
    console.log(list().filter((f) => !triaged(read(dir, f))).join('\n'))
  } else if (cmd === 'buildable') {
    console.log(list().filter((f) => buildable(read(dir, f))).join('\n'))
  } else if (cmd === 'held') {
    console.log(list().filter((f) => !buildable(read(dir, f))).join('\n'))
  } else if (cmd === 'branch') {
    console.log(branchFor(arg || ''))
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
    console.error('usage: intake.mjs pending|untriaged|buildable|held|branch <f>|fires <path>')
    process.exit(2)
  }
}
