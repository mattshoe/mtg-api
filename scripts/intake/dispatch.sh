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
# ten-minute tool limit. Both of those are fixed. None of the machinery
# was ever the problem, and every bug Matt saw today lived in it.
#
# Matt: "I just want a fucking request to start a fucking agent."
#
# There is no lock because `git worktree add -b` is the lock: the second
# dispatcher to ask for the same branch is refused by git, atomically. That
# is also what makes parallel free-for-all work — each tick takes whatever
# request has no worktree yet.
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
  node "$REPO/scripts/intake/task-status.mjs" reconcile >>"$LOG" 2>&1 || true
  exit 0
fi

# A linked worktree's .git is a file. The agent edits its own request file,
# which fires the hook, which would otherwise start a dispatcher inside the
# agent's own worktree.
[ -d "$REPO/.git" ] || exit 0

mkdir -p "$STATE"

# Anything Matt submitted from the app, turned into a request file. Without
# this a task submitted in the app sat at `pending` forever: nothing polled
# the inbox, and launchd only fires on a change under requests/, which
# submitting from the app does not cause.
node "$REPO/scripts/intake/inbox.mjs" >>"$LOG" 2>&1 || true

# Every run puts D1 right about what this laptop can see: a dead agent
# reads paused rather than in progress forever, a held request paused, a
# withdrawn one cancelled. It may not stop a build.
node "$REPO/scripts/intake/task-status.mjs" reconcile >>"$LOG" 2>&1 || true

git -C "$REPO" worktree prune
git -C "$REPO" fetch -q origin main 2>>"$LOG" || say "could not fetch; base may be stale"

# What origin/main already has filed. The queue is decided from the LOCAL
# requests/ folder, and that folder lags main whenever an agent merges its
# own work — which is every time one succeeds. Twice a finished request was
# re-dispatched, and the agent then committed to a branch whose pull
# request had already merged. Ask main, not the laptop.
filed="$(git -C "$REPO" ls-tree --name-only "$BASE" requests/done/ 2>/dev/null | sed 's|requests/done/||')"

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

# Take the first ready request that has no worktree yet. Trying each
# candidate rather than returning on the first failure matters: one request
# with a leftover branch would otherwise block everything behind it.
file=''
while IFS= read -r f; do
  [ -n "$f" ] || continue
  if printf '%s\n' "$filed" | /usr/bin/grep -qxF "$f"; then
    say "skipping ${f%.md}: already filed under requests/done on ${BASE}"
    continue
  fi
  # A worktree already there means an agent is on it, or one stopped on it
  # and its work is kept. Either way this is not the dispatcher's to take —
  # reconcile has already told the app which of the two it is.
  wt="$STATE/wt/${f%.md}"
  [ -e "$wt" ] && continue
  b="$(cd "$REPO" && node scripts/intake.mjs branch "$f")" || continue
  if git -C "$REPO" worktree add -q -b "$b" "$wt" "$BASE" 2>>"$LOG"; then
    file="$f"; branch="$b"; tree="$wt"; break
  fi
  say "skipping ${f%.md}: no worktree on $b (the branch may already exist)"
done <<EOF
$queue
EOF

[ -n "$file" ] || exit 0
name="${file%.md}"

# node_modules, shared rather than installed. A cold worktree costs `npm
# ci` — measured at 5 minutes, and one agent spent exactly that before it
# could run a single test. A symlink makes it free.
if [ -d "$REPO/node_modules" ] && [ ! -e "$tree/node_modules" ]; then
  ln -s "$REPO/node_modules" "$tree/node_modules"
  # and keep it out of git: it showed up as untracked in a worktree, so
  # `git add -A` would have committed a symlink to somebody's home.
  printf 'node_modules\n' >> "$tree/.git/info/exclude" 2>/dev/null || true
fi

# The Android SDK path. Without it NO Gradle task touching :androidApp runs
# in a worktree — not the suite, not one test by name; it dies in sixteen
# seconds on "SDK location not found". It is gitignored, as it has to be,
# so it exists in the clone and in no worktree. All three live worktrees
# were missing it, which is why no agent could ever check its own Android
# work, and is half of why they kept dying.
mkdir -p "$tree/apps" 2>/dev/null
cp "$REPO/apps/local.properties" "$tree/apps/local.properties" 2>/dev/null || true

# The instructions, as they are NOW. An agent reads CLAUDE.md and .claude/
# out of the tree it works in, so a worktree cut days ago hands it the law
# as it stood then — and main had just banned the suite that was killing
# agents while their own worktrees still said to run it.
cp "$REPO/CLAUDE.md" "$tree/CLAUDE.md" 2>/dev/null || true
cp -R "$REPO/.claude/." "$tree/.claude/" 2>/dev/null || true

# Hand over the LIVE request file, not the one the worktree was cut from.
# Without this the agent reads main's copy: four requests sat at `status:
# hold` on main while the live files said `ready`, so every agent correctly
# refused to build and stopped. The file Matt is looking at is the file the
# agent must read.
cp "$REQUESTS/$file" "$tree/requests/$file" 2>>"$LOG" \
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

say "building $name on $branch"
node "$REPO/scripts/intake/task-status.mjs" building "$file" >>"$LOG" 2>&1 || true

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

Run tests the way .claude/agents/request-builder.md says: one unit test at a time during the cycle, named, red then green. The functional suites you may run — test:web and npm test — run ONCE at the end, on what your diff reaches. Never in the cycle.

Do NOT run npm run test:screens or the whole :androidApp:testDebugUnitTest task, ever. A cold worktree compile takes 12 to 20 minutes and your Bash tool stops every command at 10, so it cannot finish in the foreground and backgrounding it is banned — seven agent runs on one request died in four hours doing exactly that. Run your own Android test by name with --tests 'YourTest' during the cycle, then push and read CI's android job.

Never background a command and poll its output, and never background a suite. One agent spent 55% of its run inside polling loops and another spent twelve minutes reading its own background task files. Run it in the foreground and let it finish.

node_modules is already there, symlinked. Do not run npm ci.

Commit and push BEFORE your first test run, and after every part that passes. Not at the end. An agent can be rate limited or killed at any moment, and anything uncommitted at that point is work somebody has to read back off disk. Four agents were caught by this with up to thirteen files uncommitted.

NOBODY IS COMING AFTER YOU. There is no resume: if you stop, this request sits with your worktree until Matt looks at it. Finish it." \
  --permission-mode bypassPermissions --model opus \
  --output-format stream-json --verbose --include-partial-messages \
  >>"$STATE/$name.log" 2>&1

say "$name finished ($?) — its pull request, if it opened one, is the agent's own"
node "$REPO/scripts/intake/task-status.mjs" settle "$file" "$branch" >>"$LOG" 2>&1 || true
