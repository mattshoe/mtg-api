// The Gradle mutex must not turn contention into a red suite.
//
// `guard.mjs` puts every Gradle invocation behind `~/.mtg-gradle.lock`,
// because three concurrent builds on one laptop thrash rather than going
// three times faster. The wait was five minutes and the comment beside it
// said: "If the wait runs out, the build runs anyway and the two contend
// — slower than queueing, far better than stopping."
//
// It did not. `lockf -t` exits non-zero when the wait runs out and the
// command never runs, so the suite reported:
//
//     lockf: /Users/matthewshoemaker/.mtg-gradle.lock: already locked
//     FAILED at core after 5.0m. Nothing after it ran.
//
// which is a red that has nothing to do with the code. A full gate run
// holds the lock for up to twenty minutes — the device suite alone is
// seventeen — so any second agent running the gate hit this, and paid
// for it with a whole re-run. It is part of why two agents spent three
// hours on tasks worth twenty minutes.

import { describe, it, expect, afterEach } from 'vitest'
import { spawnSync, spawn } from 'node:child_process'
import { mkdtempSync, rmSync, existsSync, readFileSync, writeFileSync, chmodSync } from 'node:fs'
import { join, dirname } from 'node:path'
import { tmpdir } from 'node:os'
import { fileURLToPath } from 'node:url'

const REPO = dirname(dirname(fileURLToPath(import.meta.url)))
const GUARD = join(REPO, 'scripts', 'guard.mjs')

let root
afterEach(() => { if (root) rmSync(root, { recursive: true, force: true }); root = null })

/**
 * Run guard against a fake `gradlew`, with the lock already held by
 * somebody else for longer than guard is willing to wait.
 */
function underAHeldLock({ waitSeconds, holdSeconds = 30 }) {
  root = mkdtempSync(join(tmpdir(), 'guard-'))
  const lock = join(root, 'gradle.lock')
  const proof = join(root, 'ran.txt')
  // A "gradlew" that only records that it ran.
  const fake = join(root, 'gradlew')
  spawnSync('bash', ['-c', `printf '#!/bin/bash\\necho ran > ${proof}\\n' > ${fake} && chmod +x ${fake}`])

  const holder = spawn('/usr/bin/lockf', ['-k', lock, 'sleep', String(holdSeconds)], {
    detached: true, stdio: 'ignore',
  })
  return {
    lock,
    proof,
    release: () => { try { process.kill(-holder.pid, 'SIGKILL') } catch { /* gone */ } holder.kill('SIGKILL') },
    run: () => spawnSync('node', [GUARD, '60', '--', fake, 'build'], {
      encoding: 'utf8',
      timeout: 120_000,
      env: {
        ...process.env,
        INTAKE_GRADLE_LOCK: lock,
        ...(waitSeconds !== undefined && { MTG_GRADLE_LOCK_WAIT: String(waitSeconds) }),
      },
    }),
  }
}

// Every Gradle command in this repo was starting a fresh JVM.
//
// `~/.gradle/gradle.properties` has carried `org.gradle.daemon=false`
// since July 2024 — a machine-wide setting with nothing to do with this
// project — and the user home file beats a project one, so nothing in
// the repo could turn it back on. Gradle said as much on every single
// run and nobody read it: "To honour the JVM settings for this build a
// single-use Daemon process will be forked... Daemon will be stopped at
// the end of the build."
//
// So a narrow test, the thing the TDD cycle runs constantly, cost 11.9s
// of which 10.0s was Gradle starting up. Agents spent 22.3 measured
// hours on narrow tests.
//
// A command-line flag is the one thing that outranks the home file, and
// guard already wraps every Gradle invocation. With `--daemon` and
// `--configuration-cache`: 11.9s to 0.8s re-running unchanged, and
// 46.5s to 24.9s for a real edit on :androidApp.
describe('how Gradle is invoked', () => {
  const shown = (extraEnv = {}) => {
    root = mkdtempSync(join(tmpdir(), 'guard-'))
    const seen = join(root, 'argv.txt')
    const fake = join(root, 'gradlew')
    // Written directly: building this through a shell let the outer
    // shell eat the "$*" before the fake ever saw it, and the test then
    // asserted against an empty file.
    writeFileSync(fake, `#!/bin/bash\nprintf '%s\\n' "$*" > ${JSON.stringify(seen)}\n`)
    chmodSync(fake, 0o755)
    const r = spawnSync('node', [GUARD, '60', '--', fake, ':core:jvmTest'], {
      encoding: 'utf8',
      timeout: 60_000,
      env: { ...process.env, INTAKE_GRADLE_LOCK: join(root, 'l.lock'), ...extraEnv },
    })
    return { r, argv: existsSync(seen) ? readFileSync(seen, 'utf8') : '' }
  }

  it('asks for the daemon, because the user home file switched it off', () => {
    const { r, argv } = shown()
    expect(r.status, r.stderr).toBe(0)
    expect(argv, 'no --daemon, so every invocation forks a single-use JVM').toContain('--daemon')
  })

  it('asks for the configuration cache, which is most of what is left', () => {
    const { argv } = shown()
    expect(argv).toContain('--configuration-cache')
  })

  it('keeps the task and its arguments', () => {
    const { argv } = shown()
    expect(argv).toContain(':core:jvmTest')
  })

  it('can be switched off, for working out whether it is the cache lying to you', () => {
    const { argv } = shown({ MTG_GRADLE_PLAIN: '1' })
    expect(argv).not.toContain('--configuration-cache')
    expect(argv).toContain(':core:jvmTest')
  })
})

