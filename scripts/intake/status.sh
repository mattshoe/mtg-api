#!/bin/bash
# The whole queue in about twenty lines.
#
# This exists to keep an agent's context small. Reading six logs, four
# `gh` calls and a worktree listing to answer "what is happening" burns
# thousands of tokens per report and crowds out the actual work. One
# command, one compact table, no log bodies.
#
# Every judgement here comes from `scripts/intake.mjs`. This file used to
# re-implement `triaged` and `needs-matt` with grep, and the two
# implementations disagreed: `grep -qF '## Plan'` is an unanchored
# substring, and `grep -q '^status: needs-matt'` matches the body outside
# the frontmatter. So status said "ready" for files the dispatcher held,
# and the other way round. Decisions live in the tested module.
set -uo pipefail
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO" || exit 1
STATE="$REPO/.intake"
BASE="${INTAKE_BASE:-origin/main}"
WT_ROOT="${INTAKE_WORKTREE_ROOT:-$HOME/.cache/mtg-intake/wt}"
# A Bash call this old is a builder that is stuck, not a builder that is
# thinking.
STUCK_MINUTES="${INTAKE_STUCK_MINUTES:-8}"

[ -f "$STATE/disabled" ] && echo "OFF  ($STATE/disabled exists — nothing will dispatch)"

# ---------------------------------------------------------------------
# What the dispatcher is doing
# ---------------------------------------------------------------------
# Neither the lock nor the claims appeared anywhere, so a wedged queue
# was indistinguishable from an idle one and learning what the system was
# actually doing took six manual commands.

alive() {
  [ -n "$1" ] && kill -0 "$1" 2>/dev/null
}

echo "DISPATCHER"
if [ -d "$STATE/dispatch.lock" ]; then
  pid="$(cat "$STATE/dispatch.lock/pid" 2>/dev/null)"
  started="$(cat "$STATE/dispatch.lock/started" 2>/dev/null)"
  age="?"
  [ -n "$started" ] && age="$(( ( $(date +%s) - started ) / 60 ))m"
  if [ -z "$pid" ]; then
    echo "  lock held, no pid yet (that is the double-fire window, and it counts as live)"
  elif alive "$pid"; then
    echo "  lock held by $pid, alive, ${age} old"
  else
    echo "  lock held by $pid, WHICH IS GONE — the next dispatch will clear it"
  fi
else
  echo "  no lock, so nothing is dispatching"
fi
claims=0
for c in "$STATE"/*.building "$STATE"/slot*.claim; do
  [ -d "$c" ] || continue
  claims=$((claims + 1))
  cpid="$(cat "$c/pid" 2>/dev/null)"
  if [ -z "$cpid" ]; then
    printf '  claim %-32s no pid yet (live)\n' "$(basename "$c")"
  elif alive "$cpid"; then
    printf '  claim %-32s pid %s alive\n' "$(basename "$c")" "$cpid"
  else
    printf '  claim %-32s pid %s GONE — stale\n' "$(basename "$c")" "$cpid"
  fi
done
[ "$claims" -eq 0 ] && echo "  no claims"

# ---------------------------------------------------------------------
# The requests
# ---------------------------------------------------------------------

# What a builder is waiting on, from the jsonl the dispatcher already
# writes. A stall becomes visible in one line instead of after an hour.
last_event() {
  local jsonl="$1" line kind when now age
  [ -s "$jsonl" ] || { printf 'no events yet'; return; }
  line="$(tail -1 "$jsonl")"
  kind="$(printf '%s' "$line" | /usr/bin/sed -nE 's/.*"type":"([a-z_]+)".*/\1/p' | head -1)"
  [ -n "$kind" ] || kind="?"
  # `stat -f %m` is BSD and `stat -c %Y` is GNU, and neither fails usefully
  # on the other: GNU's `-f` asks about the FILESYSTEM and exits 0 having
  # printed something that is not a timestamp. So the value is checked, not
  # the exit code — which is the lesson of this whole branch.
  when="$(stat -c %Y "$jsonl" 2>/dev/null)"
  case "$when" in ''|*[!0-9]*) when="$(stat -f %m "$jsonl" 2>/dev/null)" ;; esac
  case "$when" in ''|*[!0-9]*) when="" ;; esac
  if [ -n "$when" ]; then
    now="$(date +%s)"
    age=$(( (now - when) / 60 ))
    printf '%s, %sm ago' "$kind" "$age"
    case "$line" in
      *'"name":"Bash"'*)
        [ "$age" -ge "$STUCK_MINUTES" ] && printf ' — STUCK? a Bash call has been open %sm' "$age"
        ;;
    esac
  else
    printf '%s' "$kind"
  fi
}

