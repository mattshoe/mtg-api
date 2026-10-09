#!/bin/bash
# Stops the request watcher, and keeps it stopped.
#
# `launchctl bootout` on its own is not enough. The PostToolUse hook in
# `.claude/settings.json` is committed, so after unloading the job an edit
# to any requests/*.md from any Claude session in this repo still spawned a
# dispatcher, which still launched a `claude -p` builder with
# `bypassPermissions`. The only way to stop the machine was hand-editing a
# committed file.
#
# So the off switch is a file: `.intake/disabled`. Both `hook.sh` and
# `dispatch.sh` exit 0 immediately when it exists. Delete it to turn the
# machine back on; nothing else is needed.
set -uo pipefail
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
STATE="$REPO/.intake"
LABEL="com.matt.mtg.intake"
PLIST="$HOME/Library/LaunchAgents/$LABEL.plist"

mkdir -p "$STATE"
touch "$STATE/disabled"
echo "off: $STATE/disabled exists, so hook.sh and dispatch.sh both exit 0"

# `bootout` is the modern spelling and says something useful when the job
# was not loaded. `unload` is the fallback for older systems.
if launchctl bootout "gui/$(id -u)/$LABEL" 2>/dev/null; then
  echo "booted out $LABEL"
elif launchctl unload "$PLIST" 2>/dev/null; then
  echo "unloaded $LABEL"
else
  echo "$LABEL was not loaded"
fi

if [ -f "$PLIST" ]; then
  rm -f "$PLIST"
  echo "removed $PLIST"
fi

# The inbox job, which collects tasks written in the app.
INBOX_LABEL="com.matt.mtg.intake-inbox"
launchctl bootout "gui/$(id -u)/$INBOX_LABEL" 2>/dev/null || true
rm -f "$HOME/Library/LaunchAgents/$INBOX_LABEL.plist"

echo
echo "To turn it back on:  rm $STATE/disabled && bash $REPO/scripts/intake/install.sh"
