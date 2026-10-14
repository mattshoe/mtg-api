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
import { mkdtempSync, rmSync, existsSync, readFileSync } from 'node:fs'
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

  it('waits long enough by default to outlast another full gate run', () => {
    // The device suite alone is seventeen minutes and it holds the lock
    // for all of it. Five minutes of patience guaranteed the collision.
    const src = readFileSync(GUARD, 'utf8')
    const m = src.match(/MTG_GRADLE_LOCK_WAIT[^;]*?\|\|\s*'?(\d+)'?/)
    expect(m, 'no default lock wait found to check').toBeTruthy()
    expect(Number(m[1]), 'the default wait is shorter than a device suite').toBeGreaterThanOrEqual(1200)
  })
})
