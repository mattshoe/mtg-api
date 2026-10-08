// Black-box tests for the intake shell scripts.
//
// `scripts/intake.mjs` holds the decisions and `test/intake-dispatch.test.js`
// holds them to account. But nine of the criticals in the intake audit were
// bugs in the bash around it — a lock published before its pid file, a
// worktree deleted out from under a live builder, `gh pr list --state open`
// that cannot see a merged pull request — and no unit test could have caught
// any of them, because none of them live in a function.
//
// So this drives the real scripts. Each test builds a throwaway git repo in a
// temp dir with a fake `requests/`, a bare `origin` to push to, and stubs for
// `claude`, `gh` and `osascript` earlier on `PATH` that record their argv to a
// file. Nothing here touches the real repo, the real GitHub, or the real
// `claude`.
//
// These run in node, not workerd — see `vitest.shell.config.js`. The worker
// suite's config excludes this file, so its floor is unaffected.

import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import { spawnSync, spawn } from 'node:child_process'
import {
  mkdtempSync, mkdirSync, writeFileSync, readFileSync, rmSync, existsSync,
  cpSync, chmodSync, readdirSync, utimesSync,
} from 'node:fs'
import { join, dirname } from 'node:path'
import { tmpdir } from 'node:os'
import { fileURLToPath } from 'node:url'

const REPO = dirname(dirname(fileURLToPath(import.meta.url)))

/** Files a fake repo needs before any intake script will work in it. */
const COPY = [
  'scripts/intake.mjs',
  'scripts/intake/dispatch.sh',
  'scripts/intake/hook.sh',
  'scripts/intake/status.sh',
  'scripts/intake/install.sh',
  'scripts/intake/uninstall.sh',
  'scripts/intake/com.matt.mtg.intake.plist',
  'scripts/guard.mjs',
  'scripts/check-test-count.mjs',
  'scripts/suite-floor.mjs',
  'test/suite-floors.json',
  'CLAUDE.md',
  '.claude/skills/mtg/SKILL.md',
  '.claude/agents/request-builder.md',
  // Everything `equipped` requires has to be here, or the guard passes in
  // every test for the wrong reason. The Part D files were the ones
  // missing: a worktree without them runs bypassPermissions with the deny
  // hook silently absent, and no test noticed.
  '.claude/agents/request-triage.md',
  '.claude/settings.json',
  'scripts/bash-deny.mjs',
  'scripts/intake/deny-bash.mjs',
  // Not required, but `prepare_slot`'s npm-stamp branch is dead code
  // without them — which is why the stamp living inside the worktree,
  // four lines after a `git clean` that deleted it, went unnoticed.
  'package.json',
  'package-lock.json',
]

const READY = (title) => `---
status: ready
merge: auto
---

# ${title}

## Plan

Edit the thing.

## Tests

A test for the thing.

## Done when

The thing is done.
`

let box

/**
 * A throwaway repo with the real scripts in it.
 *
 * `dispatch.sh` and `hook.sh` both find their repo from `BASH_SOURCE`, so
 * copying them into this tree is enough to make them operate on it.
 */
function build({ requests = {}, done = {} } = {}) {
  const root = mkdtempSync(join(tmpdir(), 'intake-shell-'))
  const repo = join(root, 'repo')
  const origin = join(root, 'origin.git')
  const bin = join(root, 'bin')
  mkdirSync(repo, { recursive: true })
  mkdirSync(bin, { recursive: true })

  const git = (...args) => spawnSync('git', ['-C', repo, ...args], {
    encoding: 'utf8',
    env: {
      ...process.env,
      GIT_AUTHOR_NAME: 't', GIT_AUTHOR_EMAIL: 't@t',
      GIT_COMMITTER_NAME: 't', GIT_COMMITTER_EMAIL: 't@t',
    },
  })

  spawnSync('git', ['init', '--bare', '-b', 'main', origin], { encoding: 'utf8' })
  spawnSync('git', ['init', '-b', 'main', repo], { encoding: 'utf8' })
  git('config', 'user.name', 't')
  git('config', 'user.email', 't@t')
  git('config', 'commit.gpgsign', 'false')

  for (const rel of COPY) {
    const from = join(REPO, rel)
    if (!existsSync(from)) continue
    mkdirSync(join(repo, dirname(rel)), { recursive: true })
    cpSync(from, join(repo, rel))
    if (rel.endsWith('.sh')) chmodSync(join(repo, rel), 0o755)
  }
  writeFileSync(join(repo, '.gitignore'), '.intake/\nnode_modules/\n')
  mkdirSync(join(repo, 'requests', 'done'), { recursive: true })
  writeFileSync(join(repo, 'requests', 'README.md'), '# requests\n')
  for (const [name, text] of Object.entries(requests)) {
    writeFileSync(join(repo, 'requests', name), text)
  }
  for (const [name, text] of Object.entries(done)) {
    writeFileSync(join(repo, 'requests', 'done', name), text)
  }

  git('add', '-A')
  git('commit', '-qm', 'base')
  git('remote', 'add', 'origin', origin)
  git('push', '-q', 'origin', 'main')
  git('fetch', '-q', 'origin')

  mkdirSync(join(repo, '.intake'), { recursive: true })

  const calls = join(root, 'calls.log')
  writeFileSync(calls, '')

  // Stubs. Each records its whole argv on one line so a test can assert on
  // what the script decided to run without anything actually running.
  const stub = (name, body) => {
    const p = join(bin, name)
    // A builder prompt is many lines long. Recorded raw it would span
    // lines in the log and no assertion could find the whole argv.
    writeFileSync(p, `#!/bin/bash\nprintf '%s %s\\n' ${JSON.stringify(name)} "$(printf '%s ' "$@" | tr '\\n' ' ')" >> ${JSON.stringify(calls)}\n${body}\n`)
    chmodSync(p, 0o755)
  }
  stub('claude', 'exit 0')
  stub('gh', 'exit 0')
  stub('osascript', 'exit 0')

  box = {
    root, repo, origin, bin, calls, git, stub,
    path: (...p) => join(repo, ...p),
    /** Everything the stubs were asked to do. */
    log: () => readFileSync(calls, 'utf8'),
    /** The dispatcher's own log. */
    intakeLog: () => (existsSync(join(repo, '.intake/intake.log'))
      ? readFileSync(join(repo, '.intake/intake.log'), 'utf8') : ''),
    disable: () => writeFileSync(join(repo, '.intake/disabled'), ''),
  }
  return box
}

/** Run one of the intake scripts in the fake repo. */
function run(script, { env = {}, stdin = '', timeout = 60_000 } = {}) {
  return spawnSync('bash', [box.path('scripts/intake', script)], {
    encoding: 'utf8',
    input: stdin,
    timeout,
    cwd: box.repo,
    env: {
      ...process.env,
      PATH: `${box.bin}:${process.env.PATH}`,
      INTAKE_DEBOUNCE: '0',
      INTAKE_SETTLE: '0',
      // The fake repo has a real package.json so the stamp logic is
      // exercised, but `npm ci` in a temp dir with no network is not the
      // thing under test.
      INTAKE_SKIP_NPM: '1',
      HOME: box.root,
      ...env,
    },
  })
}

afterEach(() => {
  if (!box) return
  // Any worktree the dispatcher made has to go before the temp dir does,
  // or its admin entry outlives the test.
  spawnSync('git', ['-C', box.repo, 'worktree', 'prune'], { encoding: 'utf8' })
  try { rmSync(box.root, { recursive: true, force: true }) } catch { /* gone */ }
  box = null
})

describe('the kill switch', () => {
  // There was no way to stop this machine. `launchctl unload` leaves the
  // committed PostToolUse hook in place, so editing any requests/*.md from
  // any Claude session in the repo still spawned a bypassPermissions builder
  // that would open and merge a pull request. The only off switch was
  // hand-editing a committed file.

  beforeEach(() => {
    build({ requests: { 'a-thing.md': READY('A thing') } })
  })

  it('stops hook.sh dead, so an edit launches no dispatcher', () => {
    box.disable()
    // Stand in for the dispatcher, so "it launched one" is observable.
    writeFileSync(box.path('scripts/intake/dispatch.sh'),
      `#!/bin/bash\nprintf 'DISPATCHED\\n' >> ${JSON.stringify(box.calls)}\n`)
    chmodSync(box.path('scripts/intake/dispatch.sh'), 0o755)

    const r = run('hook.sh', {
      stdin: JSON.stringify({ tool_input: { file_path: box.path('requests/a-thing.md') } }),
    })
    expect(r.status).toBe(0)
    expect(box.log()).not.toContain('DISPATCHED')
  })

  it('stops dispatch.sh dead, so no triage and no builder run', () => {
    box.disable()
    const r = run('dispatch.sh')
    expect(r.status).toBe(0)
    expect(box.log()).not.toContain('claude')
    expect(box.intakeLog()).toBe('')
  })

  it('is what uninstall.sh leaves behind', () => {
    box.stub('launchctl', 'exit 0')
    const r = spawnSync('bash', [box.path('scripts/intake/uninstall.sh')], {
      encoding: 'utf8',
      cwd: box.repo,
      env: { ...process.env, PATH: `${box.bin}:${process.env.PATH}`, HOME: box.root },
    })
    expect(r.status).toBe(0)
    expect(existsSync(box.path('.intake/disabled'))).toBe(true)
    expect(box.log()).toContain('launchctl bootout')
  })

  it('is the only thing holding it back — without it, both go', () => {
    writeFileSync(box.path('scripts/intake/dispatch.sh'),
      `#!/bin/bash\nprintf 'DISPATCHED\\n' >> ${JSON.stringify(box.calls)}\n`)
    chmodSync(box.path('scripts/intake/dispatch.sh'), 0o755)
    run('hook.sh', {
      stdin: JSON.stringify({ tool_input: { file_path: box.path('requests/a-thing.md') } }),
    })
    // nohup backgrounds it, so give it a moment to land.
    const deadline = Date.now() + 5000
    while (Date.now() < deadline && !box.log().includes('DISPATCHED')) { /* spin */ }
    expect(box.log()).toContain('DISPATCHED')
  })
})

// ---------------------------------------------------------------------
// The dispatcher.
//
// Everything below drives `dispatch.sh` end to end with a stub `claude`
// that commits and pushes like a builder would, and a stub `gh` that can
// be told what GitHub says. The assertions are filesystem facts and the
// argv the stubs recorded, not log prose, wherever a fact was available.
// ---------------------------------------------------------------------

/** A stub `claude` that behaves like a builder: commits, pushes, opens a PR. */
const BUILDER = `
# The real builder is handed its request outside the worktree and works in
# whatever cwd the dispatcher put it in.
branch="$(git rev-parse --abbrev-ref HEAD)"
echo "x" >> built.txt
git add -A
git -c user.name=b -c user.email=b@b commit -qm "build: $branch"
git push -q origin "HEAD:refs/heads/$branch"
exit 0
`

/** A stub `claude` that does what three of four real builders did: nothing. */
const BUILDER_STALLS = `
echo "half a change" >> half.txt
exit 0
`

/** `gh` told what GitHub says. */
function ghStub({ pr = 7, state = 'OPEN', checks = 'all-pass' } = {}) {
  return `
case "$1 $2" in
  "pr list")
    [ "${state}" = "NONE" ] || printf '${pr} ${state}\\n' ;;
  "run list") printf '101\\n' ;;
  "run watch") exit 0 ;;
  "pr checks")
    case "${checks}" in
      all-pass) printf 'shared\\tpass\\t1m\\tu\\nweb\\tpass\\t1m\\tu\\nandroid\\tpass\\t1m\\tu\\ntally\\tpass\\t1m\\tu\\n'; exit 0 ;;
      one-red) printf 'shared\\tpass\\t1m\\tu\\ntally\\tfail\\t1m\\tu\\n'; exit 1 ;;
      pending) printf 'shared\\tpass\\t1m\\tu\\ntally\\tpending\\t1m\\tu\\n'; exit 8 ;;
      none) exit 0 ;;
    esac ;;
  "pr merge") exit 0 ;;
esac
exit 0
`
}

/** The dispatcher's log, which is the only record of what it decided. */
const logged = (s) => expect(box.intakeLog()).toContain(s)

