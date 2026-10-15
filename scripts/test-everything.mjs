// Everything, in one command, because this is what used to be CI.
//
// Matt: "Just disable CI and make a HARD RULE that all tasks MUST have a
// fully passing test suite locally before merging to main. THE ENTIRE
// test suite must pass before merging to main."
//
// So there has to be ONE thing to run, it has to say plainly whether it
// passed, and it must not be possible to pass it by running four suites
// out of six. A rule nobody can check in one step is a rule that gets
// half-followed on a tired evening.
//
// Order is cheapest-first, and it stops at the first failure: there is
// no point spending twenty emulator minutes to find out a unit test was
// already red.

import { spawnSync, execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';

/** Every suite, in the order worth running them. */
const SUITES = [
  ['worker', 'npm', ['run', 'test:raw']],
  ['core', 'npm', ['run', 'test:core']],
  ['screens', 'npm', ['run', 'test:screens']],
  ['web', 'npm', ['run', 'test:web']],
  // Last and slowest. The emulator is the only place the device tests
  // run at all, and with CI gone it is the only place full stop.
  //
  // `emulator.mjs` first, because nothing else in this repo ever started
  // a phone. With none attached Gradle fails in 24 seconds with "No
  // connected devices!", which reads like a broken change and is not
  // one — it happened here when an orphaned emulator from a dead agent
  // exited between two gate runs, four green suites in. The gate is the
  // one command that decides whether main moves; it cannot depend on
  // somebody having remembered to boot a phone by hand.
  ['emulator', 'node', ['scripts/emulator.mjs']],
  ['device', 'npm', ['run', 'test:android']],
];

// What each suite can actually see.
//
// Matt: "Why the fuck are these simple tasks running for 3 hours?!" One
// agent had run this whole thing eight times and another four, which was
// the three hours. The gate starts at suite one every run, so a one-line
// fix to a Kotlin screen re-ran the Worker suite, the core suite and the
// web suite — none of which that line can reach — before getting back to
// the thing that broke. Two agents share one Gradle lock, so each one's
// thirty-five minutes blocked the other's as well.
//
// So a suite that passed is remembered against a hash of the files below,
// and skipped while that hash holds.
//
// These lists are deliberately generous. A suite listed against too much
// re-runs when it did not have to, which costs minutes. A suite listed
// against too little is skipped when it should have run, which is a
// suite going green without executing — worse than one that fails,
// because it is believed. When in doubt, add the path.
const COVERS = {
  worker: [
    'src/', 'test/', 'frontend/', 'migrations/', 'schema.sql',
    'wrangler.toml', 'vitest.config.js', 'vitest.shell.config.js',
    'package.json', 'package-lock.json', 'scripts/', '.eslintrc', 'eslint.config.js',
    // `icon-actions.test.js` reads both shells' screens, and
    // `android-apps.test.js` every module's build file.
    'apps/androidApp/src/main/kotlin/', 'apps/webApp/src/jsMain/kotlin/',
    'apps/androidApp/build.gradle.kts', 'apps/core/build.gradle.kts', 'apps/core-net/build.gradle.kts',
    'apps/sender/build.gradle.kts', 'apps/webApp/build.gradle.kts',
  ],
  core: [
    'apps/core/', 'apps/core-net/', 'apps/build.gradle.kts', 'apps/settings.gradle.kts',
    'apps/gradle/', 'apps/gradle.properties', 'apps/gradlew', 'apps/gradlew.bat',
    'scripts/', 'package.json', 'test/suite-floors.json',
  ],
  screens: [
    'apps/', 'scripts/', 'package.json', 'test/suite-floors.json', 'test/fixtures/',
  ],
  web: [
    'apps/core/', 'apps/core-net/', 'apps/webApp/', 'apps/build.gradle.kts',
    'apps/settings.gradle.kts', 'apps/gradle/', 'apps/gradle.properties', 'apps/gradlew',
    'frontend/', 'scripts/', 'package.json', 'test/suite-floors.json',
  ],
  device: [
    'apps/', 'schema.sql', 'test/fixtures/', 'scripts/', 'package.json',
    'test/suite-floors.json',
  ],
};

const STAMP = '.gate/passed.json';

/** Every file git knows about, tracked or not, with a hash of its content. */
function treeHashes() {
  const names = execFileSync('git', ['ls-files', '-z', '--cached', '--others', '--exclude-standard'],
    { encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 }).split('\0').filter(Boolean);
  if (!names.length) return null;
  const blobs = execFileSync('git', ['hash-object', '--stdin-paths'],
    { input: `${names.join('\n')}\n`, encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 })
    .split('\n').filter(Boolean);
  if (blobs.length !== names.length) return null;
  return names.map((n, i) => [n, blobs[i]]);
}

const digest = (pairs) => createHash('sha256')
  .update(pairs.map(([n, h]) => `${n} ${h}`).sort().join('\n')).digest('hex').slice(0, 16);

/**
 * A hash per suite, plus one for everything no suite claims.
 *
 * The unclaimed hash is the safety catch: a file outside every map has
 * an unknown blast radius, so when it moves, nothing is skipped.
 * Returns null when git cannot answer, and then nothing is skipped
 * either — resuming is an optimisation and it fails closed.
 */
function fingerprints() {
  let pairs;
  try { pairs = treeHashes(); } catch { return null; }
  if (!pairs) return null;
  const claimed = new Set();
  const out = {};
  for (const [name, prefixes] of Object.entries(COVERS)) {
    const mine = pairs.filter(([f]) => prefixes.some((p) => (p.endsWith('/') ? f.startsWith(p) : f === p)));
    mine.forEach(([f]) => claimed.add(f));
    out[name] = digest(mine);
  }
  out.__unclaimed = digest(pairs.filter(([f]) => !claimed.has(f)));
  return out;
}

const readStamp = () => {
  try { return JSON.parse(readFileSync(STAMP, 'utf8')); } catch { return {}; }
};
const writeStamp = (stamp) => {
  try {
    mkdirSync('.gate', { recursive: true });
    writeFileSync(STAMP, `${JSON.stringify(stamp, null, 2)}\n`);
  } catch { /* remembering is a convenience; not being able to is not an error */ }
};

const only = process.argv.slice(2).filter((a) => !a.startsWith('-'));
// A name nobody recognises is a typo, and a gate command that silently
// runs nothing for it is the worst possible answer: it prints EVERY
// SUITE PASSED having run four of six. `shell` is the one people reach
// for — it lives inside `worker` — so say so rather than shrug.
const known = new Set(SUITES.map(([n]) => n).filter((n) => n !== 'emulator'));
const unknown = only.filter((n) => !known.has(n));
if (unknown.length) {
  console.error(`no suite called ${unknown.join(', ')}. There is: ${[...known].join(', ')}.`);
  if (unknown.includes('shell')) console.error('The shell harness runs inside `worker`.');
  process.exit(2);
}
const skipDevice = process.argv.includes('--no-device');
const fresh = process.argv.includes('--fresh');
const chosen = SUITES.filter(([name]) => (
  // `emulator` is the device suite's preflight, so asking for one asks
  // for the other and skipping one skips both. It is not a suite anybody
  // chooses on its own.
  (only.length === 0 || only.includes(name) || (name === 'emulator' && only.includes('device')))
  && !(skipDevice && (name === 'device' || name === 'emulator'))
));

const started = Date.now();
const now = fresh ? null : fingerprints();
const stamp = fresh ? {} : readStamp();
// Anything outside every suite's map moved, so nothing is trusted.
const unclaimedMoved = !now || stamp.__unclaimed !== now.__unclaimed;
const settled = (name) => Boolean(
  now && !unclaimedMoved && now[name] && stamp[name] === now[name],
);

const done = [];
const skipped = [];
let anyRan = false;
for (const [name, cmd, args] of chosen) {
  // The preflight is not a suite and has nothing to remember; it is
  // cheap and the emulator may have died since.
  if (name !== 'emulator' && settled(name)) {
    skipped.push(name);
    continue;
  }
  const at = Date.now();
  process.stdout.write(`\n=== ${name}\n`);
  const r = spawnSync(cmd, args, { stdio: 'inherit', shell: false });
  const mins = ((Date.now() - at) / 60000).toFixed(1);
  if (r.status !== 0) {
    // Everything proved before the failure is kept, so fixing this one
    // does not re-run them. That is the whole point: a fix used to cost
    // the suites in front of it as well as the one behind.
    // Including the unclaimed hash: those files did not move, and
    // leaving it out made the next run distrust everything and re-run
    // the suites this one had just proved.
    if (now) { stamp.__unclaimed = now.__unclaimed; writeStamp(stamp); }
    done.forEach((d) => console.log(`  ok   ${d}`));
    skipped.forEach((n) => console.log(`  ok   ${n} (already passed on this tree)`));
    console.error(`\nFAILED at ${name} after ${mins}m. Nothing after it ran.`);
    console.error('Main takes a fully green suite now. Fix it and run this again.');
    process.exit(1);
  }
  if (name !== 'emulator') {
    if (now) stamp[name] = now[name];
    anyRan = true;
  }
  done.push(`${name} (${mins}m)`);
}
if (now) {
  stamp.__unclaimed = now.__unclaimed;
  writeStamp(stamp);
}

// The floors, from the run that just happened.
//
// They used to be counted by running the whole suite AGAIN with a junit
// reporter — three sharded runs on top of the one that had just
// finished. Four runs to answer one question, and every test opens
// sockets that macOS holds in TIME_WAIT for fifteen seconds: 15,307 of
// them against an ephemeral range of 16,384, after which nothing on
// this machine can open a connection at all and the suite dies in six
// seconds looking like a code failure.
//
// `test:raw` now writes the junit file as it goes, so the numbers are
// already on disk and this is free.
// Whether worker RAN, not whether it is settled now — the loop has
// already stamped it by this point, so asking `settled` here always says
// yes and the floors were silently never checked. A gate that stops
// noticing a shrinking suite is the exact failure floors exist for.
if (chosen.some(([n]) => n === 'worker') && !skipped.includes('worker')) {
  const check = spawnSync('node', ['scripts/check-suite-floor.mjs',
    'worker=test-results/worker', 'shell=test-results/shell'], { stdio: 'inherit' });
  if (check.status !== 0) {
    console.error('\nThe suites passed but a floor did not. Raise it in the same commit:');
    console.error('  npm run check:floor -- --raise worker=test-results/worker shell=test-results/shell');
    process.exit(1);
  }
}

const mins = ((Date.now() - started) / 60000).toFixed(1);
console.log('\nEVERY SUITE PASSED');
done.forEach((d) => console.log(`  ok   ${d}`));
skipped.forEach((n) => console.log(`  ok   ${n} (already passed on this tree, skipped)`));
if (skipped.length && !anyRan) {
  console.log('\n  Nothing ran: every suite had already passed on exactly these files.');
  console.log('  `--fresh` runs them all again.');
}
if (skipDevice) {
  console.log('\n  NOT the device suite — you passed --no-device.');
  console.log('  That is not a full pass and main asks for a full one.');
}
console.log(`\n${mins}m`);
