#!/usr/bin/env node
// The body of the GitHub release `release.yml` cuts on every merge to main.
//
// Admin Settings lists those releases as the release notes, newest first,
// so this is what Matt reads. The note is the file the pull request added
// under `release-notes/`, written by hand. Not generated from the PR text:
// #35's described four features its diff did not contain.
//
// Only a note the merge commit *added*. An edit to one that already shipped
// is a correction to an old release, not news about this one.
//
//   node scripts/release-note.mjs     # prints the body for HEAD

import { execFileSync } from 'node:child_process'
import { readFileSync } from 'node:fs'
import { pathToFileURL } from 'node:url'

export function releaseBody(notes, sha) {
  const written = notes.map((n) => n.trim()).filter(Boolean)
  const built = `Built from ${sha}. Install over the top; same signing key.`
  return [...written, built].join('\n\n')
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

if (import.meta.url === pathToFileURL(process.argv[1]).href) {
  process.stdout.write(releaseBody(addedNotes(), git('rev-parse', 'HEAD')) + '\n')
}
