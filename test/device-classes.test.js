// The emulator runs what needs a phone, and nothing else.
//
// Matt: "26 minutes is way too fucking long for validations." Of that,
// 19 was the device suite re-running 649 tests that had already passed
// on Robolectric two minutes earlier, because the run was defined by
// directory rather than by need.
//
// The danger in narrowing it is obvious and these tests are about that,
// not about the speed: a class that quietly drops off this list stops
// being run on hardware at all, and nothing goes red to say so.

import { describe, it, expect } from 'vitest'
import { mkdtempSync, mkdirSync, writeFileSync, rmSync } from 'node:fs'
import { join, dirname } from 'node:path'
import { tmpdir } from 'node:os'
import { fileURLToPath } from 'node:url'
import { deviceClasses } from '../scripts/device-classes.mjs'

const REPO = dirname(dirname(fileURLToPath(import.meta.url)))

let root
const fake = (files) => {
  root = mkdtempSync(join(tmpdir(), 'dev-'))
  for (const [p, text] of Object.entries(files)) {
    const full = join(root, p)
    mkdirSync(dirname(full), { recursive: true })
    writeFileSync(full, text)
  }
  return root
}
const shared = 'apps/androidApp/src/sharedTest/kotlin/org/mattshoe/mtg/android'
const android = 'apps/androidApp/src/androidTest/kotlin/org/mattshoe/mtg/android/e2e'

describe('what the emulator is given', () => {
  it('takes the gated METHOD, not the whole class it lives in', () => {
    // Filtering by class took 279 tests and ten and a half minutes:
    // one gated test drags along every other test in its file, all of
    // which passed on Robolectric two minutes earlier.
    const r = fake({
      [`${shared}/CropTest.kt`]:
        'package org.mattshoe.mtg.android\n'
        + '@Test fun needsPixels() { Parity.needsRealRendering() }\n'
        + '@Test fun doesNot() { assertEquals(1, 1) }\n',
    })
    expect(deviceClasses(r)).toEqual(['org.mattshoe.mtg.android.CropTest#needsPixels'])
    rmSync(r, { recursive: true, force: true })
  })

  it('takes every end-to-end journey whole, in the package it declares', () => {
    // Assuming the package dropped both journeys: they are in
    // `...android.e2e`, and the runner answered "Failed loading
    // specified test class" for a name that does not exist.
    const r = fake({
      [`${android}/JourneyTest.kt`]: 'package org.mattshoe.mtg.android.e2e\n@Test fun journey() {}',
    })
    expect(deviceClasses(r)).toEqual(['org.mattshoe.mtg.android.e2e.JourneyTest'])
    rmSync(r, { recursive: true, force: true })
  })

  it('leaves out a plain parity test, because Robolectric already ran it', () => {
    const r = fake({ [`${shared}/PlainTest.kt`]: 'package org.mattshoe.mtg.android\n@Test fun b() {}' })
    expect(deviceClasses(r)).toEqual([])
    rmSync(r, { recursive: true, force: true })
  })

  it('leaves out a helper that mentions it but holds no test', () => {
    // `Parity.kt` and `Retry.kt` both say `needsRealRendering` and
    // neither is a test. Handing them over makes the runner fail with
    // "no tests found".
    const r = fake({
      [`${shared}/Parity.kt`]: 'package org.mattshoe.mtg.android\nobject Parity { fun needsRealRendering() {} }',
    })
    expect(deviceClasses(r)).toEqual([])
    rmSync(r, { recursive: true, force: true })
  })

  it('misses no gated test in this repository', () => {
    // The one that matters. A gated test left off this list runs
    // NOWHERE: skipped on the JVM, never asked for on the phone. It
    // would not go red, it would simply stop existing.
    const { readdirSync, readFileSync, statSync } = require('node:fs')
    const walk = (d, out = []) => {
      for (const e of readdirSync(d)) {
        const p = join(d, e)
        if (statSync(p).isDirectory()) walk(p, out)
        else if (e.endsWith('.kt')) out.push(p)
      }
      return out
    }
    const wanted = []
    for (const p of walk(join(REPO, 'apps/androidApp/src/sharedTest'))) {
      const text = readFileSync(p, 'utf8')
      if (!/@Test\b/.test(text) || !text.includes('needsRealRendering')) continue
      const cls = p.split('/').pop().replace('.kt', '')
      for (const block of text.split(/@Test\b/).slice(1)) {
        if (!block.includes('needsRealRendering')) continue
        const name = (block.match(/fun\s+([A-Za-z_][\w]*)\s*\(/) || [])[1]
        if (name) wanted.push(`${cls}#${name}`)
      }
    }
    const got = deviceClasses(REPO).map((n) => n.replace(/^.*\.(?=[A-Z])/, ''))
    expect(wanted.filter((w) => !got.includes(w)), 'a gated test would never run anywhere').toEqual([])
    expect(wanted.length, 'no gated tests found at all, so this proves nothing').toBeGreaterThan(5)
  })

  it('runs both journeys on the phone, which nothing else can', () => {
    const got = deviceClasses(REPO)
    expect(got).toContain('org.mattshoe.mtg.android.e2e.JourneyTest')
    expect(got).toContain('org.mattshoe.mtg.android.e2e.HarnessTest')
  })
})
