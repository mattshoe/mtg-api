import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import { execFileSync } from 'node:child_process'
import { mkdtempSync, mkdirSync, writeFileSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join, resolve } from 'node:path'
import { releaseBody } from '../scripts/release-note.mjs'

// The body of the GitHub release `release.yml` cuts on every merge.
//
// Admin Settings lists those releases as the release notes, so this is
// what Matt reads. The note is the file the pull request added under
// `release-notes/`, written by hand — not the PR text, which for #35
// described four features its diff did not contain.

const SCRIPT = resolve('scripts/release-note.mjs')

describe('releaseBody', () => {
  it('is the note a person wrote, then which commit it was built from', () => {
    expect(releaseBody(['Release notes are on Admin Settings.\n'], 'abc123')).toBe(
      'Release notes are on Admin Settings.\n\nBuilt from abc123. Install over the top; same signing key.',
    )
  })

  it('joins two notes when one merge carried two', () => {
    expect(releaseBody(['One.', 'Two.'], 'abc')).toBe(
      'One.\n\nTwo.\n\nBuilt from abc. Install over the top; same signing key.',
    )
  })

  it('leaves the bump line out, which is for the version and not for Matt', () => {
    expect(releaseBody(['---\nbump: minor\n---\nA new page.\n'], 'abc')).toBe(
      'A new page.\n\nBuilt from abc. Install over the top; same signing key.',
    )
  })

  it('is only the build line when nobody wrote a note', () => {
    expect(releaseBody([], 'abc')).toBe('Built from abc. Install over the top; same signing key.')
    expect(releaseBody(['  \n'], 'abc')).toBe('Built from abc. Install over the top; same signing key.')
  })
})

describe('the script, in a real repository', () => {
  let dir

  const git = (...args) => execFileSync('git', args, { cwd: dir, encoding: 'utf8' })
  const commit = (msg) => git('-c', 'user.name=t', '-c', 'user.email=t@t', 'commit', '-q', '-m', msg)
  const run = () => execFileSync('node', [SCRIPT], { cwd: dir, encoding: 'utf8' })

  beforeEach(() => {
    dir = mkdtempSync(join(tmpdir(), 'release-note-'))
    git('init', '-q')
    mkdirSync(join(dir, 'release-notes'))
    writeFileSync(join(dir, 'release-notes', 'README.md'), 'How to write one.\n')
    writeFileSync(join(dir, 'release-notes', 'older.md'), 'An older change.\n')
    git('add', '-A')
    commit('first')
  })

  afterEach(() => rmSync(dir, { recursive: true, force: true }))

  it('reads the note the last commit added, and not the ones already shipped', () => {
    writeFileSync(join(dir, 'release-notes', 'this-one.md'), 'Release notes are on Admin Settings.\n')
    writeFileSync(join(dir, 'other.txt'), 'x')
    git('add', '-A')
    commit('the merge')
    const sha = git('rev-parse', 'HEAD').trim()
    expect(run()).toBe(
      `Release notes are on Admin Settings.\n\nBuilt from ${sha}. Install over the top; same signing key.\n`,
    )
  })

  it('says nothing but the build when the merge added no note', () => {
    writeFileSync(join(dir, 'other.txt'), 'x')
    git('add', '-A')
    commit('no note')
    const sha = git('rev-parse', 'HEAD').trim()
    expect(run()).toBe(`Built from ${sha}. Install over the top; same signing key.\n`)
  })

  it('ignores an edit to a note that already shipped, and the README', () => {
    writeFileSync(join(dir, 'release-notes', 'older.md'), 'An older change, reworded.\n')
    writeFileSync(join(dir, 'release-notes', 'README.md'), 'Reworded.\n')
    git('add', '-A')
    commit('reword')
    const sha = git('rev-parse', 'HEAD').trim()
    expect(run()).toBe(`Built from ${sha}. Install over the top; same signing key.\n`)
  })
})
