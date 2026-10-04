import { describe, it, expect } from 'vitest'
import { verdict, raise, requested } from '../scripts/suite-floor.mjs'

// The suite cannot shrink.
//
// This is the part of the discipline a machine can actually hold.
// Whether a test was written before the code is a fact about an
// afternoon, and nothing in a commit records it. Whether the test
// still exists and still runs is a fact about the build, and that is
// checkable forever.
//
// It has been wrong twice here. Kotlin's incremental compiler dropped
// three classes — 71 tests — out of a device APK and the build said
// BUILD SUCCESSFUL, green because the tests were not there to fail.
// And a batch of lifted tests landed with no `@Test` annotation at
// all: they compiled, never ran, and the total sat at a healthy-looking
// number for days.
//
// `check-test-count.mjs` already catches the first (source count
// against reported count). This catches the other direction: a total
// that goes down, for any reason, including somebody deleting a test
// because it was in the way.

describe('verdict', () => {
  it('passes when every suite holds its floor', () => {
    const v = verdict({ core: 2144, screens: 467 }, { core: 2144, screens: 467 })
    expect(v.failed).toBe(false)
  })

  it('passes when a suite grows', () => {
    const v = verdict({ core: 2144 }, { core: 2200 })
    expect(v.failed).toBe(false)
    expect(v.grown).toEqual([{ suite: 'core', floor: 2144, actual: 2200 }])
  })

  it('fails when a suite shrinks, and says by how much', () => {
    const v = verdict({ core: 2144 }, { core: 2100 })
    expect(v.failed).toBe(true)
    expect(v.reasons.join(' ')).toContain('core')
    expect(v.reasons.join(' ')).toContain('2144')
    expect(v.reasons.join(' ')).toContain('2100')
  })

  it('fails when a suite reported nothing at all', () => {
    // Zero is the shape a killed task leaves behind, and it must not
    // read as "no tests to run".
    const v = verdict({ core: 2144 }, { core: 0 })
    expect(v.failed).toBe(true)
    expect(v.reasons.join(' ')).toContain('core')
  })

  it('fails when a suite in the floor file is missing from the run', () => {
    // A job that silently stopped being wired up is the same bug as a
    // suite that shrank to nothing.
    const v = verdict({ core: 2144, screens: 467 }, { core: 2144 })
    expect(v.failed).toBe(true)
    expect(v.reasons.join(' ')).toContain('screens')
    expect(v.reasons.join(' ')).toContain('did not report')
  })

  it('ignores a suite that ran but has no floor yet', () => {
    // A new suite is not a regression. It gets a floor the first time
    // somebody raises it.
    const v = verdict({ core: 2144 }, { core: 2144, brandNew: 12 })
    expect(v.failed).toBe(false)
  })
})

describe('raise', () => {
  it('lifts a floor to the new total', () => {
    expect(raise({ core: 2144 }, { core: 2200 })).toEqual({ core: 2200 })
  })

  it('never lowers a floor, even when asked to', () => {
    // `--raise` after deleting tests would otherwise launder the
    // deletion into the committed baseline, which is the one thing
    // this file exists to prevent.
    expect(raise({ core: 2144 }, { core: 2000 })).toEqual({ core: 2144 })
  })

  it('adds a suite it has never seen', () => {
    expect(raise({ core: 2144 }, { core: 2144, web: 399 }))
      .toEqual({ core: 2144, web: 399 })
  })

  it('keeps a floor for a suite that did not report this time', () => {
    // Running one suite locally must not erase the others' floors.
    expect(raise({ core: 2144, screens: 467 }, { core: 2150 }))
      .toEqual({ core: 2150, screens: 467 })
  })
})

describe('requested', () => {
  // Running one suite locally must not report the other three as
  // missing. The first version of this did exactly that — asking only
  // about `screens` printed three failures for suites nobody had
  // mentioned — which would have trained everyone to ignore the
  // output.

  it('judges only the suites that were asked about', () => {
    const floors = { core: 10, screens: 20, web: 30 }
    expect(requested(floors, ['screens'])).toEqual({ screens: 20 })
  })

  it('still judges an asked-for suite that reported nothing', () => {
    // This is the case worth keeping: naming a suite and getting no
    // results is a job that silently stopped running.
    const floors = { core: 10, screens: 20 }
    expect(requested(floors, ['core', 'screens'])).toEqual({ core: 10, screens: 20 })
  })

  it('ignores an asked-for suite with no floor yet', () => {
    expect(requested({ core: 10 }, ['core', 'brandNew'])).toEqual({ core: 10 })
  })
})