describe('the lock', () => {
  // The lock directory was published nine lines before its pid file, so
  // in the normal hook+launchd double-fire the second dispatcher saw a
  // pidless lock, declared it stale, deleted it, and proceeded. Two
  // dispatchers then triaged and built the same requests and `rm -rf`d
  // under each other.

  beforeEach(() => {
    build({ requests: { 'a-thing.md': READY('A thing') } })
    box.stub('claude', BUILDER)
    box.stub('gh', ghStub())
  })

  it('lets one dispatcher through and turns the second away', () => {
    // A pid whose argv actually reads as a dispatcher. `kill -0` alone is
    // not enough — the kernel reuses pids, and liveness by itself wedged
    // the queue permanently after a reboot.
    const stand = join(box.root, 'dispatch.sh')
    writeFileSync(stand, '#!/bin/bash\nsleep 45\n')
    chmodSync(stand, 0o755)
    const held = spawn('bash', [stand], { detached: true, stdio: 'ignore' })
    held.unref()
    mkdirSync(box.path('.intake/dispatch.lock'))
    writeFileSync(box.path('.intake/dispatch.lock/pid'), String(held.pid))
    writeFileSync(box.path('.intake/dispatch.lock/started'), String(Math.floor(Date.now() / 1000)))
    // What `ps` will actually report, because the reused-pid check now
    // compares against the recorded string rather than a loose glob.
    writeFileSync(box.path('.intake/dispatch.lock/command'),
      spawnSync('ps', ['-o', 'command=', '-p', String(held.pid)], { encoding: 'utf8' }).stdout.trim())

    const r = run('dispatch.sh')
    held.kill('SIGKILL')
    expect(r.status).toBe(0)
    expect(box.log()).not.toContain('claude')
    expect(existsSync(box.path('.intake/dispatch.lock'))).toBe(true)
  })

  it('treats a lock with no pid file as LIVE, never as stale', () => {
    // This is the double-fire window. The second dispatcher used to read
    // it as abandoned.
    mkdirSync(box.path('.intake/dispatch.lock'))

    const r = run('dispatch.sh')
    expect(r.status).toBe(0)
    expect(box.log()).not.toContain('claude')
    expect(existsSync(box.path('.intake/dispatch.lock'))).toBe(true)
    logged('no pid yet')
  })

  it('clears a lock whose pid is gone, so a kill -9 does not wedge it forever', () => {
    mkdirSync(box.path('.intake/dispatch.lock'))
    writeFileSync(box.path('.intake/dispatch.lock/pid'), '999999')
    writeFileSync(box.path('.intake/dispatch.lock/started'), String(Math.floor(Date.now() / 1000)))
    writeFileSync(box.path('.intake/dispatch.lock/command'), 'bash scripts/intake/dispatch.sh')

    run('dispatch.sh')
    expect(box.log()).toContain('claude')
  })

  it('does not let a pidless lock wedge intake forever', () => {
    // The `[ -z "$pid" ]` branch returned LIVE before any age check, so a
    // kill -9 inside the `mkdir`→`stamp_claim` window wedged the queue
    // permanently and silently: five consecutive runs, zero builders, and
    // `already running as , leaving it to that one` with an empty pid.
    mkdirSync(box.path('.intake/dispatch.lock'))
    // Backdate the directory so it is older than the window.
    const old = new Date(Date.now() - 600_000)
    utimesSync(box.path('.intake/dispatch.lock'), old, old)

    run('dispatch.sh', { env: { INTAKE_PIDLESS_SECONDS: '5' } })
    logged('has had no pid for')
    expect(box.log()).toContain('claude')
  })

  it('never steals the lock from a dispatcher that is simply slow', () => {
    // The age cap was applied to `$LOCK` as well as to claims, so at
    // MAX_MINUTES a LIVE dispatcher's lock was taken — `kill -0`
    // succeeding and `ps` matching did not save it, and it was never
    // signalled, so its own `drop_lock` no-opped and it never found out.
    // Two dispatchers then raced on the same pull request, which is the
    // original double-dispatch critical.
    const stand = join(box.root, 'dispatch.sh')
    writeFileSync(stand, '#!/bin/bash\nsleep 45\n')
    chmodSync(stand, 0o755)
    const held = spawn('bash', [stand], { detached: true, stdio: 'ignore' })
    held.unref()
    mkdirSync(box.path('.intake/dispatch.lock'))
    writeFileSync(box.path('.intake/dispatch.lock/pid'), String(held.pid))
    writeFileSync(box.path('.intake/dispatch.lock/started'), '1')
    writeFileSync(box.path('.intake/dispatch.lock/command'),
      spawnSync('ps', ['-o', 'command=', '-p', String(held.pid)], { encoding: 'utf8' }).stdout.trim())

    const r = run('dispatch.sh', { env: { INTAKE_MAX_MINUTES: '0' } })
    held.kill('SIGKILL')
    expect(r.status).toBe(0)
    expect(box.log()).not.toContain('claude')
    expect(existsSync(box.path('.intake/dispatch.lock'))).toBe(true)
  })

  it('clears a lock whose pid was reused by something that is not a dispatcher', () => {
    // `kill -0` on a reused pid succeeds, so liveness alone wedged the
    // queue permanently after a reboot.
    mkdirSync(box.path('.intake/dispatch.lock'))
    writeFileSync(box.path('.intake/dispatch.lock/pid'), String(process.pid))
    writeFileSync(box.path('.intake/dispatch.lock/started'), String(Math.floor(Date.now() / 1000)))
    writeFileSync(box.path('.intake/dispatch.lock/command'), 'node some-other-thing.js')

    run('dispatch.sh')
    expect(box.log()).toContain('claude')
  })

  it('clears a lock whose pid is alive but is not the process it recorded', () => {
    // This test used to be called "clears a lock older than the cap even
    // if its pid is alive", which is the exact behaviour R5 was written to
    // FORBID — its name documented the bug. It stayed green only by
    // accident: `process.pid`'s real argv is the vitest node process, not
    // the `bash dispatch.sh` written into `command`, so the recorded-command
    // mismatch cleared the lock and the age cap never came into it. A
    // revert of R5 would have left it green.
    mkdirSync(box.path('.intake/dispatch.lock'))
    writeFileSync(box.path('.intake/dispatch.lock/pid'), String(process.pid))
    writeFileSync(box.path('.intake/dispatch.lock/started'), String(Math.floor(Date.now() / 1000)))
    writeFileSync(box.path('.intake/dispatch.lock/command'), 'bash scripts/intake/dispatch.sh')

    run('dispatch.sh')
    logged('not the')
    expect(box.log()).toContain('claude')
  })

  it('is released before the wave waits, not after every builder is done', () => {
    // The old test only checked the lock was gone AFTERWARDS, which the
    // previous code also did via its EXIT trap — so it could not fail.
    // What matters is WHEN: the dispatcher now owns the CI wait, so a
    // wave is a builder plus 13-17 minutes of `apps`, and holding the
    // lock through all of it left A15's dropped-event bug fully intact.
    // A builder that blocks until a file appears lets us look mid-wave.
    const gate = join(box.root, 'let-the-builder-finish')
    box.stub('claude', `
branch="$(git rev-parse --abbrev-ref HEAD)"
echo x >> built.txt
git add -A
git -c user.name=b -c user.email=b@b commit -qm "build: $branch"
git push -q origin "HEAD:refs/heads/$branch"
while [ ! -f ${JSON.stringify(gate)} ]; do sleep 0.2; done
exit 0
`)
    box.stub('gh', ghStub())
    const opener = spawn('bash', ['-c',
      `for i in $(seq 1 100); do [ -f ${JSON.stringify(box.path('.intake/intake.log'))} ] && grep -q 'lock released' ${JSON.stringify(box.path('.intake/intake.log'))} && break; sleep 0.3; done; touch ${JSON.stringify(gate)}`],
      { detached: true, stdio: 'ignore' })
    opener.unref()
    const r = run('dispatch.sh', { timeout: 120_000 })
    expect(r.status).toBe(0)
    logged('lock released')
  })

  it('is only ever removed by the dispatcher that owns it', () => {
    // `drop_lock` compares the pid before removing, so a dispatcher that
    // released early cannot take a successor's lock. Asserted directly
    // rather than inferred from the lock being absent at the end.
    mkdirSync(box.path('.intake/dispatch.lock'))
    writeFileSync(box.path('.intake/dispatch.lock/pid'), '999999')
    writeFileSync(box.path('.intake/dispatch.lock/command'), 'bash scripts/intake/dispatch.sh')
    const r = spawnSync('bash', ['-c',
      `LOCK=${JSON.stringify(box.path('.intake/dispatch.lock'))}
       LOG=/dev/null
       . <(sed -n '/^lock_pid()/,/^}/p;/^drop_lock()/,/^}/p' ${JSON.stringify(box.path('scripts/intake/dispatch.sh'))})
       drop_lock
       [ -d "$LOCK" ] && echo KEPT || echo REMOVED`],
      { encoding: 'utf8' })
    expect(r.stdout).toContain('KEPT')
  })
})

describe('refusing to run from a linked worktree', () => {
  // The PostToolUse hook is committed, so it is checked out in every
  // builder worktree. The builder is told to edit its own request file,
  // which fires the hook, which starts a SECOND dispatcher rooted at the
  // worktree — its own lock, its own `.intake/`, its own MAX_BUILDERS, so
  // the global cap is gone — and that dispatcher runs a triage agent
  // inside the live builder's tree, rewriting files it is about to
  // commit, then launches nested builders. Recursively.

  beforeEach(() => {
    build({ requests: { 'a-thing.md': READY('A thing') } })
    box.stub('claude', BUILDER)
    box.stub('gh', ghStub())
  })

  function linkedWorktree() {
    const wt = join(box.root, 'linked')
    box.git('worktree', 'add', '-q', '-b', 'linked', wt, 'main')
    mkdirSync(join(wt, '.intake'), { recursive: true })
    mkdirSync(join(wt, 'requests'), { recursive: true })
    writeFileSync(join(wt, 'requests', 'a-thing.md'), READY('A thing'))
    return wt
  }

  it('stops dispatch.sh dead', () => {
    const wt = linkedWorktree()
    const r = spawnSync('bash', [join(wt, 'scripts/intake/dispatch.sh')], {
      encoding: 'utf8',
      cwd: wt,
      timeout: 60_000,
      env: {
        ...process.env,
        PATH: `${box.bin}:${process.env.PATH}`,
        INTAKE_DEBOUNCE: '0', INTAKE_SETTLE: '0', HOME: box.root,
      },
    })
    expect(r.status).toBe(0)
    expect(box.log()).not.toContain('claude')
  })

  it('stops hook.sh dead, so a builder editing a file cannot spawn a dispatcher', () => {
    const wt = linkedWorktree()
    writeFileSync(join(wt, 'scripts/intake/dispatch.sh'),
      `#!/bin/bash\nprintf 'NESTED\\n' >> ${JSON.stringify(box.calls)}\n`)
    chmodSync(join(wt, 'scripts/intake/dispatch.sh'), 0o755)
    const r = spawnSync('bash', [join(wt, 'scripts/intake/hook.sh')], {
      encoding: 'utf8',
      cwd: wt,
      timeout: 60_000,
      input: JSON.stringify({ tool_input: { file_path: join(wt, 'requests/a-thing.md') } }),
      env: { ...process.env, PATH: `${box.bin}:${process.env.PATH}`, HOME: box.root },
    })
    expect(r.status).toBe(0)
    const deadline = Date.now() + 3000
    while (Date.now() < deadline) { /* give nohup a chance */ }
    expect(box.log()).not.toContain('NESTED')
  })
})

describe('asking intake.mjs', () => {
  // `ask()` discarded the exit status of `node scripts/intake.mjs`, so
  // node missing, a PATH the plist does not carry, a parse error, a wrong
  // branch checked out or ENOSPC all yielded empty stdout — and the
  // dispatcher logged "nothing pending" and exited 0 with a full queue.
  // `.intake/launchd.log` already holds the sibling case. Empty output
  // and failure must never be the same thing anywhere in this system.

  beforeEach(() => {
    build({ requests: { 'a-thing.md': READY('A thing') } })
    box.stub('claude', BUILDER)
    box.stub('gh', ghStub())
  })

  it('fails loudly when node is not there at all', () => {
    box.stub('node', 'exit 127')
    const r = run('dispatch.sh')
    expect(r.status).not.toBe(0)
    logged('refusing to decide')
    expect(box.log()).not.toContain('claude')
  })

  it('fails loudly when intake.mjs itself blows up', () => {
    box.stub('node', 'echo "SyntaxError" >&2; exit 1')
    const r = run('dispatch.sh')
    expect(r.status).not.toBe(0)
    logged('refusing to decide')
  })

  it('does not confuse an empty queue with a broken one', () => {
    // A guard, not a fix: the previous code also logged "nothing
    // pending" here. It is kept because the pair with the two tests above
    // is what makes the distinction meaningful, and it would catch a
    // future `ask` that failed loudly on an empty folder.
    rmSync(box.path('requests/a-thing.md'))
    const r = run('dispatch.sh')
    expect(r.status).toBe(0)
    logged('nothing pending')
    expect(box.intakeLog()).not.toContain('refusing to decide')
  })

  it('fails loudly when gh cannot answer, rather than reading silence as no PR', () => {
    box.stub('gh', 'exit 4')
    run('dispatch.sh')
    logged('gh')
    expect(box.intakeLog()).toContain('failed')
  })
})

