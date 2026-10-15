// Black-box tests for the intake shell scripts.
//
// `scripts/intake.mjs` holds the decisions and `test/intake-dispatch.test.js`
// holds them to account. But the bugs that cost real work were bugs in the
// bash around it — a lock published before its pid file, a worktree deleted
// out from under a live builder, a hook that spawned a dispatcher inside a
// builder's own worktree — and no unit test could have caught any of them,
// because none of them live in a function.
//
// So this drives the real scripts. Each test builds a throwaway git repo in a
// temp dir with a fake `requests/`, a bare `origin` to push to, and stubs for
// `claude` and `gh` earlier on `PATH` that record their argv to a file.
// Nothing here touches the real repo, the real GitHub, or the real `claude`.
//
// This file was 2,437 lines against a 2,306-line dispatcher, then 1,309
// against a ~400-line one. Most of it tested machinery that no longer
// exists: triage, worktree slots, per-request claim directories, salvage,
// time ceilings and process-group kills, `.fixme`/`.held`/`.attempts`,
// and — removed later, for the same reason — the pick lock, the resume
// pass and the attempt ledger that bounded it,
// fix-only re-dispatch, notifications, and the dispatcher's own CI wait,
// merge, request filing and deploy verification. All of that went with the
// rewrite and so did its tests. What is left is the behaviour the dispatcher
// actually has: one lock, one worktree, one agent, one log line.
//
// These run in node, not workerd — see `vitest.shell.config.js`. The worker
// suite's config excludes this file, so its floor is unaffected.

import { describe, it, expect, afterEach } from 'vitest'
import { spawnSync, spawn } from 'node:child_process'
import {
  mkdtempSync, mkdirSync, writeFileSync, readFileSync, rmSync, existsSync,
  cpSync, chmodSync, utimesSync,
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
  'scripts/intake/com.matt.mtg.intake-inbox.plist',
  'scripts/intake/inbox.mjs',
  'scripts/intake/task-status.mjs',
  'scripts/guard.mjs',
  'scripts/check-test-count.mjs',
  'test/suite-floors.json',
  'CLAUDE.md',
  '.claude/skills/mtg/SKILL.md',
  // The only agent definition there is. `request-triage.md` is gone from
  // git and from here with it.
  '.claude/agents/request-builder.md',
  '.claude/settings.json',
  'scripts/bash-deny.mjs',
  'scripts/intake/deny-bash.mjs',
]

/**
 * A request as the coordinating session writes one.
 *
 * `status: ready` is the whole gate, so the sections below are decoration
 * as far as the dispatcher is concerned — a `status: ready` file with
 * nothing but prose in it is just as buildable, and planning it is the
 * agent's job.
 */
const READY = (title) => `---
status: ready
merge: auto
---

# ${title}

Make the thing do the thing.
`

let box

/**
 * A throwaway repo with the real scripts in it.
 *
 * `dispatch.sh` and `hook.sh` both find their repo from their own path, so
 * copying them into this tree is enough to make them operate on it.
 */
function build({ requests = {}, done = {}, enabled = true } = {}) {
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
  // install.sh writes this; the dispatcher and the hook are both opt-in and
  // do nothing without it.
  if (enabled) writeFileSync(join(repo, '.intake', 'enabled'), '')

  const calls = join(root, 'calls.log')
  writeFileSync(calls, '')

  // Stubs. Each records its whole argv on one line so a test can assert on
  // what the script decided to run without anything actually running.
  const stub = (name, body) => {
    const p = join(bin, name)
    // An agent prompt is many lines long. Recorded raw it would span lines
    // in the log and no assertion could find the whole argv.
    writeFileSync(p, `#!/bin/bash\nprintf '%s %s\\n' ${JSON.stringify(name)} "$(printf '%s ' "$@" | tr '\\n' ' ')" >> ${JSON.stringify(calls)}\n${body}\n`)
    chmodSync(p, 0o755)
  }
  stub('claude', 'exit 0')
  stub('gh', 'exit 0')
  // Stubbed for every box: otherwise `tell` fires a real desktop
  // notification on the machine running the suite, and no assertion can see
  // that it happened.
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
    /**
     * Put a file on origin/main WITHOUT the local checkout noticing.
     *
     * That gap is the whole point: the local requests/ folder lags main the
     * moment an agent merges its own work, and the dispatcher decides the
     * queue from the local folder.
     */
    commitOnBase: (rel, body) => {
      const tmp = join(root, 'base-push')
      spawnSync('git', ['clone', '-q', origin, tmp], { encoding: 'utf8' })
      const g = (...a) => spawnSync('git', ['-C', tmp, ...a], { encoding: 'utf8' })
      g('config', 'user.email', 'a@b'); g('config', 'user.name', 'a')
      mkdirSync(dirname(join(tmp, rel)), { recursive: true })
      writeFileSync(join(tmp, rel), body)
      g('add', '-A'); g('commit', '-qm', `base: ${rel}`); g('push', '-q', 'origin', 'main')
      rmSync(tmp, { recursive: true, force: true })
      git('fetch', '-q', 'origin')
    },
    /**
     * Break the copied dispatcher, to prove an assertion is load-bearing.
     *
     * It THROWS when `from` is not in the script, and that is the whole
     * reason it is safe to use. A silent no-op is the worst thing a
     * mutation helper can do: the test would then run an UNMUTATED script,
     * see the behaviour it was checking for, and pass — reporting that it
     * had proved a line load-bearing when it had not touched it. That is
     * the "green test that proves nothing" this repo has shipped six of.
     */
    breakDispatcher: (from, to) => {
      const p = join(repo, 'scripts/intake/dispatch.sh')
      const text = readFileSync(p, 'utf8')
      if (!text.includes(from)) {
        throw new Error(
          `breakDispatcher: dispatch.sh has no ${JSON.stringify(from)} to break`)
      }
      writeFileSync(p, text.replace(from, to))
    },
  }
  return box
}

/**
 * Run one of the intake scripts in the fake repo.
 *
 * The dispatcher writes its own decisions to `.intake/intake.log` and the
 * agent's output to `.intake/<name>.log`, so there is nothing on stdout
 * worth collecting — and leaving the pipe alone means `spawnSync` waits for
 * the dispatcher to exit rather than for a pipe some orphan still holds.
 */
function run(script, { env = {}, timeout = 60_000 } = {}) {
  // stderr is CAPTURED, not discarded. It used to be ignored, which is how
  // `tell: command not found` ran green through 124 tests while failing
  // nine times in production.
  return spawnSync('bash', [box.path('scripts/intake', script)], {
    encoding: 'utf8',
    timeout,
    cwd: box.repo,
    stdio: ['pipe', 'ignore', 'pipe'],
    env: {
      ...process.env,
      PATH: `${box.bin}:${process.env.PATH}`,
      HOME: box.root,
      ...env,
    },
  })
}

