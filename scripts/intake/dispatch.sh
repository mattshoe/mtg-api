#!/bin/bash
#
# A file appears in requests/, an agent builds it and opens a pull request.
#
# That is the whole job. It was 2306 lines, then 395, then 312, now this.
#
# What came out, and why it was never needed: a lock, a `--claim`
# re-invocation to get a value back out of it, a resume pass for stopped
# agents, an attempt ledger bounding the resumes, and a notification path.
# All of it existed to survive agents dying. The agents were dying because
# their worktree had no apps/local.properties, so every Android command
# failed in sixteen seconds with nowhere to go, and because the
# instructions told them to run a suite that cannot finish inside their
# ten-minute tool limit. Both of those are fixed, and the worktrees that
# caused the first are gone.
#
# Matt: "I just want a fucking request to start a fucking agent."
#
# One task at a time, in one checkout, on a feature branch.
#
# There used to be a worktree per request, and `git worktree add -b` was
# the lock: the second dispatcher to ask for the same branch is refused
# by git, atomically, which is what made a parallel free-for-all safe.
# There is an explicit cap now, so that trick is not needed, and the
# isolation it bought was from a working copy nobody has. Matt: "I HAVE
# NO FUCKING WORKING COPY... THE CODE IS YOUR RESPONSIBILITY NOT MINE".
#
# What it cost was every module compiling from nothing in each new
# directory, inside an agent whose tool kills a command at ten minutes.
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

# Reconcile BEFORE honouring the off switch. Status is only ever written
# inside a dispatch, so pausing intake used to freeze every row mid-flight:
# Matt watched a task read `in progress` for three hours after I had
# switched the system off and nothing was running.
if [ -f "$STATE/disabled" ]; then
  node "$REPO/scripts/intake/task-status.mjs" cancel >>"$LOG" 2>&1 || true
  node "$REPO/scripts/intake/task-status.mjs" reconcile >>"$LOG" 2>&1 || true
  exit 0
fi

# A linked worktree's .git is a file. Nothing runs in a worktree any
# more, but the guard stays: it costs a line and it is what stops a
# dispatcher starting inside one if anybody adds them back.
[ -d "$REPO/.git" ] || exit 0

mkdir -p "$STATE"

# Anything Matt submitted from the app, turned into a request file. Without
# this a task submitted in the app sat at `pending` forever: nothing polled
# the inbox, and launchd only fires on a change under requests/, which
# submitting from the app does not cause.
node "$REPO/scripts/intake/inbox.mjs" >>"$LOG" 2>&1 || true

# A task cancelled in the app: its request file marked cancelled, so it is
# never built again, and any agent on it killed. Matt: "any running agent
# should be immediately killed". Every tick, so within a minute of the tap,
# and before reconcile so the row it reads already says cancelled.
node "$REPO/scripts/intake/task-status.mjs" cancel >>"$LOG" 2>&1 || true

# Every run puts D1 right about what this laptop can see: a dead agent
# reads paused rather than in progress forever, a held request paused, a
# withdrawn one cancelled. It may not stop a build.
node "$REPO/scripts/intake/task-status.mjs" reconcile >>"$LOG" 2>&1 || true

# Leftovers from the worktree era. Harmless when there are none.
git -C "$REPO" worktree prune
git -C "$REPO" fetch -q origin main 2>>"$LOG" || say "could not fetch; base may be stale"

# What origin/main already has filed. The queue is decided from the LOCAL
# requests/ folder, and that folder lags main whenever an agent merges its
# own work — which is every time one succeeds. Twice a finished request was
# re-dispatched, and the agent then committed to a branch whose pull
# request had already merged. Ask main, not the laptop.
filed="$(git -C "$REPO" ls-tree --name-only "$BASE" requests/done/ 2>/dev/null | sed 's|requests/done/||')"

