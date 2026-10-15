#!/usr/bin/env node
// A test run that cannot outlive itself.
//
// Twice in one afternoon a `npm test` was left running for over an
// hour: the shell around it was killed when the machine ran low on
// memory, and `node` and `workerd` underneath it were never told. They
// kept a core of the CPU and a lot of RAM, which made the machine run
// low on memory, which killed the next run's shell, and so on. Nothing
// in the output said any of this was happening, because a hung run and
// a slow run look identical from outside.
//
// So every test command goes through here, and this does three things
// that between them make that impossible:
//
//   1. Before starting, it kills whatever the last run left behind.
//      That alone breaks the loop above: a run orphaned by a SIGKILL
//      that no handler can catch is cleaned up by the next one.
//   2. It runs the command in a process group of its own and kills the
//      whole group — not just the shell — on timeout, on Ctrl-C, and
//      on its own exit.
//   3. It prints how long the run took, and says plainly when it has
//      killed something, so a slow suite and a stuck one stop looking
//      the same.
//
// Usage: node scripts/guard.mjs <seconds> -- <command> [args...]
import { spawn } from 'node:child_process';
import { createHash } from 'node:crypto';
import { readFileSync, writeFileSync, unlinkSync, existsSync, mkdirSync } from 'node:fs';
import { join } from 'node:path';

const split = process.argv.indexOf('--');
const seconds = Number(process.argv[2]);
if (split < 0 || !Number.isFinite(seconds) || seconds <= 0) {
  console.error('usage: node scripts/guard.mjs <seconds> -- <command> [args...]');
  process.exit(2);
}
let [cmd, ...args] = process.argv.slice(split + 1);

// Gradle does not share a laptop. `--no-daemon` means a full JVM start every
// time, `~/.gradle` is locked, and androidApp's `forkEvery(1)` spawns a JVM per
// test class — three concurrent builds thrash rather than going three times
// faster, and one afternoon measured them an order of magnitude slower.
//
// That is a reason to serialise the BUILD, not the agents. Agents spend most of
// their wall clock reading, editing and waiting on CI, none of which contends;
// only this step does. So a Gradle invocation waits its turn behind `lockf`,
// which blocks in the kernel — not a polling loop, and it cannot orphan: the
// lock dies with the process holding it.
const GRADLE_LOCK = process.env.INTAKE_GRADLE_LOCK
  || join(process.env.HOME || '/tmp', '.mtg-gradle.lock');
const isGradle = /(^|\/)gradlew?$/.test(cmd) || args.some((a) => /(^|\/)gradlew?$/.test(a));

// Make Gradle keep its JVM, and skip configuring the build it already
// configured.
//
// `~/.gradle/gradle.properties` has carried `org.gradle.daemon=false`
// since July 2024 — a machine-wide setting with nothing to do with this
// repository — and the user home file outranks a project one, so
// nothing committed here could turn it back on. Every single Gradle
// command was therefore starting a JVM and throwing it away, and Gradle
// said so on every run: "To honour the JVM settings for this build a
// single-use Daemon process will be forked... Daemon will be stopped at
// the end of the build." Nobody read it.
//
// The cost: a narrow test, which is what the TDD cycle runs over and
// over, took 11.9 seconds of which 10.0 was Gradle starting up. Agents
// spent 22.3 measured hours on narrow tests.
//
// A command-line flag is the only thing that outranks the home file.
// Measured after: 11.9s to 0.8s re-running an unchanged :core test, and
// 46.5s to 24.9s for a real edit on :androidApp.
//
// MTG_GRADLE_PLAIN=1 turns both off, for when you need to know whether
// the configuration cache is what is lying to you.
if (isGradle && process.env.MTG_GRADLE_PLAIN !== '1') {
  const already = (f) => args.some((a) => a === f || a.startsWith(`${f}=`));
  for (const flag of ['--daemon', '--configuration-cache']) {
    if (!already(flag)) args = [...args, flag];
  }
}
if (isGradle && process.env.INTAKE_NO_GRADLE_LOCK !== '1') {
  // `lockf` is BSD (macOS), `flock` is Linux. Use whichever exists and run
  // unlocked if neither does — CI builds one thing at a time anyway, and a
  // missing mutex must not stop a build.
  // Long enough to outlast another full gate run, and then build anyway.
  //
  // This was five minutes, and the comment here claimed that when the wait
  // ran out "the build runs anyway and the two contend". It did not: `lockf
  // -t` exits without running the command, so a second agent running the
  // gate got
  //
  //     lockf: ~/.mtg-gradle.lock: already locked
  //     FAILED at core after 5.0m. Nothing after it ran.
  //
  // — a red with nothing wrong in the code, paid for with a whole re-run.
  // A gate run holds this lock across its Gradle suites and the device one
  // alone is seventeen minutes, so five minutes of patience guaranteed the
  // collision.
  //
  // Thirty minutes of patience, and if it really does run out, build
  // anyway. Two builds contending is slower than queueing and far better
  // than a suite reporting a failure it did not have, which is what the
  // comment always said and the code never did.
  const wait = String(Number(process.env.MTG_GRADLE_LOCK_WAIT) || 1800);
  // 75 is EX_TEMPFAIL from sysexits.h, which is what `lockf` exits when
  // it cannot take the lock; `flock` is told to use the same so one
  // branch handles both. A Gradle build exiting 75 on its own would be
  // misread as contention, and in ten thousand runs it never has.
  let locker = null;
  if (existsSync('/usr/bin/lockf')) {
    // -k keeps the lock file, -t is how long to wait for it.
    locker = `/usr/bin/lockf -k -t ${wait} ${JSON.stringify(GRADLE_LOCK)}`;
  } else if (existsSync('/usr/bin/flock')) {
    locker = `/usr/bin/flock -w ${wait} -E 75 ${JSON.stringify(GRADLE_LOCK)}`;
  }
  if (locker) {
    const script = `${locker} "$@"\n`
      + 'rc=$?\n'
      + 'if [ $rc -eq 75 ]; then\n'
      + `  echo "guard: gave up waiting for the Gradle lock after ${wait}s; building anyway" >&2\n`
      + '  exec "$@"\n'
      + 'fi\n'
      + 'exit $rc\n';
    args = ['-c', script, 'guard-lock', cmd, ...args];
    cmd = '/bin/bash';
  }
}

