#!/usr/bin/env node
// The semantic version the release `release.yml` cuts on every merge to main.
//
// It was `versionName = "2.1.0"` in build.gradle.kts, edited by hand, which
// in practice meant never: forty releases all said 2.1.0. Now the version
// moves from the newest `android-vX.Y.Z-N` tag by however much the merge's
// release note says, in its frontmatter:
//
//   ---
//   bump: minor
//   ---
//   Release notes are on Admin Settings.
//
// `major`, `minor` or `patch`. No frontmatter, or no note at all, is a patch.
// When one merge added two notes the bigger bump wins. Only notes the merge
// commit *added* count, the same rule as scripts/release-note.mjs.
//
//   node scripts/next-version.mjs     # prints the version for HEAD

import { execFileSync } from 'node:child_process'
import { readFileSync } from 'node:fs'
import { pathToFileURL } from 'node:url'

const BUMPS = ['patch', 'minor', 'major']
const TAG = /^android-v(\d+)\.(\d+)\.(\d+)-\d+$/
const FRONTMATTER = /^---\r?\n([\s\S]*?)\r?\n---\r?\n?/

/** A note with its frontmatter taken off, for the release body. */
export function withoutFrontmatter(note) {
  return note.replace(FRONTMATTER, '')
}

/** The bump a release note declares. */
export function bumpIn(note) {
  const head = note.match(FRONTMATTER)?.[1] ?? ''
  const line = head.match(/^bump:\s*(.*?)\s*$/m)
  if (!line) return 'patch'
  if (!BUMPS.includes(line[1])) {
    throw new Error(`release note says "bump: ${line[1]}", which is not one of ${BUMPS.join(', ')}`)
  }
  return line[1]
}

/**
 * The newest released version, bumped by the biggest of `bumps`.
 * With nothing released yet it is `fallback` as it stands.
 */
export function nextVersion(tags, bumps, fallback = '0.1.0') {
  const released = tags
    .map((t) => t.match(TAG))
    .filter(Boolean)
    .map((m) => m.slice(1, 4).map(Number))
    .sort((a, b) => a[0] - b[0] || a[1] - b[1] || a[2] - b[2])
  const last = released.at(-1)
  if (!last) return fallback
  const size = Math.max(0, ...bumps.map((b) => BUMPS.indexOf(b)))
  const [major, minor, patch] = last
  if (size === 2) return `${major + 1}.0.0`
  if (size === 1) return `${major}.${minor + 1}.0`
  return `${major}.${minor}.${patch + 1}`
}

function git(...args) {
  return execFileSync('git', args, { encoding: 'utf8' }).trim()
}

function addedNotes() {
  const files = git('diff', '--name-only', '--diff-filter=A', 'HEAD~1', 'HEAD', '--', 'release-notes/')
  return files.split('\n')
    .filter((f) => f.endsWith('.md') && !f.endsWith('/README.md'))
    .sort()
    .map((f) => readFileSync(f, 'utf8'))
}

/** What build.gradle.kts builds locally, the start when nothing is tagged. */
function gradleVersion() {
  try {
    const kts = readFileSync('apps/androidApp/build.gradle.kts', 'utf8')
    return kts.match(/MTG_VERSION_NAME"\) \?: "([0-9.]+)"/)?.[1]
  } catch {
    return undefined
  }
}

if (import.meta.url === pathToFileURL(process.argv[1]).href) {
  const tags = git('tag', '--list', 'android-v*').split('\n').filter(Boolean)
  const version = nextVersion(tags, addedNotes().map(bumpIn), gradleVersion())
  process.stdout.write(version + '\n')
}