describe('a request that gets built', () => {
  beforeEach(() => {
    build({ requests: { 'a-thing.md': READY('A thing') } })
    box.stub('claude', BUILDER)
    box.stub('gh', ghStub())
  })

  it('hands the builder its request from outside requests/', () => {
    // The builder committed the live request file onto the branch it
    // merged, so main permanently carried finished requests and every new
    // worktree was seeded with them — which is the fuel for the recursive
    // hook. `requests/kayla-account-owns-her-cards.md` is on origin/main
    // right now while `requests/done/` holds one file.
    run('dispatch.sh')
    const prompt = box.log().split('\n').find((l) => l.startsWith('claude '))
    expect(prompt).toBeDefined()
    expect(prompt).not.toContain('requests/a-thing.md')
    expect(prompt).toContain('.intake/handed/a-thing.md')
  })

  it('works in a slot outside the repo, where git clean cannot reach it', () => {
    run('dispatch.sh')
    expect(existsSync(box.path('.intake/wt'))).toBe(false)
    logged('slot1')
  })

  it('passes the model explicitly, because the agent frontmatter is inert', () => {
    run('dispatch.sh')
    const prompt = box.log().split('\n').find((l) => l.startsWith('claude '))
    expect(prompt).toContain('--model opus')
  })

  it('does not tell the builder to wait for CI or to merge', () => {
    run('dispatch.sh')
    const prompt = box.log().split('\n').find((l) => l.startsWith('claude '))
    expect(prompt).not.toContain('CI is green')
    expect(prompt).not.toMatch(/\bmerged? it\b/)
    expect(prompt).toContain('Stop when')
  })

  it('files the request under done/ in the real repo and commits it', () => {
    // Not in the worktree. The worktree's copy is on another branch that
    // the real repo never pulls, which is why two finished requests were
    // still listed as buildable hours later.
    run('dispatch.sh')
    expect(existsSync(box.path('requests/a-thing.md'))).toBe(false)
    expect(existsSync(box.path('requests/done/a-thing.md'))).toBe(true)
    const show = box.git('log', '--oneline', '-3', '--', 'requests')
    expect(show.stdout).toContain('a-thing')
    expect(box.git('status', '--porcelain', '--', 'requests').stdout.trim()).toBe('')
  })

  it('blocks on CI itself rather than making an agent sit through it', () => {
    run('dispatch.sh')
    expect(box.log()).toContain('gh run watch')
  })

  it('merges a green merge: auto request, after checking every check', () => {
    run('dispatch.sh')
    expect(box.log()).toContain('gh pr checks')
    expect(box.log()).toContain('pr merge')
  })

  it('never merges with --admin or --delete-branch', () => {
    run('dispatch.sh')
    const merge = box.log().split('\n').find((l) => l.includes('pr merge'))
    expect(merge).not.toContain('--admin')
    expect(merge).not.toContain('--delete-branch')
  })

  it('calls it done only when the pull request merged and the request was filed', () => {
    // The old name promised worktree cleanup, asserted `logged('a-thing
    // done')`, and the code never removes a slot worktree at all — so it
    // was testing a different thing from the one it claimed. Slots are
    // persistent now, on purpose; what has to be true is the verdict.
    run('dispatch.sh')
    logged('done: MERGED')
    expect(existsSync(box.path('requests/done/a-thing.md'))).toBe(true)
    expect(existsSync(box.path('requests/a-thing.md'))).toBe(false)
  })

  it('keeps the slot rather than deleting it, which is what makes it reusable', () => {
    run('dispatch.sh')
    expect(existsSync(join(box.root, '.cache/mtg-intake/wt/slot1/.git'))).toBe(true)
  })
})

describe('a request that says merge: ask', () => {
  beforeEach(() => {
    build({ requests: { 'a-thing.md': READY('A thing').replace('merge: auto', 'merge: ask') } })
    box.stub('claude', BUILDER)
    box.stub('gh', ghStub())
  })

  it('leaves the pull request open and tells Matt', () => {
    // Nothing ever told Matt a request needed him. `merge: ask` ended
    // with a green pull request and silence, and four of five live
    // requests said `merge: ask` — so silence was the NORMAL outcome.
    run('dispatch.sh')
    expect(box.log()).not.toContain('pr merge')
    expect(box.log()).toContain('osascript')
  })
})

describe('a pull request whose checks are not all green', () => {
  beforeEach(() => {
    build({ requests: { 'a-thing.md': READY('A thing') } })
    box.stub('claude', BUILDER)
  })

  it('does not merge with one check red', () => {
    box.stub('gh', ghStub({ checks: 'one-red' }))
    run('dispatch.sh')
    expect(box.log()).not.toContain('pr merge')
  })

  it('does not merge with a check still pending', () => {
    // `concurrency: cancel-in-progress` makes a cancelled `tally` easy to
    // produce, and `gh pr merge` inspects no checks at all.
    box.stub('gh', ghStub({ checks: 'pending' }))
    run('dispatch.sh')
    expect(box.log()).not.toContain('pr merge')
  })

  it('does not merge a pull request with no checks at all', () => {
    // This state reads as benign and is exactly what a hundred commits
    // sat in.
    box.stub('gh', ghStub({ checks: 'none' }))
    run('dispatch.sh')
    expect(box.log()).not.toContain('pr merge')
    logged('no checks')
  })
})

describe('a builder that did not finish', () => {
  beforeEach(() => {
    build({ requests: { 'a-thing.md': READY('A thing') } })
    box.stub('claude', BUILDER_STALLS)
    box.stub('gh', ghStub({ state: 'NONE' }))
  })

  it('is judged unfinished, and the request stays in the live folder', () => {
    run('dispatch.sh')
    logged('NOT FINISHED')
    expect(existsSync(box.path('requests/a-thing.md'))).toBe(true)
  })

  it('has its work salvaged to a commit and pushed before anything is removed', () => {
    // `.intake/wt/card-page-shows-everything` held ~32 modified files,
    // zero commits and nothing pushed, and the next dispatch `rm -rf`d
    // it — so the "nothing was thrown away" guard only delayed the loss
    // by one event. A `git clean -xfd` in the primary tree would have
    // taken it too, because `.intake/` is gitignored.
    run('dispatch.sh')
    const branch = spawnSync('node', ['scripts/intake.mjs', 'branch', 'a-thing.md'],
      { encoding: 'utf8', cwd: box.repo }).stdout.trim()
    const onOrigin = spawnSync('git', ['-C', box.origin, 'log', '--oneline', branch],
      { encoding: 'utf8' })
    expect(onOrigin.status).toBe(0)
    expect(onOrigin.stdout).toContain('wip: a-thing (builder did not finish)')
  })

  it('survives the next dispatch, which salvages again rather than deleting', () => {
    run('dispatch.sh')
    const r = run('dispatch.sh')
    expect(r.status).toBe(0)
    const branch = spawnSync('node', ['scripts/intake.mjs', 'branch', 'a-thing.md'],
      { encoding: 'utf8', cwd: box.repo }).stdout.trim()
    const onOrigin = spawnSync('git', ['-C', box.origin, 'log', '--oneline', branch],
      { encoding: 'utf8' })
    expect(onOrigin.stdout).toContain('wip: a-thing')
  })

  it('tells Matt, rather than appending to a log nobody reads', () => {
    run('dispatch.sh')
    expect(box.log()).toContain('osascript')
  })
})

describe('a pull request that was already merged', () => {
  // `gh pr list --head <branch> --state open` cannot see a MERGED pull
  // request, so a builder that did exactly what it was told — merge on
  // green — was judged unfinished every single time, and the request was
  // rebuilt from a base that already had the feature in it.
  //
  // But `--state all --limit 1` also surfaces an OLD merged pull request
  // on the same branch, so the fix needs a second question: is this
  // work actually in the base? Both halves are here.

  beforeEach(() => {
    build({ requests: { 'a-thing.md': READY('A thing') } })
    box.stub('gh', ghStub({ state: 'MERGED' }))
    // A builder whose work really did land on main, which is what a
    // merged pull request means.
    box.stub('claude', `
branch="$(git rev-parse --abbrev-ref HEAD)"
echo x >> built.txt
git add -A
git -c user.name=b -c user.email=b@b commit -qm "build: $branch"
git push -q origin "HEAD:refs/heads/$branch"
git push -q origin "HEAD:refs/heads/main"
exit 0
`)
  })

  it('is judged done, not unfinished', () => {
    run('dispatch.sh')
    expect(box.intakeLog()).not.toContain('NOT FINISHED')
    expect(existsSync(box.path('requests/done/a-thing.md'))).toBe(true)
  })

  it('does not try to merge it again', () => {
    run('dispatch.sh')
    expect(box.log()).not.toContain('pr merge')
  })

  it('is NOT judged done off an older merged PR whose work is not in main', () => {
    // A builder that commits and pushes but opens no pull request used to
    // be filed as done off whatever MERGED pull request the branch had
    // previously had — committing "Merged as #7" while its own commits
    // sat unmerged. The `--state all` fix is what opened this.
    box.stub('claude', BUILDER)
    run('dispatch.sh')
    logged('that is an older pull request')
    logged('NOT FINISHED')
    expect(existsSync(box.path('requests/done/a-thing.md'))).toBe(false)
    expect(existsSync(box.path('requests/a-thing.md'))).toBe(true)
  })
})

describe('whatever Matt named the file', () => {
  it('is one request even with spaces in it', () => {
    // `for file in $(buildable)` word-split, so `fix the card page.md`
    // became four bogus requests, one of which got a worktree.
    build({ requests: { 'fix the card page.md': READY('Fix the card page') } })
    box.stub('claude', BUILDER)
    box.stub('gh', ghStub())
    run('dispatch.sh')
    const builders = box.log().split('\n').filter((l) => l.startsWith('claude '))
    expect(builders.length).toBe(1)
    expect(existsSync(box.path('requests/done/fix the card page.md'))).toBe(true)
  })
})

describe('a queue with more in it than one builder', () => {
  it('comes back for the rest instead of dropping them', () => {
    // `buildable` was read once; the dispatcher then just `wait`ed,
    // holding the lock for up to four hours, while launchd discarded
    // every WatchPaths event fired during the run. `.intake/intake.log`
    // shows an hour of dropped events, and the header comment claiming
    // it re-reads the folder was false.
    build({
      requests: {
        'one-thing.md': READY('One thing'),
        'two-thing.md': READY('Two thing'),
      },
    })
    box.stub('claude', BUILDER)
    box.stub('gh', ghStub())
    run('dispatch.sh', { env: { INTAKE_MAX_BUILDERS: '1' }, timeout: 120_000 })
    expect(existsSync(box.path('requests/done/one-thing.md'))).toBe(true)
    expect(existsSync(box.path('requests/done/two-thing.md'))).toBe(true)
  })
})

describe('triage', () => {
  it('runs after the requests that already have a plan have been dispatched', () => {
    // Triage measured 5.3 minutes and sat unconditionally ahead of the
    // builder loop, so a request that already had a plan waited it out
    // for nothing.
    build({
      requests: {
        'ready-one.md': READY('Ready one'),
        'raw-one.md': '# Raw one\n\nmake the tiles smaller\n',
      },
    })
    box.stub('claude', BUILDER)
    box.stub('gh', ghStub())
    run('dispatch.sh', { timeout: 120_000 })
    const lines = box.log().split('\n').filter((l) => l.startsWith('claude '))
    const builder = lines.findIndex((l) => l.includes('.intake/handed/ready-one.md'))
    const triage = lines.findIndex((l) => l.includes('request-triage'))
    expect(builder).toBeGreaterThanOrEqual(0)
    expect(triage).toBeGreaterThanOrEqual(0)
    expect(builder).toBeLessThan(triage)
  })

  it('stops re-triaging a file it has already failed to plan three times', () => {
    // `triaged()` tested only for `## Plan`, so triage could write a
    // complete-looking file that was still untriaged and got a full opus
    // run on every dispatch, forever.
    build({ requests: { 'raw-one.md': '# Raw one\n\nmake it faster\n' } })
    box.stub('claude', 'exit 0')
    box.stub('gh', ghStub({ state: 'NONE' }))
    for (let i = 0; i < 3; i += 1) run('dispatch.sh')
    logged('TRIAGE PRODUCED NO PLAN FOR')
    const before = box.log().split('\n').filter((l) => l.includes('request-triage')).length
    run('dispatch.sh')
    const after = box.log().split('\n').filter((l) => l.includes('request-triage')).length
    expect(after).toBe(before)
    logged('giving up')
  })

  it('does not run in Matt live checkout, and commits what it produced', () => {
    build({ requests: { 'raw-one.md': '# Raw one\n\nmake it faster\n' } })
    // Triage that actually writes a plan, in whatever cwd it was given.
    box.stub('claude', `
if [ -f requests/raw-one.md ]; then
  printf -- '---\\nstatus: ready\\nmerge: auto\\n---\\n\\n# Raw one\\n\\n## Plan\\n\\np\\n\\n## Tests\\n\\nt\\n\\n## Done when\\n\\nd\\n' > requests/raw-one.md
fi
exit 0
`)
    box.stub('gh', ghStub({ state: 'NONE' }))
    run('dispatch.sh', { timeout: 120_000 })
    // The plan survives a `git checkout` in the primary tree, which it
    // did not before: triage ran with acceptEdits in Matt's live
    // checkout and its output was never committed.
    expect(box.git('status', '--porcelain', '--', 'requests').stdout.trim()).toBe('')
    expect(box.git('log', '-1', '--format=%s', '--', 'requests').stdout).toContain('triage')
  })
})

