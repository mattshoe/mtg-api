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

import { spawnSync } from 'node:child_process';

/** Every suite, in the order worth running them. */
const SUITES = [
  ['worker', 'npm', ['run', 'test:raw']],
  ['core', 'npm', ['run', 'test:core']],
  ['screens', 'npm', ['run', 'test:screens']],
  ['web', 'npm', ['run', 'test:web']],
  // Last and slowest. The emulator is the only place the device tests
  // run at all, and with CI gone it is the only place full stop.
  ['device', 'npm', ['run', 'test:android']],
];

const only = process.argv.slice(2).filter((a) => !a.startsWith('-'));
// A name nobody recognises is a typo, and a gate command that silently
// runs nothing for it is the worst possible answer: it prints EVERY
// SUITE PASSED having run four of six. `shell` is the one people reach
// for — it lives inside `worker` — so say so rather than shrug.
const known = new Set(SUITES.map(([n]) => n));
const unknown = only.filter((n) => !known.has(n));
if (unknown.length) {
  console.error(`no suite called ${unknown.join(', ')}. There is: ${[...known].join(', ')}.`);
  if (unknown.includes('shell')) console.error('The shell harness runs inside `worker`.');
  process.exit(2);
}
const skipDevice = process.argv.includes('--no-device');
const chosen = SUITES.filter(([name]) => (
  (only.length === 0 || only.includes(name)) && !(skipDevice && name === 'device')
));

const started = Date.now();
const done = [];
for (const [name, cmd, args] of chosen) {
  const at = Date.now();
  process.stdout.write(`\n=== ${name}\n`);
  const r = spawnSync(cmd, args, { stdio: 'inherit', shell: false });
  const mins = ((Date.now() - at) / 60000).toFixed(1);
  if (r.status !== 0) {
    done.forEach((d) => console.log(`  ok   ${d}`));
    console.error(`\nFAILED at ${name} after ${mins}m. Nothing after it ran.`);
    console.error('Main takes a fully green suite now. Fix it and run this again.');
    process.exit(1);
  }
  done.push(`${name} (${mins}m)`);
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
if (chosen.some(([n]) => n === 'worker')) {
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
if (skipDevice) {
  console.log('\n  NOT the device suite — you passed --no-device.');
  console.log('  That is not a full pass and main asks for a full one.');
}
console.log(`\n${mins}m`);
