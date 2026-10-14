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

const run = (cmd, args) => spawnSync(cmd, args, { encoding: 'utf8', env });
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

const already = attached();
if (already.length) {
  console.log(`emulator: ${already.join(', ')} already attached`);
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
  `nohup emulator -avd ${JSON.stringify(avd)} -no-snapshot-save -no-boot-anim `
  + '-gpu swiftshader_indirect >/dev/null 2>&1 &'],
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
    process.exit(0);
  }
  await sleep(POLL_MS);
}
console.error(`emulator: ${avd} did not finish booting in 10 minutes.`);
process.exit(5);
