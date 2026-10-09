#!/bin/bash
# The whole queue on one screen: the two switches, the lock, the request
# files, the worktrees under `.intake/wt`, the open pull requests. Slots,
# claims, holds, attempt counters, `.fixme` and `handed/` are gone and
# nothing here looks for them.
#
# Two rules this file has broken before. Every judgement about a request
# comes from `scripts/intake.mjs` — this file re-implemented the predicates
# with grep and the two disagreed, because `grep -q '^status: needs-matt'`
# matches the body outside the frontmatter. And a banner fires on a NONZERO
# EXIT only, never on empty output, which is the healthy idle state.
set -u
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)"
cd "$REPO" || exit 1
STATE="$REPO/.intake"
BASE="${INTAKE_BASE:-origin/main}"
WT="${INTAKE_WT:-$STATE/wt}"

# An absent `enabled` is as off as a present `disabled`; only one showed.
echo "SWITCHES"
[ -f "$STATE/enabled" ] && echo "  enabled" \
  || echo "  NOT ENABLED ($STATE/enabled is missing — run install.sh)"
[ -f "$STATE/disabled" ] && echo "  OFF ($STATE/disabled exists — nothing will dispatch)"

echo "DISPATCHER"
# What is actually running, not what used to leave a lock behind. This read
# "$STATE/lock", a directory the dispatcher stopped creating when it moved to
# lockf on "$STATE/pick.lock" — so this section reported "nothing is
# dispatching" through every build, which is the opposite of its job.
live=$(pgrep -f 'intake/dispatch.sh' 2>/dev/null | wc -l | tr -d ' ')
agents=$(pgrep -f 'claude -p' 2>/dev/null | wc -l | tr -d ' ')
if [ "${live:-0}" -eq 0 ] && [ "${agents:-0}" -eq 0 ]; then
  echo "  idle — no dispatcher, no agent"
else
  echo "  ${live:-0} dispatcher(s), ${agents:-0} agent(s)"
  pgrep -f 'claude -p' 2>/dev/null | while read -r ap; do
    nm=$(ps -o command= -p "$ap" 2>/dev/null | /usr/bin/grep -o 'requests/[^ ]*\.md' | head -1)
    [ -n "$nm" ] && printf '    %-9s %s\n' "$(ps -o etime= -p "$ap" | tr -d ' ')" "${nm#requests/}"
  done
fi

states="$(node scripts/intake.mjs state 2>/dev/null)"
rc=$?
[ "$rc" -eq 0 ] && echo "REQUESTS" \
  || echo "REQUESTS (intake.mjs exited $rc, so this listing cannot be trusted)"
printf '%s\n' "$states" | while IFS="$(printf '\t')" read -r file want; do
  [ -n "$file" ] || continue
  printf '  %-44s %s\n' "${file%.md}" "$want"
done
[ "$rc" -eq 0 ] && [ -z "$states" ] && echo "  none pending"

# A worktree is never deleted automatically, so a dead builder's tree is
# still on disk with whatever it had, and pushed-or-not says if it is safe.
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
  # by `worktree add -b` has no upstream, so a just-pushed branch read as
  # never pushed.
  here="$(git -C "$t" rev-parse HEAD 2>/dev/null)"
  there="$(git -C "$t" rev-parse --verify --quiet "refs/remotes/origin/$branch" 2>/dev/null)"
  if [ -z "$there" ]; then pushed="NOT ON ORIGIN"
  elif [ "$here" = "$there" ]; then pushed="pushed"; else pushed="UNPUSHED"; fi
  printf '  %-28s %-34s %s changed, %s commits, %s\n' \
    "$(basename "$t")" "$branch" "$files" "$commits" "$pushed"
done
[ "$found" -eq 0 ] && echo "  none"

# A failing `gh` used to render as a clean queue, the same shape as
# "nothing is open". A pull request whose three counts are all zero has no
# checks at all, which reads as benign and is what a hundred commits sat in.
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
if not rows: print("  none")
for r in rows:
    c = r.get("statusCheckRollup") or []
    n = lambda f: sum(1 for x in c if f(x))
    red, green = n(lambda x: x.get("conclusion") == "FAILURE"), n(lambda x: x.get("conclusion") == "SUCCESS")
    wait = n(lambda x: x.get("status") in ("IN_PROGRESS", "QUEUED", "PENDING"))
    state = ("no checks - unverified, which is not green" if red + wait + green == 0
             else "%d failing, %d pending, %d green" % (red, wait, green))
    print("  #%s %s %s" % (r["number"], r["headRefName"], state))
' || echo "  (could not read the check rollup)"
fi

echo "LAST DISPATCH"
tail -6 "$STATE/intake.log" 2>/dev/null | sed 's/^/  /'
