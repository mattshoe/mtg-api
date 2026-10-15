// The gate's emulator preflight, driven for real with a stubbed SDK.
//
// `npm run test:everything` is the whole merge gate, and the device suite
// is the half of it nothing else covers. It assumed a booted emulator was
// already there. When there was not one, Gradle failed in 24 seconds with
// "com.android.builder.testing.api.DeviceException: No connected devices!"
// — which reads like a broken change and is not one. It happened on this
// branch after an orphaned emulator from a dead agent exited.
//
// Nothing started an emulator anywhere in the repo. So the one command
// that decides whether main moves depended on a person having remembered
// to boot a phone by hand, and said nothing about it until the last of
// its five suites.
//
// `adb` and `emulator` here are scripts on PATH that record their argv.
// No SDK is touched and no emulator is started.

import { describe, it, expect, afterEach } from 'vitest'
import { spawnSync } from 'node:child_process'
import { mkdtempSync, mkdirSync, writeFileSync, readFileSync, rmSync, chmodSync } from 'node:fs'
import { join, dirname } from 'node:path'
import { tmpdir } from 'node:os'
import { fileURLToPath } from 'node:url'

const REPO = dirname(dirname(fileURLToPath(import.meta.url)))
const SCRIPT = join(REPO, 'scripts', 'emulator.mjs')

let root
afterEach(() => { if (root) rmSync(root, { recursive: true, force: true }); root = null })

/**
 * A fake Android SDK on PATH. `adb` answers `devices` from a file the test
 * writes, so a test can say "nothing attached" and then "attached" without
 * timing anything.
 */
function sdk({ attached = [], avds = ['Pixel_9'], bootsAfter = 0, unauthorized = false, installed = false, hang = false } = {}) {
  root = mkdtempSync(join(tmpdir(), 'emu-'))
  const bin = join(root, 'bin')
  mkdirSync(bin, { recursive: true })
  const calls = join(root, 'calls.txt')
  writeFileSync(calls, '')
  const state = join(root, 'state.txt')
  // 0 nothing, 1 attached and usable, 2 attached but unauthorized until
  // adb is restarted — which is what this machine actually did.
  writeFileSync(state, unauthorized ? '2' : String(attached.length))

  writeFileSync(join(bin, 'adb'), `#!/bin/bash
printf 'adb %s\\n' "$*" >> ${JSON.stringify(calls)}
n=$(cat ${JSON.stringify(state)})
case "$1" in
  devices)
    echo "List of devices attached"
    [ "$n" = "1" ] && echo "emulator-5554	device"
    [ "$n" = "2" ] && echo "emulator-5554	unauthorized"
    exit 0 ;;
  kill-server)
    # Restarting adb is what clears an unauthorized emulator.
    [ "$n" = "2" ] && echo 1 > ${JSON.stringify(state)}
    exit 0 ;;
  start-server|wait-for-device)
    exit 0 ;;
  uninstall)
    ${hang ? 'sleep 300' : 'true'}
    echo Success; exit 0 ;;
  shell)
    if [ "$2" = "pm" ]; then
      if [ ${JSON.stringify(installed ? '1' : '0')} = 1 ]; then
        echo "package:org.mathoe.other"
        echo "package:org.mattshoe.mtg.share"
        echo "package:org.mattshoe.mtg.share.test"
      fi
      exit 0
    fi
    # getprop sys.boot_completed: boots after the test's chosen polls.
    seen=$(grep -c 'adb shell getprop' ${JSON.stringify(calls)})
    if [ "$seen" -gt ${JSON.stringify(String(bootsAfter))} ]; then echo 1; else echo 0; fi
    exit 0 ;;
esac
exit 0
`)
  writeFileSync(join(bin, 'emulator'), `#!/bin/bash
printf 'emulator %s\\n' "$*" >> ${JSON.stringify(calls)}
if [ "$1" = "-list-avds" ]; then printf '%s\\n' ${avds.map((a) => JSON.stringify(a)).join(' ')}; exit 0; fi
# Starting one makes it attached from now on.
echo 1 > ${JSON.stringify(state)}
exit 0
`)
  chmodSync(join(bin, 'adb'), 0o755)
  chmodSync(join(bin, 'emulator'), 0o755)
  return {
    bin,
    calls: () => readFileSync(calls, 'utf8'),
    run: (extra = {}) => spawnSync('node', [SCRIPT], {
      encoding: 'utf8',
      timeout: 60_000,
      env: { ...process.env, PATH: `${bin}:${process.env.PATH}`, MTG_EMULATOR_POLL_MS: '10', ...extra },
    }),
  }
}

