#!/usr/bin/env node
// The suite cannot shrink, as a command.
//
// Reads every JUnit XML a run left behind, totals it per suite, and
// compares against the floors committed in `test/suite-floors.json`.
// The rules live in `suite-floor.mjs` so they can be unit tested —
// the suite runs in workerd, which has no `node:fs`.
//
//   check-suite-floor.mjs <suite>=<resultsDir> [...]        check
//   check-suite-floor.mjs --raise <suite>=<resultsDir> [...] commit growth
//
// A suite's directory may be absent — a local run of one suite should
// not fail on the others — but a suite that *is* given and reports
// nothing is a failure, because zero is the shape a killed task
// leaves behind.
import { readdirSync, readFileSync, statSync, existsSync, writeFileSync } from 'node:fs'
import { join, extname } from 'node:path'
import { execFileSync } from 'node:child_process'
import { verdict, raise, requested, notLowered } from './suite-floor.mjs'

const FLOORS = 'test/suite-floors.json'

function walk(dir) {
  if (!existsSync(dir)) return []
  return readdirSync(dir).flatMap((name) => {
    const p = join(dir, name)
    return statSync(p).isDirectory() ? walk(p) : [p]
  })
}

/**
 * Tests reported, minus skips.
 *
 * Skips are excluded on purpose: a `needsRealRendering` test skipped
 * on the JVM has not run, and counting it would let the JVM total
 * stand in for the device's.
 */
function countFrom(dir) {
  const files = walk(dir).filter((f) => extname(f) === '.xml')
  let total = 0
  let skipped = 0
  for (const f of files) {
    const xml = readFileSync(f, 'utf8')
    for (const m of xml.matchAll(/<testsuite\b[^>]*>/g)) {
      total += Number(/\btests="(\d+)"/.exec(m[0])?.[1] ?? 0)
      skipped += Number(/\bskipped="(\d+)"/.exec(m[0])?.[1] ?? 0)
    }
  }
  return { ran: total - skipped, files: files.length }
}

const args = process.argv.slice(2)
const raising = args.includes('--raise')
const pairs = args.filter((a) => a !== '--raise')

if (pairs.length === 0) {
  console.error('usage: check-suite-floor.mjs [--raise] <suite>=<resultsDir> ...')
  process.exit(2)
}

const floors = existsSync(FLOORS) ? JSON.parse(readFileSync(FLOORS, 'utf8')) : {}
const actual = {}
const absent = []
const named = []

for (const pair of pairs) {
  const [suite, dir] = pair.split('=')
  if (suite) named.push(suite)
  if (!suite || !dir) {
    console.error(`check-suite-floor: cannot read "${pair}", want <suite>=<dir>`)
    process.exit(2)
  }
  if (!existsSync(dir)) {
    absent.push(suite)
    continue
  }
  const { ran, files } = countFrom(dir)
  if (files === 0) {
    absent.push(suite)
    continue
  }
  actual[suite] = ran
}

if (absent.length > 0) {
  console.log(`check-suite-floor: no results for ${absent.join(', ')} — not checked`)
}

if (raising) {
  // Re-read immediately before writing and merge per suite, so a
  // concurrent `--raise` in another worktree is a three-way merge rather
  // than a last-writer-wins overwrite. Only the suites named on the
  // command line may move.
  const onDisk = existsSync(FLOORS) ? JSON.parse(readFileSync(FLOORS, 'utf8')) : {}
  const next = raise(onDisk, actual, named)
  writeFileSync(FLOORS, `${JSON.stringify(next, null, 2)}\n`)
  Object.entries(next).forEach(([s, n]) => {
    const was = onDisk[s]
    console.log(was === n ? `  ${s}: ${n}` : `  ${s}: ${was ?? 0} -> ${n}`)
  })
  console.log(`check-suite-floor: ${FLOORS} written. Commit it.`)
  process.exit(0)
}

// A floor below the one main carries is invisible otherwise: `raise`
// will not lower one, but a merge resolution in this file will, and the
// result goes green. `main` is the reference; a checkout with no `main`
// to compare against just skips this.
let reference
try {
  reference = JSON.parse(execFileSync('git', ['show', `${process.env.FLOOR_REF || 'origin/main'}:${FLOORS}`], {
    encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'],
  }))
} catch { reference = undefined }
// The commit being checked is the one that has to authorise a lowering.
let message = ''
try {
  message = execFileSync('git', ['log', '-1', '--format=%B'], {
    encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'],
  })
} catch { /* no git log to read; nothing is authorised */ }
const held = notLowered(reference, floors, message)
if (!held.ok) {
  console.error('check-suite-floor: a committed floor is below main\'s —\n')
  held.lowered.forEach((r) => console.error(`  \u2717 ${r}`))
  console.error(
    '\nA floor may only ever go up. If a test was deliberately removed, put '
    + '\n  Floor-lowered: <suite>=<number> <why>\nin the commit message, with '
    + 'the exact number the file now carries.\n',
  )
  process.exit(1)
}

// Only the suites named on the command line are judged. The rest
// keep their floors for whoever runs them.
const v = verdict(requested(floors, named), actual)

Object.entries(actual).forEach(([s, n]) => {
  const floor = floors[s]
  console.log(`  ${s}: ${n}${floor === undefined ? ' (no floor yet)' : ` (floor ${floor})`}`)
})

if (v.grown.length > 0) {
  console.log(
    '\ncheck-suite-floor: grown — ' +
      v.grown.map((g) => `${g.suite} ${g.floor}->${g.actual}`).join(', ') +
      `\nRun \`npm run check:floor -- --raise ...\` and commit ${FLOORS}.`,
  )
}

if (v.failed) {
  console.error('\ncheck-suite-floor: the suite shrank —\n')
  v.reasons.forEach((r) => console.error(`  ✗ ${r}`))
  console.error(
    '\nIf a test was deliberately removed, raise the floor in the same ' +
      `commit and say why:\n  npm run check:floor -- --raise <suite>=<dir>\n`,
  )
  process.exit(1)
}

console.log('\ncheck-suite-floor: every suite held its floor.')