# Put the checkout back on main once a task has shipped.
#
# Matt: "Why do you need worktrees?!?! Can't you just use a fucking
# branch?!" and "I HAVE NO FUCKING WORKING COPY... THE CODE IS YOUR
# RESPONSIBILITY NOT MINE". He is right on both counts. The worktree
# existed because `git worktree add -b` is refused atomically for a
# branch that already exists, which made it the lock in a free-for-all;
# there is an explicit cap of one now, so the lock is not needed. The
# isolation it bought was from a working copy nobody has.
#
# What it cost was the whole remaining problem: a NEW directory per task
# has no `build/`, so every module compiled from nothing inside an agent
# whose tool kills any command at ten minutes. Measured on one task —
# worktree cut at 12:45, :core first built 12:48, :webApp 12:49,
# :androidApp 12:55 — ten minutes of cold compile paid inside turns that
# are not allowed to last that long, so they died and paid again. Three
# narrow test runs hit exactly 10.0 minutes.
#
# So: one checkout, a feature branch per task, build directories that
# stay warm. A narrow test here is about a second.
#
# Filed under requests/done on main is the signal that a branch is
# finished with, the same one the worktree sweep used: the agent files
# its request in the commit it merges, so filed means merged and the
# branch holds nothing main does not have.
cur="$(git -C "$STATE/agent" rev-parse --abbrev-ref HEAD 2>/dev/null || echo main)"
case "$cur" in
  request/*)
    done_name="$(cd "$REPO" && node scripts/intake/task-status.mjs request-of "$cur" 2>/dev/null)"
    if [ -n "$done_name" ] \
      && printf '%s\n' "$filed" | /usr/bin/grep -qxF "$done_name.md" \
      && ! pgrep -f "requests/$done_name.md" >/dev/null 2>&1; then
      if git -C "$STATE/agent" switch -q --detach "$BASE" 2>>"$LOG"; then
        say "back on main: $done_name is filed under requests/done"
        git -C "$REPO" branch -D "$cur" >>"$LOG" 2>&1
        cur=HEAD
      fi
    fi
    ;;
esac

# One agent at a time.
#
# Matt: "I thought you optimized things. I have several tasks in flight,
# all exceeding 2 hours already." Five were running, and each had
# already spent 20 to 46 minutes inside `npm run test:everything`.
#
# The gate needs the machine to itself: it holds ~/.mtg-gradle.lock
# across its Gradle suites and the device one alone is twenty minutes.
# Five exclusive 25-minute slots is two hours of queueing before anybody
# does any work. That is arithmetic, not inefficiency, and making the
# individual commands faster — which the daemon fix did — does not touch
# it. They fight for memory too: a gate run was killed by the system
# while an agent's device suite held the emulator, on a 16GB laptop.
#
# Matt: "Update it so only ONE task runs at a time. A very very SIMPLE
# queue system. NOTHING FANCY. JUST ONE AT A TIME". So one, and the
# queue is the request folder it already reads, oldest first.
#
# Everything above this line still runs on every tick — cancel,
# reconcile, the sweep — because freezing the bookkeeping behind
# dispatch is how a row came to read `in progress` for three hours with
# nothing running.
CAP="${MTG_AGENT_CAP:-1}"
# One tree means at most one agent, and it is working if the tree is on
# a request branch and a process names that request. This used to walk
# `.intake/wt/*`, which no longer exists — so the count was always
# zero, and a live agent read as a stalled one.
working=0
on="$(git -C "$STATE/agent" rev-parse --abbrev-ref HEAD 2>/dev/null || true)"
busy_with="$(cd "$REPO" && node scripts/intake/task-status.mjs request-of "${on:-}" 2>/dev/null)"
if [ -n "$busy_with" ] && pgrep -f "requests/$busy_with.md" >/dev/null 2>&1; then
  working=1
fi
if [ "$working" -ge "$CAP" ]; then
  say "$busy_with is still building; $CAP at a time, so nothing started"
  exit 0
fi

# An empty queue and a broken one must never look the same to this loop.
# That conflation is the oldest bug in this system and it has come back
# twice, so the queue is read into a variable where a failure is visible
# rather than piped in, where it arrives as "no requests".
queue="$(cd "$REPO" && node scripts/intake.mjs buildable)" || {
  # The one thing worth waking Matt for: nothing can build at all until
  # this is fixed, and no row in the app will say so because reconcile
  # asks the same broken file. There was a `tell` helper for alarms like
  # this; it had nine callers, was never defined, and died as `tell:
  # command not found` on a stderr launchd throws away. Two lines with one
  # caller cannot do that.
  say "NEEDS YOU: intake.mjs failed; nothing can build until that is fixed"
  osascript -e 'display notification "mtg intake cannot read the queue" with title "mtg intake"' >/dev/null 2>&1 || true
  exit 1
}

file=''
branch=''
finishing=''

# ONE tree, reused forever, not one per task.
#
# Matt: "Why do you need worktrees?!?! Can't you just use a fucking
# branch?!" The per-task worktree was the whole remaining problem: a new
# directory has no `build/`, so every module compiled from nothing
# inside an agent whose tool kills a command at ten minutes. Measured on
# one task — tree cut 12:45, :core built 12:48, :webApp 12:49,
# :androidApp 12:55 — and three of its test runs died at exactly 10.0
# minutes.
#
# Reuse is what fixes that, not removal. This tree is made once and then
# switched between branches, so its build directories stay warm and a
# narrow test costs about a second instead of nine minutes.
#
# It is a second directory rather than the clone itself because the
# clone is where the coordinating session works — editing, running the
# gate — and two things cannot sit on different branches in one
# checkout. Not because anybody has a working copy to protect: "I HAVE
# NO FUCKING WORKING COPY... THE CODE IS YOUR RESPONSIBILITY NOT MINE".
TREE="$STATE/agent"
if [ ! -e "$TREE" ]; then
  if git -C "$REPO" worktree add -q --detach "$TREE" "$BASE" 2>>"$LOG"; then
    say "made the agent tree at $TREE; its first build is cold and every one after is not"
    [ -d "$REPO/node_modules" ] && ln -s "$REPO/node_modules" "$TREE/node_modules"
    # A linked worktree's `.git` is a FILE, so its exclude list is not
    # at `.git/info/exclude`. Ask git where it is.
    ex="$(git -C "$TREE" rev-parse --git-path info/exclude 2>/dev/null)"
    [ -n "$ex" ] && mkdir -p "$(dirname "$ex")" && printf 'node_modules\n' >> "$ex"
  else
    say "NEEDS YOU: could not make the agent tree at $TREE"
    exit 1
  fi
fi
# The SDK path, and only that.
#
# `apps/local.properties` is gitignored, so it lives in the clone and
# in no worktree, and without it NO Gradle task touching :androidApp
# runs — it dies in sixteen seconds on "SDK location not found", which
# is half of why agents used to die. Untracked, so copying it leaves
# the tree clean.
#
# CLAUDE.md and `.claude/` used to be copied here too, because a tree
# cut days ago handed the agent the law as it stood then. That reason
# is gone: every task switches the tree to a branch off current main,
# so the instructions are already current. Copying them was also
# deadlocking the dispatcher — they are tracked files, so the copy made
# the tree dirty, and the next run refused to start anything because
# the tree was dirty. Six ticks in a row said "1 changed files and
# nobody working" about a file it had changed itself.
mkdir -p "$TREE/apps" 2>/dev/null
cp "$REPO/apps/local.properties" "$TREE/apps/local.properties" 2>/dev/null || true

# The tree has to be clean before anything new starts.
#
# With one checkout and no worktrees, a task that stopped leaves its
# branch checked out with its work on it. Starting the next request on
# top of that would discard somebody's unpushed commits, which is the
# one thing the worktree arrangement got right and cost thirty-two
# uncommitted files to learn.
#
# So a checkout that is not on a clean main is a stalled task: say which
# one and why, and stop. Agents are told to commit and push before their
# first test run and after every part that passes, so this is rare, and
# when it happens it is loud rather than silent.
cur="$(git -C "$TREE" rev-parse --abbrev-ref HEAD 2>/dev/null)"
dirty="$(git -C "$TREE" status --porcelain 2>/dev/null | /usr/bin/grep -cv '^?? ')"
if [ "$dirty" -gt 0 ] || [ -n "$(cd "$REPO" && node scripts/intake/task-status.mjs request-of "$cur" 2>/dev/null)" ]; then
  stuck="$(cd "$REPO" && node scripts/intake/task-status.mjs request-of "$cur" 2>/dev/null)"
  # An open pull request means the building is done and what is left is
  # the gate and a merge, which is a bounded job. One finisher, marked
  # on disk and never cleared — something picking work back up over and
  # over is how seven agents came to run on a single request.
  mark="$STATE/${stuck:-unknown}.finished-once"
  if [ -n "$stuck" ] && [ ! -e "$mark" ] \
    && gh pr list --head "$cur" --state open --json number 2>/dev/null | /usr/bin/grep -q '"number"'; then
    : > "$mark"
    file="$stuck.md"; branch="$cur"; finishing=1
  else
    say "the agent tree is on $cur with $dirty changed files and nobody working; left alone"
    if [ -n "$stuck" ]; then
      why="$(tail -c 2000 "$STATE/$stuck.log" 2>/dev/null | tr -d '\0' \
        | sed -n 's/.*"result":"\([^"]\{1,120\}\)".*/\1/p' | tail -1)"
      node "$REPO/scripts/intake/task-status.mjs" stalled "$stuck.md" "$why" >>"$LOG" 2>&1 || true
    fi
    exit 0
  fi