// One file per command, not one for all of them. A single shared
// pidfile would mean starting the web suite killed the core suite
// running beside it, which is a worse bug than the one this fixes.
const DIR = join(process.cwd(), '.test-guard');
const PIDFILE = join(
  DIR,
  createHash('sha1').update([cmd, ...args].join(' ')).digest('hex').slice(0, 16) + '.pid',
);
mkdirSync(DIR, { recursive: true });

/** Kill a whole process group, politely and then not. */
function killGroup(pgid, why) {
  if (!pgid) return false;
  try { process.kill(-pgid, 'SIGTERM'); } catch { return false; }
  console.error(`guard: ${why} — sent SIGTERM to process group ${pgid}`);
  const deadline = Date.now() + 5000;
  while (Date.now() < deadline) {
    try { process.kill(-pgid, 0); } catch { return true; }
  }
  try { process.kill(-pgid, 'SIGKILL'); } catch { /* already gone */ }
  console.error(`guard: process group ${pgid} would not stop, killed it`);
  return true;
}

// 1. Whatever the last run left behind.
if (existsSync(PIDFILE)) {
  const stale = Number(readFileSync(PIDFILE, 'utf8').trim());
  if (killGroup(stale, `a previous run was still alive`)) {
    console.error('guard: cleaned up a run that outlived its shell');
  }
  try { unlinkSync(PIDFILE); } catch { /* raced with another guard */ }
}

// 2. Its own process group, so the whole tree can be killed at once.
const started = Date.now();
const child = spawn(cmd, args, { stdio: 'inherit', detached: true });
writeFileSync(PIDFILE, String(child.pid));

// A command that is not there must say so, not throw an unhandled
// 'error' event and bury the reason under a Node stack trace.
child.on('error', (err) => {
  clearTimeout(timer);
  try { unlinkSync(PIDFILE); } catch { /* already gone */ }
  console.error(`guard: could not run \`${[cmd, ...args].join(' ')}\` — ${err.message}`);
  process.exit(127);
});

let killedBy = null;
const timer = setTimeout(() => {
  killedBy = `timed out after ${seconds}s`;
  killGroup(child.pid, killedBy);
}, seconds * 1000);

function stop(signal) {
  killedBy = killedBy ?? `caught ${signal}`;
  killGroup(child.pid, killedBy);
}
process.on('SIGINT', () => stop('SIGINT'));
process.on('SIGTERM', () => stop('SIGTERM'));
process.on('SIGHUP', () => stop('SIGHUP'));

child.on('exit', (code, signal) => {
  clearTimeout(timer);
  try { unlinkSync(PIDFILE); } catch { /* already gone */ }
  const took = ((Date.now() - started) / 1000).toFixed(1);
  if (killedBy) {
    console.error(`guard: killed after ${took}s — ${killedBy}`);
    process.exit(124);
  }
  console.error(`guard: finished in ${took}s`);
  process.exit(signal ? 1 : (code ?? 0));
});