# Files, commits and pushed-ness, for EVERY state. These used to be
# computed only inside the `building` branch, so the STALLED case printed
# a bare "worktree kept" with no hint that thirty-two uncommitted files
# were sitting in it.
tree_shape() {
  local where="$1" files commits here there pushed
  [ -e "$where/.git" ] || { printf 'no tree'; return; }
  files="$(git -C "$where" status --porcelain 2>/dev/null | wc -l | tr -d ' ')"
  commits="$(git -C "$where" rev-list --count "$BASE..HEAD" 2>/dev/null || echo 0)"
  here="$(git -C "$where" rev-parse HEAD 2>/dev/null)"
  there="$(git -C "$where" rev-parse '@{upstream}' 2>/dev/null)"
  if [ -z "$there" ]; then pushed="never pushed"
  elif [ "$here" = "$there" ]; then pushed="pushed"
  else pushed="UNPUSHED"
  fi
  printf '%s changed, %s commits, %s' "$files" "$commits" "$pushed"
}

# One call, and the names looked up in the result.
states="$(INTAKE_DIR="$REPO/requests" node scripts/intake.mjs state 2>/dev/null)"
if [ -z "$states" ] && [ -n "$(ls requests/*.md 2>/dev/null)" ]; then
  echo "REQUESTS (intake.mjs unavailable — this listing cannot be trusted)"
else
  echo "REQUESTS"
fi

