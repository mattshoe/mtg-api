// The gate remembers which suites already passed on this exact tree.
//
// Matt: "Why the fuck are these simple tasks running for 3 hours?!"
//
// Measured on two live agents: one had run the full 35-minute gate eight
// times, the other four. That was the whole three hours. The gate starts
// at suite one every run, so a one-line fix to a Kotlin screen re-ran the
// Worker suite, the core suite and the web suite — none of which that
// line can reach — before getting back to the thing that broke. Two
// agents share one Gradle lock, so each one's 35 minutes also blocked the
// other's.
//
// So a suite that passed is remembered against a hash of the files it
// actually covers, and skipped while that hash holds. The first run still
// costs 35 minutes. The seventh costs what it should.
//
// The danger in this is obvious and it is the reason for the last test:
// a suite wrongly skipped is a suite that goes green without running,
// which is worse than one that fails. So anything changing that no suite
// claims makes every suite run.

import { describe, it, expect, afterEach } from 'vitest'
import { spawnSync } from 'node:child_process'
import {
  mkdtempSync, mkdirSync, writeFileSync, readFileSync, rmSync, existsSync, cpSync, chmodSync,
} from 'node:fs'
import { join, dirname } from 'node:path'
import { tmpdir } from 'node:os'
import { fileURLToPath } from 'node:url'

const REPO = dirname(dirname(fileURLToPath(import.meta.url)))

let box
afterEach(() => { if (box) rmSync(box.root, { recursive: true, force: true }); box = null })

/**
 * A throwaway repo with the real gate script in it and a fake `npm` on
 * PATH. Nothing here runs a test suite; `npm` writes down that it was
 * asked and exits with whatever the test wants.
 */
function build() {
  const root = mkdtempSync(join(tmpdir(), 'gate-'))
  const repo = join(root, 'repo')
  const bin = join(root, 'bin')
  mkdirSync(repo, { recursive: true })
  mkdirSync(bin, { recursive: true })
  mkdirSync(join(repo, 'scripts'), { recursive: true })
  mkdirSync(join(repo, 'src'), { recursive: true })
  mkdirSync(join(repo, 'test'), { recursive: true })
  mkdirSync(join(repo, 'apps', 'core'), { recursive: true })
  mkdirSync(join(repo, 'apps', 'androidApp'), { recursive: true })
  mkdirSync(join(repo, 'apps', 'webApp'), { recursive: true })

  cpSync(join(REPO, 'scripts', 'test-everything.mjs'), join(repo, 'scripts', 'test-everything.mjs'))
  // The real preflight talks to an SDK; this one only has to succeed.
  writeFileSync(join(repo, 'scripts', 'emulator.mjs'), 'process.exit(0)\n')
  // The floor check runs only after the worker suite and reads junit
  // files that do not exist here.
  writeFileSync(join(repo, 'scripts', 'check-suite-floor.mjs'), 'process.exit(0)\n')

  writeFileSync(join(repo, 'src', 'index.js'), 'worker\n')
  writeFileSync(join(repo, 'test', 'a.test.js'), 'test\n')
  writeFileSync(join(repo, 'apps', 'core', 'Core.kt'), 'core\n')
  writeFileSync(join(repo, 'apps', 'androidApp', 'Screen.kt'), 'screen\n')
  writeFileSync(join(repo, 'apps', 'webApp', 'Web.kt'), 'web\n')
  writeFileSync(join(repo, 'package.json'), '{"name":"x"}\n')
  writeFileSync(join(repo, 'README.md'), 'readme\n')
  writeFileSync(join(repo, '.gitignore'), '.gate/\n')

  const calls = join(root, 'calls.txt')
  const failing = join(root, 'failing.txt')
  writeFileSync(calls, '')
  writeFileSync(failing, '')
  writeFileSync(join(bin, 'npm'), `#!/bin/bash
printf '%s\\n' "$2" >> ${JSON.stringify(calls)}
grep -qxF "$2" ${JSON.stringify(failing)} && exit 1
exit 0
`)
  chmodSync(join(bin, 'npm'), 0o755)

  for (const a of [['init', '-b', 'main'], ['add', '-A'], ['-c', 'user.email=t@t', '-c', 'user.name=t', 'commit', '-qm', 'base']]) {
    spawnSync('git', ['-C', repo, ...a], { encoding: 'utf8' })
  }

  box = {
    root,
    repo,
    calls: () => readFileSync(calls, 'utf8').split('\n').filter(Boolean),
    clearCalls: () => writeFileSync(calls, ''),
    fail: (...names) => writeFileSync(failing, names.map((n) => `test:${n}`).join('\n')),
    edit: (p, text) => writeFileSync(join(repo, p), text),
    run: (...args) => spawnSync('node', ['scripts/test-everything.mjs', ...args], {
      cwd: repo,
      encoding: 'utf8',
      timeout: 60_000,
      env: { ...process.env, PATH: `${bin}:${process.env.PATH}` },
    }),
  }
  return box
}

