#!/bin/bash
# Loads the request watcher. Run once; it survives reboots after that.
set -euo pipefail
PLIST="$HOME/Library/LaunchAgents/com.matt.mtg.intake.plist"
cp "$(dirname "$0")/com.matt.mtg.intake.plist" "$PLIST"
mkdir -p "$(dirname "$0")/../../.intake"
launchctl unload "$PLIST" 2>/dev/null || true
launchctl load "$PLIST"
echo "loaded. drop a file in requests/ and watch .intake/intake.log"
