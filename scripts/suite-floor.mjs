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
 *
 * It merges rather than overwrites, per suite, against whatever is on
 * disk at the moment of writing — two builders raising different
 * floors are then an ordinary three-way merge instead of a resolution
 * that picks one side and silently drops the other.
 *
 * `named` is the suites the command line asked about. A suite nobody
 * named cannot move its own floor even if the run left results for it,
 * because a killed Gradle task leaves the PREVIOUS run's XML on disk
 * and that reads as a real count.
 */
export function raise(floors, actual, named) {
  const asked = named === undefined ? null : new Set(named)
  const next = { ...floors }
  for (const [suite, n] of Object.entries(actual)) {
    if (asked && !asked.has(suite)) continue
    next[suite] = Math.max(next[suite] ?? 0, n)
  }
  return next
}

/**
 * Floors that went DOWN against a reference copy, usually main's.
 *
 * `raise` cannot lower a floor, but a human resolving a merge conflict
 * in `suite-floors.json` can — and a floor below main's real count
 * means a later deletion of up to twenty tests passes unnoticed. The
 * file is the one place in this repo where the wrong three-way merge is
 * invisible, so it gets checked rather than trusted.
 */
/**
 * The suites a commit message explicitly authorises lowering.
 *
 * `Floor-lowered: shell=128 worker=872` and a reason. Nothing else gets
 * past `notLowered`, which is the point — the guard exists because a merge
 * resolution can quietly drop a floor and the result goes green.
 *
 * This had no implementation for a while and the failure message promised
 * one: "If a test was deliberately removed, say so in the commit message
 * and lower it in a commit of its own." It said that and then refused
 * every such commit, so a legitimate deletion could not land at all. A
 * guard with no legitimate path is a wedge, not a guard.
 *
 * Unlike the `Red:` trailer Matt rightly rejected, this does not claim a
 * discipline was followed — it names a number and a suite, and the number
 * either matches the file or it does not.
 */
export function authorised(message) {
  const m = /^Floor-lowered:(.*)$/m.exec(String(message ?? ''))
  if (!m) return {}
  const out = {}
  for (const pair of m[1].matchAll(/([A-Za-z0-9_-]+)=(\d+)/g)) out[pair[1]] = Number(pair[2])
  return out
}

export function notLowered(reference, floors, message) {
  const ok = authorised(message)
  const lowered = []
  for (const [suite, was] of Object.entries(reference ?? {})) {
    // Authorised only for the exact number the message names, so a
    // trailer cannot licence a drop further than it says.
    if (ok[suite] !== undefined && ok[suite] === floors[suite]) continue
    const now = floors[suite]
    if (now === undefined) {
      lowered.push(`${suite} lost its floor of ${was}`)
    } else if (now < was) {
      lowered.push(`${suite} floor ${was} -> ${now}`)
    }
  }
  return { ok: lowered.length === 0, lowered }
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