/** Which suites actually ran, by the npm script the gate asked for. */
const ran = (b) => b.calls().map((c) => c.replace(/^test:/, '').replace('raw', 'worker').replace('android', 'device'))

describe('the gate skips what it already proved', () => {
  it('runs everything the first time', () => {
    const b = build()
    const r = b.run()
    expect(r.status, r.stderr).toBe(0)
    expect(ran(b)).toEqual(['worker', 'core', 'screens', 'web', 'device'])
  })

  it('runs nothing the second time, and says why rather than looking like a pass it did not earn', () => {
    const b = build()
    b.run()
    b.clearCalls()
    const r = b.run()
    expect(r.status, r.stderr).toBe(0)
    expect(ran(b), 'it re-ran a suite nothing had touched').toEqual([])
    expect(r.stdout).toContain('EVERY SUITE PASSED')
    expect(r.stdout, 'a skipped suite was not called skipped').toMatch(/already passed|skipped/i)
  })

  it('re-runs only the suites a changed file can reach', () => {
    const b = build()
    b.run()
    b.clearCalls()
    // A Kotlin screen. The Worker suite cannot see this file.
    b.edit('apps/androidApp/Screen.kt', 'screen, edited\n')
    const r = b.run()
    expect(r.status, r.stderr).toBe(0)
    const again = ran(b)
    expect(again, 'the worker suite re-ran for a Kotlin screen').not.toContain('worker')
    expect(again, 'the screens suite did not re-run for a screen').toContain('screens')
    expect(again, 'the device suite did not re-run for a screen').toContain('device')
  })

  it('re-runs the worker suite when the Worker changes, and not the Kotlin ones', () => {
    const b = build()
    b.run()
    b.clearCalls()
    b.edit('src/index.js', 'worker, edited\n')
    const r = b.run()
    expect(r.status, r.stderr).toBe(0)
    const again = ran(b)
    expect(again).toContain('worker')
    expect(again, 'core re-ran for a Worker edit').not.toContain('core')
  })

  it('does not remember a suite that failed', () => {
    const b = build()
    b.fail('screens')
    const first = b.run()
    expect(first.status).toBe(1)
    b.clearCalls()
    // Still failing, nothing edited: it has to try again rather than
    // treat the earlier attempt as settled.
    const r = b.run()
    expect(r.status).toBe(1)
    expect(ran(b), 'a failed suite was remembered as passed').toContain('screens')
  })

  it('remembers the suites that passed before the failure, so a fix does not re-run them', () => {
    const b = build()
    b.fail('screens')
    b.run()
    b.clearCalls()
    b.fail()
    const r = b.run()
    expect(r.status, r.stderr).toBe(0)
    const again = ran(b)
    expect(again, 'worker re-ran though it passed and nothing touched it').not.toContain('worker')
    expect(again).toContain('screens')
  })

  it('runs everything again when something no suite claims changes', () => {
    // The whole safety of this. A file outside every suite's map has an
    // unknown blast radius, and guessing it is harmless is how a suite
    // goes green without running. README.md is claimed by nothing.
    const b = build()
    b.run()
    b.clearCalls()
    b.edit('README.md', 'readme, edited\n')
    const r = b.run()
    expect(r.status, r.stderr).toBe(0)
    expect(ran(b), 'an unclaimed file did not force a full run')
      .toEqual(['worker', 'core', 'screens', 'web', 'device'])
  })

  it('runs everything when told to, however much it remembers', () => {
    const b = build()
    b.run()
    b.clearCalls()
    const r = b.run('--fresh')
    expect(r.status, r.stderr).toBe(0)
    expect(ran(b)).toEqual(['worker', 'core', 'screens', 'web', 'device'])
  })
})
