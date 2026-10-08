import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import { execFileSync } from 'node:child_process'
import { mkdtempSync, mkdirSync, writeFileSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join, resolve } from 'node:path'
import { nextVersion, bumpIn } from '../scripts/next-version.mjs'

// The version every merge to main ships as. It used to be 2.1.0 forever,
// with only the build number moving. Now the release note says how big
// the change is and the version moves by that much from the last release.

const SCRIPT = resolve('scripts/next-version.mjs')

describe('bumpIn', () => {
  it('reads the bump a note declares', () => {
    expect(bumpIn('---\nbump: minor\n---\nA new page.\n')).toBe('minor')
    expect(bumpIn('---\nbump: major\n---\nEverything moved.\n')).toBe('major')
  })

  it('is a patch when the note says nothing about it', () => {
    expect(bumpIn('A fix.\n')).toBe('patch')
  })

  it('refuses a bump it does not know, rather than guessing', () => {
    expect(() => bumpIn('---\nbump: huge\n---\nx\n')).toThrow(/bump: huge/)
  })
})

describe('nextVersion', () => {
  const tags = ['pre-kmp', 'android-v2.1.0-306', 'android-v2.1.0-307', 'android-v2.0.9-200']

  it('bumps the patch of the newest release when the notes ask for nothing', () => {
    expect(nextVersion(tags, [])).toBe('2.1.1')
  })

  it('bumps the minor and resets the patch', () => {
    expect(nextVersion(['android-v2.1.4-310'], ['minor'])).toBe('2.2.0')
  })

  it('bumps the major and resets the rest', () => {
    expect(nextVersion(['android-v2.1.4-310'], ['patch', 'major'])).toBe('3.0.0')
  })

  it('takes the biggest bump when one merge carried two notes', () => {
    expect(nextVersion(tags, ['patch', 'minor'])).toBe('2.2.0')
  })

  it('compares versions as numbers, not text', () => {
    expect(nextVersion(['android-v2.9.0-300', 'android-v2.10.0-301'], [])).toBe('2.10.1')
  })

  it('starts from the fallback when nothing has been released yet', () => {
    expect(nextVersion(['pre-kmp'], ['minor'], '2.1.0')).toBe('2.1.0')
  })
})

describe('the script, in a real repository', () => {
  let dir

  const git = (...args) => execFileSync('git', args, { cwd: dir, encoding: 'utf8' })
  const commit = (msg) => git('-c', 'user.name=t', '-c', 'user.email=t@t', 'commit', '-q', '-m', msg)
  const run = () => execFileSync('node', [SCRIPT], { cwd: dir, encoding: 'utf8' })

  beforeEach(() => {
    dir = mkdtempSync(join(tmpdir(), 'next-version-'))
    git('init', '-q')
    mkdirSync(join(dir, 'release-notes'))
    writeFileSync(join(dir, 'release-notes', 'older.md'), '---\nbump: major\n---\nShipped already.\n')
    git('add', '-A')
    commit('first')
    git('tag', 'android-v2.1.0-307')
  })

  afterEach(() => rmSync(dir, { recursive: true, force: true }))

  it('bumps by the note the merge added, and not the ones already shipped', () => {
    writeFileSync(join(dir, 'release-notes', 'this-one.md'), '---\nbump: minor\n---\nA new page.\n')
    git('add', '-A')
    commit('the merge')
    expect(run()).toBe('2.2.0\n')
  })

  it('is a patch when the merge added no note', () => {
    writeFileSync(join(dir, 'other.txt'), 'x')
    git('add', '-A')
    commit('no note')
    expect(run()).toBe('2.1.1\n')
  })
})