describe('a request withdrawn while it is being built', () => {
  it('stops the builder, because both docs promise deleting the file does', () => {
    build({ requests: { 'a-thing.md': READY('A thing') } })
    // A builder that outlives the withdrawal.
    box.stub('claude', 'sleep 120; exit 0')
    box.stub('gh', ghStub({ state: 'NONE' }))
    // Withdrawn from outside this process: `spawnSync` below blocks the
    // event loop, so a setTimeout here would never fire.
    const live = box.path('requests/a-thing.md')
    const withdraw = spawn('bash', ['-c', `sleep 4; rm -f ${JSON.stringify(live)}`],
      { detached: true, stdio: 'ignore' })
    withdraw.unref()
    const r = run('dispatch.sh', { env: { INTAKE_WATCH_SECONDS: '2' }, timeout: 120_000 })
    expect(r.status).toBe(0)
    logged('withdrawn')
  })
})

describe('the script itself', () => {
  it('is parsed in one read, so editing it mid-run cannot make it execute garbage', () => {
    // bash reads a script by byte offset. `.intake/launchd.log` records
    // what editing it during a four-hour run did to the RUNNING shell:
    // `line 177: ites,: command not found`, `line 193: code: unbound
    // variable`, `line 210: syntax error`.
    build()
    const text = readFileSync(box.path('scripts/intake/dispatch.sh'), 'utf8')
    const lines = text.trimEnd().split('\n')
    expect(lines[lines.length - 1]).toBe('main "$@"')
    expect(text).toContain('\nmain() {')
  })

  it('never bare rm -rf a worktree', () => {
    build()
    const text = readFileSync(box.path('scripts/intake/dispatch.sh'), 'utf8')
    const bad = text.split('\n')
      .filter((l) => /rm -rf .*\$(tree|slot)\b/.test(l) && !l.trim().startsWith('#'))
      .filter((l) => !/remove_worktree/.test(l))
    expect(bad).toEqual([])
    expect(text).toContain('worktree prune')
  })

  it('is portable enough to run where the tests run', () => {
    build()
    const text = readFileSync(box.path('scripts/intake/dispatch.sh'), 'utf8')
    // `md5` is macOS-only and `| md5` of the pending list was load-bearing.
    expect(text).not.toMatch(/\|\s*md5\b/)
  })
})

describe('installing the watcher', () => {
  // The plist used to be a literal file with four hardcoded paths pointing
  // straight at a script inside a mutable working tree on a feature branch.
  // A checkout, a `git clean -xfd`, or the repo moving left the watcher
  // loaded and pointing at nothing — and `status.sh` only tailed
  // intake.log, so the dashboard showed a healthy idle queue while every
  // request was lost.

  function install() {
    box.stub('launchctl', 'exit 0')
    return spawnSync('bash', [box.path('scripts/intake/install.sh')], {
      encoding: 'utf8',
      cwd: box.repo,
      timeout: 60_000,
      env: { ...process.env, PATH: `${box.bin}:${process.env.PATH}`, HOME: box.root },
    })
  }

  beforeEach(() => { build({ requests: { 'a-thing.md': READY('A thing') } }) })

  it('substitutes this repo path in rather than shipping somebody else\'s', () => {
    const r = install()
    expect(r.status).toBe(0)
    const plist = readFileSync(
      join(box.root, 'Library/LaunchAgents/com.matt.mtg.intake.plist'), 'utf8')
    expect(plist).not.toMatch(/@[A-Z]+@/)
    expect(plist).toContain(`${box.repo}/requests`)
  })

  it('points launchd at a launcher outside the working tree', () => {
    install()
    const plist = readFileSync(
      join(box.root, 'Library/LaunchAgents/com.matt.mtg.intake.plist'), 'utf8')
    expect(plist).not.toContain(`${box.repo}/scripts/intake/dispatch.sh`)
    const runner = join(box.root, 'Library/Application Support/mtg-intake/run-intake.sh')
    expect(existsSync(runner)).toBe(true)
  })

  it('leaves a launcher that says so loudly when the dispatcher is not there', () => {
    install()
    const runner = join(box.root, 'Library/Application Support/mtg-intake/run-intake.sh')
    rmSync(box.path('scripts/intake/dispatch.sh'))
    const r = spawnSync('bash', [runner], { encoding: 'utf8', timeout: 30_000 })
    expect(r.status).toBe(0)
    const log = readFileSync(
      join(box.root, 'Library/Application Support/mtg-intake/logs/launchd.log'), 'utf8')
    expect(log).toContain('NO DISPATCHER')
  })

  it('drains the queue after a reboot, which RunAtLoad false never did', () => {
    install()
    const plist = readFileSync(
      join(box.root, 'Library/LaunchAgents/com.matt.mtg.intake.plist'), 'utf8')
    expect(plist).toMatch(/<key>RunAtLoad<\/key>\s*<true\/>/)
  })

  it('puts node on the PATH launchd gives the job', () => {
    install()
    const plist = readFileSync(
      join(box.root, 'Library/LaunchAgents/com.matt.mtg.intake.plist'), 'utf8')
    const nodeDir = spawnSync('bash', ['-c', 'dirname "$(command -v node)"'],
      { encoding: 'utf8' }).stdout.trim()
    expect(plist).toContain(nodeDir)
  })

  it('turns the off switch back off, because installing is when that happens', () => {
    box.disable()
    install()
    expect(existsSync(box.path('.intake/disabled'))).toBe(false)
  })
})

describe('status.sh', () => {
  // It used to re-implement the `triaged` and `needs-matt` predicates with
  // grep, and the two implementations DISAGREED: `grep -qF '## Plan'` is an
  // unanchored substring, and `grep -q '^status: needs-matt'` matches the
  // body outside the frontmatter. So status said "ready" for files the
  // dispatcher held, and the other way round.

  function status(env = {}) {
    return spawnSync('bash', [box.path('scripts/intake/status.sh')], {
      encoding: 'utf8',
      cwd: box.repo,
      timeout: 60_000,
      env: {
        ...process.env,
        PATH: `${box.bin}:${process.env.PATH}`,
        HOME: box.root,
        INTAKE_BASE: 'origin/main',
        ...env,
      },
    })
  }

  it('agrees with intake.mjs instead of guessing with grep', () => {
    build({
      requests: {
        // A plan in prose and a needs-matt in the BODY. The old greps
        // called the first ready and the second waiting; both are wrong.
        'prose.md': '# T\n\nMy ## Plan is to wait\n',
        'body.md': `${READY('Body')}\nstatus: needs-matt\n`,
        'asked.md': READY('Asked').replace('status: ready', 'status: needs-matt'),
      },
    })
    const out = status().stdout
    expect(out).toMatch(/prose\s+untriaged/)
    expect(out).toMatch(/body\s+ready/)
    expect(out).toMatch(/asked\s+needs-matt/)
  })

  it('says plainly when intake.mjs could not answer', () => {
    build({ requests: { 'a-thing.md': READY('A thing') } })
    box.stub('node', 'exit 1')
    expect(status().stdout).toContain('intake.mjs unavailable')
  })

  it('prints files, commits and pushed-ness for a STALLED slot, not a bare line', () => {
    // The old version computed these only inside the `building` branch, so
    // the stalled case printed "worktree kept" with no hint that
    // thirty-two uncommitted files were sitting in it — which is exactly
    // the state `.intake/wt/card-page-shows-everything` was found in.
    build({ requests: { 'a-thing.md': READY('A thing') } })
    const branch = spawnSync('node', ['scripts/intake.mjs', 'branch', 'a-thing.md'],
      { encoding: 'utf8', cwd: box.repo }).stdout.trim()
    const slot = join(box.root, 'wt', 'slot1')
    box.git('worktree', 'add', '-q', '-b', branch, slot, 'main')
    writeFileSync(join(slot, 'half.txt'), 'half a change\n')
    const out = status({ INTAKE_WORKTREE_ROOT: join(box.root, 'wt') }).stdout
    expect(out).toContain('STALLED')
    expect(out).toMatch(/1 changed, 0 commits, not on origin/)
  })

  it('shows the lock and the claims, so a wedged queue is not an idle one', () => {
    build({ requests: { 'a-thing.md': READY('A thing') } })
    mkdirSync(box.path('.intake/dispatch.lock'))
    writeFileSync(box.path('.intake/dispatch.lock/pid'), '999999')
    mkdirSync(box.path('.intake/request_a-thing.building'))
    writeFileSync(box.path('.intake/request_a-thing.building/pid'), '999999')
    const out = status().stdout
    expect(out).toContain('DISPATCHER')
    expect(out).toContain('WHICH IS GONE')
    expect(out).toContain('request_a-thing.building')
    expect(out).toContain('GONE — stale')
  })

  it('treats a pidless lock as live here too', () => {
    build({ requests: { 'a-thing.md': READY('A thing') } })
    mkdirSync(box.path('.intake/dispatch.lock'))
    expect(status().stdout).toContain('no pid yet')
  })

  it('lists request branches with ahead-of-base and pushed-ness', () => {
    build({ requests: { 'a-thing.md': READY('A thing') } })
    box.git('branch', 'request/not-pushed-abc1234', 'main')
    const out = status().stdout
    expect(out).toContain('BRANCHES')
    expect(out).toMatch(/request\/not-pushed-abc1234\s+0 ahead of origin\/main, NOT ON ORIGIN/)
  })

  it('distinguishes a failing gh from a clean queue', () => {
    build({ requests: { 'a-thing.md': READY('A thing') } })
    box.stub('gh', 'exit 4')
    expect(status().stdout).toContain('OPEN PRS (gh unavailable')
  })

  it('calls a pull request with no checks unverified rather than green', () => {
    build({ requests: { 'a-thing.md': READY('A thing') } })
    box.stub('gh', `printf '[{"number":7,"headRefName":"request/x","statusCheckRollup":[]}]\\n'`)
    const out = status().stdout
    expect(out).toContain('no checks')
    expect(out).toContain('not green')
  })

  it('surfaces what a builder is waiting on, and flags an old Bash call', () => {
    build({ requests: { 'a-thing.md': READY('A thing') } })
    writeFileSync(box.path('.intake/a-thing.jsonl'),
      `${JSON.stringify({ type: 'assistant', message: { content: [{ type: 'tool_use', name: 'Bash' }] } })}\n`)
    const out = status({ INTAKE_STUCK_MINUTES: '0' }).stdout
    expect(out).toContain('last event')
    expect(out).toContain('STUCK?')
  })

  it('says when the whole machine is switched off', () => {
    build({ requests: { 'a-thing.md': READY('A thing') } })
    box.disable()
    expect(status().stdout).toContain('OFF')
  })
})

describe('what a builder is launched with', () => {
  // The deny list is string matching, and string matching was attacked
  // with eighteen spellings a model would reach for first — `eval`,
  // `bash -c`, `W=wrangler; $W deploy`, `--admin=true`, `wrangler
  // dep""loy`, `wrangler d1 execute` with no `--remote` — and every one
  // got through. So the first line of defence is not holding the keys.

  beforeEach(() => {
    build({ requests: { 'a-thing.md': READY('A thing') } })
    box.stub('gh', ghStub())
    // A builder that reports its own environment back.
    box.stub('claude', `
printenv | grep -E '^(CLOUDFLARE|CF_|WRANGLER)' > ${JSON.stringify(join(box.root, 'builder-env.txt'))} || true
printenv WRANGLER_HOME >> ${JSON.stringify(join(box.root, 'builder-env.txt'))} || true
branch="$(git rev-parse --abbrev-ref HEAD)"
echo x >> built.txt
git add -A
git -c user.name=b -c user.email=b@b commit -qm "build: $branch"
git push -q origin "HEAD:refs/heads/$branch"
exit 0
`)
  })

  const builderEnv = () => readFileSync(join(box.root, 'builder-env.txt'), 'utf8')

  it('carries no Cloudflare credentials, even when the dispatcher has them', () => {
    run('dispatch.sh', {
      env: {
        CLOUDFLARE_API_TOKEN: 'a-real-looking-token',
        CLOUDFLARE_ACCOUNT_ID: 'an-account',
        CF_API_TOKEN: 'another',
      },
    })
    const env = builderEnv()
    expect(env).not.toContain('a-real-looking-token')
    expect(env).not.toContain('an-account')
    expect(env).not.toContain('another')
  })

  it('has WRANGLER_HOME pointed somewhere with no stored login in it', () => {
    // Wrangler finds credentials in exactly two places: the environment,
    // and its own config under WRANGLER_HOME. Both are taken away.
    run('dispatch.sh')
    expect(builderEnv()).toContain('no-cloudflare-auth')
  })

  it('still gets everything it legitimately needs', () => {
    run('dispatch.sh', { env: { SOME_HARMLESS_VAR: 'kept' } })
    // The scrub is a named list, not a whitelist: it must not have
    // emptied the environment wholesale.
    expect(existsSync(box.path('requests/done/a-thing.md'))).toBe(true)
  })
})

