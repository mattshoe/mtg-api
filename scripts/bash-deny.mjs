// What a builder is not allowed to run. The rules, with no IO.
//
// THIS IS MITIGATION, NOT A CONTROL. Say it plainly, because two rounds of
// this file have been described as though it were a control and it is not.
//
// A builder runs as the user. `~/.mtg-api.env` exists, mode 600, holding
// `CLOUDFLARE_API_TOKEN`, `CLOUDFLARE_ACCOUNT_ID` and
// `MTG_ADMIN_PASSWORD` — and inside the environment scrub a reviewer ran
// `set -a; . "$HOME/.mtg-api.env"; set +a; npx wrangler whoami` and got
// the real account id back. Worse, a builder can write a script with the
// Write tool and run it, so the hook only ever sees `bash x.sh` and every
// rule here is bypassed at once. That one is not closable by pattern,
// which is why `bash <file>` is refused outright rather than inspected.
//
// Real isolation means a separate uid that cannot read that file and holds
// no Cloudflare credentials. That is the user's decision to make and is
// not something this file can substitute for.
//
// What this does do: `scripts/intake/dispatch.sh` launches every agent
// through `env -u` with an EXPLICIT list of the Cloudflare credential
// variables, points `XDG_CONFIG_HOME` at an empty directory so a stored
// wrangler login cannot be found either, and turns the Keychain backend
// off. A named list and not a pattern, which means a new spelling in a
// future wrangler release has to be added there by hand — the trade is
// that the list is readable and cannot sweep up something a builder needs.
// The rules below close the spellings a model would reach for. Two rounds
// of attack have found 18 and then 21 bypasses, so assume there are more.
//
// Split from the hook because the suite runs inside workerd, which has no
// `node:fs` — the same shape as `suite-floor.mjs`.

import { resolve } from 'node:path'

/** The credential file, and the production database id. */
const SECRET_FILE = /\.mtg-api\.env\b/
const D1_ID = 'b7053e24-b783-46fe-9dc6-32772d42e274'

/**
 * Arguments that are TEXT, not commands, blanked before any rule reads
 * the line.
 *
 * `git commit -m "push to main is blocked"` and
 * `npm run test:web -- -t "deploy banner"` were both refused, and both are
 * things a builder on this very branch would type. A rule that fires on a
 * commit message trains people to work around the rules.
 */
function blankTextArgs(command) {
  let out = String(command ?? '')
  // --flag="..." / --flag='...' / --flag=word
  out = out.replace(
    /(--(?:message|grep|author|fixup|squash|tests|filter|reporter)=)("[^"]*"|'[^']*'|\S+)/g,
    '$1TEXT',
  )
  // -m "..." / -t '...' / --grep word / -F file
  out = out.replace(
    /((?:^|\s)(?:-m|-t|-F|-S|-G|--message|--grep|--tests|--filter|--author)\s+)("[^"]*"|'[^']*'|\S+)/g,
    '$1TEXT',
  )
  return out
}

/**
 * One command, with nothing left to hide behind.
 *
 * Quotes, backslashes and `#` comments all go, so `dep""loy`, `w\rangler`,
 * `HEAD:"main"` and a command smuggled inside `sh -c "…"` read as what
 * they will actually run. It is deliberately not a shell parser: it cannot
 * be tricked into reading LESS than what runs, only more, and a rule that
 * fires on more text is the failure mode to want.
 */