/** `hook.sh` reads JSON on stdin and has no agent, so it can be piped. */
function hook(cwd, filePath, extra = {}) {
  return spawnSync('bash', [join(cwd, 'scripts/intake/hook.sh')], {
    encoding: 'utf8',
    timeout: 60_000,
    cwd,
    input: JSON.stringify({ tool_input: { file_path: filePath } }),
    env: { ...process.env, PATH: `${box.bin}:${process.env.PATH}`, HOME: box.root, ...extra },
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

/** A stub `claude` that behaves like an agent: commits and pushes. */
const BUILDER = `
branch="$(git rev-parse --abbrev-ref HEAD)"
echo "x" >> built.txt
git add -A
git -c user.name=b -c user.email=b@b commit -qm "build: $branch"
git push -q origin "HEAD:refs/heads/$branch"
exit 0
`

/** `gh` told what GitHub says about the open pull requests. status.sh asks. */
function ghStub({ pr = 7, state = 'OPEN' } = {}) {
  return `
case "$1 $2" in
  "pr list") [ "${state}" = "NONE" ] || printf '${pr} ${state}\\n' ;;
esac
exit 0
`
}

/** The dispatcher's log, which is the only record of what it decided. */
const logged = (s) => expect(box.intakeLog()).toContain(s)

/** Poll until `fn` is true, or give up. Yields, rather than burning a core. */
async function until(fn, ms = 20_000) {
  const deadline = Date.now() + ms
  while (Date.now() < deadline) {
    if (fn()) return true
    await new Promise((r) => setTimeout(r, 50))
  }
  return false
}

/** Every `claude -p` the dispatcher launched, in order. */
const builders = () => box.log().split('\n').filter((l) => l.startsWith('claude '))

describe('the switches', () => {
  // There was no way to stop this machine, and then no way to start it on
  // purpose. `.intake/` is gitignored, so the committed PostToolUse hook
  // armed a fresh clone the first time any Claude session edited a request
  // file — no install, no consent. And `launchctl unload` does not stop a
  // committed hook, so the off switch has to be a file both scripts read.

  it('does nothing at all without .intake/enabled', () => {
    build({ requests: { 'a-thing.md': READY('A thing') }, enabled: false })
    const r = run('dispatch.sh')
    expect(r.status).toBe(0)
    expect(box.log()).not.toContain('claude')
    expect(box.intakeLog()).toBe('')
    // Not even the lock: it exits before taking anything.
    expect(existsSync(box.path('.intake/lock'))).toBe(false)
  })

  it('still tells the truth about what it is not doing while paused', () => {
    // Status is only written inside a dispatch, so pausing intake used to
    // freeze every row mid-flight. Matt watched a task read `in progress`
    // for three hours after the system was switched off and nothing was
    // running. A paused system has to reconcile before it stops.
    build({ requests: { 'a-thing.md': READY('A thing') } })
    box.disable()
    const r = run('dispatch.sh')

    expect(r.status).toBe(0)
    // It reconciled...
    expect(box.intakeLog()).toContain('task-status')
    // ...and still started nothing.
    expect(box.log()).not.toContain('claude')
  })

  // Matt: "any running agent should be immediately killed". A paused
  // system still has agents running, so a cancel is carried out either way.
  for (const paused of [false, true]) {
    it(`carries out a cancel from the app before it reconciles, ${paused ? 'paused' : 'running'}`, () => {
      build({ requests: { 'a-thing.md': READY('A thing') } })
      if (paused) box.disable()
      expect(run('dispatch.sh', { timeout: 110_000 }).status).toBe(0)
      const log = box.intakeLog()
      expect(log).toContain('nothing was cancelled')
      expect(log.indexOf('nothing was cancelled'), 'cancel ran after reconcile')
        .toBeLessThan(log.indexOf('nothing was reconciled'))
    })
  }

  it('stops dispatch.sh dead once .intake/disabled exists', () => {
    build({ requests: { 'a-thing.md': READY('A thing') } })
    box.disable()
    const r = run('dispatch.sh')
    expect(r.status).toBe(0)
    expect(box.log()).not.toContain('claude')
    // Not silent any more: a paused system still reconciles, so the rows do
    // not freeze mid-flight. It just starts nothing.
    expect(box.intakeLog()).toContain('task-status')
  })

  it('stops hook.sh dead, so an edit launches no dispatcher', () => {
    build({ requests: { 'a-thing.md': READY('A thing') } })
    box.disable()
    // Stand in for the dispatcher, so "it launched one" is observable.
    writeFileSync(box.path('scripts/intake/dispatch.sh'),
      `#!/bin/bash\nprintf 'DISPATCHED\\n' >> ${JSON.stringify(box.calls)}\n`)
    chmodSync(box.path('scripts/intake/dispatch.sh'), 0o755)
    const r = hook(box.repo, box.path('requests/a-thing.md'))
    expect(r.status).toBe(0)
    expect(box.log()).not.toContain('DISPATCHED')
  })

  it('is what uninstall.sh leaves behind', () => {
    build({ requests: { 'a-thing.md': READY('A thing') } })
    box.stub('launchctl', 'exit 0')
    const r = spawnSync('bash', [box.path('scripts/intake/uninstall.sh')], {
      encoding: 'utf8',
      cwd: box.repo,
      env: { ...process.env, PATH: `${box.bin}:${process.env.PATH}`, HOME: box.root },
    })
    expect(r.status).toBe(0)
    expect(existsSync(box.path('.intake/disabled'))).toBe(true)
    expect(box.log()).toContain('launchctl bootout')
    expect(box.log()).toContain('com.matt.mtg.intake-inbox')
  })

  it('is the only thing holding it back — without it, the hook dispatches', () => {
    build({ requests: { 'a-thing.md': READY('A thing') } })
    writeFileSync(box.path('scripts/intake/dispatch.sh'),
      `#!/bin/bash\nprintf 'DISPATCHED\\n' >> ${JSON.stringify(box.calls)}\n`)
    chmodSync(box.path('scripts/intake/dispatch.sh'), 0o755)
    hook(box.repo, box.path('requests/a-thing.md'))
    // nohup backgrounds it, so give it a moment to land.
    const deadline = Date.now() + 5000
    while (Date.now() < deadline && !box.log().includes('DISPATCHED')) { /* spin */ }
    expect(box.log()).toContain('DISPATCHED')
  })
})

describe('the lock', () => {
  // One at a time, because Gradle does not share a laptop. The lock is a
  // `mkdir`, which is atomic, and it is held for the WHOLE build rather
  // than for the moment it takes to start one — the agent runs in the
  // foreground for that reason. launchd's WatchPaths and the PostToolUse
  // hook both fire on the same edit, so two dispatchers racing is the
  // normal case and not an edge one.

  it('lets one dispatcher through and turns the second away', async () => {
    build({ requests: { 'a-thing.md': READY('A thing') } })
    box.stub('claude', '/bin/sleep 6\nexit 0')
    const first = spawn('bash', [box.path('scripts/intake/dispatch.sh')], {
      cwd: box.repo,
      stdio: ['pipe', 'ignore', 'ignore'],
      env: { ...process.env, PATH: `${box.bin}:${process.env.PATH}`, HOME: box.root },
    })
    const gone = new Promise((done) => first.on('exit', done))
    expect(await until(() => box.log().includes('claude ')),
      'the first dispatcher never started an agent, so there was no lock to contend')
      .toBe(true)

    const second = run('dispatch.sh')
    expect(second.status).toBe(0)
    // Exactly one agent, and the lock is why: the second one stopped before
    // it ever looked at the worktree.
    expect(builders().length).toBe(1)
    expect(box.intakeLog()).not.toContain('already has')

    await gone
    expect(existsSync(box.path('.intake/lock'))).toBe(false)
  })

  it('is released when the run finishes', () => {
    build({ requests: { 'a-thing.md': READY('A thing') } })
    box.stub('claude', BUILDER)
    run('dispatch.sh')
    logged('a-thing finished')
    expect(existsSync(box.path('.intake/lock'))).toBe(false)
  })

  it('is dropped before the agent starts, so the next dispatcher can claim', () => {
    // The lock covers choosing a request and making its worktree, and nothing
    // more. It used to be held for the WHOLE build, which made the queue
    // serial however many requests were ready — and the reason for that,
    // Gradle thrashing, is handled by a mutex around the build step instead.
    build({ requests: { 'a-thing.md': READY('A thing') } })
    const r = run('dispatch.sh')
    expect(r.status).toBe(0)
    expect(box.intakeLog()).toContain('building a-thing')
    expect(existsSync(box.path('.intake/lock'))).toBe(false)
  })
})

describe('task status in D1', () => {
  // Matt: "The system is just looking at fucking branch names?!?!" The
  // dispatcher is what starts a builder and sees it end, so it writes each
  // transition to the task's row as it happens. No token in this box's
  // HOME, so nothing reaches the network: the log says what would have
  // been written, which is what proves the dispatcher asked.

  it('writes in progress before the builder starts and what its pull request came to after', () => {
    build({ requests: { 'a-thing.md': READY('A thing') } })
    box.stub('claude', BUILDER)
    box.stub('gh', `[ "$1 $2" = "pr list" ] && echo '[{"state":"MERGED","url":"https://github.com/x/y/pull/7"}]'\nexit 0`)
    run('dispatch.sh')
    const log = box.intakeLog()
    expect(log).toContain('a-thing in progress was not written')
    expect(log).toContain('a-thing merged was not written')
    expect(log.indexOf('a-thing in progress')).toBeLessThan(log.indexOf('a-thing finished'))
    const calls = box.log().split('\n')
    const claude = calls.findIndex((l) => l.startsWith('claude '))
    const asked = calls.findIndex((l) => /^gh pr list --head request\/a-thing-[0-9a-f]{7} --state all/.test(l))
    expect(claude, 'the builder never ran').toBeGreaterThanOrEqual(0)
    expect(asked, 'gh was never asked what the pull request came to').toBeGreaterThan(claude)
  })
})

describe('refusing to run from a linked worktree', () => {
  // The PostToolUse hook is committed, so it is checked out in every agent's
  // worktree. The agent is told to move its own request file, which fires
  // the hook, which would start a SECOND dispatcher rooted at the worktree —
  // its own lock, its own `.intake/` — and launch nested agents inside the
  // live one's tree. Recursively. A linked worktree's `.git` is a file, not
  // a directory, and that is the test.

  function linkedWorktree() {
    const wt = join(box.root, 'linked')
    box.git('worktree', 'add', '-q', '-b', 'linked', wt, 'main')
    mkdirSync(join(wt, '.intake'), { recursive: true })
    writeFileSync(join(wt, '.intake', 'enabled'), '')
    writeFileSync(join(wt, 'requests', 'a-thing.md'), READY('A thing'))
    return wt
  }

  it('stops dispatch.sh dead', () => {
    build({ requests: { 'a-thing.md': READY('A thing') } })
    box.stub('claude', BUILDER)
    const wt = linkedWorktree()
    const r = spawnSync('bash', [join(wt, 'scripts/intake/dispatch.sh')], {
      encoding: 'utf8',
      cwd: wt,
      timeout: 60_000,
      stdio: ['pipe', 'ignore', 'ignore'],
      env: { ...process.env, PATH: `${box.bin}:${process.env.PATH}`, HOME: box.root },
    })
    expect(r.status).toBe(0)
    expect(box.log()).not.toContain('claude')
    expect(existsSync(join(wt, '.intake/lock'))).toBe(false)
  })

  it('stops hook.sh dead, so an agent moving a file cannot spawn a dispatcher', () => {
    build({ requests: { 'a-thing.md': READY('A thing') } })
    const wt = linkedWorktree()
    writeFileSync(join(wt, 'scripts/intake/dispatch.sh'),
      `#!/bin/bash\nprintf 'NESTED\\n' >> ${JSON.stringify(box.calls)}\n`)
    chmodSync(join(wt, 'scripts/intake/dispatch.sh'), 0o755)
    const r = hook(wt, join(wt, 'requests/a-thing.md'))
    expect(r.status).toBe(0)
    const deadline = Date.now() + 3000
    while (Date.now() < deadline) { /* give nohup a chance */ }
    expect(box.log()).not.toContain('NESTED')
  })
})

describe('asking intake.mjs', () => {
  // The dispatcher used to discard the exit status of `node
  // scripts/intake.mjs`, so node missing, a PATH the plist does not carry,
  // a parse error or ENOSPC all yielded empty stdout — and it logged
  // "nothing pending" and exited 0 with a full queue. Empty output and
  // failure must never be the same thing.

  it('reaches Matt when it gives up, rather than dying on an undefined tell', () => {
    // `tell` was called and never defined. Every notification since #43 died
    // as `tell: command not found` on a stderr launchd discards — nine times
    // in the production log — so the one path that exists to reach Matt when
    // the queue stops never reached him. The test asserts the notification
    // actually leaves, not merely that the line was executed.
    build({ requests: { 'a-thing.md': READY('A thing') } })
    box.stub('osascript', 'exit 0')
    box.stub('node', 'exit 7')               // intake.mjs cannot answer
    const r = run('dispatch.sh')

    expect(r.status).toBe(1)
    expect(box.intakeLog()).toContain('NEEDS YOU')
    // The desktop notification was actually attempted.
    expect(box.log()).toContain('osascript')
    // And nothing fell over on the way.
    expect(r.stderr || '').not.toContain('command not found')
  })

  it('fails loudly when node is not there at all', () => {
    build({ requests: { 'a-thing.md': READY('A thing') } })
    box.stub('claude', BUILDER)
    box.stub('node', 'exit 127')
    const r = run('dispatch.sh')
    expect(r.status).not.toBe(0)
    logged('intake.mjs failed')
    expect(box.log()).not.toContain('claude')
    // And the lock went with it, so a broken node cannot wedge the queue.
    expect(existsSync(box.path('.intake/lock'))).toBe(false)
  })

  it('fails loudly when intake.mjs itself blows up', () => {
    // And it has to be the SCRIPT that stops. The call is a `$( )`
    // substitution, so a guard that exited inside the subshell would kill
    // only the subshell and the run would carry on with an empty answer —
    // the original bug wearing a guard.
    build({ requests: { 'a-thing.md': READY('A thing') } })
    box.stub('claude', BUILDER)
    box.stub('node', 'echo "SyntaxError" >&2; exit 1')
    const r = run('dispatch.sh')
    expect(r.status).not.toBe(0)
    logged('intake.mjs failed')
    expect(box.log()).not.toContain('claude')
  })

  it('does not confuse an empty queue with a broken one', () => {
    // The pair for the two above is what makes the distinction mean
    // anything, and this catches a future guard that failed loudly on an
    // empty folder — the healthy idle state, which happens every time
    // launchd fires after a merge.
    build({})
    box.stub('claude', BUILDER)
    const r = run('dispatch.sh')
    expect(r.status).toBe(0)
    expect(box.intakeLog()).not.toContain('intake.mjs failed')
    expect(box.log()).not.toContain('claude')
  })
})

// One agent at a time.
//
// Matt: "I thought you optimized things. I have several tasks in flight,
// all exceeding 2 hours already." Five were running. Then: "Update it so
// only ONE task runs at a time. A very very SIMPLE queue system.
// NOTHING FANCY. JUST ONE AT A TIME".
//
// Measured from their logs, each of the five had already spent 20 to 46
// minutes inside `npm run test:everything`, and the gate needs the
// machine to itself: it holds ~/.mtg-gradle.lock across its Gradle
// suites and the device one alone is twenty minutes. Five exclusive
// 25-minute slots is two hours of queueing before anybody does any
// work. Arithmetic, not inefficiency — making the individual commands
// faster, which the daemon fix did, does not touch it. They fight for
// memory too: a gate run was killed by the system while an agent's
// device suite held the emulator, on a 16GB laptop.
describe('how many agents at a time', () => {
  /** A worktree with a live process whose argv names its request. */
  const busy = (name) => {
    mkdirSync(join(box.repo, '.intake', 'wt', name, 'requests'), { recursive: true })
    return spawn('/bin/sh', ['-c', `sleep 30; : requests/${name}.md`], {
      detached: true, stdio: 'ignore',
    })
  }

  it('starts nothing at all while one is already working', () => {
    build({
      requests: {
        'a-thing.md': READY('A thing'),
        'b-thing.md': READY('B thing'),
      },
    })
    box.stub('claude', BUILDER)
    const one = busy('a-thing')
    try {
      run('dispatch.sh')
      expect(box.log(), 'a second agent was started').not.toContain('claude ')
      expect(existsSync(box.path('.intake/wt/b-thing')), 'b-thing was claimed anyway').toBe(false)
      expect(box.intakeLog()).toMatch(/already working|at a time/i)
    } finally {
      one.kill('SIGKILL')
    }
  })

  it('takes the oldest request first, which is what first come first serve means', () => {
    // Matt: "First come first serve". The queue was `readdirSync`
    // order — filesystem order, near enough to insertion order on APFS
    // to look right and not a promise of anything. With one agent at a
    // time the order IS the feature, so it is sorted by when the
    // request file was written.
    build({ requests: { 'newer.md': READY('Newer'), 'older.md': READY('Older') } })
    box.stub('claude', BUILDER)
    const old = new Date(Date.now() - 60 * 60 * 1000)
    utimesSync(join(box.repo, 'requests', 'older.md'), old, old)

    run('dispatch.sh')

    expect(box.log(), 'it did not start the one that was sent first').toContain('requests/older.md')
  })

  it('starts one the moment nothing is running', () => {
    build({ requests: { 'a-thing.md': READY('A thing') } })
    box.stub('claude', BUILDER)
    run('dispatch.sh')
    expect(box.log()).toContain('claude ')
  })

  it('takes the cap from the environment, so it can be raised without an edit', () => {
    build({ requests: { 'a-thing.md': READY('A thing'), 'b-thing.md': READY('B thing') } })
    box.stub('claude', BUILDER)
    const one = busy('a-thing')
    try {
      run('dispatch.sh', { env: { MTG_AGENT_CAP: '2' } })
      expect(box.log(), 'the cap in the environment was ignored').toContain('claude ')
    } finally {
      one.kill('SIGKILL')
    }
  })

  it('still reconciles and sweeps while it waits', () => {
    // Holding back dispatch must not hold back the bookkeeping. Status
    // froze once for exactly this reason and Matt watched a row read
    // `in progress` for three hours with nothing running.
    build({ requests: { 'a-thing.md': READY('A thing'), 'b-thing.md': READY('B thing') } })
    box.stub('claude', BUILDER)
    const tree = box.path('.intake/wt/done-thing')
    box.git('worktree', 'add', '-q', '-b', 'request/done-thing-0000000', tree, 'main')
    box.commitOnBase('requests/done/done-thing.md', 'Merged as #1.\n')
    const one = busy('a-thing')
    try {
      run('dispatch.sh')
      expect(existsSync(tree), 'the sweep did not run while waiting').toBe(false)
    } finally {
      one.kill('SIGKILL')
    }
  })
})

describe('the agent that gets launched', () => {
  function builtRequest() {
    build({ requests: { 'a-thing.md': READY('A thing') } })
    box.stub('claude', BUILDER)
    run('dispatch.sh')
    return box.log().split('\n').find((l) => l.startsWith('claude '))
  }

  it('runs inside its own worktree under .intake/wt', () => {
    // Not in the real repo. A `claude -p` started in the checkout Matt is
    // working in would edit his tree, and a `git` command in it would move
    // his HEAD.
    build({ requests: { 'a-thing.md': READY('A thing') } })
    const pwdfile = join(box.root, 'agent-pwd.txt')
    box.stub('claude', `printf '%s\\n' "$PWD" > ${JSON.stringify(pwdfile)}\n${BUILDER}`)
    run('dispatch.sh')
    logged('building a-thing on request/a-thing-')
    expect(existsSync(box.path('.intake/wt/a-thing/.git'))).toBe(true)
    // `pwd -P` resolves symlinks and /var is one on macOS, so the tail is
    // the part worth asserting.
    expect(readFileSync(pwdfile, 'utf8').trim()).toMatch(/\/\.intake\/wt\/a-thing$/)
  })

  it('gets the model explicitly, because the agent frontmatter is inert', () => {
    // `model: opus` in `.claude/agents/request-builder.md` applies when a
    // parent spawns it through the Agent tool. This is a top-level
    // `claude -p`, so it sat there doing nothing while agents ran on the
    // CLI default.
    expect(builtRequest()).toContain('--model opus')
  })

  it('is told to batch its tool calls, in the prompt rather than only in a document', () => {
    // The rule went into CLAUDE.md and the brief, and five agents then
    // ran 184, 153, 103, 86 and 54 tool calls over exactly that many
    // turns: 0% batched, every one of them. A rule in a document the
    // agent may or may not re-read is not a mechanism — the same thing
    // Matt said about making CI police a `Red:` trailer.
    //
    // The launch prompt is the one text an agent cannot skip.
    const prompt = builtRequest()
    expect(prompt, 'nothing in the prompt says to batch').toMatch(/one message|same message|at once/i)
    expect(prompt).toMatch(/independent|do not depend|dependency/i)
  })

  it('is told the gate is local and there is no CI to wait for', () => {
    // This used to assert the opposite, and was right to at the time:
    // agents told to "watch CI" ended their turn waiting for a
    // notification a headless `claude -p` can never deliver, so the
    // prompt had to name a command that blocks in the foreground.
    //
    // CI was then switched off entirely. The command stayed in the
    // prompt and accounts for 18.7 hours over 220 calls across the agent
    // logs; it now returns "no checks reported" instantly, so it is no
    // longer slow, just false. The blocking command that matters is the
    // gate.
    const prompt = builtRequest()
    expect(prompt).toContain('npm run test:everything')
    expect(prompt, 'still telling agents to wait for CI that does not run').not.toContain('gh pr checks')
    expect(prompt, 'still telling agents to watch the deploy').not.toContain('run watch')
    expect(prompt).toContain('merge: ask')
  })

  it('forbids ScheduleWakeup and Monitor, by name', () => {
    const prompt = builtRequest()
    expect(prompt).toContain('ScheduleWakeup')
    expect(prompt).toContain('Monitor')
    expect(prompt).toContain('you get no second turn')
  })

  it('is told to file the request itself, in its own commit', () => {
    // The dispatcher used to do this after reading GitHub for the pull
    // request's state, which is most of what the deleted machinery was.
    expect(builtRequest()).toContain('requests/done/')
  })

  it('is one request even when Matt put spaces in the name', () => {
    // `for file in $(buildable)` word-split, so `fix the card page.md`
    // became four bogus requests, one of which got a worktree.
    build({ requests: { 'fix the card page.md': READY('Fix the card page') } })
    box.stub('claude', BUILDER)
    run('dispatch.sh')
    expect(builders().length).toBe(1)
    expect(existsSync(box.path('.intake/wt/fix the card page/.git'))).toBe(true)
  })
})

describe('a worktree that is already there', () => {
  // Overwriting one is how work gets lost, and deleting one is how work got
  // lost: `.intake/wt/card-page-shows-everything` held about thirty-two
  // modified files, zero commits and nothing pushed, and the next dispatch
  // deleted it. So an existing tree stops the request instead, and the
  // dispatcher says which tree to go and look at.

  function withExistingWorktree() {
    build({ requests: { 'a-thing.md': READY('A thing') } })
    box.stub('claude', BUILDER)
    const tree = box.path('.intake/wt/a-thing')
    box.git('worktree', 'add', '-q', '--detach', tree, 'main')
    writeFileSync(join(tree, 'half.txt'), 'half a change\n')
    return tree
  }

  it('hands over the live request file, not the committed one', () => {
    // Four requests sat at `status: hold` on main while the live files said
    // `ready`. Every agent read its worktree's copy, correctly refused to
    // build, and stopped — so the queue looked broken when it was obeying.
    build({ requests: { 'a-thing.md': READY('A thing') } })
    writeFileSync(
      join(box.repo, 'requests', 'a-thing.md'),
      '---\nstatus: ready\n---\n\n# A thing\n\nEDITED AFTER THE COMMIT\n',
    )
    run('dispatch.sh')
    const handed = join(box.repo, '.intake', 'wt', 'a-thing', 'requests', 'a-thing.md')
    expect(readFileSync(handed, 'utf8')).toContain('EDITED AFTER THE COMMIT')
  })

  it('will not rebuild a request main has already filed under done', () => {
    // The queue is read from the local requests/ folder, which lags main the
    // moment an agent merges its own work. Twice a finished request was
    // re-dispatched and the agent committed onto a branch whose pull request
    // had already merged — which Admin Settings shows as a task stuck in
    // `building`, because a request branch with no open PR is what that means.
    build({ requests: { 'a-thing.md': READY('A thing') } })
    // main has it filed; the laptop has not noticed yet.
    box.commitOnBase('requests/done/a-thing.md', 'Merged as #1.\n')

    run('dispatch.sh')

    expect(box.intakeLog()).toContain('already filed under requests/done')
    expect(box.log()).not.toContain('claude')
    expect(existsSync(join(box.repo, '.intake', 'wt', 'a-thing'))).toBe(false)
  })

  it('is left alone, agent or no agent, and no second one is started on it', () => {
    // There is no resume any more. The machinery that picked a stopped
    // agent's worktree back up is what ran SEVEN agents on one request in
    // four hours, and it existed only because agents kept dying of a
    // missing apps/local.properties and a suite that cannot finish inside
    // their tool limit. Both of those are fixed, so a worktree that is
    // already there is simply not the dispatcher's to take — reconcile has
    // already told the app whether an agent is alive in it.
    build({ requests: { 'a-thing.md': READY('A thing') } })
    const wt = join(box.repo, '.intake', 'wt', 'a-thing')
    mkdirSync(join(wt, 'requests'), { recursive: true })
    writeFileSync(join(wt, 'precious.txt'), 'work from the agent that stopped\n')

    run('dispatch.sh')

    expect(box.log()).not.toContain('claude')
    // and it did not touch what was already there.
    expect(readFileSync(join(wt, 'precious.txt'), 'utf8')).toContain('work from the agent that stopped')
  })

  it('is never deleted, and the request stays live so nothing is lost', () => {
    const tree = withExistingWorktree()
    run('dispatch.sh')
    expect(existsSync(join(tree, 'half.txt'))).toBe(true)
    expect(existsSync(box.path('.intake/wt/a-thing/.git'))).toBe(true)
    expect(existsSync(box.path('requests/a-thing.md'))).toBe(true)
  })
})

// The one case where a worktree IS swept, and why it is the only one.
//
// Fourteen worktrees had piled up against two live agents. Nine of them
// were for requests main had already filed under done — work that
// shipped weeks ago — and each one still held its branch, its build
// directories and a full copy of the tree. `git worktree prune` does not
// touch them, because prune is only for a worktree whose directory has
// gone; these all had their directories. They were cleared by hand, which
// is the part that must not happen again.
//
// Filed under done on main is what makes this safe: the agent files the
// request as part of the same commit it merges, so a filed request is a
// merged one, and there is nothing left in the tree that main does not
// have. Everything else about the old rule stands — an unfiled worktree
// is never touched, whatever state it is in.
describe('a worktree whose work has already shipped', () => {
  function shipped() {
    build({ requests: { 'a-thing.md': READY('A thing') } })
    const branch = 'request/a-thing-0000000'
    const tree = box.path('.intake/wt/a-thing')
    box.git('worktree', 'add', '-q', '-b', branch, tree, 'main')
    // The dispatcher's own copies, which is all a finished worktree has
    // left in it. They must not count as work.
    writeFileSync(join(tree, 'CLAUDE.md'), 'copied in by the dispatcher\n')
    box.commitOnBase('requests/done/a-thing.md', 'Merged as #1.\n')
    return { branch, tree }
  }

  it('is swept, with its branch, once main has the request filed under done', () => {
    const { branch, tree } = shipped()

    run('dispatch.sh')

    expect(existsSync(tree), 'a shipped worktree was left on disk').toBe(false)
    const branches = spawnSync('git', ['-C', box.repo, 'branch', '--list', branch], { encoding: 'utf8' })
    expect(branches.stdout.trim(), 'the branch outlived its worktree').toBe('')
    expect(box.intakeLog()).toContain('swept a-thing')
  })

  it('is left alone while an agent is still in it', () => {
    // The agent files the request in the commit it merges, and then keeps
    // going: it still has to say so and shut down. Sweeping it there would
    // delete the tree out from under a live process, which is the exact
    // way work was lost before any of this existed.
    const { tree } = shipped()
    // Compound, deliberately. `sh -c 'sleep 30 # marker'` is one simple
    // command, so the shell execs sleep over itself and the marker is gone
    // from the process list — which is the only thing pgrep can match. Both
    // tests here faked a live agent that way and neither was looking at one.
    const alive = spawn('/bin/sh', ['-c', 'sleep 30; : requests/a-thing.md'], {
      detached: true, stdio: 'ignore',
    })
    try {
      run('dispatch.sh')
      expect(existsSync(tree), 'swept a worktree with an agent still in it').toBe(true)
    } finally {
      alive.kill('SIGKILL')
    }
  })

  it('leaves a worktree alone when its request is not filed', () => {
    // The whole safety of the sweep is that filed means merged. A request
    // still live is work that may exist nowhere else.
    build({ requests: { 'a-thing.md': READY('A thing') } })
    const tree = box.path('.intake/wt/a-thing')
    box.git('worktree', 'add', '-q', '-b', 'request/a-thing-0000000', tree, 'main')
    writeFileSync(join(tree, 'precious.txt'), 'the only copy\n')

    run('dispatch.sh')

    expect(existsSync(join(tree, 'precious.txt'))).toBe(true)
  })
})

describe('what the agent is launched with', () => {
  // The deny list is string matching, and string matching was attacked with
  // eighteen spellings a model would reach for first — `eval`, `bash -c`,
  // `W=wrangler; $W deploy`, `--admin=true`, `wrangler dep""loy` — and every
  // one got through. So the first line of defence is not holding the keys.

  function withEnv(env) {
    build({ requests: { 'a-thing.md': READY('A thing') } })
    // An agent that reports its own environment back.
    box.stub('claude', `
printenv | grep -E '^(CLOUDFLARE|CF_|XDG_|GH_CONFIG_DIR)' > ${JSON.stringify(join(box.root, 'agent-env.txt'))} || true
${BUILDER}
`)
    run('dispatch.sh', { env })
    return readFileSync(join(box.root, 'agent-env.txt'), 'utf8')
  }

  it('carries no Cloudflare credentials, even when the dispatcher has them', () => {
    const env = withEnv({
      CLOUDFLARE_API_TOKEN: 'planted-a',
      CLOUDFLARE_ACCOUNT_ID: 'planted-b',
      CLOUDFLARE_API_KEY: 'planted-c',
      CLOUDFLARE_EMAIL: 'planted-d',
      CF_API_TOKEN: 'planted-e',
      CF_ACCOUNT_ID: 'planted-f',
      CF_EMAIL: 'planted-g',
    })
    for (const planted of 'abcdefg'.split('').map((c) => `planted-${c}`)) {
      expect(env, `${planted} reached the agent`).not.toContain(planted)
    }
  })

  it('has the wrangler config path pointed somewhere with no stored login in it', () => {
    // An earlier version asserted a variable wrangler 4.141 does not read
    // at all, so the thing it checked did nothing. The real config path
    // comes from `XDG_CONFIG_HOME`, plus a Keychain backend that has to be
    // turned off separately or an `oauth` login is still there.
    const env = withEnv({})
    expect(env).toContain('XDG_CONFIG_HOME')
    expect(env).toContain('.intake/void')
    expect(env).toContain('CLOUDFLARE_AUTH_USE_KEYRING=false')
  })

  it('keeps the gh login, because the agent opens and merges its own PR', () => {
    // `XDG_CONFIG_HOME` is pointed at an empty directory to take wrangler's
    // login away, and `gh` reads its own config from there too — so without
    // `GH_CONFIG_DIR` the scrub silently logs the agent out of GitHub and
    // it cannot open the pull request it exists to open.
    const env = withEnv({})
    expect(env).toMatch(/^GH_CONFIG_DIR=.+/m)
    expect(env).not.toMatch(/^GH_CONFIG_DIR=.*\.intake\/void/m)
  })
})

describe('hook.sh deciding whether a change is a request', () => {
  // `hookFires` is unit-tested in test/intake-dispatch.test.js. hook.sh's
  // USE of it was not, and the hook is the thing that actually launches a
  // bypassPermissions agent — so a hook that fired on the wrong path would
  // start one for a file nobody asked about.

  /** A stand-in dispatcher, so "it launched one" is observable. */
  function withStandIn() {
    build({ requests: { 'a-thing.md': READY('A thing') } })
    writeFileSync(box.path('scripts/intake/dispatch.sh'),
      `#!/bin/bash\nprintf 'DISPATCHED\\n' >> ${JSON.stringify(box.calls)}\n`)
    chmodSync(box.path('scripts/intake/dispatch.sh'), 0o755)
  }

  /** nohup backgrounds it, so give it a moment either way. */
  const settle = () => new Promise((r) => setTimeout(r, 2500))

  it('fires for a live request file', async () => {
    withStandIn()
    expect(hook(box.repo, box.path('requests/a-thing.md')).status).toBe(0)
    expect(await until(() => box.log().includes('DISPATCHED'), 5000)).toBe(true)
  })

  it('declines a request already filed under requests/done/', async () => {
    // The agent moves its own file there, and that edit fires this hook.
    // Firing would start a dispatcher for finished work on every merge.
    withStandIn()
    expect(hook(box.repo, box.path('requests/done/a-thing.md')).status).toBe(0)
    await settle()
    expect(box.log()).not.toContain('DISPATCHED')
  })

  it('declines a file that is not under requests/ at all', async () => {
    withStandIn()
    expect(hook(box.repo, box.path('scripts/intake.mjs')).status).toBe(0)
    await settle()
    expect(box.log()).not.toContain('DISPATCHED')
  })

  it('declines requests/README.md and a non-markdown file beside it', async () => {
    withStandIn()
    hook(box.repo, box.path('requests/README.md'))
    hook(box.repo, box.path('requests/notes.txt'))
    await settle()
    expect(box.log()).not.toContain('DISPATCHED')
  })

  it('declines a payload with no file_path in it', async () => {
    // hook.sh guards this twice — `[ -z "$path" ] && exit 0` and then
    // `hookFires('')` returning false. Breaking either one alone leaves
    // this green; it takes removing both. Worth saying, because a
    // single-line change to hook.sh cannot make this test fail.
    withStandIn()
    const r = spawnSync('bash', [box.path('scripts/intake/hook.sh')], {
      encoding: 'utf8',
      timeout: 60_000,
      cwd: box.repo,
      input: JSON.stringify({ tool_input: {} }),
      env: { ...process.env, PATH: `${box.bin}:${process.env.PATH}`, HOME: box.root },
    })
    expect(r.status).toBe(0)
    await settle()
    expect(box.log()).not.toContain('DISPATCHED')
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
    build({ requests: { 'a-thing.md': READY('A thing') } })
    box.stub('launchctl', 'exit 0')
    const r = spawnSync('bash', [box.path('scripts/intake/install.sh')], {
      encoding: 'utf8',
      cwd: box.repo,
      timeout: 60_000,
      env: { ...process.env, PATH: `${box.bin}:${process.env.PATH}`, HOME: box.root },
    })
    return {
      status: r.status,
      plist: () => readFileSync(
        join(box.root, 'Library/LaunchAgents/com.matt.mtg.intake.plist'), 'utf8'),
      runner: join(box.root, 'Library/Application Support/mtg-intake/run-intake.sh'),
    }
  }

  it('substitutes this repo path in rather than shipping somebody else\'s', () => {
    const i = install()
    expect(i.status).toBe(0)
    expect(i.plist()).not.toMatch(/@[A-Z]+@/)
    expect(i.plist()).toContain(`${box.repo}/requests`)
  })

  it('points launchd at a launcher outside the working tree', () => {
    const i = install()
    expect(i.plist()).not.toContain(`${box.repo}/scripts/intake/dispatch.sh`)
    expect(existsSync(i.runner)).toBe(true)
  })

  it('leaves a launcher that says so loudly when the dispatcher is not there', () => {
    const i = install()
    rmSync(box.path('scripts/intake/dispatch.sh'))
    const r = spawnSync('bash', [i.runner], { encoding: 'utf8', timeout: 30_000 })
    expect(r.status).toBe(0)
    const log = readFileSync(
      join(box.root, 'Library/Application Support/mtg-intake/logs/launchd.log'), 'utf8')
    expect(log).toContain('NO DISPATCHER')
  })

  it('drains the queue after a reboot, which RunAtLoad false never did', () => {
    expect(install().plist()).toMatch(/<key>RunAtLoad<\/key>\s*<true\/>/)
  })

  it('puts node on the PATH launchd gives the job', () => {
    const i = install()
    const nodeDir = spawnSync('bash', ['-c', 'dirname "$(command -v node)"'],
      { encoding: 'utf8' }).stdout.trim()
    expect(i.plist()).toContain(nodeDir)
  })

  it('writes the opt-in, because installing is the consent', () => {
    const i = install()
    expect(i.status).toBe(0)
    expect(existsSync(box.path('.intake/enabled'))).toBe(true)
  })

  it('installs a second job that collects tasks sent from the app every five minutes', () => {
    const i = install()
    expect(i.status).toBe(0)
    const plist = join(box.root, 'Library/LaunchAgents/com.matt.mtg.intake-inbox.plist')
    const inbox = readFileSync(plist, 'utf8')
    expect(inbox).not.toMatch(/@[A-Z]+@/)
    expect(inbox).toMatch(/<key>StartInterval<\/key>\s*<integer>300<\/integer>/)
    expect(inbox).toContain(`${box.repo}/scripts/intake/inbox.mjs`)
    expect(box.log()).toContain(`launchctl load ${plist}`)
  })

  it('turns the off switch back off, because installing is when that happens', () => {
    build({ requests: { 'a-thing.md': READY('A thing') } })
    box.disable()
    box.stub('launchctl', 'exit 0')
    spawnSync('bash', [box.path('scripts/intake/install.sh')], {
      encoding: 'utf8',
      cwd: box.repo,
      timeout: 60_000,
      env: { ...process.env, PATH: `${box.bin}:${process.env.PATH}`, HOME: box.root },
    })
    expect(existsSync(box.path('.intake/disabled'))).toBe(false)
  })
})

describe('status.sh', () => {
  // It used to re-implement the request predicates with grep, and the two
  // implementations DISAGREED: `grep -q '^status: needs-matt'` matches the
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
        // A half-written file with no frontmatter, and a needs-matt in the
        // BODY rather than the frontmatter.
        'prose.md': '# T\n\nmake the tiles smaller\n',
        'body.md': `${READY('Body')}\nstatus: needs-matt\n`,
        'asked.md': READY('Asked').replace('status: ready', 'status: needs-matt'),
      },
    })
    const out = status().stdout
    expect(out).toMatch(/prose\s+held: no status/)
    expect(out).toMatch(/body\s+ready/)
    expect(out).toMatch(/asked\s+needs-matt/)
  })

  it('says plainly when intake.mjs could not answer', () => {
    build({ requests: { 'a-thing.md': READY('A thing') } })
    box.stub('node', 'exit 1')
    expect(status().stdout).toContain('cannot be trusted')
  })

  it('does not cry wolf over an empty queue, which is the healthy state', () => {
    // The banner used to fire whenever the output was empty, so the one
    // line that says the dashboard is lying fired every time nothing was
    // pending — which is how people learn to ignore it.
    build({})
    const out = status().stdout
    expect(out).not.toContain('cannot be trusted')
    expect(out).toContain('REQUESTS')
    expect(out).toContain('none pending')
  })

  it('names the request an agent is on, not just that one exists', () => {
    // The dashboard's job is answering "what is happening" without opening
    // anything. A count alone cannot do that.
    build({ requests: { 'a-thing.md': READY('A thing') } })
    const out = spawnSync('bash', [box.path('scripts/intake', 'status.sh')], {
      encoding: 'utf8', cwd: box.repo,
      env: { ...process.env, PATH: `${box.bin}:${process.env.PATH}`, HOME: box.root },
    }).stdout
    // Every live agent line carries an elapsed time and a request name.
    for (const line of out.split('\n')) {
      if (!/^ {4}\d/.test(line)) continue
      expect(line).toMatch(/^ {4}[0-9:]+\s+\S+\.md$/)
    }
    expect(out).toContain('WORKTREES')
  })

  it('says plainly when intake.mjs cannot answer, instead of listing nothing', () => {
    // An empty listing and a broken one must never look the same — the
    // oldest bug in this system, and the dashboard is where it would hide.
    build({ requests: { 'a-thing.md': READY('A thing') } })
    box.stub('node', 'exit 9')
    const out = spawnSync('bash', [box.path('scripts/intake', 'status.sh')], {
      encoding: 'utf8', cwd: box.repo,
      env: { ...process.env, PATH: `${box.bin}:${process.env.PATH}`, HOME: box.root },
    }).stdout
    expect(out).toMatch(/cannot be trusted|exited 9/)
  })

  it('reports the dispatchers and agents that are actually running', () => {
    // This read `$STATE/lock`, a directory the dispatcher stopped creating
    // when it moved to lockf — so the dashboard said "nothing is
    // dispatching" through every build. Three tests asserted that dead
    // output, which is how it went unnoticed.
    build({ requests: { 'a-thing.md': READY('A thing') } })
    const out = spawnSync('bash', [box.path('scripts/intake', 'status.sh')], {
      encoding: 'utf8', cwd: box.repo,
      env: { ...process.env, PATH: `${box.bin}:${process.env.PATH}`, HOME: box.root },
    }).stdout
    expect(out).toContain('DISPATCHER')
    // It reports processes. The count itself is machine-wide — pgrep sees
    // every dispatcher on the host, including other test forks — so assert
    // the shape, not a number.
    expect(out).toMatch(/idle — no dispatcher, no agent|\d+ dispatcher\(s\), \d+ agent\(s\)/)
    // And never the lock line, which described a path nothing creates.
    expect(out).not.toContain('lock held')
  })


  it('distinguishes a failing gh from a clean queue', () => {
    // A failing `gh` used to render as a clean queue, which is the same
    // shape as "nothing is open".
    build({ requests: { 'a-thing.md': READY('A thing') } })
    box.stub('gh', 'exit 4')
    expect(status().stdout).toContain('gh exited 4')
  })

  it('calls a pull request with no checks unverified rather than green', () => {
    // This state reads as benign and is exactly what a hundred commits
    // sat in.
    build({ requests: { 'a-thing.md': READY('A thing') } })
    box.stub('gh', `printf '[{"number":7,"headRefName":"request/x","statusCheckRollup":[]}]\\n'`)
    const out = status().stdout
    expect(out).toContain('no checks')
    expect(out).toContain('not green')
  })

  it('says when the whole machine is switched off', () => {
    build({ requests: { 'a-thing.md': READY('A thing') } })
    box.stub('gh', ghStub())
    box.disable()
    expect(status().stdout).toContain('OFF')
  })

  it('says when it was never switched on, which is just as dead', () => {
    // `.intake/enabled` absent stops both scripts just as hard as
    // `disabled` present, and only one of the two was reported here.
    build({ requests: { 'a-thing.md': READY('A thing') }, enabled: false })
    const out = status().stdout
    expect(out).toContain('NOT ENABLED')
    expect(out).not.toContain('OFF (')
  })
})

