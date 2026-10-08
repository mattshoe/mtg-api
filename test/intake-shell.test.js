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
import { spawnSync } from 'node:child_process'
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
    writeFileSync(p, `#!/bin/bash\nprintf '%s %s\\n' ${JSON.stringify(name)} "$*" >> ${JSON.stringify(calls)}\n${body}\n`)
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