fi

# Take the first ready request main has not already filed.
if [ -z "$finishing" ]; then
  file=''
  while IFS= read -r f; do
    [ -n "$f" ] || continue
    if printf '%s\n' "$filed" | /usr/bin/grep -qxF "$f"; then
      say "skipping ${f%.md}: already filed under requests/done on ${BASE}"
      continue
    fi
    b="$(cd "$REPO" && node scripts/intake.mjs branch "$f")" || continue
    if git -C "$TREE" switch -q -C "$b" "$BASE" 2>>"$LOG"; then
      file="$f"; branch="$b"; break
    fi
    say "skipping ${f%.md}: could not start $b"
  done <<EOF
$queue
EOF
fi

[ -n "$file" ] || exit 0
name="${file%.md}"

# Nothing to hand over any more.
#
# A worktree needed `node_modules` symlinked (an `npm ci` cost five
# minutes), `apps/local.properties` copied in (without the SDK path NO
# Gradle task touching :androidApp runs at all — it dies in sixteen
# seconds on "SDK location not found", which is half of why agents used
# to die), and CLAUDE.md and `.claude/` refreshed, because a tree cut
# days ago handed the agent the law as it stood then.
#
# One checkout has all of it already, always current. That whole class
# of failure is gone with the worktrees.

# Hand over the LIVE request file, not the one the branch was cut from.
#
# The tree branches off main, so its copy is whatever was committed.
# Four requests once sat at `status: hold` on main while the live files
# said `ready`: every agent read the committed copy, correctly refused
# to build, and stopped, so the queue looked broken when it was obeying.
# The file Matt is looking at is the file the agent must read.
mkdir -p "$TREE/requests" 2>/dev/null
cp "$REQUESTS/$file" "$TREE/requests/$file" 2>>"$LOG" \
  || say "could not hand over $file; the agent will read the committed copy"

