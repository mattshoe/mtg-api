#!/bin/bash
#
# Drop a file in requests/, get an agent and a pull request. That is all.
#
# The builder owns its own work end to end: it builds, opens the pull request,
# drives CI to green and merges. Green CI is the gate, not a human.
#
# This script does not do any of that for it. It starts one builder, keeps a
# ceiling on it so a stuck one cannot run all night, files a merged request
# out of the queue, and says who needs looking at. An earlier version tried to
# own the CI wait and the merge in 2,306 lines of bash; three audit rounds
# found seven criticals and four of them lived in exactly that machinery.
#
# The reason builders used to fail at this was not the rule, it was the
# mechanism: they were told to "watch CI", so they ended their turn waiting for
# a notification that headless `claude -p` can never deliver. Three of four did
# it. `gh pr checks --watch` blocks in the foreground instead, which is why the
# prompt below names that command specifically.
#
# Two invariants, because both were learned by losing work:
#   1. A worktree is never deleted automatically. If a builder dies, its tree
#      stays on disk with whatever it had. `npm run intake:status` shows it.
#   2. Every decision comes from scripts/intake.mjs, which has a test suite.
#      No predicate is reimplemented here.
#
set -u

REPO="$(cd "$(dirname "$0")/../.." && pwd -P)"
STATE="$REPO/.intake"
REQUESTS="$REPO/requests"
LOG="$STATE/intake.log"
BASE="${INTAKE_BASE:-origin/main}"
WT="${INTAKE_WT:-$STATE/wt}"
# A build, plus a red/green cycle, plus the emulator job at ~17 minutes, plus
# a fix and a second CI run. Shorter than this killed builders that were fine.
MAX_MINUTES="${INTAKE_MAX_MINUTES:-180}"

say() { printf '%s  %s\n' "$(date '+%H:%M:%S')" "$1" >>"$LOG"; }
tell() {
  say "NEEDS YOU: $1"
  osascript -e "display notification \"$1\" with title \"mtg intake\"" 2>/dev/null || true
}

# intake.mjs or nothing. An empty answer and a broken one must never look the
# same, so every caller writes `|| die`.
#
# `ask` CANNOT exit the script itself: every call site is a $( ) substitution,
# so an `exit` in here kills only the subshell and the run carries on with an
# empty answer. That is the bug this guard exists to prevent, and the first
# draft of this file had it.
ask() {
  local out status
  out="$(cd "$REPO" && node scripts/intake.mjs "$@" 2>>"$LOG")"
  status=$?
  [ "$status" -ne 0 ] && { say "intake.mjs $* failed ($status)"; return 1; }
  printf '%s' "$out" | sed '/^$/d'
  return 0
}

die() {
  tell "intake stopped: $1"
  exit 1
}

# A lock that is complete before it is visible, so a second dispatcher never
# sees a half-built one and calls it stale.
take_lock() {
  local tmp="$STATE/lock.$$"
  rm -rf "$tmp"
  mkdir -p "$tmp" || return 1
  printf '%s\n' "$$" >"$tmp/pid"
  mkdir "$STATE/lock" 2>/dev/null || { rm -rf "$tmp"; return 1; }
  mv "$tmp/pid" "$STATE/lock/pid"
  rmdir "$tmp"
  return 0
}

held_by_a_live_dispatcher() {
  local pid age
  pid="$(cat "$STATE/lock/pid" 2>/dev/null)"
  if [ -z "$pid" ]; then
    # Mid-acquire counts as alive — but only for a few seconds. A SIGKILL or a
    # reboot inside that window used to leave a pidless lock that wedged intake
    # permanently and silently, while the dashboard called it healthy.
    age=$(( $(date +%s) - $(stat -f %m "$STATE/lock" 2>/dev/null || stat -c %Y "$STATE/lock" 2>/dev/null || echo 0) ))
    [ "$age" -lt 60 ] && return 0
    say "a lock with no pid has been there ${age}s; treating it as dead"
    return 1
  fi
  kill -0 "$pid" 2>/dev/null || return 1
  ps -o command= -p "$pid" 2>/dev/null | /usr/bin/grep -q 'dispatch\.sh'
}

