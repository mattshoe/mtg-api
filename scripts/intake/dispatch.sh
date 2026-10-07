#!/bin/bash
# Spins up an agent for every new file in requests/.
#
# Called two ways:
#   - launchd, via WatchPaths on requests/ (see com.matt.mtg.intake.plist),
#     which is what catches files dropped in while nobody is here
#   - the FileChanged hook in .claude/settings.json, for files written
#     during a session
#
# Only one copy runs at a time. A second invocation while the first is
# still working exits immediately, because the first one re-reads the
# folder after triage and will see whatever arrived in the meantime.

set -uo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
REQUESTS="$REPO/requests"
# Deliberately NOT inside requests/. launchd's WatchPaths fires on any
# change under the folder it watches, so a log written in there makes the
# dispatcher trigger itself, forever.
STATE="$REPO/.intake"
LOG="$STATE/intake.log"
LOCK="$STATE/dispatch.lock"
DEBOUNCE="${INTAKE_DEBOUNCE:-15}"

mkdir -p "$STATE"
touch "$LOG"

say() { echo "$(date '+%Y-%m-%d %H:%M:%S')  $*" >> "$LOG"; }

# INTAKE_DRY_RUN=1 prints what would be launched instead of launching it.
# It is how this script gets tested without opening a pull request.
run() {
  if [ -n "${INTAKE_DRY_RUN:-}" ]; then say "DRY RUN would launch: $*"; return 0; fi
  "$@"
}

# mkdir is atomic on every filesystem that matters, and flock is not on macOS.
if ! mkdir "$LOCK" 2>/dev/null; then
  if [ -f "$LOCK/pid" ] && kill -0 "$(cat "$LOCK/pid")" 2>/dev/null; then
    say "already running as $(cat "$LOCK/pid"), leaving it to that one"
    exit 0
  fi
  say "clearing a lock left behind by pid $(cat "$LOCK/pid" 2>/dev/null)"
  rm -rf "$LOCK"
  mkdir "$LOCK" 2>/dev/null || exit 0
fi
echo $$ > "$LOCK/pid"
trap 'rm -rf "$LOCK"' EXIT

# Every decision about what is a request, what triage has touched and what
# a builder may pick up lives in scripts/intake.mjs, where the suite tests
# it. This script asks; it does not decide.
ask() { ( cd "$REPO" && INTAKE_DIR="$REQUESTS" node scripts/intake.mjs "$@" ); }
pending()   { ask pending | sed '/^$/d' | sort; }
untriaged() { ask untriaged | sed '/^$/d' | sort; }
buildable() { ask buildable | sed '/^$/d' | sort; }

[ -z "$(pending)" ] && { say "nothing pending"; exit 0; }

# Let a burst of files land before triage looks, so it can see that three
# of them are the same request and fold them together.
say "waiting ${DEBOUNCE}s for the folder to settle"
sleep "$DEBOUNCE"
while :; do
  before="$(pending | md5)"
  sleep 5
  [ "$(pending | md5)" = "$before" ] && break
  say "still arriving, waiting again"
done

cd "$REPO" || exit 1

# Everything may have been withdrawn while we waited, and grep with no
# file arguments reads stdin and hangs forever.
settled="$(pending)"
[ -z "$settled" ] && { say "every request withdrawn while waiting"; exit 0; }

untriaged="$(untriaged)"
if [ -n "$untriaged" ]; then
  say "triage over: $(echo "$untriaged" | tr '\n' ' ')"
  run claude -p "Invoke the mtg skill first, then run the request-triage agent over requests/. There are untriaged \
files in there: $(echo "$untriaged" | tr '\n' ' '). Follow its definition in \
.claude/agents/request-triage.md exactly — combine what overlaps, split what is \
secretly several jobs, and rewrite each file in place with the frontmatter and \
the Plan/Tests/Done-when sections. Do not write production code and do not open \
a branch." \
    --permission-mode acceptEdits \
    >> "$LOG" 2>&1
  say "triage done"
fi

held="$(comm -23 <(pending) <(buildable))"
[ -n "$held" ] && say "held back, not triaged or waiting on Matt: $(echo "$held" | tr '\n' ' ')"

for file in $(buildable); do
  name="${file%.md}"
  branch="$(ask branch "$file")"
  claimed="$STATE/$name.building"

  if [ -d "$claimed" ]; then
    if kill -0 "$(cat "$claimed/pid" 2>/dev/null)" 2>/dev/null; then
      say "$name already has a builder, skipping"
      continue
    fi
    say "$name had a builder that died, retrying"
    rm -rf "$claimed"
  fi

  mkdir -p "$claimed"

  # Each builder gets its own checkout.
  #
  # The agent definition says `isolation: worktree`, but that only
  # applies when a parent spawns it through the Agent tool. These are
  # headless `claude -p` runs, which start wherever they are started —
  # so without this, three builders would share one working tree and one
  # branch, and the third would commit the other two's half-finished
  # work.
  tree="$REPO/.intake/wt/$name"
  rm -rf "$tree"
  if ! git -C "$REPO" worktree add -q --force -B "$branch" "$tree" origin/main 2>>"$LOG"; then
    say "$name could not get a worktree, skipping"
    rm -rf "$claimed"
    continue
  fi
  # The request file may not be committed anywhere yet; the builder needs
  # to be able to read it, and to commit it moved into done/.
  mkdir -p "$tree/requests"
  cp "$REQUESTS/$file" "$tree/requests/$file"

  say "building $name on $branch in .intake/wt/$name"
  (
    cd "$tree" || exit 1
    run claude -p "Invoke the mtg skill first — it carries this project's \
architecture and hard requirements and you will break things without it. Then \
implement requests/$name.md. You are the request-builder agent: \
read .claude/agents/request-builder.md and follow it exactly, including the TDD \
discipline, the parity rule, and reading the BUILD line rather than the exit \
code. You are already on branch $branch in your own worktree. Open a PR and \
drive it to green. Do not merge unless that file's frontmatter says merge: auto." \
      --permission-mode bypassPermissions \
      >> "$STATE/$name.log" 2>&1
    code=$?
    say "$name builder exited $code  (see $STATE/$name.log)"
    # The worktree stays when it failed, so it can be looked at.
    if [ "$code" -eq 0 ]; then
      git -C "$REPO" worktree remove --force "$tree" 2>>"$LOG" && say "$name worktree cleaned up"
    else
      say "$name worktree left at .intake/wt/$name for inspection"
    fi
    rm -rf "$claimed"
  ) &
  # bash 3.2 on macOS has no BASHPID, so the parent records the child.
  echo $! > "$claimed/pid"
done

wait
say "dispatch finished"