// What a worktree is handed, beyond the request file.
//
// Two things an agent cannot work without and cannot get for itself.
describe('what a worktree is handed', () => {
  it('gets the Android SDK path, or no Android task can run in it at all', () => {
    // `apps/local.properties` is gitignored, as it has to be, so it exists
    // in the clone and in no worktree cut from it. All three live worktrees
    // were missing it: any Gradle task touching :androidApp died in sixteen
    // seconds on "SDK location not found", so the narrow `--tests` run the
    // instructions send agents to was never available to them either.
    build({ requests: { 'a-thing.md': READY('A thing') } })
    mkdirSync(box.path('apps'), { recursive: true })
    writeFileSync(box.path('apps/local.properties'), 'sdk.dir=/the/clone\n')

    run('dispatch.sh')

    expect(readFileSync(join(box.repo, '.intake/wt/a-thing/apps/local.properties'), 'utf8'))
      .toContain('sdk.dir=/the/clone')
  })

  it('gets the instructions as they are now, not as its branch was cut', () => {
    // An agent reads CLAUDE.md out of the tree it works in.
    // `remove-task-title` nearly died an eighth time on this: main had just
    // banned the suite that killed its seven previous agents, and its own
    // worktree still said to run it.
    build({ requests: { 'a-thing.md': READY('A thing') } })
    writeFileSync(box.path('CLAUDE.md'), '# the law\n\nNever run the screens suite locally.\n')

    run('dispatch.sh')

    expect(readFileSync(join(box.repo, '.intake/wt/a-thing/CLAUDE.md'), 'utf8'))
      .toContain('Never run the screens suite')
  })

  it('builds anyway when the clone has no SDK path, because most requests are not Android', () => {
    build({ requests: { 'a-thing.md': READY('A thing') } })

    run('dispatch.sh')

    expect(box.log()).toContain('claude')
  })
})

