#!/usr/bin/env node
// Did the run actually run every test that exists?
//
// Moving the Android tests into a shared source set left Kotlin's
// incremental compiler holding a stale picture: three whole classes,
// 71 tests, were quietly left out of the device APK and the build
// said BUILD SUCCESSFUL. The run was green because the tests were
// never there to fail. `--rerun-tasks` put them back.
//
// A suite that silently shrinks is worse than one that fails, so the
// source is counted and compared against what the run reported.
//
// Usage: node scripts/check-test-count.mjs <sourceDir> <resultsGlobDir>
import { readdirSync, readFileSync, statSync, existsSync } from 'node:fs';
import { join, extname } from 'node:path';

const [, , sourceDir, resultsDir] = process.argv;
if (!sourceDir || !resultsDir) {
  console.error('usage: node scripts/check-test-count.mjs <sourceDir> <resultsDir>');
  process.exit(2);
}

function walk(dir) {
  if (!existsSync(dir)) return [];
  return readdirSync(dir).flatMap((name) => {
    const p = join(dir, name);
    return statSync(p).isDirectory() ? walk(p) : [p];
  });
}

// `@Test` on its own line or in front of a one-line `fun`. Not inside
// a block comment, which is where the house style explains things.
function countTests(text) {
  let n = 0;
  let inBlock = false;
  for (const raw of text.split('\n')) {
    const line = raw.trim();
    if (inBlock) { if (line.includes('*/')) inBlock = false; continue; }
    if (line.startsWith('/*')) { if (!line.includes('*/')) inBlock = true; continue; }
    if (line.startsWith('//') || line.startsWith('*')) continue;
    if (/(^|\s)@Test\b/.test(line)) n += 1;
  }
  return n;
}

const inSource = walk(sourceDir)
  .filter((f) => extname(f) === '.kt')
  .reduce((n, f) => n + countTests(readFileSync(f, 'utf8')), 0);

const xml = walk(resultsDir).filter((f) => extname(f) === '.xml');
if (xml.length === 0) {
  console.error(`check: no results under ${resultsDir} — the run produced nothing to check`);
  process.exit(1);
}
const ran = xml.reduce((n, f) => {
  const m = readFileSync(f, 'utf8').match(/<testsuite[^>]*\btests="(\d+)"/);
  return n + (m ? Number(m[1]) : 0);
}, 0);

if (ran < inSource) {
  console.error(
    `check: ${sourceDir} declares ${inSource} tests but the run reported ${ran}. ` +
      `${inSource - ran} never ran. A green suite that quietly shrank is not a green suite — ` +
      `try ./gradlew <task> --rerun-tasks, which is what the last one of these turned out to be.`,
  );
  process.exit(1);
}
console.error(`check: ${ran} tests ran, ${inSource} declared in ${sourceDir}`);