describe('waiting for the Gradle lock', () => {
  it('runs the build anyway when the wait runs out, rather than failing red', () => {
    // One second of patience against a lock held for thirty. The build
    // has to happen; two builds contending is slower than queueing and
    // very much better than a suite that reports a failure it did not
    // have.
    const box = underAHeldLock({ waitSeconds: 1 })
    try {
      const r = box.run()
      expect(r.status, `guard exited ${r.status}: ${r.stderr}`).toBe(0)
      expect(existsSync(box.proof), 'the build never ran, so contention was reported as a failure').toBe(true)
      expect(r.stderr, 'it did not say it had given up waiting').toMatch(/lock/i)
    } finally {
      box.release()
    }
  })

  it('still takes the lock when it is free, rather than always barging', () => {
    root = mkdtempSync(join(tmpdir(), 'guard-'))
    const lock = join(root, 'gradle.lock')
    const proof = join(root, 'ran.txt')
    const fake = join(root, 'gradlew')
    spawnSync('bash', ['-c', `printf '#!/bin/bash\\necho ran > ${proof}\\n' > ${fake} && chmod +x ${fake}`])
    const r = spawnSync('node', [GUARD, '60', '--', fake, 'build'], {
      encoding: 'utf8', timeout: 60_000,
      env: { ...process.env, INTAKE_GRADLE_LOCK: lock },
    })
    expect(r.status, r.stderr).toBe(0)
    expect(existsSync(proof)).toBe(true)
    expect(r.stderr, 'it announced giving up on a lock that was free').not.toMatch(/gave up waiting/i)
  })

  it('never waits for the lock longer than it is allowed to live', () => {
    // The gate reported "FAILED at core after 15.0m" with nothing
    // wrong: `test:core` is `guard.mjs 900`, the lock wait was 1800,
    // and an agent held the lock. So the guard killed its own command
    // while that command was still queueing for a lock it would have
    // given up on and built anyway nine hundred seconds later.
    //
    // A wait longer than the guard's own ceiling cannot ever finish.
    // It has to leave time to actually run the build.
    const src = readFileSync(GUARD, 'utf8')
    expect(src, 'the lock wait is not bounded by the guard timeout')
      .toMatch(/Math\.min\(/)

    root = mkdtempSync(join(tmpdir(), 'guard-'))
    const seen = join(root, 'argv.txt')
    const fake = join(root, 'gradlew')
    writeFileSync(fake, `#!/bin/bash\nprintf '%s\\n' "$*" > ${JSON.stringify(seen)}\n`)
    chmodSync(fake, 0o755)
    // A sixty second guard must not ask for a thirty minute wait.
    const r = spawnSync('node', [GUARD, '60', '--', fake, 'build'], {
      encoding: 'utf8', timeout: 60_000,
      env: { ...process.env, INTAKE_GRADLE_LOCK: join(root, 'l.lock') },
    })
    expect(r.status, r.stderr).toBe(0)
    const asked = Number((r.stderr.match(/after (\d+)s/) || [])[1] || 0)
    expect(asked, `it asked to wait ${asked}s inside a 60s ceiling`).toBeLessThan(60)
  })

  it('waits long enough by default to outlast another full gate run', () => {
    // The device suite alone is seventeen minutes and it holds the lock
    // for all of it. Five minutes of patience guaranteed the collision.
    const src = readFileSync(GUARD, 'utf8')
    const m = src.match(/MTG_GRADLE_LOCK_WAIT[^;]*?\|\|\s*'?(\d+)'?/)
    expect(m, 'no default lock wait found to check').toBeTruthy()
    expect(Number(m[1]), 'the default wait is shorter than a device suite').toBeGreaterThanOrEqual(1200)
  })
})