describe('a worktree that is missing its instructions', () => {
  // The guard for "a builder that works blind" was unreachable code that
  // killed the dispatcher instead. `ask()` calls `exit` on nonzero and
  // `|| true` cannot catch an `exit`; `intake.mjs equipped` exits 1 BY
  // DESIGN when files are missing, so the only branch that reached it
  // always took the process down — dropping every request behind it in
  // the wave and leaving the claim and the slot claim on disk.

  beforeEach(() => {
    build({
      requests: {
        'a-thing.md': READY('A thing'),
        'b-thing.md': READY('B thing'),
      },
    })
    box.stub('claude', BUILDER)
    box.stub('gh', ghStub())
  })

  function stripInstructions() {
    // Remove it from the base so every fresh slot lacks it.
    box.git('rm', '-q', 'CLAUDE.md')
    box.git('-c', 'user.name=t', '-c', 'user.email=t@t', 'commit', '-qm', 'no CLAUDE.md')
    box.git('push', '-q', 'origin', 'main')
    box.git('fetch', '-q', 'origin')
  }

  it('is skipped, and does not take the dispatcher down with it', () => {
    stripInstructions()
    const r = run('dispatch.sh', { env: { INTAKE_MAX_BUILDERS: '2' } })
    expect(r.status).toBe(0)
    logged('NOT STARTED')
    expect(box.log()).not.toContain('claude -p')
  })

  it('names what is missing, rather than only that something is', () => {
    stripInstructions()
    run('dispatch.sh')
    logged('CLAUDE.md')
  })

  it('leaves no claim and no slot claim behind', () => {
    stripInstructions()
    run('dispatch.sh')
    const left = readdirSync(box.path('.intake'))
      .filter((f) => f.endsWith('.building') || f.endsWith('.claim'))
    expect(left).toEqual([])
  })

  it('tells Matt, because a blind builder is the thing this exists to stop', () => {
    stripInstructions()
    run('dispatch.sh')
    expect(box.log()).toContain('osascript')
  })

  it('needs the files that make the refusals real, not just the instructions', () => {
    // `.claude/settings.json`, `bash-deny.mjs` and `deny-bash.mjs` are the
    // Part D enforcement mechanism. A worktree without them runs
    // bypassPermissions with the deny hook silently absent.
    box.git('rm', '-q', '.claude/settings.json')
    box.git('-c', 'user.name=t', '-c', 'user.email=t@t', 'commit', '-qm', 'no settings')
    box.git('push', '-q', 'origin', 'main')
    box.git('fetch', '-q', 'origin')
    const r = run('dispatch.sh')
    expect(r.status).toBe(0)
    logged('NOT STARTED')
    logged('.claude/settings.json')
  })

  it('checks the triage worktree too', () => {
    build({ requests: { 'raw-one.md': '# Raw\n\nmake it faster\n' } })
    box.stub('claude', BUILDER)
    box.stub('gh', ghStub({ state: 'NONE' }))
    box.git('rm', '-q', '.claude/agents/request-triage.md')
    box.git('-c', 'user.name=t', '-c', 'user.email=t@t', 'commit', '-qm', 'no triage def')
    box.git('push', '-q', 'origin', 'main')
    box.git('fetch', '-q', 'origin')
    const r = run('dispatch.sh')
    expect(r.status).toBe(0)
    logged('triage NOT STARTED')
    // No triage agent was launched. The notification mentions the missing
    // file, so the assertion has to be about the `claude` call.
    const launched = box.log().split('\n').filter((l) => l.startsWith('claude '))
    expect(launched.filter((l) => l.includes('request-triage'))).toEqual([])
  })
})

describe('gh answering nothing', () => {
  // Three consumers still conflated failure with an answer: a failing
  // `pr list` produced "NOT FINISHED — no pull request for its branch"
  // and a false notification about a builder that had done everything
  // right; a failing `run list` produced zero `gh run watch` calls and
  // the dispatcher judged CI anyway; and `all_checks_green` bypassed the
  // checked wrapper entirely.

  beforeEach(() => {
    build({ requests: { 'a-thing.md': READY('A thing') } })
    box.stub('claude', BUILDER)
  })

  it('is not a verdict when pr list fails', () => {
    box.stub('gh', `
case "$1 $2" in
  "pr list") exit 4 ;;
esac
exit 0
`)
    run('dispatch.sh')
    expect(box.intakeLog()).not.toContain('no pull request for its branch')
    logged('could not ask GitHub')
    // The slot is kept, so nothing is lost and it can be re-dispatched.
    expect(existsSync(box.path('requests/a-thing.md'))).toBe(true)
    expect(existsSync(box.path('requests/done/a-thing.md'))).toBe(false)
  })

  it('is not a green CI when run list fails', () => {
    box.stub('gh', `
case "$1 $2" in
  "pr list") printf '7 OPEN\\n' ;;
  "run list") exit 4 ;;
  "pr checks") printf 'shared\\tpass\\t1m\\tu\\n'; exit 0 ;;
esac
exit 0
`)
    run('dispatch.sh')
    expect(box.log()).not.toContain('pr merge')
    logged('could not ask GitHub')
  })

  it('is not a green CI when pr checks fails to run at all', () => {
    box.stub('gh', `
case "$1 $2" in
  "pr list") printf '7 OPEN\\n' ;;
  "run list") printf '101\\n' ;;
  "run watch") exit 0 ;;
  "pr checks") exit 4 ;;
esac
exit 0
`)
    run('dispatch.sh')
    expect(box.log()).not.toContain('pr merge')
  })
})

describe('every terminal outcome', () => {
  // A request left the live queue only when it MERGED. For `merge: ask`
  // — which is four of five live requests — and for a red pull request,
  // it stayed live and buildable, and the consequence was concrete: run 1
  // leaves it there, run 2 launches a second builder on the same branch,
  // `prepare_slot` resets the branch, and `salvage` force-pushes the
  // duplicate OVER the first builder's commit, destroying the pull
  // request Matt was asked to review.

  it('files a green merge: ask request, so a second builder cannot overwrite it', () => {
    build({ requests: { 'a-thing.md': READY('A thing').replace('merge: auto', 'merge: ask') } })
    box.stub('claude', BUILDER)
    box.stub('gh', ghStub())
    run('dispatch.sh')
    expect(box.log()).not.toContain('pr merge')
    expect(existsSync(box.path('requests/a-thing.md'))).toBe(false)
    expect(existsSync(box.path('requests/done/a-thing.md'))).toBe(true)
    expect(readFileSync(box.path('requests/done/a-thing.md'), 'utf8'))
      .toContain('waiting for you as #7')
  })

  it('does not relaunch a builder on the same branch on the next dispatch', () => {
    build({ requests: { 'a-thing.md': READY('A thing').replace('merge: auto', 'merge: ask') } })
    box.stub('claude', BUILDER)
    box.stub('gh', ghStub())
    run('dispatch.sh')
    const first = box.log().split('\n').filter((l) => l.startsWith('claude ')).length
    run('dispatch.sh')
    const second = box.log().split('\n').filter((l) => l.startsWith('claude ')).length
    expect(second).toBe(first)
  })

  it('holds a request that keeps failing, instead of retrying it forever', () => {
    // `TRIED` is per-process (`tried.$$`), so it counted nothing across
    // dispatches, and `TRIAGE_GIVE_UP` only ever applied to triage.
    build({ requests: { 'a-thing.md': READY('A thing') } })
    box.stub('claude', BUILDER_STALLS)
    box.stub('gh', ghStub({ state: 'NONE' }))
    for (let i = 0; i < 3; i += 1) run('dispatch.sh')
    logged('GIVING UP on a-thing')
    expect(existsSync(box.path('.intake/a-thing.held'))).toBe(true)
    const before = box.log().split('\n').filter((l) => l.startsWith('claude ')).length
    run('dispatch.sh')
    const after = box.log().split('\n').filter((l) => l.startsWith('claude ')).length
    expect(after).toBe(before)
    // Held, not lost. It leaves the live folder at give-up, which the
    // README promises happens on every outcome, and the hold file plus the
    // branch are what keep it recoverable.
    expect(existsSync(box.path('requests/done/a-thing.md'))).toBe(true)
    expect(readFileSync(box.path('requests/done/a-thing.md'), 'utf8')).toContain('Held after')
  })
})

describe('a red pull request', () => {
  // The fix-only re-dispatch did not exist. `build_one` wrote
  // `$STATE/<name>.fixme` and the only reader anywhere was `status.sh`,
  // which printed it — while request-builder.md and requests/README.md
  // both promised the feature and the PR body claimed it.

  function redCi(extra = '') {
    return `
case "$1 $2" in
  "pr list") printf '7 OPEN\\n' ;;
  "run list") printf '101\\n' ;;
  "run watch") exit 0 ;;
  "run view") printf 'FAILED: the thing that broke\\n' ;;
  "pr checks") printf 'shared\\tpass\\t1m\\tu\\ntally\\tfail\\t1m\\tu\\n'; exit 1 ;;
  "pr merge") exit 0 ;;
esac
${extra}
exit 0
`
  }

  beforeEach(() => {
    build({ requests: { 'a-thing.md': READY('A thing') } })
    box.stub('gh', redCi())
  })

  it('sends a fix-only builder, with the failing log in its hands', () => {
    box.stub('claude', BUILDER)
    run('dispatch.sh', { timeout: 120_000 })
    logged('dispatching a fix-only builder')
    const launched = box.log().split('\n').filter((l) => l.startsWith('claude '))
    const fix = launched.find((l) => l.includes('FIX-ONLY'))
    expect(fix).toBeDefined()
    expect(fix).toContain('.ci-failure.log')
    expect(fix).toContain('#7')
    expect(existsSync(box.path('.intake/a-thing.ci-failure.log'))).toBe(true)
    expect(readFileSync(box.path('.intake/a-thing.ci-failure.log'), 'utf8'))
      .toContain('the thing that broke')
  })

  it('tells the fix builder not to re-implement, merge, or open another PR', () => {
    box.stub('claude', BUILDER)
    run('dispatch.sh', { timeout: 120_000 })
    const fix = box.log().split('\n').find((l) => l.includes('FIX-ONLY'))
    expect(fix).toContain('not implementing the request again')
    expect(fix).toContain('Do not open another pull request')
    expect(fix).toContain('Do not merge')
  })

  it('gives up after a bounded number of fixes rather than looping', () => {
    box.stub('claude', BUILDER)
    run('dispatch.sh', { env: { INTAKE_FIX_GIVE_UP: '1' }, timeout: 120_000 })
    run('dispatch.sh', { env: { INTAKE_FIX_GIVE_UP: '1' }, timeout: 120_000 })
    logged('still red')
    expect(existsSync(box.path('requests/done/a-thing.md'))).toBe(true)
  })
})

describe('reusing a slot', () => {
  // `npm ci` is 403 seconds and is the single thing slots exist to avoid.
  // The stamp used to live INSIDE the worktree, four lines after a
  // `git clean -qxdf` that deleted it, so the comparison always failed
  // and it ran on every build. No test caught it, because the fake repo
  // had no package.json and the whole block was dead code.

  beforeEach(() => {
    build({ requests: { 'a-thing.md': READY('A thing') } })
    box.stub('claude', BUILDER)
    box.stub('gh', ghStub())
  })

  it('does not re-run npm ci when the lockfile has not moved', () => {
    run('dispatch.sh')
    logged('lockfile moved')
    // Second request, same slot, same lockfile.
    writeFileSync(box.path('requests/b-thing.md'), READY('B thing'))
    run('dispatch.sh')
    const unchanged = box.intakeLog().split('\n')
      .filter((l) => l.includes('lockfile unchanged'))
    expect(unchanged.length).toBeGreaterThan(0)
  })

  it('keeps the stamp where git clean cannot reach it', () => {
    run('dispatch.sh')
    expect(existsSync(box.path('.intake/slot1.npm-lock'))).toBe(true)
    expect(existsSync(join(box.root, '.cache/mtg-intake/wt/slot1/.intake-npm-lock'))).toBe(false)
  })
})

describe('salvage that cannot push', () => {
  // "Nothing is thrown away without being pushed first" was false when
  // the push failed: salvage committed, logged COULD NOT PUSH, and
  // `prepare_slot` reset the branch one line later — so
  // `git branch -a --contains <sha>` came back empty and only the reflog
  // held the work until the next `git gc`.

  beforeEach(() => {
    build({ requests: { 'a-thing.md': READY('A thing') } })
    box.stub('gh', ghStub({ state: 'NONE' }))
    box.stub('claude', BUILDER_STALLS)
  })

  function breakOrigin() {
    // A remote that accepts nothing.
    box.git('remote', 'set-url', 'origin', join(box.root, 'gone.git'))
  }

  it('tags the commit so it is reachable, and says so out loud', () => {
    run('dispatch.sh')
    breakOrigin()
    writeFileSync(join(box.root, '.cache/mtg-intake/wt/slot1', 'more.txt'), 'more work\n')
    writeFileSync(box.path('requests/b-thing.md'), READY('B thing'))
    run('dispatch.sh')
    logged('COULD NOT PUSH')
    const tags = spawnSync('git',
      ['-C', join(box.root, '.cache/mtg-intake/wt/slot1'), 'tag', '-l', 'intake-salvage/*'],
      { encoding: 'utf8' })
    expect(tags.stdout.trim().length).toBeGreaterThan(0)
  })

  it('tells Matt, rather than only writing it to a log', () => {
    run('dispatch.sh')
    breakOrigin()
    writeFileSync(join(box.root, '.cache/mtg-intake/wt/slot1', 'more.txt'), 'more\n')
    writeFileSync(box.path('requests/b-thing.md'), READY('B thing'))
    run('dispatch.sh')
    expect(box.log()).toContain('osascript')
    expect(box.intakeLog()).toContain('could not be pushed')
  })
})

