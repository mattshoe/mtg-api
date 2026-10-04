// The TDD gate's rules, with no I/O in them.
//
// Split from the CLI deliberately: the test suite runs inside
// workerd, which has no `node:child_process`, and a rule set that
// cannot be unit tested is not much of a gate. `check-tdd.mjs` is the
// thin shell that reads git and calls this.
//
// The gate itself.
//
// CI cannot watch somebody write a test before they write the code.
// It can check the two things that are left behind when they did:
// the commit changed tests as well as production code, and the commit
// message says what was red before the change.
//
// Both are proxies. Together they are enough to make skipping the
// discipline more work than following it, which is all a gate can do.
//

export const PRODUCTION = 'production'
export const TESTS = 'tests'

// A test source tree, by directory. Matching on the filename would
// call `jsMain/.../LatestTest.kt` a test, and a production file with
// an unlucky name is exactly how a gate like this gets quietly
// defeated.
const TEST_DIRS = [
  /(^|\/)src\/commonTest\//,
  /(^|\/)src\/jvmTest\//,
  /(^|\/)src\/jsTest\//,
  /(^|\/)src\/test\//,
  /(^|\/)src\/sharedTest\//,
  /(^|\/)src\/androidTest\//,
  /^test\//,
]

// Everything a user can actually see or run.
const PRODUCTION_DIRS = [
  /(^|\/)src\/commonMain\//,
  /(^|\/)src\/jvmMain\//,
  /(^|\/)src\/jsMain\//,
  /(^|\/)src\/main\//,
  /(^|\/)src\/androidMain\//,
  /^frontend\//,
  /^src\//,
  /^migrations\//,
  /^schema\.sql$/,
]

// Fixtures are data a test reads, not a test. Changing one alone
// proves nothing, so it must not satisfy the gate.
const NOT_A_TEST = [/^test\/fixtures\//]

/** production, tests, or null for anything that is neither. */
export function classify(path) {
  if (NOT_A_TEST.some((re) => re.test(path))) return null
  if (TEST_DIRS.some((re) => re.test(path))) return TESTS
  if (PRODUCTION_DIRS.some((re) => re.test(path))) return PRODUCTION
  return null
}

/**
 * The commit that introduced the rule, in `CLAUDE.md`.
 *
 * Nothing before it is judged. The repository has a hundred commits
 * that predate the discipline, and failing a pull request for commits
 * written under no such rule is not enforcement — it is noise, and
 * noise is how a gate ends up switched off. Everything from this
 * commit onward is judged, with no way out but the logged
 * `TDD-exempt:` trailer.
 */
export const RULE_COMMIT = 'a50b33da1c8fb8f0195326d43b605935003146af'

/**
 * The commits in `all` that are worth judging.
 *
 * `descendants` is the rule commit and everything after it, as git
 * reports it, or `null` when the rule commit is not in this history
 * at all — a shallow clone, or a range that predates it. In that case
 * everything is judged: a gate erring strict is recoverable, a gate
 * erring silent is not.
 */
export function afterTheRule(all, descendants) {
  if (!descendants) return all
  const allowed = new Set(descendants)
  return all.filter((sha) => allowed.has(sha))
}

const RED_LINE = /^\s*Red:\s*\S+/im
const EXEMPT_LINE = /^\s*TDD-exempt:\s*(.*)$/im
const REVERT = /^\s*Revert "/m

/**
 * One commit's verdict.
 *
 * `{ failed, reasons, exempt, skipped }`. Pure, so the whole thing is
 * testable without a git repository.
 */
export function judge(commit) {
  const { sha = '', subject = '', files = [], message = '', parents = 1 } = commit

  // A merge authored none of what it carries; each commit being
  // merged was judged on its own branch. Judging the union again
  // fails honest history.
  if (parents > 1) return { failed: false, reasons: [], skipped: true, sha, subject }

  const kinds = files.map(classify)
  const production = files.filter((_, i) => kinds[i] === PRODUCTION)
  const tests = files.filter((_, i) => kinds[i] === TESTS)

  if (production.length === 0) {
    return { failed: false, reasons: [], sha, subject }
  }

  const exemption = message.match(EXEMPT_LINE)
  if (exemption) {
    const reason = exemption[1].trim()
    if (!reason) {
      return {
        failed: true,
        reasons: ['TDD-exempt: was given with no reason after it'],
        sha,
        subject,
      }
    }
    return { failed: false, reasons: [], exempt: reason, sha, subject }
  }

  if (REVERT.test(message)) {
    return { failed: false, reasons: [], exempt: 'a revert, already tested forwards', sha, subject }
  }

  const reasons = []
  if (tests.length === 0) {
    reasons.push(
      `production code changed with no test in the same commit: ${production.join(', ')}`,
    )
  }
  if (!RED_LINE.test(message)) {
    reasons.push(
      'no `Red:` line in the message — say what failed before the change, ' +
        'or `Red: n/a, tests only` if nothing could',
    )
  }
  return { failed: reasons.length > 0, reasons, sha, subject }
}
