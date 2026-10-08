// What a builder is not allowed to run. The rules, with no IO.
//
// Builders run `claude -p --permission-mode bypassPermissions` with the
// full Bash tool, and neither `.claude/settings.json` nor the user
// settings defined any `permissions.deny`. The worktree holds
// `wrangler.toml` with the production D1 id, wrangler's auth lives in
// $HOME, and `npm run deploy` is in package.json — so a remote
// `wrangler d1 execute` against the real collection was one model
// decision away. `gh pr merge --admin` bypasses every required check and
// the repo ruleset has `required_approving_review_count: 0` with Matt as
// owner, so the token really does have the power; the only thing stopping
// it was the sentence "never --admin" in a markdown file.
//
// Split from the hook because the suite runs inside workerd, which has
// no `node:fs` — the same shape as `suite-floor.mjs`.
//
// `permissions.deny` in settings.json is declared alongside this, but the
// hook is the half that enforces: deny rules are a permission decision,
// and `bypassPermissions` is the mode that skips permission decisions. A
// PreToolUse hook answering `deny` blocks the call regardless of mode.

/** The pieces of a command line that run independently of each other. */
function parts(command) {
  // Split on &&, ||, ;, |, and newlines. Crude on purpose: it over-splits
  // inside quotes, which can only ever make a rule fire on more text, not
  // less. A rule that reads only the start of the line is how
  // `npm test && npm run deploy` gets through.
  return String(command ?? '')
    .split(/(?:\|\||&&|[;\n|&])/)
    .map((p) => p.trim())
    .filter(Boolean)
}

/** The words of one part, with env assignments and `cd x` prefixes gone. */
function words(part) {
  const out = part.match(/(?:[^\s"']+|"[^"]*"|'[^']*')+/g) ?? []
  return out.map((w) => w.replace(/^["']|["']$/g, ''))
}

/** Whether a part invokes `name`, through npx/npm-exec wrappers too. */
function invokes(w, name) {
  for (let i = 0; i < w.length; i += 1) {
    const t = w[i]
    if (t === name || t.endsWith(`/${name}`)) return i
    // npx wrangler, npx --yes wrangler@4, pnpm dlx wrangler
    if (t.startsWith(`${name}@`)) return i
  }
  return -1
}

const DENIALS = [
  {
    why: 'wrangler against the real database. The production D1 id is in '
      + 'wrangler.toml and wrangler\'s credentials are in $HOME, so this '
      + 'would reach the live collection. Use --local.',
    hit: (w) => {
      if (invokes(w, 'wrangler') === -1) return false
      if (!w.includes('d1')) return false
      if (w.includes('--local')) return false
      // Anything remote, and anything destructive that has no local form.
      return w.includes('--remote') || w.includes('delete') || w.includes('drop')
    },
  },
  {
    why: 'deploying. A builder\'s job ends at an open pull request; '
      + 'merging is what deploys, and that is the dispatcher\'s decision.',
    hit: (w) => invokes(w, 'wrangler') !== -1 && w.includes('deploy'),
  },
  {
    why: 'deploying through npm. `npm run deploy` is `wrangler deploy`.',
    hit: (w) => {
      const runner = w.findIndex((t) => ['npm', 'yarn', 'pnpm'].includes(t))
      if (runner === -1) return false
      const rest = w.slice(runner + 1).filter((t) => !t.startsWith('-') && t !== 'run')
      return rest[0] === 'deploy'
    },
  },
  {
    why: 'pushing to main. Builders push their own request branch and '
      + 'nothing else; main moves when a pull request is merged.',
    hit: (w) => {
      if (invokes(w, 'git') === -1 || !w.includes('push')) return false
      // Only a refspec can land on main. A bare `git push` goes to the
      // current branch, and the dispatcher put the builder on its own.
      return w.slice(w.indexOf('push') + 1)
        .filter((t) => !t.startsWith('-'))
        .some((t) => /(^|[:/+])(main|master)$/.test(t))
    },
  },
  {
    why: '--admin on a merge. It bypasses every required check, and the '
      + 'ruleset gives this token the power to do it. Green CI is the gate.',
    hit: (w) => w.includes('--admin') && (invokes(w, 'gh') !== -1 || invokes(w, 'git') !== -1),
  },
  {
    why: 'merging through the API, which skips the check gate the same way '
      + '--admin does.',
    hit: (w) => invokes(w, 'gh') !== -1 && w.includes('api')
      && w.some((t) => /\/merge$/.test(t)),
  },
]

/**
 * Whether a `git -C <path>` points outside the worktree.
 *
 * A builder with the full Bash tool can operate on Matt's live checkout,
 * or on another builder's slot, by naming it. Everything it is allowed to
 * touch is inside its own tree.
 */
function escapesWorktree(w, cwd) {
  const i = w.indexOf('-C')
  if (i === -1 || invokes(w, 'git') === -1) return false
  const target = w[i + 1]
  if (!target) return false
  if (!target.startsWith('/') && !target.startsWith('~')) return false
  const root = String(cwd ?? '')
  if (!root) return true
  return !(target === root || target.startsWith(`${root}/`))
}

/**
 * `{ deny, reason }` for one Bash command.
 *
 * Every part of a compound command is judged, because a rule that reads
 * only the start of the line lets `npm test && npm run deploy` through.
 */
export function denied(command, { cwd } = {}) {
  for (const part of parts(command)) {
    const w = words(part)
    if (w.length === 0) continue
    for (const rule of DENIALS) {
      if (rule.hit(w)) {
        return { deny: true, reason: `Refused: ${rule.why}\n  in: ${part}` }
      }
    }
    if (escapesWorktree(w, cwd)) {
      return {
        deny: true,
        reason: 'Refused: `git -C` pointing outside this worktree. Everything '
          + `you may touch is under ${cwd}.\n  in: ${part}`,
      }
    }
  }
  return { deny: false, reason: '' }
}