# A builder has no business deploying or touching remote D1, so it runs without
# the credentials for either. This is mitigation and not a control: the token is
# in a file the builder can read as the same user. Real isolation needs a
# separate uid, which is Matt's call to make.
# $1 prompt, $2 merge mode ("auto" or "ask"). The mode is passed as an
# environment variable so the PreToolUse hook can refuse `gh pr merge` on an
# `ask` request, rather than the rule being a sentence the model may ignore.
run_agent() {
  env -u CLOUDFLARE_API_TOKEN -u CLOUDFLARE_ACCOUNT_ID -u CLOUDFLARE_API_KEY \
      -u CLOUDFLARE_EMAIL -u CF_API_TOKEN -u CF_ACCOUNT_ID -u CF_EMAIL \
      -u CLOUDFLARE_API_USER_SERVICE_KEY -u WRANGLER_CF_AUTHORIZATION_TOKEN \
      -u CLOUDFLARE_CONFIG_FILE -u CLOUDFLARE_API_BASE_URL \
      CLOUDFLARE_AUTH_USE_KEYRING=false \
      XDG_CONFIG_HOME="$STATE/void" XDG_CACHE_HOME="$STATE/void" \
      GH_CONFIG_DIR="${GH_CONFIG_DIR:-$HOME/.config/gh}" \
      INTAKE_BUILDER=1 INTAKE_MERGE="$2" \
      claude -p "$1" --permission-mode bypassPermissions --model opus \
        --output-format stream-json --verbose --include-partial-messages
}

# Run an agent with a hard ceiling, in the foreground, and return its code.
#
# `where` is not optional: an agent is only isolated if it actually runs in its
# worktree, and the first draft of this file never cd'd into one, so both the
# triage agent and the builder ran in the live checkout.
#
# `claude` is backgrounded only so the killer can name the right pid — the
# version that used $$ inside a subshell killed every sibling instead.
agent_with_ceiling() {
  local where="$1" prompt="$2" out="$3" mode="${4:-ask}" pid killer code pgid
  [ -d "$where" ] || { say "no directory to run in: $where"; return 1; }
  # Own process group, so the ceiling can kill the whole tree. `pkill -P`
  # reached only direct children, and a timed-out builder left its gradle JVM
  # holding ~/.gradle against Matt's own builds.
  set -m
  ( cd "$where" && run_agent "$prompt" "$mode" ) >"$out" 2>&1 &
  pid=$!
  set +m
  pgid="$pid"
  # The sidecar must take its own sleep with it. `kill $killer` reaped the
  # subshell and left `sleep 10800` reparented to pid 1 holding the inherited
  # stdout, so anything piping this script blocked for hours after it finished.
  ( sp=''
    trap '[ -n "$sp" ] && kill "$sp" 2>/dev/null; exit 0' TERM
    sleep $((MAX_MINUTES * 60)) & sp=$!
    wait "$sp" 2>/dev/null || exit 0
    kill -0 "$pid" 2>/dev/null && {
      kill -TERM -- "-$pgid" 2>/dev/null || kill -TERM "$pid" 2>/dev/null
      sleep 10
      kill -KILL -- "-$pgid" 2>/dev/null || kill -KILL "$pid" 2>/dev/null
    } ) &
  killer=$!
  wait "$pid"; code=$?
  # Kill the sidecar and reap it quietly: bash otherwise prints its own
  # "Terminated" line to stderr when it collects the job.
  kill "$killer" 2>/dev/null
  wait "$killer" 2>/dev/null
  return $code
}

