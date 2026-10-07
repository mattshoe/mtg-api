#!/bin/bash
# PostToolUse hook. Fires on every Write/Edit; does nothing unless the
# file that changed was a request. Reads the hook payload on stdin.
#
# The launchd watcher covers files dropped in while nobody is here. This
# covers files written during a session, where launchd's WatchPaths may
# already have fired and been throttled.
#
# Whether a given path counts is decided by scripts/intake.mjs, which the
# suite tests. Nothing here guesses at a path.
set -uo pipefail
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

path="$(cat | /usr/bin/python3 -c \
  'import json,sys; print(json.load(sys.stdin).get("tool_input",{}).get("file_path",""))' \
  2>/dev/null)"

[ -z "$path" ] && exit 0
( cd "$REPO" && node scripts/intake.mjs fires "$path" ) || exit 0

nohup "$REPO/scripts/intake/dispatch.sh" >/dev/null 2>&1 &
exit 0
