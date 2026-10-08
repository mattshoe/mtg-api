// Black-box tests for the intake shell scripts.
//
// `scripts/intake.mjs` holds the decisions and `test/intake-dispatch.test.js`
// holds them to account. But the bugs that cost real work were bugs in the
// bash around it — a lock published before its pid file, a worktree deleted
// out from under a live builder, `gh pr list --state open` that cannot see a
// merged pull request — and no unit test could have caught any of them,
// because none of them live in a function.
//
// So this drives the real scripts. Each test builds a throwaway git repo in a
// temp dir with a fake `requests/`, a bare `origin` to push to, and stubs for
// `claude`, `gh` and `osascript` earlier on `PATH` that record their argv to a
// file. Nothing here touches the real repo, the real GitHub, or the real
// `claude`.
//
// This file was 2,437 lines against a 2,306-line dispatcher. Most of it tested
// machinery that no longer exists: slots, per-request claim directories,
// salvage, start-ref juggling, `.fixme`/`.held`/`.attempts`, fix-only
// re-dispatch, the dispatcher's CI wait and its deploy verification. All of
// that went with the rewrite, and so did the tests for it. What is left is the
// behaviour the 258-line dispatcher actually has.
//
// These run in node, not workerd — see `vitest.shell.config.js`. The worker
// suite's config excludes this file, so its floor is unaffected.

import { describe, it, expect, afterEach } from 'vitest'
import { spawnSync, spawn } from 'node:child_process'
import {
  mkdtempSync, mkdirSync, writeFileSync, readFileSync, rmSync, existsSync,
  cpSync, chmodSync,
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
  // Everything `equipped` requires has to be here, or the guard passes in
  // every test for the wrong reason. The Part D files were the ones
  // missing: a worktree without them runs bypassPermissions with the deny
  // hook silently absent, and no test noticed.
  '.claude/agents/request-triage.md',
  '.claude/settings.json',
  'scripts/bash-deny.mjs',
  'scripts/intake/deny-bash.mjs',
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
    /** Break the copied dispatcher, to prove a test can fail. */
    breakDispatcher: (from, to) => {
      const p = join(repo, 'scripts/intake/dispatch.sh')
      writeFileSync(p, readFileSync(p, 'utf8').replace(from, to))
    },
  }
  return box
}

/**
 * Run one of the intake scripts in the fake repo.
 *
 * `stdio` throws the dispatcher's stdout away on purpose, and that is not a
 * tidiness choice. `agent_with_ceiling` starts a `sleep $((MAX_MINUTES*60))`
 * watchdog inside a backgrounded subshell and signals the subshell when the
 * agent returns. The subshell dies; its `sleep` child is orphaned and still
 * holds the stdout it inherited. Piped, `spawnSync` waits for that pipe to
 * close rather than for the dispatcher to exit — so every test here sat out
 * its own timeout and the suite took as long as the ceiling. Measured: 15s
 * against a 15s timeout piped, 0.2s with stdout left alone.
 */