# Agents authenticate as their own service account, `intake-agent`, with
# its own session token. They never see the operator password: that one
# unlocks /admin/sql on any database and is the way back in when every
# account has lost admin, so it stays out of reach.
AGENT_TOKEN=""
if [ -f "$HOME/.mtg-agent.env" ]; then
  AGENT_TOKEN="$(/usr/bin/grep -m1 "^MTG_AGENT_TOKEN=" "$HOME/.mtg-agent.env" | cut -d= -f2-)"
fi
[ -n "$AGENT_TOKEN" ] || say "no ~/.mtg-agent.env; the agent cannot act on the API as admin"

if [ -n "$finishing" ]; then
  say "finishing $name on $branch: its pull request is open and nobody is on it"
else
  say "building $name on $branch"
fi
node "$REPO/scripts/intake/task-status.mjs" building "$file" >>"$LOG" 2>&1 || true

# A finisher is told what it is, because the work is already written and
# re-doing it is the wrong instinct. Everything else about the brief
# applies: the gate is the gate.
FINISH_PROMPT=""
if [ -n "$finishing" ]; then
  FINISH_PROMPT="THIS WORK IS ALREADY BUILT AND PUSHED. Its pull request is open on $branch and the agent that wrote it is gone. You are finishing it, not rebuilding it: read the diff, rebase onto origin/main if it has moved, run 'npm run test:everything' in the foreground, fix whatever is red, and merge on EVERY SUITE PASSED. Do not start the work over. You are the only finisher this request gets.