// A worktree nobody is working in says so.
//
// There is no resume: the machinery that picked a stopped agent's
// worktree back up is what ran seven agents on one request in four hours,
// and it went in the strip. The consequence nobody wrote down is that a
// dead agent's worktree blocks its request FOREVER — the dispatcher skips
// anything with a worktree, so the request is never claimed again and
// nothing anywhere says why.
//
// Two tasks sat at `in review` for three hours with no agent alive on
// them. The only reason it came to light is that Matt asked.
describe('a worktree with nobody in it', () => {
  it('marks the task paused, so somebody knows it needs picking up', () => {
    build({ requests: { 'a-thing.md': READY('A thing') } })
    // A stopped agent's worktree: it exists, nothing is running in it.
    mkdirSync(join(box.repo, '.intake', 'wt', 'a-thing', 'requests'), { recursive: true })

    run('dispatch.sh')

    // task-status.mjs runs for real here and has no credential in a box,
    // so what it writes to the log is the proof it was asked at all.
    // `paused`, not `blocked`. A worktree with work in it and no agent
    // is recoverable by definition — another agent picks it up. Blocked
    // is reserved for what cannot be recovered at all, and writing it
    // here made tasks flap in and out of it once a minute.
    expect(box.intakeLog(), 'nothing told D1 the task had stalled')
      .toMatch(/a-thing paused|paused was not written/)
    expect(box.intakeLog(), 'a recoverable stall was called blocked')
      .not.toMatch(/a-thing blocked/)
    // And still no second agent on it.
    expect(box.log()).not.toContain('claude ')
  })

  it('says nothing when an agent is alive in there', () => {
    build({ requests: { 'a-thing.md': READY('A thing') } })
    const wt = join(box.repo, '.intake', 'wt', 'a-thing')
    mkdirSync(join(wt, 'requests'), { recursive: true })
    // A process whose command line names the request, which is how the
    // dispatcher tells a live agent from a dead one.
    // Compound, deliberately. `sh -c 'sleep 30 # marker'` is one simple
    // command, so the shell execs sleep over itself and the marker is gone
    // from the process list — which is the only thing pgrep can match. Both
    // tests here faked a live agent that way and neither was looking at one.
    const alive = spawn('/bin/sh', ['-c', 'sleep 30; : requests/a-thing.md'], {
      detached: true, stdio: 'ignore',
    })
    try {
      run('dispatch.sh')
      expect(box.intakeLog(), 'a live agent was called stalled')
        .not.toMatch(/a-thing paused/)
    } finally {
      alive.kill('SIGKILL')
    }
  })
})