function run(script, { env = {}, timeout = 60_000 } = {}) {
  return spawnSync('bash', [box.path('scripts/intake', script)], {
    encoding: 'utf8',
    timeout,
    cwd: box.repo,
    stdio: ['pipe', 'ignore', 'ignore'],
    env: {
      ...process.env,
      PATH: `${box.bin}:${process.env.PATH}`,
      // One minute, so the orphaned watchdog above dies in a minute rather
      // than sitting out the three-hour production ceiling. Every stub
      // builder here finishes in well under a second.
      INTAKE_MAX_MINUTES: '1',
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

/** A stub `claude` that behaves like a builder: commits and pushes. */
const BUILDER = `
branch="$(git rev-parse --abbrev-ref HEAD)"
echo "x" >> built.txt
git add -A
git -c user.name=b -c user.email=b@b commit -qm "build: $branch"
git push -q origin "HEAD:refs/heads/$branch"
exit 0
`

/** `gh` told what GitHub says about the branch's pull request. */
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

describe('the kill switch', () => {
  // There was no way to stop this machine. `launchctl unload` leaves the
  // committed PostToolUse hook in place, so editing any requests/*.md from
  // any Claude session in the repo still spawned a bypassPermissions builder.

  it('stops dispatch.sh dead, so no triage and no builder run', () => {
    build({ requests: { 'a-thing.md': READY('A thing') } })
    box.disable()
    const r = run('dispatch.sh')
    expect(r.status).toBe(0)
    expect(box.log()).not.toContain('claude')
    expect(box.intakeLog()).toBe('')
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
  })

  it('is the only thing holding it back — without it, both go', () => {
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
  // The lock directory used to be published nine lines before its pid file,
  // so in the normal hook+launchd double-fire the second dispatcher saw a
  // pidless lock, declared it stale, deleted it, and proceeded. Two
  // dispatchers then triaged and built the same requests.

  /** A live process whose argv reads as a dispatcher, holding the lock. */
  function lockHeldByALiveDispatcher() {
    const stand = join(box.root, 'dispatch.sh')
    writeFileSync(stand, '#!/bin/bash\nsleep 45\n')
    chmodSync(stand, 0o755)
    const held = spawn('bash', [stand], { detached: true, stdio: 'ignore' })
    held.unref()
    mkdirSync(box.path('.intake/lock'))
    writeFileSync(box.path('.intake/lock/pid'), String(held.pid))
    return held
  }

  it('lets one dispatcher through and turns the second away', () => {
    build({ requests: { 'a-thing.md': READY('A thing') } })
    box.stub('claude', BUILDER)
    box.stub('gh', ghStub())
    const held = lockHeldByALiveDispatcher()

    const r = run('dispatch.sh')
    held.kill('SIGKILL')
    expect(r.status).toBe(0)
    expect(box.log()).not.toContain('claude')
    expect(existsSync(box.path('.intake/lock'))).toBe(true)
  })

  it('treats a lock with no pid file as LIVE, never as stale', () => {
    // This is the double-fire window: the lock exists and the dispatcher
    // that made it has not written its pid yet. The second dispatcher used
    // to read it as abandoned and delete it.
    build({ requests: { 'a-thing.md': READY('A thing') } })
    box.stub('claude', BUILDER)
    box.stub('gh', ghStub())
    mkdirSync(box.path('.intake/lock'))

    const r = run('dispatch.sh')
    expect(r.status).toBe(0)
    expect(box.log()).not.toContain('claude')
    expect(existsSync(box.path('.intake/lock'))).toBe(true)
    expect(box.intakeLog()).not.toContain('clearing a lock')
  })

  it('clears a lock whose dispatcher is gone, so a kill -9 does not wedge it', () => {
    build({ requests: { 'a-thing.md': READY('A thing') } })
    box.stub('claude', BUILDER)
    box.stub('gh', ghStub())
    mkdirSync(box.path('.intake/lock'))
    writeFileSync(box.path('.intake/lock/pid'), '999999')

    run('dispatch.sh')
    logged('clearing a lock whose dispatcher is gone')
    expect(box.log()).toContain('claude')
  })

  it('clears a lock whose pid was reused by something that is not a dispatcher', () => {
    // `kill -0` on a reused pid succeeds, so liveness alone wedged the
    // queue permanently after a reboot. `process.pid` here is vitest's
    // node, which is alive and is not a dispatcher.
    build({ requests: { 'a-thing.md': READY('A thing') } })
    box.stub('claude', BUILDER)
    box.stub('gh', ghStub())
    mkdirSync(box.path('.intake/lock'))
    writeFileSync(box.path('.intake/lock/pid'), String(process.pid))

    run('dispatch.sh')
    logged('clearing a lock whose dispatcher is gone')
    expect(box.log()).toContain('claude')
  })
})

describe('refusing to run from a linked worktree', () => {
  // The PostToolUse hook is committed, so it is checked out in every builder
  // worktree. The builder is told to edit its own request file, which fires
  // the hook, which would start a SECOND dispatcher rooted at the worktree —
  // its own lock, its own `.intake/` — running a triage agent inside the live
  // builder's tree and then launching nested builders. Recursively.

  function linkedWorktree() {
    const wt = join(box.root, 'linked')
    box.git('worktree', 'add', '-q', '-b', 'linked', wt, 'main')
    mkdirSync(join(wt, '.intake'), { recursive: true })
    writeFileSync(join(wt, 'requests', 'a-thing.md'), READY('A thing'))
    return wt
  }

  it('stops dispatch.sh dead', () => {
    build({ requests: { 'a-thing.md': READY('A thing') } })
    box.stub('claude', BUILDER)
    box.stub('gh', ghStub())
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
  })

  it('stops hook.sh dead, so a builder editing a file cannot spawn a dispatcher', () => {
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
  // `ask()` used to discard the exit status of `node scripts/intake.mjs`, so
  // node missing, a PATH the plist does not carry, a parse error, a wrong
  // branch checked out or ENOSPC all yielded empty stdout — and the
  // dispatcher logged "nothing pending" and exited 0 with a full queue.
  // Empty output and failure must never be the same thing anywhere here.

  it('fails loudly when node is not there at all', () => {
    build({ requests: { 'a-thing.md': READY('A thing') } })
    box.stub('claude', BUILDER)
    box.stub('gh', ghStub())
    box.stub('node', 'exit 127')
    const r = run('dispatch.sh')
    expect(r.status).not.toBe(0)
    logged('intake.mjs pending failed (127)')
    logged('intake stopped')
    expect(box.log()).toContain('osascript')
    expect(box.log()).not.toContain('claude')
  })

  it('fails loudly when intake.mjs itself blows up', () => {
    // And it has to be the SCRIPT that stops, not a subshell. Every call
    // site is a `$( )` substitution, so an `exit` inside `ask` would kill
    // only the subshell and the run would carry on with an empty answer —
    // which is the original bug wearing a guard.
    build({ requests: { 'a-thing.md': READY('A thing') } })
    box.stub('claude', BUILDER)
    box.stub('gh', ghStub())
    box.stub('node', 'echo "SyntaxError" >&2; exit 1')
    const r = run('dispatch.sh')
    expect(r.status).not.toBe(0)
    logged('intake stopped')
    expect(box.intakeLog()).not.toContain('nothing pending')
    expect(box.log()).not.toContain('claude')
  })

  it('does not confuse an empty queue with a broken one', () => {
    // The pair with the two tests above is what makes the distinction
    // mean anything, and this would catch a future `ask` that failed
    // loudly on an empty folder.
    build({})
    box.stub('claude', BUILDER)
    box.stub('gh', ghStub())
    const r = run('dispatch.sh')
    expect(r.status).toBe(0)
    logged('nothing pending')
    expect(box.intakeLog()).not.toContain('intake cannot run')
  })
})

describe('a request that gets built', () => {
  function builtRequest(gh = {}) {
    build({ requests: { 'a-thing.md': READY('A thing') } })
    box.stub('claude', BUILDER)
    box.stub('gh', ghStub(gh))
    run('dispatch.sh')
    return box.log().split('\n').find((l) => l.startsWith('claude '))
  }

  it('hands the builder its request from outside requests/', () => {
    // The builder committed the live request file onto the branch it
    // merged, so main permanently carried finished requests and every new
    // worktree was seeded with them — which is the fuel for the recursive
    // hook.
    const prompt = builtRequest()
    expect(prompt).toBeDefined()
    expect(prompt).not.toContain('requests/a-thing.md')
    expect(prompt).toContain('.intake/handed/a-thing.md')
  })

  it('passes the model explicitly, because the agent frontmatter is inert', () => {
    expect(builtRequest()).toContain('--model opus')
  })

  it('tells the builder to block on gh pr checks and merge itself', () => {
    // The rule was never the problem, the mechanism was: builders told to
    // "watch CI" ended their turn waiting for a notification that headless
    // `claude -p` can never deliver, and three of four died that way. The
    // prompt has to name a command that blocks in the foreground.
    const prompt = builtRequest()
    expect(prompt).toContain('gh pr checks')
    expect(prompt).toContain('--watch')
    expect(prompt).toContain('gh pr merge')
  })

  it('forbids ScheduleWakeup, Monitor and backgrounding, by name', () => {
    const prompt = builtRequest()
    expect(prompt).toContain('ScheduleWakeup')
    expect(prompt).toContain('Monitor')
    expect(prompt).toContain('do not background a build')
  })

  it('works in a worktree under .intake/wt, one per request', () => {
    builtRequest()
    expect(existsSync(box.path('.intake/wt/a-thing/.git'))).toBe(true)
  })

  it('is one request even when Matt put spaces in the name', () => {
    // `for file in $(buildable)` word-split, so `fix the card page.md`
    // became four bogus requests, one of which got a worktree.
    build({ requests: { 'fix the card page.md': READY('Fix the card page') } })
    box.stub('claude', BUILDER)
    box.stub('gh', ghStub({ state: 'MERGED' }))
    run('dispatch.sh')
    const builders = box.log().split('\n').filter((l) => l.startsWith('claude '))
    expect(builders.length).toBe(1)
    expect(existsSync(box.path('requests/done/fix the card page.md'))).toBe(true)
  })
})

describe('what the pull request did', () => {
  function afterBuild(state) {
    build({ requests: { 'a-thing.md': READY('A thing') } })
    box.stub('claude', BUILDER)
    box.stub('gh', ghStub({ state }))
    run('dispatch.sh')
  }

  it('files a merged request under requests/done/', () => {
    // Two finished requests were still listed as buildable hours later,
    // each rebuild starting from a base that already had the feature — so
    // the TDD red could not reproduce and every pass opened another PR.
    afterBuild('MERGED')
    logged('merged as #7')
    expect(existsSync(box.path('requests/a-thing.md'))).toBe(false)
    expect(existsSync(box.path('requests/done/a-thing.md'))).toBe(true)
  })

  it('leaves an open one in the live folder, and says who needs looking at', () => {
    afterBuild('OPEN')
    expect(existsSync(box.path('requests/a-thing.md'))).toBe(true)
    expect(existsSync(box.path('requests/done/a-thing.md'))).toBe(false)
    logged('left #7 open')
    expect(box.log()).toContain('osascript')
  })

  it('says so when there is no pull request at all, and keeps the worktree', () => {
    afterBuild('NONE')
    expect(existsSync(box.path('requests/a-thing.md'))).toBe(true)
    logged('no pull request')
    expect(existsSync(box.path('.intake/wt/a-thing/.git'))).toBe(true)
  })

  it('never deletes the worktree, whatever the outcome', () => {
    // Invariant, learned by losing work: `.intake/wt/card-page-shows-
    // everything` held about thirty-two modified files, zero commits and
    // nothing pushed, and the next dispatch deleted it.
    afterBuild('MERGED')
    expect(existsSync(box.path('.intake/wt/a-thing/.git'))).toBe(true)
  })
})

describe('a request that says merge: ask', () => {
  it('leaves the pull request open and tells Matt', () => {
    // Nothing ever told Matt a request needed him, and four of five live
    // requests said `merge: ask` — so silence was the NORMAL outcome.
    build({ requests: { 'a-thing.md': READY('A thing').replace('merge: auto', 'merge: ask') } })
    box.stub('claude', BUILDER)
    box.stub('gh', ghStub({ state: 'OPEN' }))
    run('dispatch.sh')
    const prompt = box.log().split('\n').find((l) => l.startsWith('claude '))
    // The builder is the one that reads the mode, so the prompt has to say
    // what `merge: ask` means.
    expect(prompt).toContain('merge: ask')
    expect(existsSync(box.path('requests/done/a-thing.md'))).toBe(false)
    expect(box.log()).toContain('osascript')
  })
})

describe('a worktree that is already there', () => {
  // Overwriting one is how work gets lost, and deleting one is how work got
  // lost. Neither is allowed, so an existing tree stops the request instead.

  function withExistingWorktree() {
    build({ requests: { 'a-thing.md': READY('A thing') } })
    box.stub('claude', BUILDER)
    box.stub('gh', ghStub())
    const tree = box.path('.intake/wt/a-thing')
    box.git('worktree', 'add', '-q', '--detach', tree, 'main')
    writeFileSync(join(tree, 'half.txt'), 'half a change\n')
    return tree
  }

  it('is never overwritten — no builder starts on top of it', () => {
    const tree = withExistingWorktree()
    run('dispatch.sh')
    expect(box.log()).not.toContain('claude')
    logged('already has a worktree')
    expect(box.log()).toContain('osascript')
    expect(readFileSync(join(tree, 'half.txt'), 'utf8')).toBe('half a change\n')
  })

  it('is never deleted, and the request stays live so nothing is lost', () => {
    const tree = withExistingWorktree()
    run('dispatch.sh')
    expect(existsSync(join(tree, 'half.txt'))).toBe(true)
    expect(existsSync(box.path('requests/a-thing.md'))).toBe(true)
  })
})

describe('triage', () => {
  const RAW = '# Raw one\n\nmake the tiles smaller\n'
  const PLAN = `---\\nstatus: ready\\nmerge: auto\\n---\\n\\n# Raw one\\n\\n## Plan\\n\\np\\n\\n## Tests\\n\\nt\\n\\n## Done when\\n\\nd\\n`

  /** Triage that actually writes a plan, in whatever cwd it was given. */
  const TRIAGE = `
if [ -f requests/raw-one.md ]; then
  printf -- '${PLAN}' > requests/raw-one.md
fi
exit 0
`

  it('lands its plan back on the live file', () => {
    // Triage runs in a throwaway worktree, so its output is worth nothing
    // until it is copied back onto the file in the real repo.
    build({ requests: { 'raw-one.md': RAW } })
    box.stub('claude', TRIAGE)
    box.stub('gh', ghStub({ state: 'NONE' }))
    run('dispatch.sh', { timeout: 120_000 })
    const live = readFileSync(box.path('requests/raw-one.md'), 'utf8')
    expect(live).toContain('## Plan')
    expect(live).toContain('status: ready')
    logged('triage planned raw-one.md')
  })

  it('never writes a plan back over a request that has gone', () => {
    // Deleting a request during the five minutes triage takes un-deleted
    // it: the copy-back wrote the file straight back into `requests/`.
    build({ requests: { 'raw-one.md': RAW } })
    // Withdrawn while triage is running, by triage's own stub, so there is
    // no race to lose.
    box.stub('claude', `
case "$2" in *request-triage*) rm -f ${JSON.stringify(box.path('requests/raw-one.md'))} ;; esac
${TRIAGE}
`)
    box.stub('gh', ghStub({ state: 'NONE' }))
    run('dispatch.sh', { timeout: 120_000 })
    expect(existsSync(box.path('requests/raw-one.md'))).toBe(false)
    logged('was withdrawn during triage')
  })

  it('is told to edit in place, because a split or a combine was thrown away', () => {
    // The copy-back could only ever return the files it was handed, so
    // anything triage created was silently dropped and the originals were
    // abandoned.
    build({ requests: { 'raw-one.md': RAW } })
    box.stub('claude', TRIAGE)
    box.stub('gh', ghStub({ state: 'NONE' }))
    run('dispatch.sh', { timeout: 120_000 })
    const prompt = box.log().split('\n').find((l) => l.includes('request-triage'))
    expect(prompt).toContain('do not create new request files')
    expect(prompt).toContain('do not combine or split them')
  })
})

describe('what a builder is launched with', () => {
  // The deny list is string matching, and string matching was attacked with
  // eighteen spellings a model would reach for first — `eval`, `bash -c`,
  // `W=wrangler; $W deploy`, `--admin=true`, `wrangler dep""loy` — and every
  // one got through. So the first line of defence is not holding the keys.

  function withEnv(env) {
    build({ requests: { 'a-thing.md': READY('A thing') } })
    box.stub('gh', ghStub({ state: 'MERGED' }))
    // A builder that reports its own environment back.
    box.stub('claude', `
printenv | grep -E '^(CLOUDFLARE|CF_|WRANGLER|XDG_|INTAKE_BUILDER)' > ${JSON.stringify(join(box.root, 'builder-env.txt'))} || true
${BUILDER}
`)
    run('dispatch.sh', { env })
    return readFileSync(join(box.root, 'builder-env.txt'), 'utf8')
  }

  it('carries no Cloudflare credentials, even when the dispatcher has them', () => {
    const env = withEnv({
      CLOUDFLARE_API_TOKEN: 'a-real-looking-token',
      CLOUDFLARE_ACCOUNT_ID: 'an-account',
      CF_API_TOKEN: 'another',
    })
    expect(env).not.toContain('a-real-looking-token')
    expect(env).not.toContain('an-account')
    expect(env).not.toContain('another')
  })

  it('has the config path pointed somewhere with no stored login in it', () => {
    // An earlier version asserted `WRANGLER_HOME`, which is not a variable
    // wrangler 4.141 reads at all — so the thing it checked did nothing.
    // The real config path comes from `XDG_CONFIG_HOME`, plus a Keychain
    // backend that has to be turned off separately.
    const env = withEnv({})
    expect(env).toContain('XDG_CONFIG_HOME')
    expect(env).toContain('.intake/void')
    expect(env).toContain('CLOUDFLARE_AUTH_USE_KEYRING=false')
  })

  it('unsets every spelling of the credentials, not a short list', () => {
    // The `-u` list was already missing CF_EMAIL,
    // CLOUDFLARE_API_USER_SERVICE_KEY and WRANGLER_CF_AUTHORIZATION_TOKEN.
    const env = withEnv({
      CF_EMAIL: 'planted-a',
      CLOUDFLARE_API_USER_SERVICE_KEY: 'planted-b',
      WRANGLER_CF_AUTHORIZATION_TOKEN: 'planted-c',
    })
    for (const planted of ['planted-a', 'planted-b', 'planted-c']) {
      expect(env, `${planted} reached the builder`).not.toContain(planted)
    }
  })

  it('marks the builder so scripts holding production credentials can refuse', () => {
    // `scripts/nightly.sh` sources ~/.mtg-api.env itself and writes to
    // production with the admin password, so `bash scripts/nightly.sh`
    // reached the real database whatever the deny list said about
    // wrangler. INTAKE_BUILDER is how it can tell.
    expect(withEnv({})).toContain('INTAKE_BUILDER=1')
  })

  it('still gets everything it legitimately needs', () => {
    // The scrub is a named list, not a whitelist: it must not have emptied
    // the environment wholesale, and the build has to have worked.
    withEnv({ SOME_HARMLESS_VAR: 'kept' })
    expect(existsSync(box.path('requests/done/a-thing.md'))).toBe(true)
  })
})

describe('a worktree that is missing its instructions', () => {
  // Two builders started in worktrees with neither the skill nor their own
  // definition, were told to invoke the `mtg` skill, found nothing, and built
  // without the parity rule or the TDD discipline. Nothing failed — they
  // worked blind and looked exactly like builders that had read everything.

  function withoutFileInBase(path) {
    build({ requests: { 'a-thing.md': READY('A thing') } })
    box.stub('claude', BUILDER)
    box.stub('gh', ghStub())
    box.git('rm', '-q', path)
    box.git('-c', 'user.name=t', '-c', 'user.email=t@t', 'commit', '-qm', `no ${path}`)
    box.git('push', '-q', 'origin', 'main')
    box.git('fetch', '-q', 'origin')
    return run('dispatch.sh')
  }

  it('is not built, and does not take the dispatcher down with it', () => {
    // The guard was unreachable code that killed the dispatcher instead:
    // `intake.mjs equipped` exits 1 BY DESIGN, and `ask` exits on nonzero.
    const r = withoutFileInBase('CLAUDE.md')
    expect(r.status).toBe(0)
    logged('cannot build')
    expect(box.log()).not.toContain('claude -p')
    expect(box.log()).toContain('osascript')
  })

  it('names what is missing, rather than only that something is', () => {
    withoutFileInBase('CLAUDE.md')
    logged('CLAUDE.md')
  })

  it('needs the files that make the refusals real, not just the instructions', () => {
    // `.claude/settings.json`, `bash-deny.mjs` and `deny-bash.mjs` are the
    // enforcement mechanism. A worktree without them runs
    // bypassPermissions with the deny hook silently absent, which is the
    // same bug as the blind builder: nothing fails.
    withoutFileInBase('.claude/settings.json')
    logged('cannot build')
    logged('.claude/settings.json')
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
  })

  it('shows a live lock, so a wedged queue is not an idle one', () => {
    build({ requests: { 'a-thing.md': READY('A thing') } })
    mkdirSync(box.path('.intake/lock'))
    writeFileSync(box.path('.intake/lock/pid'), String(process.pid))
    const out = status().stdout
    expect(out).toContain('DISPATCHER')
    expect(out).toContain(`lock held by ${process.pid}, alive`)
  })

  it('says a lock whose pid is gone will be cleared', () => {
    build({ requests: { 'a-thing.md': READY('A thing') } })
    mkdirSync(box.path('.intake/lock'))
    writeFileSync(box.path('.intake/lock/pid'), '999999')
    expect(status().stdout).toContain('WHICH IS GONE')
  })

  it('treats a pidless lock as live here too', () => {
    build({ requests: { 'a-thing.md': READY('A thing') } })
    mkdirSync(box.path('.intake/lock'))
    expect(status().stdout).toContain('no pid yet')
  })

  it('prints files, commits and pushed-ness for every worktree it finds', () => {
    // A worktree is never deleted automatically, so a dead builder's tree
    // is still on disk with whatever it had — and whether that work is
    // safe is exactly whether it was pushed.
    build({ requests: { 'a-thing.md': READY('A thing') } })
    const tree = box.path('.intake/wt/a-thing')
    box.git('worktree', 'add', '-q', '-b', 'request/a-thing-x', tree, 'main')
    writeFileSync(join(tree, 'half.txt'), 'half a change\n')
    const out = status().stdout
    expect(out).toContain('WORKTREES')
    expect(out).toMatch(/a-thing.*1 changed, 0 commits, NOT ON ORIGIN/)
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
    box.disable()
    expect(status().stdout).toContain('OFF')
  })
})
