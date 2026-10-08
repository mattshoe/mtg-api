#!/bin/bash
# The whole queue, on one screen. It exists to keep an agent's context
# small: reading the logs, the worktrees and three `gh` calls to answer
# "what is happening" burns thousands of tokens a report.
# It reports only state the dispatcher keeps — the lock, the request files,
# the worktrees under `.intake/wt`, the open pull requests. No slots, no
# claims, no holds, no attempt counters, no `.fixme`: none of those exist
# any more and nothing here looks for them.
#
# Two rules this file has broken before, and must not again:
#
#   1. Every judgement about a request comes from `scripts/intake.mjs`. This
#      file re-implemented `triaged` and `needs-matt` with grep and the two
#      disagreed: `grep -qF '## Plan'` is an unanchored substring, and
#      `grep -q '^status: needs-matt'` matches the body outside the
#      frontmatter. So status said "ready" for files the dispatcher held,
#      and the other way round.
#   2. A failure and an empty answer are not the same thing. The banner
#      fires on a NONZERO EXIT only. It used to fire whenever the output was
#      empty — the healthy idle state — so the one line that says the
#      dashboard is lying fired every time nothing was pending, which is how
#      people learn to ignore it.
set -u
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)"
cd "$REPO" || exit 1
STATE="$REPO/.intake"
BASE="${INTAKE_BASE:-origin/main}"
WT="${INTAKE_WT:-$STATE/wt}"

[ -f "$STATE/disabled" ] && echo "OFF  ($STATE/disabled exists — nothing will dispatch)"

echo "DISPATCHER"
if [ -d "$STATE/lock" ]; then
  pid="$(cat "$STATE/lock/pid" 2>/dev/null)"
  if [ -z "$pid" ]; then
    echo "  lock held, no pid yet — the double-fire window, which counts as live"
  elif kill -0 "$pid" 2>/dev/null; then
    echo "  lock held by $pid, alive"
  else
    echo "  lock held by $pid, WHICH IS GONE — the next dispatch will clear it"
  fi
else
  echo "  no lock, so nothing is dispatching"
fi

# One call, and every state read out of the answer.
states="$(node scripts/intake.mjs state 2>/dev/null)"
rc=$?
if [ "$rc" -ne 0 ]; then
  echo "REQUESTS (intake.mjs exited $rc, so this listing cannot be trusted)"
else
  echo "REQUESTS"
fi
printf '%s\n' "$states" | while IFS="$(printf '\t')" read -r file want; do
  [ -n "$file" ] || continue
  printf '  %-44s %s\n' "${file%.md}" "$want"
done
[ "$rc" -eq 0 ] && [ -z "$states" ] && echo "  none pending"

if [ -n "$(ls requests/done/*.md 2>/dev/null)" ]; then
  echo "DONE"
  for f in requests/done/*.md; do printf '  %s\n' "$(basename "$f" .md)"; done
fi

# A worktree is never deleted automatically, so a dead builder's tree is
# still on disk with whatever it had — and the fact that decides whether
# that work is safe is whether it was pushed.
echo "WORKTREES  ($WT, never removed automatically)"
found=0
for t in "$WT"/*; do
  [ -e "$t/.git" ] || continue
  found=1
  files="$(git -C "$t" status --porcelain 2>/dev/null | wc -l | tr -d ' ')"
  commits="$(git -C "$t" rev-list --count "$BASE..HEAD" 2>/dev/null)" || commits='?'
  [ -n "$commits" ] || commits='?'
  branch="$(git -C "$t" rev-parse --abbrev-ref HEAD 2>/dev/null)"
  # Against `refs/remotes/origin/<branch>`, not `@{upstream}`: a tree made
  # by `worktree add -b` has no upstream at all, so a branch that had just
  # been pushed reported "never pushed".
  here="$(git -C "$t" rev-parse HEAD 2>/dev/null)"
  there="$(git -C "$t" rev-parse --verify --quiet "refs/remotes/origin/$branch" 2>/dev/null)"
  if [ -z "$there" ]; then pushed="NOT ON ORIGIN"
  elif [ "$here" = "$there" ]; then pushed="pushed"
  else pushed="UNPUSHED"
  fi
  printf '  %-28s %-34s %s changed, %s commits, %s\n' \
    "$(basename "$t")" "$branch" "$files" "$commits" "$pushed"
done
[ "$found" -eq 0 ] && echo "  none"

# A failing `gh` used to render as a clean queue, the same shape as
# "nothing is open". And a pull request whose three counts are all zero has
# no checks at all, which reads as benign and is exactly what a hundred
# commits sat in.
prs="$(gh pr list --limit 20 --json number,headRefName,statusCheckRollup 2>/dev/null)"
rc=$?
if [ "$rc" -ne 0 ]; then
  echo "OPEN PRS (gh exited $rc, which is not the same as none)"
else
  echo "OPEN PRS"
  [ -n "$prs" ] || prs='[]'
  printf '%s' "$prs" | /usr/bin/python3 -c '
import json, sys
rows = json.load(sys.stdin)
if not rows:
    print("  none")
for r in rows:
    c = r.get("statusCheckRollup") or []
    red = sum(1 for x in c if x.get("conclusion") == "FAILURE")
    wait = sum(1 for x in c if x.get("status") in ("IN_PROGRESS", "QUEUED", "PENDING"))
    green = sum(1 for x in c if x.get("conclusion") == "SUCCESS")
    state = ("no checks - unverified, which is not green" if red + wait + green == 0
             else "%d failing, %d pending, %d green" % (red, wait, green))
    print("  #%s %s %s" % (r["number"], r["headRefName"], state))
' || echo "  (could not read the check rollup)"
fi

echo "LAST DISPATCH"
tail -6 "$STATE/intake.log" 2>/dev/null | sed 's/^/  /'
