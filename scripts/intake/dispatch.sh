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
REQUESTS="$REPO/requests"
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

# Choosing a request and creating its worktree is the only part that needs
# exclusivity, and it takes seconds. It runs behind `lockf`, so a dispatcher
# that arrives at the same instant WAITS for its turn and then picks a
# different request — rather than losing a `mkdir` race and giving up, which
# made the whole queue serial no matter how many requests were ready.
#
# `lockf` blocks in the kernel. It is not a polling loop and it cannot orphan:
# the lock dies with the process holding it.
#
# The claim runs as this same script re-invoked with --claim, because the
# chosen request has to come back out of the locked section on stdout.
claim() {
  local f b wt file branch tree resumed=''
  git -C "$REPO" worktree prune
  git -C "$REPO" fetch -q origin main 2>>"$LOG" || say "could not fetch; base may be stale"

  # 0 claimed something, 2 nothing to claim, 1 broken. An empty answer and a
  # broken one must never look the same to the caller — that conflation is the
  # oldest bug in this system and it came back when these node calls moved
  # inside a child process.
  cd "$REPO" && node scripts/intake.mjs buildable > "$STATE/queue.$$" || {
    say "intake.mjs failed"; rm -f "$STATE/queue.$$"; return 1
  }
  # Try each candidate until one actually gets a worktree. Returning on the
  # first failure would let a single request with a leftover branch — a
  # worktree removed by hand, say — block everything behind it, which is the
  # same wedge the skip above exists to prevent.
  # What origin/main already has filed. The queue is decided from the LOCAL
  # requests/ folder, and that folder lags main whenever an agent merges its
  # own work — which is every time one succeeds. Twice now a finished request
  # has been re-dispatched, and the agent then committed to a branch whose
  # pull request had already merged, which Admin Settings reads as a task
  # stuck in `building`. Ask main, not the laptop.
  filed="$(git -C "$REPO" ls-tree --name-only "$BASE" requests/done/ 2>/dev/null | sed 's|requests/done/||')"

  file=''
  while IFS= read -r f; do
    [ -n "$f" ] || continue
    if printf '%s\n' "$filed" | /usr/bin/grep -qxF "$f"; then
      say "skipping ${f%.md}: already filed under requests/done on ${BASE}"
      continue
    fi
    wt="$STATE/wt/${f%.md}"
    # A worktree that exists means an agent had this request. If one is
    # still running there, leave it alone. If not, the agent stopped —
    # rate limited, killed, crashed — and the work is sitting in that
    # tree. RESUME it rather than refusing: refusing meant the only way
    # forward was a human deleting somebody's unpushed work by hand,
    # which is not recovery, it is a wedge with a polite message.
    if [ -e "$wt" ]; then
      if pgrep -f "requests/${f}" >/dev/null 2>&1; then
        say "skipping ${f%.md}: an agent is still working in $wt"
        continue
      fi
      b="$(cd "$REPO" && node scripts/intake.mjs branch "$f")" || {
        say "intake.mjs failed naming a branch for $f"; return 1
      }
      file="$f"; branch="$b"; tree="$wt"; resumed=yes
      say "resuming ${f%.md} in $wt ($(git -C "$wt" status --porcelain 2>/dev/null | wc -l | tr -d ' ') uncommitted, $(git -C "$wt" rev-list --count "$BASE"..HEAD 2>/dev/null || echo 0) commits)"
      break
    fi
    b="$(cd "$REPO" && node scripts/intake.mjs branch "$f")" || {
      say "intake.mjs failed naming a branch for $f"; return 1
    }
    if git -C "$REPO" worktree add -q -b "$b" "$wt" "$BASE" 2>>"$LOG"; then
      file="$f"; branch="$b"; tree="$wt"; break
    fi
    say "skipping ${f%.md}: no worktree on $b (the branch may already exist)"
  done < "$STATE/queue.$$"
  rm -f "$STATE/queue.$$"
  [ -n "$file" ] || return 2

  # node_modules, shared rather than installed. A cold worktree costs `npm ci`
  # — measured at 5 minutes, and one agent spent exactly that before it could
  # run a single test. A symlink makes it free. An agent that genuinely needs a
  # new dependency edits package.json and lets CI install it.
  [ -d "$REPO/node_modules" ] && [ ! -e "$tree/node_modules" ] \
    && ln -s "$REPO/node_modules" "$tree/node_modules"

  # Hand over the LIVE request file, not the one the worktree was cut from.
  # Without this the agent reads main's copy: four requests sat at `status:
  # hold` on main while the live files said `ready`, so every agent correctly
  # refused to build and stopped. The file Matt is looking at is the file the
  # agent must read.
  cp "$REQUESTS/$file" "$tree/requests/$file" 2>>"$LOG" \
    || say "could not hand over $file; the agent will read the committed copy"

  printf '%s\t%s\t%s\t%s\n' "$file" "$branch" "$tree" "${resumed:-no}"
}

if [ "${1:-}" = "--claim" ]; then claim; exit $?; fi