// The task row says WHY the agent stopped.
//
// One morning every task failed at once and every row said the same
// thing: "stopped with work left in its worktree". The actual reason was
// the last line of each agent's own log — "Not logged in · Please run
// /login", because Matt's Claude session had expired overnight and a
// headless agent cannot refresh one. Nothing read it, so the rows could
// not tell a crashed build from a login that needed renewing.
// Work that is finished and nobody merged.
//
// `i-can-t-tap-done-tasks-to-look-at-the-details` took 52.2 hours of
// wall clock and 170 turns of actual work. The rest was its pull request
// sitting there as a draft with nobody on it. Three tasks account for 76
// of the 190 hours this repo has spent, all the same way: the building
// finished, and then nothing happened.
//
// Nothing noticed, because a worktree that already exists is not the
// dispatcher's to take — that rule is what stopped seven agents running
// on one request, and it stands. But "an agent is mid-build" and "the
// work is done and unmerged" are different states, and only the first
// one wants leaving alone.
//
// So: an open pull request, no agent, and the tree untouched for a
// while, gets ONE finisher. One, guarded by a marker that is never
// cleared, because a loop here is the failure that cost four hours.
describe('a pull request nobody finished', () => {
  const abandoned = (name = 'a-thing') => {
    const wt = join(box.repo, '.intake', 'wt', name)
    mkdirSync(join(wt, 'requests'), { recursive: true })
    // Old enough that nothing is about to touch it.
    const old = new Date(Date.now() - 60 * 60 * 1000)
    utimesSync(wt, old, old)
    return wt
  }

  it('starts one finisher when the pull request is open and nobody is on it', () => {
    build({ requests: { 'a-thing.md': READY('A thing') } })
    box.stub('claude', BUILDER)
    box.stub('gh', `[ "$1 $2" = "pr list" ] && printf '%s\\n' '[{"number":7,"state":"OPEN","headRefName":"request/a-thing-0000000"}]'\nexit 0`)
    abandoned()

    run('dispatch.sh')

    const launched = box.log().split('\n').find((l) => l.startsWith('claude '))
    expect(launched, 'nothing was started on an abandoned pull request').toBeTruthy()
    expect(launched, 'the finisher was not told it is finishing, not building').toMatch(/finish|merge/i)
  })

  it('starts exactly one, ever, however many times the timer fires', () => {
    // The whole reason this is allowed to exist. Seven agents once ran
    // on one request because something kept picking it back up.
    build({ requests: { 'a-thing.md': READY('A thing') } })
    box.stub('claude', BUILDER)
    box.stub('gh', `[ "$1 $2" = "pr list" ] && printf '%s\\n' '[{"number":7,"state":"OPEN","headRefName":"request/a-thing-0000000"}]'\nexit 0`)
    abandoned()

    run('dispatch.sh')
    const first = box.log().split('\n').filter((l) => l.startsWith('claude ')).length
    run('dispatch.sh')
    run('dispatch.sh')
    const after = box.log().split('\n').filter((l) => l.startsWith('claude ')).length

    expect(first, 'the first tick started nothing').toBe(1)
    expect(after, 'it started another finisher on a later tick').toBe(1)
  })

  it('leaves a tree alone while an agent is still in it', () => {
    build({ requests: { 'a-thing.md': READY('A thing') } })
    box.stub('claude', BUILDER)
    box.stub('gh', `[ "$1 $2" = "pr list" ] && printf '%s\\n' '[{"number":7,"state":"OPEN","headRefName":"request/a-thing-0000000"}]'\nexit 0`)
    abandoned()
    const alive = spawn('/bin/sh', ['-c', 'sleep 30; : requests/a-thing.md'], {
      detached: true, stdio: 'ignore',
    })
    try {
      run('dispatch.sh')
      expect(box.log()).not.toContain('claude ')
    } finally {
      alive.kill('SIGKILL')
    }
  })

  it('leaves a tree alone that was touched a moment ago', () => {
    // An agent that has just died still has a warm worktree, and the
    // next tick is a minute away. Waiting costs nothing and starting on
    // top of a half-written tree costs everything.
    build({ requests: { 'a-thing.md': READY('A thing') } })
    box.stub('claude', BUILDER)
    box.stub('gh', `[ "$1 $2" = "pr list" ] && printf '%s\\n' '[{"number":7,"state":"OPEN","headRefName":"request/a-thing-0000000"}]'\nexit 0`)
    mkdirSync(join(box.repo, '.intake', 'wt', 'a-thing', 'requests'), { recursive: true })

    run('dispatch.sh')

    expect(box.log()).not.toContain('claude ')
  })

  it('does not finish a tree with no pull request at all', () => {
    // That one is a stalled build, which is `paused` and wants a person.
    build({ requests: { 'a-thing.md': READY('A thing') } })
    box.stub('claude', BUILDER)
    abandoned()

    run('dispatch.sh')

    expect(box.log()).not.toContain('claude ')
  })
})

