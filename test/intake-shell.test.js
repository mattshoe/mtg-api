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
  cpSync, chmodSync, appendFileSync,
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
  'test/suite-floors.json',
  'CLAUDE.md',
  '.claude/skills/mtg/SKILL.md',
  '.claude/agents/request-builder.md',
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
    writeFileSync(box.path('.intake/dispatch.lock/command'), 'bash scripts/intake/dispatch.sh')

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
    // A pid that cannot be running: pid 0 is the kernel and kill -0 on it
    // from a user process fails.
    writeFileSync(box.path('.intake/dispatch.lock/pid'), '999999')
    writeFileSync(box.path('.intake/dispatch.lock/started'), String(Math.floor(Date.now() / 1000)))
    writeFileSync(box.path('.intake/dispatch.lock/command'), 'bash scripts/intake/dispatch.sh')

    run('dispatch.sh')
    expect(box.log()).toContain('claude')
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

  it('clears a lock older than the cap even if its pid is alive', () => {
    mkdirSync(box.path('.intake/dispatch.lock'))
    writeFileSync(box.path('.intake/dispatch.lock/pid'), String(process.pid))
    writeFileSync(box.path('.intake/dispatch.lock/started'), '1')
    writeFileSync(box.path('.intake/dispatch.lock/command'), 'bash scripts/intake/dispatch.sh')

    run('dispatch.sh')
    expect(box.log()).toContain('claude')
  })

  it('is released before the dispatcher stops, and only by its owner', () => {
    run('dispatch.sh')
    expect(existsSync(box.path('.intake/dispatch.lock'))).toBe(false)
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

  it('cleans the worktree up only once everything agreed', () => {
    run('dispatch.sh')
    logged('a-thing done')
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
  beforeEach(() => {
    build({ requests: { 'a-thing.md': READY('A thing') } })
    box.stub('claude', BUILDER)
    box.stub('gh', ghStub({ state: 'MERGED' }))
  })

  it('is judged done, not unfinished', () => {
    // `gh pr list --head <branch> --state open` cannot see a MERGED pull
    // request, so a builder that did exactly what it was told — merge on
    // green — was judged unfinished every single time, and the request
    // was rebuilt from a base that already had the feature in it.
    run('dispatch.sh')
    expect(box.intakeLog()).not.toContain('NOT FINISHED')
    expect(existsSync(box.path('requests/done/a-thing.md'))).toBe(true)
  })

  it('does not try to merge it again', () => {
    run('dispatch.sh')
    expect(box.log()).not.toContain('pr merge')
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
    expect(out).toMatch(/1 changed, 0 commits, never pushed/)
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