# `lockf` is BSD, so it is on macOS and not on the Linux runners CI uses;
# `flock` is the other way round. Pick whichever is here. With neither, run
# the claim unlocked and say so: two dispatchers would then have to collide
# inside the same few seconds, and each still refuses a worktree that exists.
if [ -x /usr/bin/lockf ]; then
  got="$(/usr/bin/lockf -k -t 120 "$STATE/pick.lock" "$0" --claim)"; rc=$?
elif command -v flock >/dev/null 2>&1; then
  got="$(flock -w 120 "$STATE/pick.lock" "$0" --claim)"; rc=$?
else
  say "no lockf and no flock; claiming without a lock"
  got="$("$0" --claim)"; rc=$?
fi

case "$rc" in
  0) : ;;
  2) exit 0 ;;                      # nothing to claim, which is normal
  *) tell "intake stopped: it could not read the queue"; exit 1 ;;
esac
[ -n "$got" ] || exit 0

# Four tab-separated fields. Read them with IFS rather than `${v%%\t*}`:
# inside a parameter expansion bash reads \t as a literal backslash-t, and
# claim now prints a fourth field, so the old split glued `resumed` onto the
# end of `tree`.
IFS="$(printf '\t')" read -r file branch tree resumed <<EOF
$got
EOF
name="${file%.md}"

# Agents authenticate as their own service account, `intake-agent`, which
# has the admin role and its own long-lived session token. They never see
# the operator password: that one unlocks /admin/sql on any database and is
# the way back in when every account has lost admin, so it stays out of
# reach. Revoking an agent is one row out of `sessions`.
AGENT_TOKEN=""
if [ -f "$HOME/.mtg-agent.env" ]; then
  AGENT_TOKEN="$(/usr/bin/grep -m1 "^MTG_AGENT_TOKEN=" "$HOME/.mtg-agent.env" | cut -d= -f2-)"
fi
[ -n "$AGENT_TOKEN" ] || say "no ~/.mtg-agent.env; the agent cannot act on the API as admin"

RESUME_NOTE=""
if [ "${resumed:-no}" = "yes" ]; then
  RESUME_NOTE="

YOU ARE RESUMING. A previous agent worked on this and stopped — rate limited,
killed, or crashed. Its work is in this worktree already: $(git -C "$tree" rev-list --count "$BASE"..HEAD 2>/dev/null || echo 0) commit(s) and $(git -C "$tree" status --porcelain 2>/dev/null | wc -l | tr -d ' ') uncommitted file(s).
Run \`git status\` and \`git log --oneline $BASE..HEAD\` before you do anything
else, and carry on from there. Do not start over and do not discard what is
there without reading it."
fi

say "building $name on $branch${resumed:+ (resuming)}"
# Not `exec`: that replaces this shell and the EXIT trap never runs, so the
# lock would be held forever. Staying in the foreground also means the lock is
# held for the whole build, which is the one-at-a-time rule.
cd "$tree" || exit 1
env \
  -u CLOUDFLARE_API_TOKEN -u CLOUDFLARE_ACCOUNT_ID -u CLOUDFLARE_API_KEY \
  -u CLOUDFLARE_EMAIL -u CF_API_TOKEN -u CF_ACCOUNT_ID -u CF_EMAIL \
  -u CLOUDFLARE_API_USER_SERVICE_KEY -u WRANGLER_CF_AUTHORIZATION_TOKEN \
  CLOUDFLARE_AUTH_USE_KEYRING=false XDG_CONFIG_HOME="$STATE/void" \
  MTG_API_TOKEN="$AGENT_TOKEN" \
  INTAKE_BUILDER=1 \
  GH_CONFIG_DIR="${GH_CONFIG_DIR:-$HOME/.config/gh}" \
  claude -p "You are the request-builder agent. Read .claude/agents/request-builder.md and follow it exactly, then build requests/$name.md.

It is yours end to end: build it test-first, open the pull request, wait for CI with \`gh pr checks <n> --watch --fail-fast\` (it blocks — do not use ScheduleWakeup or Monitor, you get no second turn), merge it on green unless the request says 'merge: ask', and move requests/$name.md into requests/done/ in your own commit.

Run tests the way .claude/agents/request-builder.md says: one unit test at a time during the cycle, named, red then green. The functional suites — test:screens, test:web, test:android, npm test — run ONCE at the end, on what your diff reaches. Never in the cycle.

Never background a command and poll its output, and never background a suite. One agent spent 55% of its run inside polling loops and another spent twelve minutes reading its own background task files. Run it in the foreground and let it finish.

node_modules is already there, symlinked. Do not run npm ci.

Commit and push BEFORE your first test run, and after every part that passes. Not at the end. An agent can be rate limited or killed at any moment, and anything uncommitted at that point is work the next agent has to read back off disk instead of building on. Four agents were caught by this with up to thirteen files uncommitted.$RESUME_NOTE" \
  --permission-mode bypassPermissions --model opus \
  --output-format stream-json --verbose --include-partial-messages \
  >>"$STATE/$name.log" 2>&1

say "$name finished ($?) — its pull request, if it opened one, is the agent's own"