# Triage adds a plan to a request file. It may not split one request into
# several or combine two into one: the copy-back could only ever return the
# files it handed over, so anything triage created was silently thrown away
# and the originals were abandoned.
triage() {
  local todo tree file
  todo="$(ask untriaged)" || die "could not read the queue"
  [ -z "$todo" ] && return 0

  # A leading dot, because `branchFor` can never produce one: `requests/triage.md`
  # used to land its BUILDER worktree here and the next triage pass rm -rf'd it.
  tree="$WT/.triage"
  # Only ever remove a tree git agrees is the detached triage one.
  if [ -d "$tree" ] && ! git -C "$REPO" worktree list --porcelain | /usr/bin/grep -A2 -F "worktree $tree" | /usr/bin/grep -q '^detached'; then
    tell "refusing to reuse $tree — it is not the detached triage worktree"
    return 0
  fi
  rm -rf "$tree"
  git -C "$REPO" worktree prune
  git -C "$REPO" worktree add -q --detach "$tree" "$BASE" || {
    say "no triage worktree, skipping triage"; return 0
  }
  rm -rf "$tree/requests"
  mkdir -p "$tree/requests"
  printf '%s\n' "$todo" | while IFS= read -r file; do
    cp "$REQUESTS/$file" "$tree/requests/$file" || say "could not hand over $file"
    # Remember what was handed over, so the copy-back can tell triage's edit
    # from one Matt made in the minutes the pass takes.
    cksum < "$REQUESTS/$file" | tr -d ' \n' > "$tree/requests/.$file.sum" 2>/dev/null || true
  done

  say "triaging: $(printf '%s' "$todo" | tr '\n' ' ')"
  agent_with_ceiling "$tree" \
    "Follow .claude/agents/request-triage.md exactly. Plan every request in requests/. Edit each file in place: do not create new request files, do not delete any, do not combine or split them." \
    "$STATE/triage.log" ask

  printf '%s\n' "$todo" | while IFS= read -r file; do
    # Only back into a request that is still live. Without this, deleting a
    # request during the five minutes triage takes un-deleted it.
    [ -f "$REQUESTS/$file" ] || { say "$file was withdrawn during triage"; continue; }
    [ -f "$tree/requests/$file" ] || continue
    cmp -s "$tree/requests/$file" "$REQUESTS/$file" && continue
    # Both sides through the same `tr`, or the stored sum's trailing newline
    # makes every file look changed and no plan is ever written back.
    if [ -f "$tree/requests/.$file.sum" ] \
       && [ "$(cksum < "$REQUESTS/$file" | tr -d ' \n')" != "$(cat "$tree/requests/.$file.sum")" ]; then
      tell "$file changed while triage was running — keeping your version, triage's plan is in $tree/requests/$file"
      continue
    fi
    cp "$tree/requests/$file" "$REQUESTS/$file" && say "triage planned $file"
  done

  still="$(ask untriaged)" || die "could not re-read the queue after triage"
  [ -n "$still" ] && tell "triage produced no plan for: $(printf '%s' "$still" | tr '\n' ' ')"
  return 0
}