"
fi

cd "$TREE" || exit 1
env \
  -u CLOUDFLARE_API_TOKEN -u CLOUDFLARE_ACCOUNT_ID -u CLOUDFLARE_API_KEY \
  -u CLOUDFLARE_EMAIL -u CF_API_TOKEN -u CF_ACCOUNT_ID -u CF_EMAIL \
  -u CLOUDFLARE_API_USER_SERVICE_KEY -u WRANGLER_CF_AUTHORIZATION_TOKEN \
  CLOUDFLARE_AUTH_USE_KEYRING=false XDG_CONFIG_HOME="$STATE/void" \
  MTG_API_TOKEN="$AGENT_TOKEN" \
  INTAKE_BUILDER=1 \
  GH_CONFIG_DIR="${GH_CONFIG_DIR:-$HOME/.config/gh}" \
  claude -p "${FINISH_PROMPT:-}You are the request-builder agent. Read .claude/agents/request-builder.md and follow it exactly, then build requests/$name.md.

It is yours end to end: build it test-first, move requests/$name.md into requests/done/ in your own commit, open the pull request, run \`npm run test:everything\` in the foreground and merge on EVERY SUITE PASSED unless the request says 'merge: ask'. There is no CI — do not wait for checks, do not watch the deploy, do not use ScheduleWakeup or Monitor; you get no second turn.

Run tests the way .claude/agents/request-builder.md says: one unit test at a time during the cycle, named, red then green. Then ONCE at the end, before you merge, run 'npm run test:everything' and let it finish. CI does not validate anything any more — that command is the whole gate, it runs every suite including the emulator, and main takes a fully green one. Not the suites your diff reaches. All of them.

During the CYCLE never run a whole suite — your Bash tool stops every command at 10 minutes and a cold worktree compile is longer than that. Run your own test by name, --tests 'YourTest'. The full run at the end goes through 'npm run test:everything', which puts every suite behind scripts/guard.mjs so none of them is subject to your tool's limit.

Ask for several things at once. Independent tool calls go in ONE message: four file reads, three greps and a git status is one message, not eight. Five agents were measured at 184, 153, 103, 86 and 54 tool calls over exactly that many turns — 0% batched, every one of them — and a turn costs about 25 seconds whatever is in it, which came to more time than every command they ran put together. The test is dependency: if you need the first answer to decide what to ask second, those are two messages and that is fine. If you are working through a list, it is one. Suites are the exception and still run one at a time in the foreground.

Never background a command and poll its output, and never background a suite. One agent spent 55% of its run inside polling loops and another spent twelve minutes reading its own background task files. Run it in the foreground and let it finish.

node_modules is already there, symlinked. Do not run npm ci.

Commit and push BEFORE your first test run, and after every part that passes. Not at the end. An agent can be rate limited or killed at any moment, and anything uncommitted at that point is work somebody has to read back off disk. Four agents were caught by this with up to thirteen files uncommitted.

NOBODY IS COMING AFTER YOU. There is no resume: if you stop, this request sits with your worktree until Matt looks at it. Finish it." \
  --permission-mode bypassPermissions --model opus \
  --output-format stream-json --verbose --include-partial-messages \
  >>"$STATE/$name.log" 2>&1

say "$name finished ($?) — its pull request, if it opened one, is the agent's own"
node "$REPO/scripts/intake/task-status.mjs" settle "$file" "$branch" >>"$LOG" 2>&1 || true