describe('why an agent stopped', () => {
  const stopped = (name, lastWords) => {
    const wt = join(box.repo, '.intake', 'wt', name)
    mkdirSync(join(wt, 'requests'), { recursive: true })
    if (lastWords !== undefined) {
      writeFileSync(join(box.repo, '.intake', `${name}.log`),
        `{"type":"result","is_error":true,"result":"${lastWords}"}\n`)
    }
  }

  it('carries the agent\'s own last words onto the row', () => {
    build({ requests: { 'a-thing.md': READY('A thing') } })
    stopped('a-thing', 'Not logged in · Please run /login')

    run('dispatch.sh')

    expect(box.intakeLog(), 'the row did not say why it stopped')
      .toContain('Not logged in')
  })

  it('still says something useful when the log has no reason in it', () => {
    build({ requests: { 'a-thing.md': READY('A thing') } })
    stopped('a-thing')

    run('dispatch.sh')

    expect(box.intakeLog()).toMatch(/a-thing paused|paused was not written/)
  })

  it('says nothing at all when the agent left a pull request behind', () => {
    // The flap this caused: reconcile reads an open pull request and
    // writes `in review`, then this path sees a worktree with nobody in
    // it and writes `paused` straight over the top, and the next tick
    // does both again. `the-ui-retry-policy-has-never-worked` went round
    // that loop once a minute for hours and the row was wrong half the
    // time.
    //
    // A worktree with no agent and an open pull request is not stalled.
    // The agent finished, pushed, and stopped — which is what `in review`
    // means. Only a worktree with nothing to show is stalled.
    build({ requests: { 'a-thing.md': READY('A thing') } })
    stopped('a-thing', 'all done')
    box.stub('gh', `[ "$1 $2" = "pr list" ] && printf '%s\\n' '[{"number":7,"state":"OPEN","headRefName":"request/a-thing-0000000"}]'\nexit 0`)

    run('dispatch.sh')

    expect(box.intakeLog(), 'a finished agent was called stalled')
      .not.toMatch(/a-thing paused/)
  })
})
