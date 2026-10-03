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
const [cmd, ...args] = process.argv.slice(split + 1);

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