describe('the gate makes sure there is a phone', () => {
  it('says nothing and starts nothing when one is already attached', () => {
    const box = sdk({ attached: ['emulator-5554'] })
    const r = box.run()
    expect(r.status, r.stderr).toBe(0)
    expect(box.calls(), 'it booted a second emulator over a working one')
      .not.toContain('emulator -avd')
  })

  it('boots one when nothing is attached, and waits for it', () => {
    const box = sdk({ attached: [], bootsAfter: 2 })
    const r = box.run()
    expect(r.status, r.stderr).toBe(0)
    expect(box.calls()).toContain('emulator -avd Pixel_9')
    // And it did not call the suite's bluff by returning before boot.
    expect(box.calls()).toContain('adb shell getprop sys.boot_completed')
  })

  it('restarts adb when the phone is there but unauthorized, rather than booting a second one', () => {
    // What actually happened on this machine: the emulator came up and
    // `adb devices` listed it as `unauthorized`, which Gradle treats as no
    // device at all. `adb kill-server && adb start-server` fixed it. Without
    // this the preflight would see nothing usable and boot a SECOND emulator
    // on a 16GB laptop next to the one already running.
    const box = sdk({ unauthorized: true })
    const r = box.run()
    expect(r.status, r.stderr).toBe(0)
    expect(box.calls()).toContain('adb kill-server')
    expect(box.calls(), 'it booted a second emulator next to the live one')
      .not.toContain('emulator -avd')
  })

  it('clears a stale install before the suite tries to replace it', () => {
    // What killed a device run: the emulator still had the app on it from
    // an earlier build signed with a different key, and every install
    // after that died with
    //
    //   INSTALL_FAILED_UPDATE_INCOMPATIBLE: Existing package
    //   org.mattshoe.mtg.share signatures do not match newer version
    //
    // The suite stays red until somebody uninstalls by hand, which is not
    // a thing an agent at 3am is going to work out. `test:android` passes
    // `leaveApksInstalledAfterRun=true`, so the stale copy is there by
    // design; removing it costs a couple of seconds out of seventeen
    // minutes and takes the whole failure away.
    const box = sdk({ attached: ['emulator-5554'], installed: true })
    const r = box.run()
    expect(r.status, r.stderr).toBe(0)
    expect(box.calls()).toContain('adb uninstall org.mattshoe.mtg.share')
    expect(box.calls()).toContain('adb uninstall org.mattshoe.mtg.share.test')
  })

  it('does not bother uninstalling what is not there', () => {
    const box = sdk({ attached: ['emulator-5554'], installed: false })
    const r = box.run()
    expect(r.status, r.stderr).toBe(0)
    expect(box.calls()).not.toContain('adb uninstall org.mattshoe.mtg.share')
  })

  it('gives up on an adb that has wedged, rather than hanging the gate behind it', () => {
    // What happened: the preflight printed "emulator-5554 already
    // attached", then sat there. The gate reported "FAILED at emulator
    // after 27.2m" — the emulator had gone `unauthorized` underneath
    // it and `adb uninstall` never returned, because none of these
    // calls had a timeout.
    //
    // A hang is the worst failure this script can have. It holds the
    // one machine, says nothing, and the twenty-five minutes behind it
    // never happen.
    const box = sdk({ attached: ['emulator-5554'], installed: true, hang: true })
    const started = Date.now()
    const r = box.run({ MTG_ADB_TIMEOUT_MS: '1500' })
    const took = (Date.now() - started) / 1000
    expect(took, `it hung for ${took}s instead of giving up`).toBeLessThan(60)
    expect(`${r.stderr}${r.stdout}`, 'it did not say adb had stopped answering')
      .toMatch(/not answering|timed out|wedged/i)
  })

  it('fails with a sentence a person can act on when the SDK has no AVD', () => {
    const box = sdk({ attached: [], avds: [] })
    const r = box.run()
    expect(r.status).not.toBe(0)
    expect(`${r.stderr}${r.stdout}`, 'the message did not say what to do')
      .toMatch(/no android virtual device/i)
  })
})
