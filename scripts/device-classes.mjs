// Which test classes actually need a phone.
//
// Matt: "26 minutes is way too fucking long for validations. That is
// like enterprise application length and we are FAR from that." And:
// "Can't we move the majority of that into compose tests in the jvm?
// Only run CRITICAL flows on an emulator."
//
// They are already JVM Compose tests. Everything in
// `apps/androidApp/src/sharedTest/` runs twice: once on Robolectric as
// the `screens` suite, 703 tests in 2.4 minutes, and once on the
// emulator as `device`, 659 tests in 19 minutes. The same source, the
// same assertions, eight times slower on hardware. Nothing needed
// moving; the emulator was simply running the suite a second time
// because the run was defined by directory rather than by need.
//
// Two kinds of test genuinely need a phone:
//
//   1. Anything calling `Parity.needsRealRendering()`. Those SKIP on
//      the JVM — Coil never decodes an image under Robolectric, so
//      `AsyncImage`'s alignment has nothing to bite on, and
//      `Modifier.border` moves no layout bounds. The device run is
//      their only run, and it has already caught two that were green
//      on the JVM and simply wrong.
//   2. Everything in `androidTest/`: the end-to-end journeys that
//      launch the real `MainActivity` against a real socket.
//
// The list is derived from the source rather than kept by hand, so a
// new `needsRealRendering` is on the phone the moment it is written and
// nobody has to remember a registry.

import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, basename, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO = dirname(dirname(fileURLToPath(import.meta.url)));

/**
 * The package a file declares, not one assumed for it.
 *
 * Assuming `org.mattshoe.mtg.android` dropped both end-to-end
 * journeys: they are in `...android.e2e`, so the runner was handed a
 * class name that does not exist and answered "Failed loading
 * specified test class". Loudly, which is the only reason it was
 * caught — the journeys are the most valuable thing on the phone and
 * a quieter failure would have removed them from the gate.
 */
const packageOf = (text) => (text.match(/^\s*package\s+([\w.]+)/m) || [])[1];

function kotlin(dir, out = []) {
  let entries;
  try { entries = readdirSync(dir); } catch { return out; }
  for (const e of entries) {
    const p = join(dir, e);
    if (statSync(p).isDirectory()) kotlin(p, out);
    else if (e.endsWith('.kt')) out.push(p);
  }
  return out;
}

/** A file is a test class only if it actually holds a `@Test`. */
const isTest = (text) => /@Test\b/.test(text);

export function deviceClasses(repo = REPO) {
  const found = new Set();
  const take = (p, text) => {
    const pkg = packageOf(text);
    found.add(pkg ? `${pkg}.${basename(p, '.kt')}` : basename(p, '.kt'));
  };
  // Per METHOD for the gated ones, not per class.
  //
  // Filtering by class took 279 tests and ten and a half minutes,
  // because `DecksParityTest` has one gated test and fifty that are
  // not, and all fifty came along for the ride having already passed
  // on Robolectric two minutes earlier. The runner takes
  // `pkg.Class#method`, so ask for the method.
  for (const p of kotlin(join(repo, 'apps/androidApp/src/sharedTest'))) {
    const text = readFileSync(p, 'utf8');
    if (!isTest(text) || !text.includes('needsRealRendering')) continue;
    const pkg = packageOf(text);
    const cls = basename(p, '.kt');
    // Each `@Test` owns the text up to the next one, which is where
    // its body is. Crude, and right for how this repo writes tests:
    // one annotation, one function, no nesting.
    const blocks = text.split(/@Test\b/).slice(1);
    for (const block of blocks) {
      if (!block.includes('needsRealRendering')) continue;
      const name = (block.match(/fun\s+([A-Za-z_][\w]*)\s*\(/) || [])[1];
      if (name) found.add(`${pkg ? `${pkg}.` : ''}${cls}#${name}`);
    }
  }
  for (const p of kotlin(join(repo, 'apps/androidApp/src/androidTest'))) {
    const text = readFileSync(p, 'utf8');
    if (isTest(text)) take(p, text);
  }
  return [...found].sort();
}

if (process.argv[1] && process.argv[1].endsWith('device-classes.mjs')) {
  const names = deviceClasses();
  if (!names.length) {
    // Never print an empty filter: an empty `class=` argument runs
    // EVERYTHING, which would silently put the 19 minutes back.
    console.error('device-classes: found no class that needs a phone, which cannot be right.');
    process.exit(1);
  }
  console.log(names.join(','));
}