function flatten(command) {
  return String(command ?? '')
    .replace(/\\(["'`])/g, '$1')
    .replace(/\\/g, '')
    .replace(/["'`]/g, '')
    .replace(/#.*$/gm, '')
}

/** The pieces of a command line that run independently of each other. */
function parts(text) {
  return text
    .split(/(?:\|\||&&|[;\n|&])/)
    .map((p) => p.replace(/^[\s(]+|[\s)]+$/g, '').trim())
    .filter(Boolean)
}

/** Whether a path, resolved for real, is inside the worktree. */
function inside(target, cwd) {
  if (!target || !cwd) return false
  const home = process.env.HOME ?? ''
  const expanded = target.startsWith('~') && home ? home + target.slice(1) : target
  const full = resolve(cwd, expanded)
  return full === cwd || full.startsWith(`${cwd}/`)
}

/** What a builder may legitimately ask wrangler to do, and nothing else. */
function wranglerAllowed(p) {
  if (/(^|\s)--version(\s|$)/.test(p) || /(^|\s)(whoami|--help|-h)(\s|$)/.test(p)) return true
  if (/(^|\s)dev(\s|$)/.test(p)) return true
  // `d1 execute --local` only, and `--local=false`/`--local false` are not
  // `--local`. That spelling satisfied the old requirement while still
  // routing remote.
  if (/(^|\s)d1(\s|$)/.test(p) && /(^|\s)execute(\s|$)/.test(p)) {
    if (/--local(\s|$)/.test(p) && !/--local[= ]+(false|0|no)(\s|$)/.test(p)) return true
  }
  return false
}

const DENIALS = [
  {
    why: 'the credential file. `~/.mtg-api.env` holds the production '
      + 'Cloudflare token and the admin password. Nothing a builder does '
      + 'needs it, and reading it defeats the environment scrub entirely.',
    hit: (p) => SECRET_FILE.test(p),
  },
  {
    why: 'dumping the environment. If a token is in there, this is how it '
      + 'leaves; if it is not, there is nothing here worth reading.',
    hit: (p) => /(^|\s)(env|printenv)(\s|$)/.test(p)
      && !/(^|\s)env\s+-[iu0-9]/.test(p),
  },
  {
    why: 'reaching Cloudflare over HTTP. The D1 id is in wrangler.toml, so '
      + 'a plain curl is the same action as a remote wrangler call with '
      + 'extra steps.',
    hit: (p) => (/\b(curl|wget|nc|httpie|http)\b/.test(p)
      && (/api\.cloudflare\.com/.test(p) || p.includes(D1_ID)))
      || p.includes(D1_ID),
  },
  {
    why: 'running a script file. The hook sees only the filename, so a '
      + 'script written a moment earlier with the Write tool bypasses every '
      + 'rule at once. This is the hole that cannot be closed by inspecting '
      + 'text, so the shape is refused instead. Run the commands directly.',
    hit: (p) => /(^|\s)(ba|z|k|da)?sh\s+[^-\s]\S*/.test(p)
      || /(^|[\s;])\.?\.?\/\S+\.(sh|bash|zsh|command)(\s|$)/.test(p)
      // `source` anywhere, but the `.` builtin only at the START of a part
      // — `git -C . status` has a ` . ` in the middle of it and was refused.
      || /(^|\s)source\s+\S+/.test(p)
      || /^\.\s+\S+/.test(p),
  },
  {
    why: 'shell indirection. `eval`, `sh -c` and `bash -c` hide what is '
      + 'about to run from anything that reads the command.',
    hit: (p) => /(^|[\s(])eval([\s(]|$)/.test(p)
      || /(^|[\s/])(ba|z|k|da)?sh\s+(-[a-z]*\s+)*-[a-z]*c([\s]|$)/.test(p),
  },
  {
    why: 'another interpreter, or make. Each one is a way to run arbitrary '
      + 'code that this file cannot read.',
    hit: (p) => /(^|\s)make(\s|$)/.test(p)
      || /(^|\s)(python3?|perl|ruby|osascript)\s+(-[a-zA-Z]*\s*)*-(c|e)(\s|$)/.test(p)
      || /(^|\s)node\s+(-[a-zA-Z]*\s*)*(-e|--eval)(\s|$)/.test(p),
  },
  {
    why: 'command substitution or a variable standing in for a program. '
      + 'Both defeat every rule that reads the command, and nothing a '
      + 'builder needs requires either in a single Bash call.',
    hit: (p) => /\$\(|\$\{/.test(p)
      || /\b[A-Za-z_][A-Za-z0-9_]*=(wrangler|gh|git|npm|npx|make|bash|sh)\b/.test(p)
      || /\$[A-Za-z_][A-Za-z0-9_]*\s+(deploy|d1|merge|push|execute)\b/.test(p),
  },
  {
    why: 'a git alias, which stores a command for later and so is never '
      + 'seen again by anything that checks commands.',
    hit: (p) => /\bgit\b/.test(p) && /\bconfig\b/.test(p) && /\balias\./.test(p),
  },
  {
    why: 'wrangler doing something other than `dev`, `d1 execute --local` '
      + 'or `--version`. The allowlist runs the other way round on purpose: '
      + 'a subcommand nobody thought of is refused rather than permitted, '
      + 'because `delete`, `secret put`, `r2` and `kv` each had no rule at '
      + 'all and each reaches production.',
    hit: (p) => /\bwrangler\b/.test(p) && !wranglerAllowed(p),
  },
  {
    why: 'deploying. Merging is what deploys, through `pages.yml`, '
      + '`release.yml` and `worker.yml`, and a builder has no Cloudflare '
      + 'credentials to deploy with anyway.',
    hit: (p) => /\b(npm|yarn|pnpm|npx)\b/.test(p)
      && /(^|\s)(run\s+)?deploy(\s|$)/.test(p),
  },
  {
    why: 'pushing to main or master. Builders push their own request branch '
      + 'and nothing else; main moves when a pull request is merged.',
    hit: (p) => {
      if (!/\bgit\b/.test(p) || !/\bpush\b/.test(p)) return false
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
    hit: (p) => /\bgit\b/.test(p) && /\bpush\b/.test(p)
      && /(--force(\s|$)|(^|\s)-f(\s|$))/.test(p)
      && !/--force-with-lease/.test(p),
  },
]

/**
 * Whether a part operates on something outside the worktree.
 *
 * `-C`, `--git-dir`, `--work-tree`, `--prefix`, and a `cd` or `pushd`
 * ANYWHERE in the part — not only at the start, which is how
 * `( cd /real/repo && git reset --hard )` walked out.
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

  for (const m of p.matchAll(/(?:^|\s)(?:cd|pushd)\s+(\S+)/g)) {
    if (m[1] === '-' || m[1] === 'TEXT') continue
    if (!inside(m[1], cwd)) return true
  }
  return false
}

/**
 * `{ deny, reason }` for one Bash command.
 *
 * Every part of a compound command is judged, because a rule that reads
 * only the start of the line lets `npm test && npm run deploy` through.
 */
export function denied(command, { cwd } = {}) {
  const flat = flatten(blankTextArgs(command))
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
        reason: 'Refused: naming a directory outside this worktree. '
          + `Everything you may touch is under ${cwd}.\n  in: ${p}`,
      }
    }
  }
  return { deny: false, reason: '' }
}
