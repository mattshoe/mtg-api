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
  # The builder's own process is the source of truth, not a lock
  # directory — a lock directory can be deleted by hand, and then a
  # running builder reads as stalled. Its command line names its request.
  bpid="$(pgrep -f "requests/$n.md" 2>/dev/null | head -1)"

  if [ -n "$bpid" ]; then
    # How long, and whether it is getting anywhere. A builder with an
    # empty log for an hour looks identical to a hung one otherwise.
    age="$(ps -o etime= -p "$bpid" 2>/dev/null | tr -d ' ')"
    wt="$STATE/wt/$n"
    files="$(git -C "$wt" status --short 2>/dev/null | wc -l | tr -d ' ')"
    commits="$(git -C "$wt" rev-list --count "@{upstream}..HEAD" 2>/dev/null || git -C "$wt" rev-list --count origin/main..HEAD 2>/dev/null || echo 0)"
    # Any child at all means it is in a command rather than thinking.
    busy="thinking"; [ -n "$(pgrep -P "$bpid" 2>/dev/null)" ] && busy="in a command"
    st="building ${age:-?}  ${files} changed, ${commits} commits, $busy"
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
