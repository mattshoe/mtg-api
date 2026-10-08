#!/bin/bash
#
# A file appears in requests/, an agent builds it and opens a pull request.
#
# That is the whole job. Everything else that used to be in here — triage, a
# time ceiling, worktree slots, salvage, CI waiting, merging, deploy checks,
# fix-only retries, notifications — was invented, not asked for, and that is
# where every bug lived. 2306 lines, then 395, now this.
#
# The agent does the work: it reads the request, builds it, opens the pull
# request, waits for CI, merges on green, and moves the request file into
# requests/done/ as part of its own commit. All of that is in
# .claude/agents/request-builder.md, which is a document, not shell.
#
set -uo pipefail

REPO="$(cd "$(dirname "$0")/../.." && pwd -P)"
STATE="$REPO/.intake"
LOG="$STATE/intake.log"
BASE="${INTAKE_BASE:-origin/main}"

say() { printf '%s  %s\n' "$(date '+%H:%M:%S')" "$1" >>"$LOG"; }

# Opt-in. .intake/ is gitignored, so a fresh clone must not be armed by the
# committed hook before anyone has installed anything.
[ -f "$STATE/enabled" ] || exit 0
[ -f "$STATE/disabled" ] && exit 0

# A linked worktree's .git is a file. The agent edits its own request file,
# which fires the hook, which would otherwise start a dispatcher inside the
# agent's own worktree.
[ -d "$REPO/.git" ] || exit 0

mkdir -p "$STATE"

# One at a time, because Gradle does not share a laptop.
mkdir "$STATE/lock" 2>/dev/null || exit 0
trap 'rm -rf "$STATE/lock"' EXIT

git -C "$REPO" fetch -q origin main 2>>"$LOG" || say "could not fetch; base may be stale"

file="$(cd "$REPO" && node scripts/intake.mjs buildable | head -1)" || { say "intake.mjs failed"; exit 1; }
[ -n "$file" ] || exit 0

name="${file%.md}"
branch="$(cd "$REPO" && node scripts/intake.mjs branch "$file")" || { say "intake.mjs failed"; exit 1; }
tree="$STATE/wt/$name"

# Never touch a worktree that is already there: it may hold work that was
# never committed, and nothing here is going to be the thing that deletes it.
[ -e "$tree" ] && { say "$name already has $tree — look at it, then remove it to retry"; exit 0; }

git -C "$REPO" worktree add -q -b "$branch" "$tree" "$BASE" 2>>"$LOG" \
  || { say "$name could not get a worktree on $branch"; exit 0; }

say "building $name on $branch"
# Not `exec`: that replaces this shell and the EXIT trap never runs, so the
# lock would be held forever. Staying in the foreground also means the lock is
# held for the whole build, which is the one-at-a-time rule.
cd "$tree" || exit 1
env \
  -u CLOUDFLARE_API_TOKEN -u CLOUDFLARE_ACCOUNT_ID -u CLOUDFLARE_API_KEY \
  -u CLOUDFLARE_EMAIL -u CF_API_TOKEN -u CF_ACCOUNT_ID -u CF_EMAIL \
  CLOUDFLARE_AUTH_USE_KEYRING=false XDG_CONFIG_HOME="$STATE/void" \
  GH_CONFIG_DIR="${GH_CONFIG_DIR:-$HOME/.config/gh}" \
  claude -p "You are the request-builder agent. Read .claude/agents/request-builder.md and follow it exactly, then build requests/$name.md.

It is yours end to end: build it test-first, open the pull request, wait for CI with \`gh pr checks <n> --watch --fail-fast\` (it blocks — do not use ScheduleWakeup or Monitor, you get no second turn), merge it on green unless the request says 'merge: ask', and move requests/$name.md into requests/done/ in your own commit.

Commit and push each part as it passes. Nothing you leave uncommitted is safe." \
  --permission-mode bypassPermissions --model opus \
  --output-format stream-json --verbose --include-partial-messages \
  >>"$STATE/$name.log" 2>&1

say "$name finished ($?) — its pull request, if it opened one, is the agent's own"
