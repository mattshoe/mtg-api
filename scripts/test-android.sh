#!/bin/bash
#
# The device suite: what actually needs a phone.
#
# Matt: "26 minutes is way too fucking long for validations." Of that,
# 19 minutes was the emulator re-running the 649 tests in `sharedTest`
# that had already passed on Robolectric two minutes earlier as the
# `screens` suite. Same source, same assertions, eight times slower.
#
# `scripts/device-classes.mjs` works out which classes genuinely need
# hardware — anything calling `Parity.needsRealRendering()`, which
# SKIPS on the JVM, plus every end-to-end journey in `androidTest`.
#
# This is a script rather than a line in package.json because the class
# list needs a command substitution, and npm puts its scripts through a
# shell that ate it: the runner was handed the literal string
# `$(node` and ran one test.
#
# `--all` runs the lot on hardware, for when you want belt and braces
# before a release.
set -uo pipefail
cd "$(dirname "$0")/.."

node scripts/emulator.mjs || exit $?

if [ "${1:-}" = "--all" ]; then
  node scripts/guard.mjs 2700 -- ./apps/gradlew -p apps :androidApp:connectedDebugAndroidTest \
    -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true || exit $?
  exec node scripts/check-test-count.mjs apps/androidApp/src/sharedTest \
    apps/androidApp/build/outputs/androidTest-results
fi

CLASSES="$(node scripts/device-classes.mjs)" || exit $?
# An empty filter runs EVERYTHING, which would put the 19 minutes back
# without saying a word. device-classes.mjs already refuses to print
# nothing; this is the second lock on the same door.
if [ -z "$CLASSES" ]; then
  echo "test-android: no classes to run on the phone, which cannot be right" >&2
  exit 1
fi

echo "device: $(printf '%s' "$CLASSES" | tr ',' '\n' | wc -l | tr -d ' ') tests and classes that need a phone"
exec node scripts/guard.mjs 2700 -- ./apps/gradlew -p apps :androidApp:connectedDebugAndroidTest \
  -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true \
  -Pandroid.testInstrumentationRunnerArguments.class="$CLASSES"
