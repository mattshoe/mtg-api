import { describe, it, expect } from 'vitest'
import { classify, judge, PRODUCTION, TESTS } from '../scripts/tdd-rules.mjs'

// The TDD gate, tested before it existed, which is the only way it
// could have been written without hypocrisy.
//
// `classify` decides whether a path is production, test, or neither.
// `judge` takes one commit's facts and returns the verdict. Both live
// in `tdd-rules.mjs` with no I/O, because this suite runs inside
// workerd and `check-tdd.mjs` imports `node:child_process`.

describe('classify', () => {
  it('calls the shared core production', () => {
    expect(classify('apps/core/src/commonMain/kotlin/org/mattshoe/mtg/core/App.kt'))
      .toBe(PRODUCTION)
  })

  it('calls every test source tree tests', () => {
    const paths = [
      'apps/core/src/commonTest/kotlin/org/mattshoe/mtg/core/BackTest.kt',
      'apps/core/src/jvmTest/kotlin/org/mattshoe/mtg/core/CoreSqlDump.kt',
      'apps/webApp/src/jsTest/kotlin/org/mattshoe/mtg/web/CardFaceTest.kt',
      'apps/androidApp/src/sharedTest/kotlin/org/mattshoe/mtg/android/ScreensTest.kt',
      'apps/androidApp/src/test/kotlin/org/mattshoe/mtg/android/DownloadDecisionTest.kt',
      'apps/androidApp/src/androidTest/kotlin/org/mattshoe/mtg/android/Foo.kt',
      'test/query.test.js',
    ]
    paths.forEach((p) => expect(classify(p), p).toBe(TESTS))
  })

  it('calls the website and the worker production', () => {
    expect(classify('frontend/js/card.js')).toBe(PRODUCTION)
    expect(classify('frontend/css/app.css')).toBe(PRODUCTION)
    expect(classify('src/index.js')).toBe(PRODUCTION)
    expect(classify('schema.sql')).toBe(PRODUCTION)
  })

  it('does not count a jsMain path as a test just because "test" is in a filename', () => {
    // `CardFaceTest.kt` under `jsMain` would be production code with
    // an unfortunate name. The directory decides, not the filename.
    expect(classify('apps/webApp/src/jsMain/kotlin/org/mattshoe/mtg/web/LatestTest.kt'))
      .toBe(PRODUCTION)
  })

  it('counts docs, CI and build config as neither', () => {
    const neither = [
      'README.md',
      'CLAUDE.md',
      '.github/workflows/apps.yml',
      'package.json',
      'apps/androidApp/build.gradle.kts',
      'test/fixtures/core-sql.json',
      'scripts/check-tdd.mjs',
    ]
    neither.forEach((p) => expect(classify(p), p).toBeNull())
  })
})

describe('judge', () => {
  const ok = { sha: 'abc1234', subject: 'x', files: [], message: '' }

  it('passes a commit with no production change', () => {
    const v = judge({
      ...ok,
      files: ['README.md', 'apps/core/src/commonTest/kotlin/A.kt'],
    })
    expect(v.failed).toBe(false)
  })

  it('fails production code with no test alongside it', () => {
    const v = judge({
      ...ok,
      files: ['apps/core/src/commonMain/kotlin/App.kt'],
      message: 'Red: 3 of 4',
    })
    expect(v.failed).toBe(true)
    expect(v.reasons.join(' ')).toContain('no test')
  })

  it('fails production code with no Red line', () => {
    const v = judge({
      ...ok,
      files: [
        'apps/core/src/commonMain/kotlin/App.kt',
        'apps/core/src/commonTest/kotlin/AppTest.kt',
      ],
      message: 'Fixed the thing.',
    })
    expect(v.failed).toBe(true)
    expect(v.reasons.join(' ')).toContain('Red:')
  })

  it('passes production code with a test and a Red line', () => {
    const v = judge({
      ...ok,
      files: [
        'apps/core/src/commonMain/kotlin/App.kt',
        'apps/core/src/commonTest/kotlin/AppTest.kt',
      ],
      message: 'Did a thing.\n\nRed: 2 of 3 in AppTest — "no type line"',
    })
    expect(v.failed).toBe(false)
  })

  it('accepts "Red: n/a, tests only" as a Red line', () => {
    const v = judge({
      ...ok,
      files: [
        'apps/core/src/commonMain/kotlin/App.kt',
        'apps/core/src/commonTest/kotlin/AppTest.kt',
      ],
      message: 'Red: n/a, tests only',
    })
    expect(v.failed).toBe(false)
  })

  it('exempts a commit with a reason, and reports it as an exemption', () => {
    const v = judge({
      ...ok,
      files: ['apps/core/src/commonMain/kotlin/App.kt'],
      message: 'Rename Foo to Bar.\n\nTDD-exempt: pure rename, no behaviour',
    })
    expect(v.failed).toBe(false)
    expect(v.exempt).toBe('pure rename, no behaviour')
  })

  it('rejects an exemption with no reason given', () => {
    const v = judge({
      ...ok,
      files: ['apps/core/src/commonMain/kotlin/App.kt'],
      message: 'TDD-exempt:',
    })
    expect(v.failed).toBe(true)
    expect(v.reasons.join(' ')).toContain('reason')
  })

  it('names the production files it is complaining about', () => {
    const v = judge({
      ...ok,
      files: ['apps/core/src/commonMain/kotlin/App.kt', 'frontend/js/card.js'],
      message: '',
    })
    // A verdict that says "no test" without saying for what sends
    // somebody to read the whole diff.
    expect(v.reasons.join(' ')).toContain('App.kt')
    expect(v.reasons.join(' ')).toContain('card.js')
  })

  it('treats a merge commit as nothing to judge', () => {
    // A merge carries the union of its parents' files but authored
    // none of them; the commits being merged were each judged on the
    // branch. Judging it again fails honest history.
    const v = judge({
      ...ok,
      parents: 2,
      files: ['apps/core/src/commonMain/kotlin/App.kt'],
      message: "Merge branch 'x'",
    })
    expect(v.failed).toBe(false)
    expect(v.skipped).toBe(true)
  })

  it('treats a revert as exempt without needing the trailer', () => {
    const v = judge({
      ...ok,
      files: ['apps/core/src/commonMain/kotlin/App.kt'],
      message: 'Revert "Did a thing"\n\nThis reverts commit abc.',
    })
    expect(v.failed).toBe(false)
    expect(v.exempt).toContain('revert')
  })
})
