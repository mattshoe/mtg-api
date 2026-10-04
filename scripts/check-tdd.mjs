#!/usr/bin/env node
// The TDD gate, as a command.
//
// Reads git, hands each commit to the rules in `tdd-rules.mjs`, and
// reports. The rules live next door so they can be unit tested —
// the suite runs in workerd, which has no `node:child_process`.
//
// Usage: check-tdd.mjs <base>..<head>   (default: origin/main..HEAD)

import { execFileSync } from 'node:child_process'
import { judge } from './tdd-rules.mjs'

function git(args) {
  return execFileSync('git', args, { encoding: 'utf8' })
}

function commitsIn(range) {
  const out = git(['log', '--format=%H', '--reverse', range]).trim()
  return out ? out.split('\n') : []
}

function read(sha) {
  // %P is the parent list; its word count is the parent count, which
  // is how a merge is recognised.
  const raw = git(['show', '-s', '--format=%P%n%s%n%B', sha])
  const [parentLine, subject, ...rest] = raw.split('\n')
  const files = git(['show', '--name-only', '--format=', sha])
    .split('\n')
    .map((l) => l.trim())
    .filter(Boolean)
  return {
    sha,
    subject,
    message: rest.join('\n'),
    parents: parentLine.trim() ? parentLine.trim().split(/\s+/).length : 0,
    files,
  }
}

function main() {
  const range = process.argv[2] || 'origin/main..HEAD'
  let shas
  try {
    shas = commitsIn(range)
  } catch {
    console.error(`check-tdd: cannot read the range ${range}`)
    process.exit(2)
  }

  if (shas.length === 0) {
    console.log(`check-tdd: no commits in ${range}, nothing to check`)
    return
  }

  const verdicts = shas.map((sha) => judge(read(sha)))
  const bad = verdicts.filter((v) => v.failed)
  const exempt = verdicts.filter((v) => v.exempt)

  // Exemptions are printed whether or not anything failed, and
  // printed loudly, because an exemption nobody sees is a hole.
  if (exempt.length > 0) {
    console.log(`\ncheck-tdd: ${exempt.length} exemption(s) in ${range} —`)
    exempt.forEach((v) => {
      console.log(`  ! ${v.sha.slice(0, 9)} ${v.subject}`)
      console.log(`      reason: ${v.exempt}`)
    })
  }

  if (bad.length === 0) {
    const judged = verdicts.filter((v) => !v.skipped).length
    console.log(
      `\ncheck-tdd: ${judged} commit(s) judged in ${range}, all of them ` +
        `test-first as far as a machine can tell.`,
    )
    return
  }

  console.error(`\ncheck-tdd: ${bad.length} commit(s) did not follow TDD —\n`)
  bad.forEach((v) => {
    console.error(`  ✗ ${v.sha.slice(0, 9)} ${v.subject}`)
    v.reasons.forEach((r) => console.error(`      ${r}`))
  })
  console.error(
    '\nCLAUDE.md has the rule. Every production change starts with a ' +
      'failing test, and the commit message records what was red.\n' +
      'A change that genuinely cannot be tested uses a ' +
      '`TDD-exempt: <reason>` trailer, which is logged for review.\n',
  )
  process.exit(1)
}

// Only when run, so the tests can import the pure half.
if (process.argv[1] && process.argv[1].endsWith('check-tdd.mjs')) main()