describe('counting checks', () => {
  it('does not lose one to a missing trailing newline', () => {
    // `printf '%s'` emits no trailing newline, so `wc -l` returned N-1 and
    // a pull request with exactly ONE check was refused as "no checks",
    // notified as red, and given a .fixme. The three-check stub logged
    // "every one of 2 checks reported pass".
    build({ requests: { 'a-thing.md': READY('A thing') } })
    box.stub('claude', BUILDER)
    box.stub('gh', `
case "$1 $2" in
  "pr list") printf '7 OPEN\\n' ;;
  "run list") printf '101\\n' ;;
  "run watch") exit 0 ;;
  "pr checks") printf 'only-one\\tpass\\t1m\\tu\\n'; exit 0 ;;
  "pr merge") exit 0 ;;
esac
exit 0
`)
    run('dispatch.sh')
    logged('every one of 1 checks reported pass')
    expect(box.log()).toContain('pr merge')
  })

  it('does not merge on a skipped or neutral required check', () => {
    // gh buckets NEUTRAL as `skipping`, and `skipping` counted as green —
    // while the comment four lines above said "never with a check pending
    // or skipped".
    build({ requests: { 'a-thing.md': READY('A thing') } })
    box.stub('claude', BUILDER)
    box.stub('gh', `
case "$1 $2" in
  "pr list") printf '7 OPEN\\n' ;;
  "run list") printf '101\\n' ;;
  "run watch") exit 0 ;;
  "pr checks") printf 'shared\\tpass\\t1m\\tu\\ntally\\tskipping\\t1m\\tu\\n'; exit 0 ;;
  "pr merge") exit 0 ;;
esac
exit 0
`)
    run('dispatch.sh')
    expect(box.log()).not.toContain('pr merge')
    logged('tally=skipping')
  })
})