for f in requests/*.md; do
  [ "$f" = "requests/README.md" ] && continue
  [ -f "$f" ] || continue
  n="$(basename "$f" .md)"
  branch="$(node scripts/intake.mjs branch "$n.md" 2>/dev/null)"
  # Slots live outside the repo now. `.intake/wt/` is where they used to
  # be, and a tree left there by an older dispatcher still holds work.
  slot=""
  for s in "$WT_ROOT"/slot*; do
    [ -e "$s/.git" ] || continue
    [ "$(git -C "$s" rev-parse --abbrev-ref HEAD 2>/dev/null)" = "$branch" ] && slot="$s"
  done
  # `.intake/wt/<name>` is where slots used to live. A tree left there by
  # an older dispatcher still holds somebody's work, and it is named after
  # the request rather than the branch — the branch names changed when a
  # digest was added to them, so matching on the branch would miss it.
  [ -z "$slot" ] && [ -e "$STATE/wt/$n/.git" ] && slot="$STATE/wt/$n"

  # `pgrep -f "requests/$n.md"` was an unescaped regex over every
  # process's argv with nothing narrowing it to `claude`. A concurrent
  # `cat`, an editor, or a second `status.sh` read as "building"; and a
  # request named `a+b.md` or `fix (decks).md` never matched its own live
  # builder, so it read "ready" and invited a second dispatch.
  esc="$(printf '%s' "$branch" | /usr/bin/sed -e 's/[][\\.*^$(){}?+|/]/\\&/g')"
  bpid="$(pgrep -f "claude .*$esc" 2>/dev/null | head -1)"

  # The predicate, from the one implementation of it.
  want="$(printf '%s\n' "$states" | awk -F'\t' -v k="$n.md" '$1==k {print $2}')"
  [ -n "$want" ] || want="not listed"

  if [ -n "$bpid" ]; then
    age="$(ps -o etime= -p "$bpid" 2>/dev/null | tr -d ' ')"
    busy="thinking"
    [ -n "$(pgrep -P "$bpid" 2>/dev/null)" ] && busy="in a command"
    st="building ${age:-?}  $busy"
  elif [ -n "$slot" ]; then
    st="STALLED, slot kept"
  else
    st="$want"
  fi

  printf '  %-36s %s\n' "$n" "$st"
  [ -n "$slot" ] && printf '      %-32s %s\n' "$(basename "$slot")" "$(tree_shape "$slot")"
  [ -f "$STATE/$n.jsonl" ] && printf '      %-32s %s\n' "last event" "$(last_event "$STATE/$n.jsonl")"
  [ -f "$STATE/$n.fixme" ] && printf '      %-32s #%s\n' "CI RED, needs a fix builder" "$(cat "$STATE/$n.fixme")"
  tries="$(cat "$STATE/$n.triage-tries" 2>/dev/null)"
  [ -n "$tries" ] && printf '      %-32s %s\n' "triage attempts with no plan" "$tries"
done

if [ -n "$(ls requests/done/*.md 2>/dev/null)" ]; then
  echo "DONE"
  for f in requests/done/*.md; do printf '  %s\n' "$(basename "$f" .md)"; done
fi

# ---------------------------------------------------------------------
# Branches, which is where the work actually is
# ---------------------------------------------------------------------
echo "BRANCHES"
found=0
while IFS= read -r b; do
  [ -n "$b" ] || continue
  found=1
  ahead="$(git rev-list --count "$BASE..$b" 2>/dev/null || echo '?')"
  if git rev-parse --verify --quiet "refs/remotes/origin/$b" >/dev/null; then
    if [ "$(git rev-parse "$b")" = "$(git rev-parse "origin/$b")" ]; then up="pushed"
    else up="UNPUSHED"; fi
  else
    up="NOT ON ORIGIN"
  fi
  printf '  %-48s %s ahead of %s, %s\n' "$b" "$ahead" "$BASE" "$up"
done < <(git branch --list 'request/*' --format='%(refname:short)' 2>/dev/null)
[ "$found" -eq 0 ] && echo "  none"

# ---------------------------------------------------------------------
# GitHub
# ---------------------------------------------------------------------
# A failing `gh` used to render as a clean queue, which is the same shape
# as "nothing is open". And a pull request whose three counts are all
# zero has no checks at all — which reads as benign and is exactly what a
# hundred commits sat in.
prs="$(gh pr list --limit 20 --json number,headRefName,statusCheckRollup 2>/dev/null)"
if [ -z "$prs" ]; then
  echo "OPEN PRS (gh unavailable — this is not the same as none)"
else
  echo "OPEN PRS"
  printf '%s' "$prs" | /usr/bin/python3 -c '
import json, sys
rows = json.load(sys.stdin)
if not rows:
    print("  none")
for r in rows:
    checks = r.get("statusCheckRollup") or []
    red = sum(1 for c in checks if c.get("conclusion") == "FAILURE")
    waiting = sum(1 for c in checks
                  if c.get("status") in ("IN_PROGRESS", "QUEUED", "PENDING"))
    green = sum(1 for c in checks if c.get("conclusion") == "SUCCESS")
    if red == 0 and waiting == 0 and green == 0:
        state = "no checks - unverified, which is not green"
    else:
        state = "%d failing, %d pending, %d green" % (red, waiting, green)
    print("  #%s %s %s" % (r["number"], r["headRefName"], state))
' 2>/dev/null || echo "  (could not read the check rollup)"
fi

echo "AGENTS"
echo "  $(pgrep -f 'claude -p' 2>/dev/null | wc -l | tr -d ' ') running"

echo "LAST DISPATCH"
tail -6 "$STATE/intake.log" 2>/dev/null | sed 's/^/  /'