build_one() {
  local file="$1" name branch tree code pr mode
  name="${file%.md}"
  branch="$(ask branch "$file")" || die "could not name a branch for $file"
  tree="$WT/$name"

  if ! git -C "$REPO" worktree add -q -b "$branch" "$tree" "$BASE" 2>>"$LOG"; then
    # The branch already exists. Reusing it is only safe if it holds nothing
    # that is not already in the base: otherwise the builder silently starts on
    # an abandoned attempt, missing every newer commit on main, and its TDD red
    # may not reproduce.
    if git -C "$REPO" merge-base --is-ancestor "$branch" "$BASE" 2>/dev/null; then
      git -C "$REPO" worktree add -q "$tree" "$branch" 2>>"$LOG" \
        || { tell "$name could not get a worktree"; return 0; }
      say "reusing $branch, which holds nothing newer than $BASE"
    else
      tell "$name: $branch already exists and has commits not in $BASE ($(git -C "$REPO" rev-parse --short "$branch" 2>/dev/null)) — look at it, nothing was touched"
      return 0
    fi
  fi

  if ! (cd "$REPO" && node scripts/intake.mjs equipped "$tree" >>"$LOG" 2>&1); then
    tell "$name cannot build: its base is missing files a builder needs"
    return 0
  fi

  mkdir -p "$STATE/handed"
  cp "$REQUESTS/$file" "$STATE/handed/$file"

  mode="$(ask merge "$file")" || die "could not read the merge mode for $file"
  say "$name is merge: $mode"

  say "building $name on $branch"
  agent_with_ceiling "$tree" "You are the request-builder agent. Read .claude/agents/request-builder.md and follow it exactly, then build the request in $STATE/handed/$file.

The work is yours end to end. Build it test-first, open the pull request, drive CI to green, and merge it yourself. Green CI is the gate — do not stop at a green pull request waiting to be told.

Wait for CI with a command that BLOCKS in the foreground:

    gh pr checks <n> --watch --fail-fast

Then confirm every check passed, not merely that none failed, and merge:

    gh pr checks <n> --json name,state --jq '[.[]|select(.state!=\"SUCCESS\")]|length'   # must be 0
    gh pr merge <n> --squash

Never --admin. Never with a check pending, skipped or cancelled. If the request's frontmatter says 'merge: ask', stop at a green pull request and say so.

Do NOT call ScheduleWakeup, Monitor, or any wait loop, and do not background a build — you get no second turn and nothing will wake you. One foreground command at a time.

Commit and push each part as it passes. Nothing you leave uncommitted is safe." \
    "$STATE/$name.log" "$mode"
  code=$?

  pr="$(cd "$tree" && gh pr list --head "$branch" --state all --limit 1 --json number,state --jq '.[0]|"\(.number) \(.state)"' 2>>"$LOG")"
  if [ $? -ne 0 ]; then
    tell "$name finished but GitHub could not be asked about its pull request — nothing was changed"
    return 0
  fi
  case "$pr" in
    '')  tell "$name finished ($code) with no pull request. Its worktree is kept at $tree" ;;
    *MERGED)
      say "$name merged as #${pr%% *}"
      file_it "$file" "$name"
      ;;
    *OPEN)
      tell "$name left #${pr%% *} open — it is either merge:ask or it could not get green"
      ;;
    *)   tell "$name finished ($code); its pull request is $pr" ;;
  esac
}

# The move into requests/done has to be committed. Left as working-tree state
# it is one `git checkout . && git clean -fd` away from the request coming back
# and being built a second time.
file_it() {
  local file="$1" name="$2" head
  mkdir -p "$REQUESTS/done"
  mv "$REQUESTS/$file" "$REQUESTS/done/$file" 2>/dev/null \
    || { tell "$name merged but its request file could not be filed — it will rebuild"; return 0; }
  say "filed $file under requests/done"
  head="$(git -C "$REPO" symbolic-ref --short HEAD 2>/dev/null)"
  if [ "$head" != "${BASE#origin/}" ]; then
    tell "filed $file, but did not commit it: the checkout is on $head, not ${BASE#origin/}"
    return 0
  fi
  # Stage the whole folder, because a per-path `add -A -- requests/$file`
  # exits 128 when that path is both gone and untracked, which is exactly a
  # request Matt has just dropped. But refuse first if there is anything
  # untracked under requests/ that is not the file being filed: a bare
  # `add -A -- requests` swept his drafts into the commit and pushed them.
  local stray
  stray="$(git -C "$REPO" status --porcelain --untracked-files=all -- requests \
            | awk '$1 == "??" { print $2 }' \
            | /usr/bin/grep -v -x "requests/done/$file" || true)"
  if [ -n "$stray" ]; then
    tell "filed $file but did not commit it: untracked files under requests/ that are not mine ($(printf '%s' "$stray" | tr '\n' ' '))"
    return 0
  fi
  git -C "$REPO" add -A -- requests 2>>"$LOG" || true
  if git -C "$REPO" diff --cached --quiet -- requests; then
    say "nothing to commit for $file"
  else
    # The two paths, never the directory: `-- requests` put a request Matt was
    # still writing into a commit whose message says it is about another file.
    git -C "$REPO" reset -q -- requests 2>>"$LOG" || true
    git -C "$REPO" add -A -- "requests/done/$file" 2>>"$LOG" || true
    git -C "$REPO" add -A -- "requests/$file" 2>>"$LOG" || true
    git -C "$REPO" commit -q -m "requests: $file is done" \
      -- "requests/$file" "requests/done/$file" 2>>"$LOG" \
      && say "committed the filing" \
      || tell "filed $file but could not commit it — it may come back"
  fi
}

