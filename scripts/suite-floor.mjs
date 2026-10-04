// The suite cannot shrink. The rules, with no I/O.
//
// This is the half of the discipline a machine can hold. Whether a
// test was written before the code is a fact about an afternoon and
// nothing in a commit records it; whether the test still exists and
// still runs is a fact about the build, and that is checkable
// forever.
//
// Split from the command because the test suite runs inside workerd,
// which has no `node:fs`.

/**
 * `{ failed, reasons, grown }` for a run against the committed floors.
 *
 * A floor is the number of tests a suite has reached. It may go up and
 * it may never go down.
 */
export function verdict(floors, actual) {
  const reasons = []
  const grown = []

  for (const [suite, floor] of Object.entries(floors)) {
    const n = actual[suite]
    if (n === undefined) {
      // A job that quietly stopped being wired up is the same bug as
      // a suite that shrank to nothing, and it looks like success.
      reasons.push(`${suite} did not report at all — expected at least ${floor}`)
      continue
    }
    if (n < floor) {
      reasons.push(
        `${suite} ran ${n} tests, down from ${floor}. ` +
          'A suite that shrinks is worse than one that fails: it goes green.',
      )
      continue
    }
    if (n > floor) grown.push({ suite, floor, actual: n })
  }

  return { failed: reasons.length > 0, reasons, grown }
}

/**
 * The floors, raised to a run's totals.
 *
 * Never lowered. `--raise` after deleting tests would otherwise
 * launder the deletion into the committed baseline, which is the one
 * thing this file exists to prevent. A suite missing from the run
 * keeps the floor it had, so running one suite locally cannot erase
 * the others.
 */
export function raise(floors, actual) {
  const next = { ...floors }
  for (const [suite, n] of Object.entries(actual)) {
    next[suite] = Math.max(next[suite] ?? 0, n)
  }
  return next
}

/**
 * The floors worth judging for this run: the suites that were named
 * on the command line and have a floor.
 *
 * The first version judged every floor in the file regardless, so
 * running one suite locally printed failures for three suites nobody
 * had mentioned. Output that is wrong three times out of four is
 * output people learn to skip.
 */
export function requested(floors, suites) {
  const asked = new Set(suites)
  return Object.fromEntries(Object.entries(floors).filter(([s]) => asked.has(s)))
}
