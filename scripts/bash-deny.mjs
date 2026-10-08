// What a builder is not allowed to run. The rules, with no IO.
//
// THIS IS THE SECOND LINE OF DEFENCE, NOT THE FIRST. The first is in
// `scripts/intake/dispatch.sh:no_creds`, which launches every agent with
// `CLOUDFLARE_API_TOKEN`, `CLOUDFLARE_ACCOUNT_ID` and the rest unset and
// `WRANGLER_HOME` pointed at an empty directory — so
// `wrangler d1 execute --remote` fails on its own with "it's necessary to
// set a CLOUDFLARE_API_TOKEN environment variable" whatever an agent
// types and whatever this file misses.
//
// It misses things. The first version of this file was attacked with
// eighteen spellings a model would reach for first and every single one
// got through: `eval "wrangler deploy"`, `bash -c '…'`,
// `W=wrangler; $W deploy`, `wrangler dep""loy`, `--admin=true`,
// `git push origin HEAD:"main"`, `gh api graphql` doing the merge with a
// `mergePullRequest` mutation, `git --git-dir=… --work-tree=…` with no
// `-C`, `cd /elsewhere && git reset --hard`, and a `wrangler d1 execute`
// with no `--remote` at all, which still reaches production.
//
// What changed: the command is normalised with every quote character
// REMOVED before any rule reads it, so `dep""loy` reads as `deploy`,
// `HEAD:"main"` reads as `HEAD:main`, and a whole command smuggled inside
// `sh -c "…"` reads as itself. The rules are then regexes over that text
// rather than exact token comparisons. Shell indirection — `eval`,
// `sh -c`, `bash -c` — is refused outright rather than parsed, because
// parsing it is how you lose.
//
// Split from the hook because the suite runs inside workerd, which has
// no `node:fs` — the same shape as `suite-floor.mjs`.

import { resolve } from 'node:path'

/**
 * One command, with nothing left to hide behind.
 *
 * Backslash-escaped quotes become quotes, then every quote and backtick
 * is deleted. That is deliberately not a shell parser: it cannot be
 * tricked into reading less than what will run, only into reading more,
 * and a rule that fires on more text is the failure mode to want.
 */