build() {
  local todo file name
  todo="$(ask buildable)" || die "could not read the queue"
  [ -z "$todo" ] && { say "nothing to build"; return 0; }
  # One at a time: Gradle does not share a laptop well, and concurrent builders
  # measured slower than serial ones. But skip past anything that already has a
  # worktree, or one dead builder at the head of the queue blocks every request
  # behind it on every dispatch, with nothing saying so.
  # NOT `printf | while`: the loop body must run in this shell or `die` inside
  # build_one exits only the pipeline subshell and the dispatcher carries on to
  # log a clean finish. That is the same mistake as `ask` calling exit, and as
  # the watchdog using $$ in a subshell. In bash, anything that needs to stop
  # the script cannot live behind a pipe.
  printf '%s\n' "$todo" > "$STATE/todo.$$"
  while IFS= read -r file; do
    name="${file%.md}"
    if [ -d "$WT/$name" ]; then
      tell "$name has a worktree already at $WT/$name — look at it, then delete it to retry"
      continue
    fi
    build_one "$file"
    break
  done < "$STATE/todo.$$"
  rm -f "$STATE/todo.$$"
}

main() {
  mkdir -p "$STATE" "$WT" "$STATE/void"

  # Opt-in. `.intake/` is gitignored, so an absent `disabled` file meant a
  # fresh clone was armed the first time any Claude session edited a request —
  # no install, no launchctl, no consent. install.sh writes `enabled`.
  [ -f "$STATE/enabled" ] || exit 0
  [ -f "$STATE/disabled" ] && exit 0

  # A linked worktree's .git is a file, not a directory. A builder editing its
  # own request file fires the hook, and this is what stops that from starting
  # a second dispatcher inside the builder's tree.
  [ -d "$REPO/.git" ] || exit 0

  if ! take_lock; then
    held_by_a_live_dispatcher && exit 0
    say "clearing a lock whose dispatcher is gone"
    rm -rf "$STATE/lock"
    take_lock || exit 0
  fi
  trap 'rm -rf "$STATE/lock"' EXIT

  git -C "$REPO" worktree prune
  git -C "$REPO" fetch -q origin main 2>>"$LOG" || say "fetch failed; base may be stale"

  pending="$(ask pending)" || die "could not read the queue"
  [ -z "$pending" ] && { say "nothing pending"; exit 0; }

  triage
  build

  held="$(ask held)" || die "could not list held requests"
  if [ -n "$held" ]; then
    # A request triage marked `needs-matt` is a question waiting on Matt. That
    # has to be a notification; it used to be one line in a log nobody reads.
    printf '%s\n' "$held" | while IFS= read -r file; do
      case "$(ask state "$file" 2>/dev/null)" in
        *needs-matt*) tell "$file is waiting on you — triage asked a question in it" ;;
        *) say "held back: $file" ;;
      esac
    done
  fi
  say "dispatch finished"
}

main "$@"
