// Make sure there is a phone before the gate asks for one.
//
// `npm run test:everything` is the whole merge gate and the device suite
// is the half of it nothing else covers. It assumed a booted emulator was
// already there, and nothing anywhere in this repo ever started one. With
// no phone attached Gradle fails in 24 seconds:
//
//   com.android.builder.testing.api.DeviceException: No connected devices!
//
// which reads like a broken change and is not one. It happened for real
// when an orphaned emulator from a dead agent exited between one gate run
// and the next, four green suites in.
//
// So the gate boots one itself. Already attached: do nothing, because
// somebody may be watching that screen. Nothing attached: start the first
// AVD and wait for `sys.boot_completed`, which is the only signal that
// means the device will accept an install. No AVD at all is the one case
// this cannot fix, and it says so in a sentence naming the command that
// makes one.

import { spawnSync } from 'node:child_process';
import { existsSync } from 'node:fs';

// The SDK is not on a plain PATH on macOS. Take whatever the environment
// has, then the two standard spots, so this works from a bare shell.
const SDK = process.env.ANDROID_HOME || process.env.ANDROID_SDK_ROOT
  || `${process.env.HOME}/Library/Android/sdk`;
const PATH = [
  process.env.PATH,
  `${SDK}/platform-tools`,
  `${SDK}/emulator`,
].filter(Boolean).join(':');
const env = { ...process.env, PATH };

const POLL_MS = Number(process.env.MTG_EMULATOR_POLL_MS || 2000);
// Ten minutes. A cold emulator on a laptop under load is a couple of
// minutes; anything past this is wedged and saying so beats hanging the
// gate until somebody notices.
const BOOT_CEILING_MS = 10 * 60 * 1000;

/**
 * Every adb call has a ceiling, because a hang is the worst failure
 * this script can have.
 *
 * The gate once reported "FAILED at emulator after 27.2m": the
 * preflight said "emulator-5554 already attached", the emulator then
 * went `unauthorized` underneath it, and `adb uninstall` never
 * returned. It held the one machine, said nothing, and the
 * twenty-five minutes of suite behind it never happened.
 *
 * Thirty seconds is far more than any of these need — `adb devices`
 * and an uninstall are instant against a healthy phone — so crossing
 * it means the phone is not healthy, which is worth saying out loud.
 */
const ADB_CEILING_MS = Number(process.env.MTG_ADB_TIMEOUT_MS) || 30_000;
const run = (cmd, args) => spawnSync(cmd, args, {
  encoding: 'utf8', env, timeout: ADB_CEILING_MS,
});
/** Did that call run out of time rather than answer? */
const wedged = (r) => r?.error?.code === 'ETIMEDOUT' || r?.signal === 'SIGTERM';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** Devices adb will actually install to. `offline` and `unauthorized` are not. */
function attached() {
  const r = run('adb', ['devices']);
  if (r.error) return [];
  return (r.stdout || '').split('\n').slice(1)
    .map((l) => l.trim().split(/\s+/))
    .filter(([name, state]) => name && state === 'device')
    .map(([name]) => name);
}

/**
 * The app and its test APK, cleared off before the suite installs them.
 *
 * `test:android` passes `leaveApksInstalledAfterRun=true`, so whatever
 * ran last is still on the phone. The moment the signing key differs —
 * a release build installed by hand, a regenerated debug keystore —
 * every install after it dies with
 *
 *   INSTALL_FAILED_UPDATE_INCOMPATIBLE: Existing package
 *   org.mattshoe.mtg.share signatures do not match newer version
 *
 * and the device suite is red until a person uninstalls by hand. That
 * is not something an agent is going to work out at three in the
 * morning. Two seconds out of seventeen minutes buys the whole failure
 * away.
 */
const PACKAGES = ['org.mattshoe.mtg.share', 'org.mattshoe.mtg.share.test'];

/**
 * The same mutex every Gradle invocation uses.
 *
 * This ran outside it and collided with a live agent: the agent's
 * device suite was installing while this was uninstalling, adb refused
 * the uninstall, this warned and carried on, and the suite then failed
 * two minutes later with INSTALL_FAILED_UPDATE_INCOMPATIBLE — a worse
 * message about the same collision. Touching the phone is exactly the
 * kind of thing the lock exists for.
 */
const LOCK = process.env.INTAKE_GRADLE_LOCK
  || `${process.env.HOME || '/tmp'}/.mtg-gradle.lock`;