function flatten(command) {
  return String(command ?? '')
    .replace(/\\(["'`])/g, '$1')
    .replace(/["'`]/g, '')
}

/** The pieces of a command line that run independently of each other. */
function parts(text) {
  return text
    .split(/(?:\|\||&&|[;\n|&])/)
    .map((p) => p.trim())
    .filter(Boolean)
}

/** Whether a path, resolved for real, is inside the worktree. */
function inside(target, cwd) {
  if (!target || !cwd) return false
  const home = process.env.HOME ?? ''
  const expanded = target.startsWith('~') && home
    ? home + target.slice(1)
    : target
  const full = resolve(cwd, expanded)
  return full === cwd || full.startsWith(`${cwd}/`)
}

// Every rule reads the FLATTENED text of one part. `p` is that part.
const DENIALS = [
  {
    why: 'shell indirection. `eval`, `sh -c` and `bash -c` hide what is '
      + 'about to run from anything that reads the command, so they are '
      + 'refused rather than parsed. Run the command directly.',
    hit: (p) => /(^|[\s(])eval([\s(]|$)/.test(p)
      || /(^|[\s/])(ba|z|k|da)?sh\s+(-[a-z]*\s+)*-[a-z]*c([\s]|$)/.test(p)
      || /(^|[\s/])(ba|z|k|da)?sh\s+-c([\s]|$)/.test(p),
  },
  {
    why: 'building a command out of a variable. There is no reason to '
      + 'reach a program through `$VAR` here, and it defeats every check '
      + 'that reads the command.',
    hit: (p) => /\b[A-Za-z_][A-Za-z0-9_]*=(wrangler|gh|git|npm|npx)\b/.test(p)
      || /\$\{?[A-Za-z_][A-Za-z0-9_]*\}?\s+(deploy|d1|merge|push)\b/.test(p),
  },
  {
    why: 'wrangler against a database that is not local. The production D1 '
      + 'id is in wrangler.toml, so anything but `--local` is a decision to '
      + 'touch the real collection. The suite uses miniflare and needs none '
      + 'of this. (The credentials are also stripped from your environment, '
      + 'so this would fail anyway.)',
    hit: (p) => /\bwrangler\b/.test(p) && /(^|\s)d1(\s|$)/.test(p)
      && !/--local\b/.test(p),
  },
  {
    why: 'deploying. A builder\'s turn ends at an open pull request; '
      + 'merging is what deploys, and that is the dispatcher\'s decision.',
    hit: (p) => (/\bwrangler\b/.test(p) && /\bdeploy\b/.test(p))
      || (/\b(npm|yarn|pnpm|npx)\b/.test(p) && /(^|\s)(run\s+)?deploy(\s|$)/.test(p)),
  },
  {
    why: 'pushing to main or master. Builders push their own request branch '
      + 'and nothing else; main moves when a pull request is merged.',
    hit: (p) => {
      if (!/\bgit\b/.test(p) || !/\bpush\b/.test(p)) return false
      // Config smuggling: `git -c remote.origin.push=HEAD:refs/heads/main`.
      if (/remote\.[^\s=]*\.push\s*=/.test(p)) return true
      const after = p.slice(p.indexOf('push') + 4)
      return /(^|[\s:+])(refs\/heads\/)?(main|master)(\s|$)/.test(after)
    },
  },
  {
    why: '--admin on a merge. It bypasses every required check, and the '
      + 'ruleset gives this token the power to do it. Green CI is the gate.',
    hit: (p) => /--admin(\s|=|$)/.test(p) && /\b(gh|git)\b/.test(p),
  },
  {
    why: 'merging through the API, which skips the check gate the same way '
      + '--admin does.',
    hit: (p) => /\bgh\b/.test(p) && /\bapi\b/.test(p)
      && (/\/merge(\?|\s|$|\/)/.test(p) || /mergePullRequest/i.test(p)
        || /enablePullRequestAutoMerge/i.test(p)),
  },
  {
    why: 'rewriting history or moving a branch somewhere it was not. Your '
      + 'branch moves by committing to it.',
    hit: (p) => /\bgit\b/.test(p)
      && /\bpush\b/.test(p)
      && /(--force(\s|$)|(^|\s)-f(\s|$))/.test(p)
      && !/--force-with-lease/.test(p),
  },
]

/**
 * Whether a part operates on a git repository outside the worktree.
 *
 * A builder with the full Bash tool can operate on Matt's live checkout,
 * or on another builder's slot, by naming it — and the first version only
 * looked at `git -C` with a path starting `/` or `~`, so
 * `git -C ../../repos/mtg-api reset --hard` walked straight out. Paths are
 * resolved for real now, and `--git-dir`, `--work-tree` and a leading `cd`
 * count as naming one.
 */
function escapesWorktree(p, cwd) {
  if (!cwd) return false

  const named = []
  for (const m of p.matchAll(/(?:^|\s)-C\s+(\S+)/g)) named.push(m[1])
  for (const m of p.matchAll(/--git-dir[=\s]+(\S+)/g)) named.push(m[1])
  for (const m of p.matchAll(/--work-tree[=\s]+(\S+)/g)) named.push(m[1])
  for (const m of p.matchAll(/(?:^|\s)--prefix[=\s]+(\S+)/g)) named.push(m[1])
  if (named.length > 0 && !/\bgit\b|\bnpm\b/.test(p)) return false
  if (named.some((t) => !inside(t, cwd))) return true

  // `cd /elsewhere` as its own part, which the hook cannot see through:
  // its cwd is the session's, not the one after the cd.
  const cd = /^cd\s+(\S+)/.exec(p)
  if (cd && !inside(cd[1], cwd) && cd[1] !== '-') return true

  return false
}

/**
 * `{ deny, reason }` for one Bash command.
 *
 * Every part of a compound command is judged, because a rule that reads
 * only the start of the line lets `npm test && npm run deploy` through.
 * A `cd` out of the worktree taints every later part of the same line,
 * since the hook sees only the session's cwd.
 */
export function denied(command, { cwd } = {}) {
  const flat = flatten(command)
  if (!flat.trim()) return { deny: false, reason: '' }

  for (const p of parts(flat)) {
    for (const rule of DENIALS) {
      if (rule.hit(p)) {
        return { deny: true, reason: `Refused: ${rule.why}\n  in: ${p}` }
      }
    }
    if (escapesWorktree(p, cwd)) {
      return {
        deny: true,
        reason: 'Refused: naming a git repository outside this worktree. '
          + `Everything you may touch is under ${cwd}.\n  in: ${p}`,
      }
    }
  }
  return { deny: false, reason: '' }
}
