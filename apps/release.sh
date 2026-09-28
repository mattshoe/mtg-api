#!/usr/bin/env bash
# Cut a release. One argument: the version, e.g. ./release.sh 1.1.0
#
# Bumps versionName and versionCode, builds a signed APK, tags, and
# publishes it to GitHub Releases as mtg-collection.apk — which is the
# file behind https://mtg.mattshoe.org/app and the one Obtainium watches.
set -euo pipefail

[ $# -eq 1 ] || { echo "usage: ./release.sh <version>   e.g. ./release.sh 1.1.0" >&2; exit 1; }
VERSION="$1"
cd "$(dirname "$0")"   # apps/, inside the mtg-api repo

export JAVA_HOME="${JAVA_HOME:-/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"

CODE=$(( $(grep -o 'versionCode = [0-9]*' app/build.gradle.kts | grep -o '[0-9]*') + 1 ))
sed -i '' "s/versionCode = .*/versionCode = $CODE/" app/build.gradle.kts
sed -i '' "s/versionName = .*/versionName = \"$VERSION\"/" app/build.gradle.kts

# Never ship something the emulator has not run.
./gradlew :app:connectedDebugAndroidTest
./gradlew :app:assembleRelease

OUT="$(mktemp -d)/mtg-collection.apk"
cp app/build/outputs/apk/release/app-release.apk "$OUT"
"$ANDROID_HOME"/build-tools/35.0.0/apksigner verify --print-certs "$OUT" | head -2

git add -A
git commit -m "Release $VERSION"
git push
gh release create "v$VERSION" "$OUT" --title "v$VERSION" --generate-notes
echo
echo "https://github.com/mattshoe/mtg-api/releases/latest/download/mtg-collection.apk"