const WAIT = String(Number(process.env.MTG_ADB_LOCK_WAIT) || 900);
const locked = (args) => (existsSync('/usr/bin/lockf')
  ? run('/usr/bin/lockf', ['-k', '-t', WAIT, LOCK, ...args])
  : run(args[0], args.slice(1)));

function clearStale() {
  const r = run('adb', ['shell', 'pm', 'list', 'packages']);
  if (wedged(r)) {
    console.error('emulator: adb is not answering (listing packages ran out of time).');
    console.error('The phone is wedged. `adb kill-server` and restart the emulator.');
    process.exit(6);
  }
  const listed = r.stdout || '';
  for (const pkg of PACKAGES) {
    if (!listed.includes(`package:${pkg}`)) continue;
    const r = locked(['adb', 'uninstall', pkg]);
    if (wedged(r)) {
      console.error(`emulator: adb is not answering (uninstall ${pkg} ran out of time).`);
      console.error('The phone is wedged. `adb kill-server` and restart the emulator.');
      process.exit(6);
    }
    if (r.status !== 0) {
      // Carrying on here just moves the failure two minutes later and
      // makes it harder to read.
      console.error(`emulator: the phone would not remove ${pkg}: ${(r.stdout || r.stderr || '').trim().slice(0, 120)}`);
      console.error('The install that follows cannot work. Something else is probably using the phone.');
      process.exit(7);
    }
    console.log(`emulator: removed the old ${pkg}`);
  }
}

const already = attached();
if (already.length) {
  console.log(`emulator: ${already.join(', ')} already attached`);
  clearStale();
  process.exit(0);
}

// A phone that is there but not usable. On this machine the emulator came
// up `unauthorized` and Gradle treats that as no device at all; restarting
// adb cleared it. Worth one try before booting anything, because the
// alternative is a SECOND emulator next to the live one on a 16GB laptop.
const listed = (run('adb', ['devices']).stdout || '').split('\n').slice(1)
  .map((l) => l.trim()).filter(Boolean);
if (listed.length) {
  console.log(`emulator: ${listed.join(', ')} — restarting adb`);
  run('adb', ['kill-server']);
  run('adb', ['start-server']);
  const second = attached();
  if (second.length) {
    console.log(`emulator: ${second.join(', ')} attached after an adb restart`);
    clearStale();
    process.exit(0);
  }
  console.log('emulator: still nothing usable after restarting adb');
}

const avds = (run('emulator', ['-list-avds']).stdout || '')
  .split('\n').map((l) => l.trim()).filter(Boolean);
if (!avds.length) {
  console.error('emulator: no Android Virtual Device exists, so the device suite cannot run.');
  console.error(`Make one: ${SDK}/cmdline-tools/latest/bin/avdmanager create avd -n Pixel_9 -k "system-images;android-35;google_apis;arm64-v8a"`);
  console.error('Or run the gate with --no-device, which is not a full pass.');
  process.exit(3);
}

const avd = avds[0];
console.log(`emulator: nothing attached, starting ${avd}`);
// Detached and with its output discarded: this process waits for the boot
// flag, it does not babysit the emulator's stdout. `-no-snapshot-save`
// keeps a crashed run from poisoning the next one's snapshot.
const child = spawnSync('bash', ['-c',
  // Headless, and the same flags both CI workflows already used.
  //
  // Matt: "why the fuck would you be running headed emulators?!" No
  // reason — I wrote this without `-no-window` while `apps.yml` and
  // `emulator-cache.yml` both had it. A window steals focus, costs
  // frames on the laptop that is already the bottleneck, and nothing
  // ever looks at it. `-camera-back none` because emulating a camera
  // nobody uses is pure cost.
  `nohup emulator -avd ${JSON.stringify(avd)} -no-window -no-snapshot-save `
  + '-noaudio -no-boot-anim -camera-back none -gpu swiftshader_indirect '
  + '>/dev/null 2>&1 &'],
{ encoding: 'utf8', env });
if (child.status !== 0) {
  console.error(`emulator: could not start ${avd}: ${child.stderr || child.status}`);
  process.exit(4);
}

run('adb', ['wait-for-device']);
const until = Date.now() + BOOT_CEILING_MS;
while (Date.now() < until) {
  const r = run('adb', ['shell', 'getprop', 'sys.boot_completed']);
  if ((r.stdout || '').trim() === '1') {
    console.log(`emulator: ${avd} booted`);
    clearStale();
    process.exit(0);
  }
  await sleep(POLL_MS);
}
console.error(`emulator: ${avd} did not finish booting in 10 minutes.`);
process.exit(5);
