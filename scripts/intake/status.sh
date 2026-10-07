#!/bin/bash
# The whole queue in about fifteen lines.
#
# This exists to keep an agent's context small. Reading six logs, four
# `gh` calls and a worktree listing to answer "what is happening" burns
# thousands of tokens per report and crowds out the actual work. One
# command, one compact table, no log bodies.
set -uo pipefail
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO" || exit 1
STATE="$REPO/.intake"

echo "REQUESTS"
for f in requests/*.md; do
  [ "$f" = "requests/README.md" ] && continue
  [ -f "$f" ] || continue
  n="$(basename "$f" .md)"
  if [ -d "$STATE/$n.building" ]; then st="building"
  elif ! /usr/bin/grep -qF '## Plan' "$f"; then st="untriaged"
  elif /usr/bin/grep -q '^status: needs-matt' "$f"; then st="NEEDS MATT"
  elif [ -d "$STATE/wt/$n" ]; then st="STALLED, worktree kept"
  else st="ready"
  fi
  printf '  %-38s %s\n' "$n" "$st"
done
[ -n "$(ls requests/done/*.md 2>/dev/null)" ] && {
  echo "DONE"
  for f in requests/done/*.md; do printf '  %s\n' "$(basename "$f" .md)"; done
}

echo "OPEN PRS"
gh pr list --limit 20 --json number,headRefName,statusCheckRollup \
  --jq '.[] | "  #\(.number) \(.headRefName) " +
    ( [.statusCheckRollup[]? | select(.conclusion=="FAILURE")] | length | tostring ) + " failing, " +
    ( [.statusCheckRollup[]? | select(.status=="IN_PROGRESS" or .status=="QUEUED" or .status=="PENDING")] | length | tostring ) + " pending, " +
    ( [.statusCheckRollup[]? | select(.conclusion=="SUCCESS")] | length | tostring ) + " green"' 2>/dev/null

echo "AGENTS"
c=$(pgrep -f 'claude -p' 2>/dev/null | wc -l | tr -d ' ')
echo "  $c running"

echo "LAST DISPATCH"
tail -4 "$STATE/intake.log" 2>/dev/null | sed 's/^/  /'