describe('after a merge', () => {
  // `verify_shipped` verified nothing. It was a 1000-byte floor on a URL
  // that was already serving the previous build, so a 404 page and the
  // prior build passed identically. It keyed on the PR head sha, which a
  // squash merge never produces, so it matched the pull request's own CI
  // runs — already watched — while `pages.yml` and `release.yml` run
  // against the new squash commit and never matched. The `--branch main`
  // fallback could not fire because the first query was never empty, and
  // when it did it watched arbitrary recent runs of any workflow. Its
  // return value was discarded, `file_as_done` had already run, and the
  // user got two contradictory notifications back to back.
  //
  // Measured: a curl stub returning 1000 bytes of zeroes produced
  // "deploys for #7 were green and the site answered".

  const MERGE_SHA = 'abc1234abc1234abc1234abc1234abc1234abc12'

  function ghDeploys({ pagesOk = true, marker = 'MARKER-xyz' } = {}) {
    return `
case "$1 $2" in
  "pr list") printf '7 OPEN\\n' ;;
  "pr view") printf '%s\\n' "${MERGE_SHA}" ;;
  "run list")
    case "$*" in
      *${MERGE_SHA}*) printf 'pages 901\\nrelease 902\\n' ;;
      *) printf '101\\n' ;;
    esac ;;
  "run watch")
    case "$3" in
      901) ${pagesOk ? 'exit 0' : 'exit 1'} ;;
      *) exit 0 ;;
    esac ;;
  "run view") printf 'pages failure\\n' ;;
  "pr checks") printf 'shared\\tpass\\t1m\\tu\\n'; exit 0 ;;
  "pr merge") exit 0 ;;
  "release download") exit 0 ;;
esac
exit 0
`
  }

  /** A builder that leaves a marker, which is what makes a check possible. */
  function markerBuilder(marker) {
    return `
branch="$(git rev-parse --abbrev-ref HEAD)"
echo x >> built.txt
git add -A
git -c user.name=b -c user.email=b@b commit -qm "build: $branch"
git push -q origin "HEAD:refs/heads/$branch"
m="$(printf '%s' "$*" | sed -nE 's#.*string to ([^ ]*\\.marker).*#\\1#p' | head -1)"
[ -n "$m" ] && printf '%s\\n' ${JSON.stringify(marker)} > "$m"
exit 0
`
  }

  beforeEach(() => {
    build({ requests: { 'a-thing.md': READY('A thing') } })
  })

  it('watches the runs for the MERGE commit, not the pull request head', () => {
    box.stub('claude', markerBuilder('MARKER-xyz'))
    box.stub('gh', ghDeploys())
    box.stub('curl', 'printf "stuff MARKER-xyz stuff"')
    run('dispatch.sh', { env: { INTAKE_SITE_JS: 'https://example.invalid/mtg.js' } })
    logged(`deploy runs for ${MERGE_SHA.slice(0, 8)}`)
    expect(box.log()).toContain('run watch 901')
    expect(box.log()).toContain('run watch 902')
  })

  it('looks for the builder marker in the shipped bundle, not a byte count', () => {
    box.stub('claude', markerBuilder('MARKER-xyz'))
    box.stub('gh', ghDeploys())
    box.stub('curl', 'printf "stuff MARKER-xyz stuff"')
    run('dispatch.sh', { env: { INTAKE_SITE_JS: 'https://example.invalid/mtg.js' } })
    logged('MARKER-xyz is in the shipped web bundle')
  })

  it('fails when the bundle is served but does not carry the change', () => {
    // The state a byte count cannot tell apart: the site is up, it is
    // serving the PREVIOUS build, and nothing shipped.
    box.stub('claude', markerBuilder('MARKER-xyz'))
    box.stub('gh', ghDeploys())
    box.stub('curl', 'printf "%01000d" 0')
    run('dispatch.sh', { env: { INTAKE_SITE_JS: 'https://example.invalid/mtg.js' } })
    logged('NOT in the shipped web bundle')
    expect(box.log()).toContain('osascript')
  })

  it('says plainly that it verified nothing when the builder left no marker', () => {
    box.stub('claude', BUILDER)
    box.stub('gh', ghDeploys())
    box.stub('curl', 'printf "anything"')
    run('dispatch.sh', { env: { INTAKE_SITE_JS: 'https://example.invalid/mtg.js' } })
    logged('no marker')
    expect(box.intakeLog()).not.toContain('is in the shipped web bundle')
  })

  it('files the request AFTER the check, with the outcome in the note', () => {
    box.stub('claude', markerBuilder('MARKER-xyz'))
    box.stub('gh', ghDeploys({ pagesOk: false }))
    box.stub('curl', 'printf "stuff MARKER-xyz stuff"')
    run('dispatch.sh', { env: { INTAKE_SITE_JS: 'https://example.invalid/mtg.js' } })
    const note = readFileSync(box.path('requests/done/a-thing.md'), 'utf8')
    expect(note).toContain('#7')
    expect(note).toMatch(/deploy|shipped/i)
  })

  it('sends one notification, not two that contradict each other', () => {
    box.stub('claude', markerBuilder('MARKER-xyz'))
    box.stub('gh', ghDeploys({ pagesOk: false }))
    box.stub('curl', 'printf "stuff MARKER-xyz stuff"')
    run('dispatch.sh', { env: { INTAKE_SITE_JS: 'https://example.invalid/mtg.js' } })
    const notes = box.log().split('\n')
      .filter((l) => l.startsWith('osascript') && l.includes('a-thing'))
    expect(notes.length).toBe(1)
    expect(notes[0]).not.toMatch(/merged as #7$/)
  })

  it('gives the deploy watch a deadline, like the CI watch has', () => {
    box.stub('claude', markerBuilder('MARKER-xyz'))
    box.stub('gh', `
case "$1 $2" in
  "pr list") printf '7 OPEN\\n' ;;
  "pr view") printf '${MERGE_SHA}\\n' ;;
  "run list") case "$*" in *${MERGE_SHA}*) printf 'pages 901\\n' ;; *) printf '101\\n' ;; esac ;;
  "run watch") case "$3" in 901) sleep 300 ;; *) exit 0 ;; esac ;;
  "pr checks") printf 'shared\\tpass\\t1m\\tu\\n'; exit 0 ;;
  "pr merge") exit 0 ;;
esac
exit 0
`)
    box.stub('curl', 'printf "stuff MARKER-xyz stuff"')
    const started = Date.now()
    const r = run('dispatch.sh', {
      env: { INTAKE_SITE_JS: 'https://example.invalid/mtg.js', INTAKE_CI_WATCH_SECONDS: '4' },
      timeout: 120_000,
    })
    expect(r.status).toBe(0)
    expect(Date.now() - started).toBeLessThan(60_000)
    logged('gave up watching')
  })
})

describe('killing a builder', () => {
  // The old watchdog `pkill -P $$`d every sibling, and the replacement
  // matched `claude .*$branch` — which also matches every branch that has
  // this one as a PREFIX, so it killed healthy builders too. Latent only
  // because MAX_BUILDERS is 1, and the code says that is the only line
  // that has to change for N.

  it('kills the whole tree, so a grandchild cannot outlive the log line', () => {
    build({ requests: { 'a-thing.md': READY('A thing') } })
    box.stub('gh', ghStub({ state: 'NONE' }))
    // A builder that forks a grandchild writer and then sits there.
    const spoor = join(box.root, 'grandchild-was-here')
    box.stub('claude', `
( while :; do echo tick >> ${JSON.stringify(spoor)}; sleep 0.2; done ) &
sleep 120
`)
    const live = box.path('requests/a-thing.md')
    const withdraw = spawn('bash', ['-c', `sleep 4; rm -f ${JSON.stringify(live)}`],
      { detached: true, stdio: 'ignore' })
    withdraw.unref()
    const r = run('dispatch.sh', { env: { INTAKE_WATCH_SECONDS: '2' }, timeout: 120_000 })
    expect(r.status).toBe(0)
    logged('withdrawn')
    // Nothing is still writing.
    const a = existsSync(spoor) ? readFileSync(spoor, 'utf8').length : 0
    const deadline = Date.now() + 2500
    while (Date.now() < deadline) { /* let an orphan prove itself */ }
    const b = existsSync(spoor) ? readFileSync(spoor, 'utf8').length : 0
    expect(b).toBe(a)
  })

  it('matches the branch exactly, not every branch it is a prefix of', () => {
    build()
    const text = readFileSync(box.path('scripts/intake/dispatch.sh'), 'utf8')
    // The unanchored form is the bug; it must not be in the file.
    expect(text).not.toContain('pgrep -f "claude .*$branch"')
    expect(text).toContain('claude_pids')
  })
})

describe('a worktree that has to go', () => {
  it('is removed through git, then pruned, never just deleted', () => {
    // A deleted directory leaves `.git/worktrees/<n>` behind, and that
    // stale admin entry is what makes the next `worktree add` for the
    // same branch fail outright.
    build({ requests: { 'a-thing.md': READY('A thing') } })
    const slot = join(box.root, 'wt', 'slot1')
    mkdirSync(join(box.root, 'wt'), { recursive: true })
    box.git('worktree', 'add', '-q', '-b', 'request/leftover-0000000', slot, 'main')
    const script = `
REPO=${JSON.stringify(box.repo)}
LOG=/dev/null
eval "$(sed -n '/^remove_worktree()/,/^}/p' ${JSON.stringify(box.path('scripts/intake/dispatch.sh'))})"
remove_worktree ${JSON.stringify(slot)}
git -C "$REPO" worktree list --porcelain | grep -c slot1 || true
`
    const r = spawnSync('bash', ['-c', script], { encoding: 'utf8' })
    expect(r.stderr).toBe('')
    expect(existsSync(slot)).toBe(false)
    expect(r.stdout.trim()).toBe('0')
  })
})

describe('triage, which must not run twice at once', () => {
  // The lock release added for R4 made this reachable: `triage_pass` ran
  // with no lock at all, so two dispatchers ran two `acceptEdits` opus
  // agents in the SAME worktree, rewriting each other's request files.
  // Stopping exactly that was the lock's only stated purpose.
  //
  // Worse, the copy-back had no liveness check, so a request that had been
  // merged and filed under done/ came back from the dead when the loser's
  // stale copy was written back and committed.

  it('holds the lock while it runs, even after a builder released it', () => {
    // The wave drops the lock once its builders are backgrounded, which is
    // the R4 fix. So the case that matters is a wave that DID start a
    // builder and then went on to triage: one ready request and one raw
    // one in the same folder.
    build({
      requests: {
        'ready-one.md': READY('Ready one'),
        'raw-one.md': '# Raw one\n\nmake it faster\n',
      },
    })
    box.stub('gh', ghStub())
    // Triage reports whether a lock exists at the moment it runs.
    box.stub('claude', `
case "$*" in
  *request-triage*)
    if [ -d ${JSON.stringify(box.path('.intake/dispatch.lock'))} ]; then
      echo "TRIAGE_SAW_LOCK" >> ${JSON.stringify(box.calls)}
    else
      echo "TRIAGE_SAW_NO_LOCK" >> ${JSON.stringify(box.calls)}
    fi
    ;;
  *)
    branch="$(git rev-parse --abbrev-ref HEAD)"
    echo x >> built.txt
    git add -A
    git -c user.name=b -c user.email=b@b commit -qm "build: $branch"
    git push -q origin "HEAD:refs/heads/$branch"
    ;;
esac
exit 0
`)
    run('dispatch.sh', { timeout: 120_000 })
    expect(box.log()).toContain('TRIAGE_SAW_LOCK')
    expect(box.log()).not.toContain('TRIAGE_SAW_NO_LOCK')
  })

  it('does not start when another dispatcher already holds the lock', () => {
    build({ requests: { 'raw-one.md': '# Raw one\n\nmake it faster\n' } })
    box.stub('gh', ghStub({ state: 'NONE' }))
    box.stub('claude', 'exit 0')
    const stand = join(box.root, 'dispatch.sh')
    writeFileSync(stand, '#!/bin/bash\nsleep 45\n')
    chmodSync(stand, 0o755)
    const held = spawn('bash', [stand], { detached: true, stdio: 'ignore' })
    held.unref()
    mkdirSync(box.path('.intake/dispatch.lock'))
    writeFileSync(box.path('.intake/dispatch.lock/pid'), String(held.pid))
    writeFileSync(box.path('.intake/dispatch.lock/command'),
      spawnSync('ps', ['-o', 'command=', '-p', String(held.pid)], { encoding: 'utf8' }).stdout.trim())
    const r = run('dispatch.sh')
    held.kill('SIGKILL')
    expect(r.status).toBe(0)
    const launched = box.log().split('\n').filter((l) => l.startsWith('claude '))
    expect(launched.filter((l) => l.includes('request-triage'))).toEqual([])
  })

  it('never writes a plan back over a request that has gone', () => {
    // Deleting a request while triage runs un-deleted it: `cmp -s` against
    // a nonexistent path fails, so the copy-back restored the file,
    // committed it to main, and dispatched a builder — the exact opposite
    // of what the README promises.
    build({ requests: { 'raw-one.md': '# Raw one\n\nmake it faster\n' } })
    box.stub('gh', ghStub({ state: 'NONE' }))
    box.stub('claude', `
printf -- '---\\nstatus: ready\\nmerge: auto\\n---\\n\\n# Raw one\\n\\n## Plan\\n\\np\\n\\n## Tests\\n\\nt\\n\\n## Done when\\n\\nd\\n' > requests/raw-one.md
rm -f ${JSON.stringify(box.path('requests/raw-one.md'))}
exit 0
`)
    run('dispatch.sh', { timeout: 120_000 })
    expect(existsSync(box.path('requests/raw-one.md'))).toBe(false)
    logged('withdrawn while triage was running')
  })

  it('carries a split back, as new files', () => {
    // `request-triage.md` tells triage to split one request into several
    // NEW files. The copy-back iterated only the handed-over filenames, so
    // every new file stayed in the triage worktree forever, the original
    // was never deleted, it was re-triaged on every dispatch, and after
    // TRIAGE_GIVE_UP it was abandoned as "needs Matt". The user's request
    // was simply gone.
    build({ requests: { 'three-jobs.md': '# Three jobs\n\nfix the deck page\n' } })
    box.stub('gh', ghStub({ state: 'NONE' }))
    box.stub('claude', `
plan() { printf -- '---\\nstatus: ready\\nmerge: auto\\n---\\n\\n# %s\\n\\n## Plan\\n\\np\\n\\n## Tests\\n\\nt\\n\\n## Done when\\n\\nd\\n' "$1"; }
plan "Part one" > requests/split-part-one.md
plan "Part two" > requests/split-part-two.md
rm -f requests/three-jobs.md
exit 0
`)
    run('dispatch.sh', { env: { INTAKE_MAX_BUILDERS: '2' }, timeout: 120_000 })
    // The originals are gone from the live folder and the new ones are there.
    expect(existsSync(box.path('requests/three-jobs.md'))).toBe(false)
    const live = readdirSync(box.path('requests')).filter((f) => f.endsWith('.md'))
    expect(live).toContain('split-part-one.md')
    expect(live).toContain('split-part-two.md')
    expect(box.intakeLog()).not.toContain('TRIAGE PRODUCED NO PLAN FOR')
  })

  it('carries a combine back, deleting both originals', () => {
    build({
      requests: {
        'card-page-a.md': '# A\n\nbigger tiles\n',
        'card-page-b.md': '# B\n\nshow the rank\n',
      },
    })
    box.stub('gh', ghStub({ state: 'NONE' }))
    box.stub('claude', `
printf -- '---\\nstatus: ready\\nmerge: auto\\n---\\n\\n# Card page\\n\\nabsorbed card-page-a.md and card-page-b.md\\n\\n## Plan\\n\\np\\n\\n## Tests\\n\\nt\\n\\n## Done when\\n\\nd\\n' > requests/card-page-combined.md
rm -f requests/card-page-a.md requests/card-page-b.md
exit 0
`)
    run('dispatch.sh', { env: { INTAKE_MAX_BUILDERS: '2' }, timeout: 120_000 })
    expect(existsSync(box.path('requests/card-page-a.md'))).toBe(false)
    expect(existsSync(box.path('requests/card-page-b.md'))).toBe(false)
    expect(readdirSync(box.path('requests')).filter((f) => f.endsWith('.md')))
      .toContain('card-page-combined.md')
  })

  it('starts from a clean triage folder, so a stale copy cannot be written back', () => {
    build({ requests: { 'raw-one.md': '# Raw one\n\nmake it faster\n' } })
    box.stub('gh', ghStub({ state: 'NONE' }))
    box.stub('claude', 'exit 0')
    // A leftover from a previous pass, for a request that no longer exists.
    mkdirSync(join(box.root, '.cache/mtg-intake/wt/triage/requests'), { recursive: true })
    writeFileSync(join(box.root, '.cache/mtg-intake/wt/triage/requests/ghost.md'),
      READY('A ghost'))
    run('dispatch.sh', { timeout: 120_000 })
    expect(existsSync(box.path('requests/ghost.md'))).toBe(false)
  })
})

describe('a claim held by something that is alive', () => {
  // R5 stopped the lock being stolen from a live dispatcher. It left the
  // slot claim and the `.building` claim still age-capped — and since the
  // global lock no longer serialises dispatchers, a second one takes both
  // and runs `prepare_slot` (salvage, `checkout -B`, `git clean -qxdf`) in
  // the worktree a live builder is writing to, then launches a second
  // builder on the branch.
  //
  // Reachable with no builder misbehaving at all, because the CI watch
  // loop and the fix builder were both unbounded.

  function liveHolder() {
    const stand = join(box.root, 'dispatch.sh')
    writeFileSync(stand, '#!/bin/bash\nsleep 60\n')
    chmodSync(stand, 0o755)
    const held = spawn('bash', [stand], { detached: true, stdio: 'ignore' })
    held.unref()
    const cmd = spawnSync('ps', ['-o', 'command=', '-p', String(held.pid)],
      { encoding: 'utf8' }).stdout.trim()
    return { held, cmd }
  }

  function claim(dir, pid, cmd, started) {
    mkdirSync(box.path(dir), { recursive: true })
    writeFileSync(box.path(`${dir}/pid`), String(pid))
    writeFileSync(box.path(`${dir}/started`), String(started))
    writeFileSync(box.path(`${dir}/command`), cmd)
  }

  it('is not broken by the age cap when its process is provably alive', () => {
    build({ requests: { 'a-thing.md': READY('A thing') } })
    box.stub('claude', BUILDER)
    box.stub('gh', ghStub())
    const { held, cmd } = liveHolder()
    const branch = spawnSync('node', ['scripts/intake.mjs', 'branch', 'a-thing.md'],
      { encoding: 'utf8', cwd: box.repo }).stdout.trim()
    const key = branch.replace(/\//g, '_')
    claim(`.intake/${key}.building`, held.pid, cmd, 1)
    claim('.intake/slot1.claim', held.pid, cmd, 1)

    const r = run('dispatch.sh', { env: { INTAKE_MAX_MINUTES: '0' } })
    held.kill('SIGKILL')
    expect(r.status).toBe(0)
    // No second builder, and nothing reset under the first one.
    expect(box.log()).not.toContain('claude -p')
    expect(box.intakeLog()).not.toContain('had a builder that died')
    logged('is alive')
  })

  it('is broken once its process is actually gone', () => {
    build({ requests: { 'a-thing.md': READY('A thing') } })
    box.stub('claude', BUILDER)
    box.stub('gh', ghStub())
    const branch = spawnSync('node', ['scripts/intake.mjs', 'branch', 'a-thing.md'],
      { encoding: 'utf8', cwd: box.repo }).stdout.trim()
    claim(`.intake/${branch.replace(/\//g, '_')}.building`, 999999,
      'bash scripts/intake/dispatch.sh', 1)
    run('dispatch.sh')
    expect(box.log()).toContain('claude -p')
  })
})

describe('a fix builder that stalls', () => {
  // `dispatch_fix` launched `claude -p` with no watchdog, no MAX_MINUTES
  // and no kill — so a stalled fix builder ran unbounded, held its claims
  // past the cap, and the claim-stealing above then reset the worktree
  // under it. Two FIX-START pids were measured in one slot.

  it('is watched the same way a full builder is', () => {
    build({ requests: { 'a-thing.md': READY('A thing') } })
    box.stub('gh', `
case "$1 $2" in
  "pr list") printf '7 OPEN\\n' ;;
  "run list") printf '101\\n' ;;
  "run watch") exit 0 ;;
  "run view") printf 'FAILED: the thing that broke\\n' ;;
  "pr checks") printf 'shared\\tpass\\t1m\\tu\\ntally\\tfail\\t1m\\tu\\n'; exit 1 ;;
esac
exit 0
`)
    // A builder that builds, then a fix builder that sits there forever.
    box.stub('claude', `
case "$*" in
  *FIX-ONLY*)
    sleep 300
    ;;
  *)
    branch="$(git rev-parse --abbrev-ref HEAD)"
    echo x >> built.txt
    git add -A
    git -c user.name=b -c user.email=b@b commit -qm "build: $branch"
    git push -q origin "HEAD:refs/heads/$branch"
    ;;
esac
exit 0
`)
    const r = run('dispatch.sh', {
      env: { INTAKE_MAX_SECONDS: '4', INTAKE_WATCH_SECONDS: '2' },
      timeout: 180_000,
    })
    expect(r.status).toBe(0)
    logged('KILLED')
  })
})

describe('waiting on CI', () => {
  it('gives up on a watch that never returns, rather than parking forever', () => {
    // The fourth of the four unsoundnesses R8 listed, and the one the
    // round-2 comment conceded ("the deadline is the watcher's own"). A
    // GitHub job's default timeout is 360 minutes, 120 past MAX_MINUTES,
    // which is what made the claim steal reachable with no builder
    // misbehaving.
    build({ requests: { 'a-thing.md': READY('A thing') } })
    box.stub('claude', BUILDER)
    box.stub('gh', `
case "$1 $2" in
  "pr list") printf '7 OPEN\\n' ;;
  "run list") printf '101\\n' ;;
  "run watch") sleep 300 ;;
  "pr checks") printf 'shared\\tpass\\t1m\\tu\\n'; exit 0 ;;
  "pr merge") exit 0 ;;
esac
exit 0
`)
    const r = run('dispatch.sh', {
      env: { INTAKE_CI_WATCH_SECONDS: '4' },
      timeout: 180_000,
    })
    expect(r.status).toBe(0)
    logged('gave up watching')
    // A watch that was killed is transport trouble, not a green CI.
    expect(box.log()).not.toContain('pr merge')
  })

  it('reports a red run as red rather than as a failure to ask', () => {
    build({ requests: { 'a-thing.md': READY('A thing') } })
    box.stub('claude', BUILDER)
    box.stub('gh', `
case "$1 $2" in
  "pr list") printf '7 OPEN\\n' ;;
  "run list") printf '101\\n' ;;
  "run watch") exit 1 ;;
  "run view") printf 'FAILED: a real failure\\n' ;;
  "pr checks") printf 'shared\\tfail\\t1m\\tu\\n'; exit 1 ;;
esac
exit 0
`)
    run('dispatch.sh', { timeout: 120_000 })
    logged('finished red')
    expect(box.log()).not.toContain('pr merge')
  })
})

describe('the log pipe', () => {
  it('does not park the dispatcher when a grandchild holds the fifo open', () => {
    // Measured at the full remaining 120 seconds, with the slot and claim
    // held and `watch_builder` already exited. The `kill_tree` comment
    // names this exact mechanism and the code did not apply it here.
    build({ requests: { 'a-thing.md': READY('A thing') } })
    box.stub('gh', ghStub({ state: 'NONE' }))
    box.stub('claude', `
( sleep 300 ) &
exit 0
`)
    const started = Date.now()
    const r = run('dispatch.sh', { timeout: 120_000 })
    expect(r.status).toBe(0)
    expect(Date.now() - started).toBeLessThan(60_000)
  })
})

describe('matching a branch to its builder', () => {
  it('does not match a branch that only shares a prefix', () => {
    // `claude_pids`' exclusion class let `/` and `.` through, so one branch
    // still matched `request/foo/deep` and `request/foo.old`. Latent only
    // because branchFor emits [a-z0-9-] plus a digest.
    build()
    const r = spawnSync('bash', ['-c', `
LOG=/dev/null
eval "$(sed -n '/^claude_pids()/,/^}/p' ${JSON.stringify(box.path('scripts/intake/dispatch.sh'))})"
( exec -a "claude -p work on request/foo/deep now" sleep 20 ) &
deep=$!
( exec -a "claude -p work on request/foo.old now" sleep 20 ) &
old=$!
( exec -a "claude -p work on request/foo now" sleep 20 ) &
exact=$!
sleep 0.5
got="$(claude_pids 'request/foo')"
kill $deep $old $exact 2>/dev/null
case " $got " in *" $deep "*) echo MATCHED_DEEP ;; esac
case " $got " in *" $old "*) echo MATCHED_OLD ;; esac
case " $got " in *" $exact "*) echo MATCHED_EXACT ;; esac
`], { encoding: 'utf8' })
    expect(r.stdout).toContain('MATCHED_EXACT')
    expect(r.stdout).not.toContain('MATCHED_DEEP')
    expect(r.stdout).not.toContain('MATCHED_OLD')
  })
})

describe('a request whose CI is red', () => {
  // The `fix` outcome left the request live and buildable, so every later
  // dispatch launched a FULL re-implementation builder on the open PR
  // branch before it re-judged CI. `FIX_GIVE_UP` bounded only the
  // fix-only builders and `bump_attempt` was never reached on this path.
  // Measured three ways: 3 full + 2 fix launches over three dispatches; a
  // full→fixonly→full→fixonly sequence, four opus runs for one red PR, two
  // of them told to follow TDD on a tree that already has the feature; and
  // a branch grown to five commits of repeated work.
  //
  // The existing test passed anyway, because it only counted the fix-only
  // launches.

  const RED_GH = `
case "$1 $2" in
  "pr list") printf '7 OPEN\\n' ;;
  "run list") printf '101\\n' ;;
  "run watch") exit 0 ;;
  "run view") printf 'FAILED: the thing that broke\\n' ;;
  "pr checks") printf 'shared\\tpass\\t1m\\tu\\ntally\\tfail\\t1m\\tu\\n'; exit 1 ;;
  "pr merge") exit 0 ;;
esac
exit 0
`

  beforeEach(() => {
    build({ requests: { 'a-thing.md': READY('A thing') } })
    box.stub('gh', RED_GH)
    box.stub('claude', `
branch="$(git rev-parse --abbrev-ref HEAD)"
echo x >> built.txt
git add -A
git -c user.name=b -c user.email=b@b commit -qm "build: $branch"
git push -q origin "HEAD:refs/heads/$branch" 2>/dev/null || true
exit 0
`)
  })

  const launches = () => {
    const all = box.log().split('\n').filter((l) => l.startsWith('claude '))
    return {
      full: all.filter((l) => l.includes('request-builder.md') && !l.includes('FIX-ONLY')).length,
      fix: all.filter((l) => l.includes('FIX-ONLY')).length,
    }
  }

  it('never gets a second full builder, only fix-only ones', () => {
    run('dispatch.sh', { timeout: 120_000 })
    run('dispatch.sh', { timeout: 120_000 })
    run('dispatch.sh', { timeout: 120_000 })
    const n = launches()
    expect(n.full, 'a full re-implementation builder ran more than once').toBe(1)
    expect(n.fix).toBeGreaterThan(0)
  })

  it('does not grow the branch with repeated work', () => {
    run('dispatch.sh', { timeout: 120_000 })
    run('dispatch.sh', { timeout: 120_000 })
    const n = spawnSync('git',
      ['-C', join(box.root, '.cache/mtg-intake/wt/slot1'), 'rev-list', '--count', 'origin/main..HEAD'],
      { encoding: 'utf8' }).stdout.trim()
    // One build plus at most FIX_GIVE_UP fixes. It was growing without
    // bound before, because each dispatch added a full re-implementation
    // on top of the fixes.
    expect(Number(n)).toBeLessThanOrEqual(3)
  })

  it('is held once the fix bound is spent, and filed', () => {
    run('dispatch.sh', { env: { INTAKE_FIX_GIVE_UP: '1' }, timeout: 120_000 })
    run('dispatch.sh', { env: { INTAKE_FIX_GIVE_UP: '1' }, timeout: 120_000 })
    run('dispatch.sh', { env: { INTAKE_FIX_GIVE_UP: '1' }, timeout: 120_000 })
    logged('still red')
    expect(existsSync(box.path('requests/done/a-thing.md'))).toBe(true)
  })
})

describe('an outcome nobody counted', () => {
  // Five non-terminal exits from `build_one` bumped no counter and set no
  // hold, so a request was rebuilt forever, once per launchd event. `gh`
  // failing only on `pr merge` produced four dispatches, four full
  // builders and a branch at four commits, with the request still live.

  it('counts a merge that would not go through', () => {
    build({ requests: { 'a-thing.md': READY('A thing') } })
    box.stub('claude', BUILDER)
    box.stub('gh', `
case "$1 $2" in
  "pr list") printf '7 OPEN\\n' ;;
  "run list") printf '101\\n' ;;
  "run watch") exit 0 ;;
  "pr checks") printf 'shared\\tpass\\t1m\\tu\\n'; exit 0 ;;
  "pr merge") exit 9 ;;
esac
exit 0
`)
    for (let i = 0; i < 4; i += 1) run('dispatch.sh', { timeout: 120_000 })
    logged('GIVING UP on a-thing')
    const full = box.log().split('\n')
      .filter((l) => l.startsWith('claude ') && l.includes('request-builder.md')).length
    expect(full).toBeLessThanOrEqual(3)
  })

  it('counts a gh that will not answer about the pull request', () => {
    build({ requests: { 'a-thing.md': READY('A thing') } })
    box.stub('claude', BUILDER)
    box.stub('gh', 'case "$1 $2" in "pr list") exit 4 ;; esac\nexit 0')
    for (let i = 0; i < 4; i += 1) run('dispatch.sh', { timeout: 120_000 })
    logged('GIVING UP on a-thing')
  })

  it('counts a slot that could not be saved, instead of failing silently forever', () => {
    // R10 made the machine safe and silently inert: `prepare_slot` refused
    // to reset an unsaved slot, and then nothing counted it, nothing was
    // held, and nothing was notified. With MAX_BUILDERS=1 every request
    // failed identically forever, the only record a line in a log nobody
    // reads. A fix that moves the failure rather than removing it does not
    // count.
    build({ requests: { 'a-thing.md': READY('A thing') } })
    box.stub('gh', ghStub({ state: 'NONE' }))
    box.stub('claude', BUILDER_STALLS)
    run('dispatch.sh')
    box.git('remote', 'set-url', 'origin', join(box.root, 'gone.git'))
    for (let i = 0; i < 3; i += 1) run('dispatch.sh')
    logged('REFUSING to reset')
    expect(box.log()).toContain('osascript')
    logged('GIVING UP on a-thing')
  })
})

describe('a held request', () => {
  // A hold was invisible: notified once, then `list_buildable` skipped it
  // with a log line only, `intake.mjs held` knew nothing about the hold
  // file, the end-of-run "needs you" loop said nothing, and `status.sh`
  // printed it as `ready`. Measured: 0 claude invocations, 0
  // notifications, dashboard said ready.

  function holdIt() {
    build({ requests: { 'a-thing.md': READY('A thing') } })
    box.stub('gh', ghStub({ state: 'NONE' }))
    box.stub('claude', BUILDER_STALLS)
    for (let i = 0; i < 3; i += 1) run('dispatch.sh')
  }

  it('says so on the dashboard rather than reading as ready', () => {
    holdIt()
    const out = spawnSync('bash', [box.path('scripts/intake/status.sh')], {
      encoding: 'utf8', cwd: box.repo, timeout: 60_000,
      env: { ...process.env, PATH: `${box.bin}:${process.env.PATH}`, HOME: box.root },
    }).stdout
    expect(out).toContain('held')
    expect(out).not.toMatch(/a-thing\s+ready/)
  })

  it('is named in the needs-you list on every later dispatch, not just once', () => {
    holdIt()
    const before = box.log().split('\n').filter((l) => l.startsWith('osascript')).length
    run('dispatch.sh')
    const after = box.log().split('\n').filter((l) => l.startsWith('osascript')).length
    expect(after).toBeGreaterThan(before)
    logged('held: ')
  })

  it('leaves the live folder at give-up, which the README promises', () => {
    holdIt()
    expect(existsSync(box.path('requests/done/a-thing.md'))).toBe(true)
    expect(existsSync(box.path('requests/a-thing.md'))).toBe(false)
  })

  it('comes back when the same request is dropped in again', () => {
    holdIt()
    // Matt re-drops it. The hold must not be dead on arrival.
    writeFileSync(box.path('requests/a-thing.md'), READY('A thing, again'))
    box.stub('claude', BUILDER)
    box.stub('gh', ghStub())
    run('dispatch.sh', { timeout: 120_000 })
    expect(box.log()).toContain('claude -p')
  })
})

describe('resetting a slot', () => {
  // The third round running with the same data-loss shape, and the general
  // version of the mistake is: a decision computed from a snapshot of
  // state that a LATER step in the same function then changes.
  //
  // `prepare_slot` chose its start ref before `salvage` pushed. So the
  // commit salvage had just made and pushed was reset away and
  // force-pushed over on the next dispatch, while the log said "nothing
  // was thrown away". Measured: `salvaged … as 50a4923 (2 commits, 1 file
  // swept up)`, then `checkout -B` with `start` still `$BASE` dropped it,
  // and `git ls-tree -r origin.git <branch>` contained neither file —
  // reachable only from the reflog.

  it('keeps the commit prepare_slot salvage just pushed', () => {
    build({ requests: { 'a-thing.md': READY('A thing') } })
    box.stub('gh', ghStub({ state: 'NONE' }))
    box.stub('claude', 'echo "work nobody committed" > wip.txt\nexit 0')

    // Run 1 with a broken origin, so the branch never reaches it and
    // `build_one`'s salvage can only tag locally.
    const realOrigin = box.origin
    box.git('remote', 'set-url', 'origin', join(box.root, 'gone.git'))
    run('dispatch.sh')
    logged('COULD NOT PUSH')

    // The commit that only exists locally, which is the thing that must
    // survive. Asserting on the FILE is not enough — the builder stub
    // recreates it, so the file reappears while the commit is gone.
    const slot = join(box.root, '.cache/mtg-intake/wt/slot1')
    const saved = spawnSync('git', ['-C', slot, 'rev-parse', 'HEAD'],
      { encoding: 'utf8' }).stdout.trim()
    expect(saved.length).toBe(40)

    // Origin comes back. Now the FIRST push of this branch is the one
    // `prepare_slot` does, which is the case where `start` was computed
    // before it and stayed at BASE.
    box.git('remote', 'set-url', 'origin', realOrigin)
    run('dispatch.sh', { timeout: 120_000 })

    const branch = spawnSync('node', ['scripts/intake.mjs', 'branch', 'a-thing.md'],
      { encoding: 'utf8', cwd: box.repo }).stdout.trim()
    const reachable = spawnSync('git',
      ['-C', realOrigin, 'merge-base', '--is-ancestor', saved, branch])
    expect(reachable.status,
      `${saved.slice(0, 8)} was salvaged and then reset away; it is not on ${branch}`).toBe(0)
  })

  it('refuses outright rather than resetting over work it cannot account for', () => {
    // The general guard: before any `checkout -B`, HEAD has to be an
    // ancestor of where the branch is going. This is the assertion that
    // catches the whole class, not the one instance of it.
    build()
    const slot = join(box.root, 'wt', 'slot1')
    mkdirSync(join(box.root, 'wt'), { recursive: true })
    box.git('worktree', 'add', '-q', '-b', 'request/orphan-0000000', slot, 'main')
    writeFileSync(join(slot, 'only-here.txt'), 'unreachable\n')
    spawnSync('git', ['-C', slot, 'add', '-A'], { encoding: 'utf8' })
    spawnSync('git', ['-C', slot, '-c', 'user.name=t', '-c', 'user.email=t@t',
      'commit', '-qm', 'work that exists nowhere else'], { encoding: 'utf8' })
    const head = spawnSync('git', ['-C', slot, 'rev-parse', 'HEAD'],
      { encoding: 'utf8' }).stdout.trim()

    const r = spawnSync('bash', ['-c', `
REPO=${JSON.stringify(box.repo)}
LOG=/dev/stdout
BASE=origin/main
eval "$(sed -n '/^would_lose()/,/^}/p' ${JSON.stringify(box.path('scripts/intake/dispatch.sh'))})"
if would_lose ${JSON.stringify(slot)} "$BASE"; then echo WOULD_LOSE; else echo SAFE; fi
`], { encoding: 'utf8' })
    expect(r.stdout).toContain('WOULD_LOSE')
    expect(head.length).toBe(40)
  })
})
