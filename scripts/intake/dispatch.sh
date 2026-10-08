#!/bin/bash
# Spins up an agent for every new file in requests/, then owns the pull
# request until it is merged or Matt is told why it is not.
#
# Called two ways:
#   - launchd, via WatchPaths on requests/ (see com.matt.mtg.intake.plist),
#     which is what catches files dropped in while nobody is here
#   - the PostToolUse hook in .claude/settings.json, for files written
#     during a session
#
# The division of labour, which is the thing to understand before editing
# anything here:
#
#   the builder   code committed, branch pushed, pull request open, local
#                 suites green. Then it writes what it did and stops.
#   this script   blocks on CI, merges a green `merge: auto` request after
#                 verifying every check, notifies Matt for `merge: ask`,
#                 flags a red one for a fix-only builder, and files the
#                 finished request under requests/done/ in the REAL repo.
#
# It used to be the builder's job to sit through CI and merge. Measured:
# the `apps` job takes 13-17 minutes and three of four builders ended
# their turn rather than wait — one called ScheduleWakeup and stopped,
# another's last line was "I'll pick up from that notification", and in
# headless `claude -p` there is no next turn. 154 minutes of agent
# wall-clock across four builders produced zero merged pull requests.
# Green CI is still the gate; it is just no longer something an agent has
# to sit through.
#
# Everything that runs lives inside main(). bash reads a script by byte
# offset, so a top-level body means editing this file during a four-hour
# run makes the RUNNING shell execute garbage — `.intake/launchd.log`
# records exactly that: `line 177: ites,: command not found`, `line 193:
# code: unbound variable`, `line 210: syntax error`. With `main "$@"` as
# the last line, bash has read the whole file before any of it runs.

set -uo pipefail

# ---------------------------------------------------------------------
# Talking
# ---------------------------------------------------------------------

say() { printf '%s  %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*" >> "$LOG"; }

# Something Matt will actually see.
#
# Nothing ever told him a request needed him. `needs-matt` appended one
# line to a log he does not read, and `merge: ask` ended with a green
# pull request and silence — while four of five live requests said
# `merge: ask`, so silence was the NORMAL outcome.
tell() {
  say "NOTIFY: $*"
  command -v osascript >/dev/null 2>&1 || return 0
  local body
  body="$(printf '%s' "$*" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g')"
  osascript -e "display notification \"$body\" with title \"MTG intake\"" \
    >/dev/null 2>&1 || true
}

# ---------------------------------------------------------------------
# Asking, with failure and silence kept apart
# ---------------------------------------------------------------------

# Every decision about what is a request, what triage has touched and
# what a builder may pick up lives in scripts/intake.mjs, where the suite
# tests it. This script asks; it does not decide.
#
# It used to discard node's exit status, so node missing, a PATH the
# plist does not carry, a parse error, a wrong branch checked out or
# ENOSPC all produced empty stdout — and the dispatcher logged "nothing
# pending" and exited 0 with a full queue. `.intake/launchd.log` already
# holds the sibling case. Empty output and failure must never be the same
# thing anywhere in this system.
#
# The answer comes back in `ASK_OUT` rather than on stdout on purpose. In
# `out="$(ask pending)"` the function runs in a subshell, where `exit`
# ends the subshell and the dispatcher carries on regardless — which is
# the bug, not a fix for it.
ask() {
  ASK_OUT="$(cd "$REPO" && INTAKE_DIR="$REQUESTS" node scripts/intake.mjs "$@" 2>>"$LOG")"
  local status=$?
  if [ "$status" -ne 0 ]; then
    say "intake.mjs $* failed ($status) — refusing to decide with a queue in the folder"
    exit "$status"
  fi
  return 0
}

tidy() { printf '%s\n' "$1" | sed '/^$/d' | sort; }

list_pending()   { ask pending;   PENDING="$(tidy "$ASK_OUT")"; }
list_untriaged() { ask untriaged; UNTRIAGED="$(tidy "$ASK_OUT")"; }
list_buildable() { ask buildable; BUILDABLE="$(tidy "$ASK_OUT")"; }
list_held()      { ask held;      HELD="$(tidy "$ASK_OUT")"; }

equipped() { ( cd "$REPO" && node scripts/intake.mjs equipped "$1" >/dev/null 2>&1 ); }

# The same rule for `gh`. A failing `gh` used to render as "no pull
# request", which is indistinguishable from a builder that opened none.
ghx() {
  local out status
  out="$(gh "$@" 2>>"$LOG")"
  status=$?
  if [ "$status" -ne 0 ]; then
    say "gh $* failed ($status) — not reading that as an answer"
    return "$status"
  fi
  printf '%s' "$out"
}

# ---------------------------------------------------------------------
# Locks and claims
# ---------------------------------------------------------------------

lock_pid() { cat "$1/pid" 2>/dev/null; }

# Whether a claim directory belongs to something still doing the job.
#
# Three ways this went wrong. The lock was published nine lines before
# its pid file, so in the normal hook+launchd double-fire the second
# dispatcher saw a pidless lock, called it stale, deleted it and
# proceeded — two dispatchers then triaged and built the same requests
# and `rm -rf`d under each other. The `.building` claim had no pid for
# the whole worktree-setup window, so a concurrent dispatcher read a LIVE
# builder as dead. And `kill -0` on a pid the kernel has since reused
# succeeds, so a `kill -9` or a reboot wedged the queue permanently.
#
# So: no pid yet means LIVE, never stale. Nothing holds a claim past the
# cap. And a live pid has to actually be the thing it claims to be.
claim_alive() {
  local dir="$1" pid started now age cmd
  [ -d "$dir" ] || return 1
  pid="$(lock_pid "$dir")"
  if [ -z "$pid" ]; then
    say "  $(basename "$dir") has no pid yet — live, which is what the double-fire needs"
    return 0
  fi
  kill -0 "$pid" 2>/dev/null || { say "  $(basename "$dir") pid $pid is gone"; return 1; }
  started="$(cat "$dir/started" 2>/dev/null)"
  if [ -n "$started" ]; then
    now="$(date +%s)"
    age=$(( (now - started) / 60 ))
    if [ "$age" -ge "$MAX_MINUTES" ]; then
      say "  $(basename "$dir") has been held ${age}m, past the ${MAX_MINUTES}m cap"
      return 1
    fi
  fi
  cmd="$(ps -o command= -p "$pid" 2>/dev/null)"
  case "$cmd" in
    *dispatch.sh*|*claude*) return 0 ;;
    *) say "  $(basename "$dir") pid $pid is '$cmd', not ours — the kernel reused it"; return 1 ;;
  esac
}

# Fill a claim in before anything can read it as abandoned.
stamp_claim() {
  local dir="$1" pid="$2"
  printf '%s\n' "$pid" > "$dir/pid"
  date +%s > "$dir/started"
  ps -o command= -p "$pid" 2>/dev/null > "$dir/command" || true
}

take_lock() {
  # `mkdir` is the atomic step: exactly one caller creates the directory
  # and every other one fails. The brief for this work proposed building
  # the lock in `$LOCK.$$` and publishing it with `mv -n`, but `mv -n` of
  # a directory onto an EXISTING directory moves it inside rather than
  # refusing — verified on this machine, it leaves
  # `.intake/dispatch.lock/dispatch.lock.4321` and exits 0, which is
  # worse than the bug. `mkdir` first, plus a pidless lock reading as
  # live, closes the same window.
  if mkdir "$LOCK" 2>/dev/null; then
    stamp_claim "$LOCK" "$$"
    return 0
  fi
  if claim_alive "$LOCK"; then
    say "already running as $(lock_pid "$LOCK"), leaving it to that one"
    return 1
  fi
  say "clearing a lock left behind by pid $(lock_pid "$LOCK")"
  rm -rf "$LOCK"
  mkdir "$LOCK" 2>/dev/null || return 1
  stamp_claim "$LOCK" "$$"
  return 0
}

# Only ever remove our own, so releasing early cannot take a successor's.
drop_lock() {
  [ -d "$LOCK" ] || return 0
  [ "$(lock_pid "$LOCK")" = "$$" ] || return 0
  rm -rf "$LOCK"
}

# ---------------------------------------------------------------------
# Worktree slots
# ---------------------------------------------------------------------

# Nothing is ever thrown away without being pushed first.
#
# `.intake/wt/card-page-shows-everything` held about thirty-two modified
# files, zero commits and nothing pushed, and the next dispatch `rm -rf`d
# it — so the "nothing was thrown away" guard only delayed the loss by
# one event. A `git clean -xfd` in the primary tree would have taken it
# too, because `.intake/` is gitignored.
salvage() {
  local where="$1" name="$2"
  [ -e "$where/.git" ] || return 0
  local branch dirty ahead sha
  branch="$(git -C "$where" rev-parse --abbrev-ref HEAD 2>/dev/null)"
  [ -n "$branch" ] && [ "$branch" != "HEAD" ] || return 0

  dirty="$(git -C "$where" status --porcelain 2>/dev/null | wc -l | tr -d ' ')"
  if [ "${dirty:-0}" -gt 0 ]; then
    git -C "$where" add -A >>"$LOG" 2>&1
    git -C "$where" \
      -c user.name=intake -c user.email=intake@localhost \
      commit -q -m "wip: $name (builder did not finish)" >>"$LOG" 2>&1
  fi

  ahead="$(git -C "$where" rev-list --count "$BASE..HEAD" 2>/dev/null || echo 0)"
  [ "${ahead:-0}" -gt 0 ] || return 0
  if git -C "$where" push -q --force-with-lease origin "HEAD:refs/heads/$branch" >>"$LOG" 2>&1; then
    sha="$(git -C "$where" rev-parse --short HEAD)"
    say "  salvaged $name as $sha on $branch ($ahead commit(s), ${dirty:-0} file(s) swept up)"
  else
    say "  COULD NOT PUSH $name — its work exists only in $where"
  fi
}

# Never a bare delete. A removed directory leaves `.git/worktrees/<n>`
# behind, and that stale admin entry is what makes a later
# `worktree add --force -B <branch>` fail with "cannot force update the
# branch used by worktree at <deleted path>" — so losing one tree wedges
# every later attempt at the same branch.
remove_worktree() {
  local dead="$1"
  [ -n "$dead" ] || return 0
  git -C "$REPO" worktree remove --force "$dead" >>"$LOG" 2>&1
  git -C "$REPO" worktree prune >>"$LOG" 2>&1
  rm -rf "$dead"
}

# A reusable slot, outside the repo.
#
# Worktrees lived under gitignored `.intake/` inside the primary tree, so
# a routine `git clean -fdx` destroyed live builders. And every one
# started cold: `npm ci` measured at 403 seconds and a first Gradle call
# in a cold worktree at 7.8 minutes against about one minute warm.
#
# So: a fixed set of slots that keep their node_modules and their Gradle
# output, reset onto the base rather than recreated.
take_slot() {
  local n claim
  for n in $(seq 1 "$MAX_BUILDERS"); do
    claim="$STATE/slot$n.claim"
    if mkdir "$claim" 2>/dev/null; then
      stamp_claim "$claim" "$$"
      printf '%s\n' "$n"
      return 0
    fi
    if ! claim_alive "$claim"; then
      say "  slot$n was claimed by something that is gone"
      rm -rf "$claim"
      if mkdir "$claim" 2>/dev/null; then
        stamp_claim "$claim" "$$"
        printf '%s\n' "$n"
        return 0
      fi
    fi
  done
  return 1
}

# Reset a slot onto the base, keeping the expensive things.
#
# Anything uncommitted in there is somebody's unfinished work until it is
# pushed, so it is salvaged first, every time.
prepare_slot() {
  local where="$1" branch="$2" name="$3" stamp
  mkdir -p "$(dirname "$where")"

  if [ -e "$where/.git" ]; then
    salvage "$where" "whatever was in $(basename "$where") before"
    if ! git -C "$where" checkout -q -B "$branch" "$BASE" >>"$LOG" 2>&1; then
      say "  $(basename "$where") would not reset; rebuilding it"
      remove_worktree "$where"
    fi
  fi

  if [ ! -e "$where/.git" ]; then
    git -C "$REPO" worktree prune >>"$LOG" 2>&1
    # No `-B`. Force-updating a branch another worktree holds either
    # orphans its commits or fails outright depending on whether a stale
    # admin entry exists, and both have happened here.
    if ! git -C "$REPO" worktree add -q "$where" -b "$branch" "$BASE" >>"$LOG" 2>&1; then
      git -C "$REPO" worktree add -q "$where" "$branch" >>"$LOG" 2>&1 || return 1
      git -C "$where" checkout -q -B "$branch" "$BASE" >>"$LOG" 2>&1 || return 1
    fi
  fi

  git -C "$where" clean -qxdf \
    -e node_modules -e .gradle -e build -e '**/build' >>"$LOG" 2>&1

  # `npm ci` only when the lockfile actually moved. It is 403 seconds.
  stamp="$where/.intake-npm-lock"
  if [ -f "$where/package-lock.json" ]; then
    if cmp -s "$where/package-lock.json" "$stamp" 2>/dev/null; then
      say "  $name: lockfile unchanged, keeping node_modules"
    elif [ -n "${INTAKE_SKIP_NPM:-}" ]; then
      say "  $name: lockfile moved, npm ci skipped by INTAKE_SKIP_NPM"
      cp "$where/package-lock.json" "$stamp" 2>/dev/null || true
    else
      say "  $name: package-lock.json moved, running npm ci (this is the slow one)"
      ( cd "$where" && npm ci ) >>"$LOG" 2>&1
      cp "$where/package-lock.json" "$stamp" 2>/dev/null || true
    fi
  fi
  return 0
}

commits_on() { git -C "$1" rev-list --count "$BASE..HEAD" 2>/dev/null || echo 0; }

pushed_up() {
  local where="$1" branch="$2" here there
  here="$(git -C "$where" rev-parse HEAD 2>/dev/null)"
  there="$(git -C "$where" ls-remote origin "refs/heads/$branch" 2>/dev/null | cut -f1)"
  [ -n "$here" ] && [ "$here" = "$there" ]
}

# ---------------------------------------------------------------------
# GitHub
# ---------------------------------------------------------------------

# A pull request for a branch, whatever state it is in, as "<n> <STATE>".
#
# `--state open` cannot see a MERGED pull request, so a builder that did
# exactly what it was told — merge on green — was judged unfinished every
# single time, and the request was rebuilt from a base that already
# contained the feature. Its TDD red could not reproduce, and each pass
# opened a duplicate.
pr_for() {
  ghx pr list --head "$1" --state all --limit 1 \
    --json number,state --jq '.[] | "\(.number) \(.state)"' 2>/dev/null
}

# Whether EVERY check on a pull request succeeded.
#
# `gh pr merge` inspects no checks at all, and the instruction that
# "green means every check" was enforced by nothing — `tally` is not even
# a required context, and `concurrency: cancel-in-progress` makes a
# cancelled one easy to produce. A pull request with NO checks reads as
# benign and is exactly what a hundred commits sat in, so zero checks is
# not green either.
all_checks_green() {
  local pr="$1" out status lines bad
  out="$(gh pr checks "$pr" 2>>"$LOG")"
  status=$?
  lines="$(printf '%s' "$out" | sed '/^$/d' | wc -l | tr -d ' ')"
  if [ "${lines:-0}" -eq 0 ]; then
    say "  #$pr has no checks — that is not green, it is unverified"
    return 1
  fi
  bad="$(printf '%s\n' "$out" | awk -F'\t' 'NF>1 && $2!="pass" && $2!="skipping" {print $1"="$2}')"
  if [ -n "$bad" ]; then
    say "  #$pr is not green: $(printf '%s' "$bad" | tr '\n' ' ')"
    return 1
  fi
  if [ "$status" -ne 0 ]; then
    say "  #$pr: gh pr checks exited $status, so something is pending"
    return 1
  fi
  say "  #$pr: every one of $lines checks reported pass"
  return 0
}

# Block on CI. A real blocking call, not a poll.
wait_for_ci() {
  local branch="$1" ids id
  ids="$(ghx run list --branch "$branch" --limit 20 \
          --json databaseId --jq '.[].databaseId' 2>/dev/null)"
  while IFS= read -r id; do
    [ -n "$id" ] || continue
    say "  gh run watch $id"
    gh run watch "$id" --exit-status >>"$LOG" 2>&1 || say "  run $id finished red"
  done <<EOF
$ids
EOF
}

# ---------------------------------------------------------------------
# Filing a finished request
# ---------------------------------------------------------------------

# In the REAL repo, which is the whole point.
#
# The builder used to move its own copy inside its worktree. The real
# repo is on another branch and never pulls, so the request stayed
# buildable forever, retook the only builder slot, and was rebuilt from a
# base that already had the feature in it. `edhrec-sort-backwards.md` and
# `kayla-account-owns-her-cards.md` were both built hours ago and both
# still read as buildable.
file_as_done() {
  local file="$1" note="$2"
  mkdir -p "$REQUESTS/done"
  [ -f "$REQUESTS/$file" ] && mv "$REQUESTS/$file" "$REQUESTS/done/$file"
  [ -f "$REQUESTS/done/$file" ] || return 1
  if [ -n "$note" ]; then
    printf '%s\n\n' "$note" | cat - "$REQUESTS/done/$file" > "$REQUESTS/done/$file.tmp" \
      && mv "$REQUESTS/done/$file.tmp" "$REQUESTS/done/$file"
  fi
  git -C "$REPO" add -A -- requests >>"$LOG" 2>&1
  git -C "$REPO" \
    -c user.name=intake -c user.email=intake@localhost \
    commit -q -m "requests: $file is done" -- requests >>"$LOG" 2>&1
  say "  filed requests/done/$file in the real repo and committed it"
}

# ---------------------------------------------------------------------
# The builder, and what happens after it
# ---------------------------------------------------------------------

builder_prompt() {
  local name="$1" branch="$2" handed="$3"
  cat <<PROMPT
Invoke the mtg skill first — it carries this project's architecture and hard
requirements and you will break things without it. Then read CLAUDE.md, which
is the law where the two disagree.

Your request is at $handed. It is deliberately not under requests/ in this
worktree: the request file must never be committed onto your branch.

You are the request-builder agent. Read .claude/agents/request-builder.md and
follow it exactly, including the TDD discipline, the parity rule, running each
suite in the FOREGROUND, and reading the BUILD line rather than the exit code.
You are already on branch $branch in your own worktree.

Stop when: the code is committed, the branch is pushed, a pull request is
open, and every suite your diff can reach went green off its BUILD line. Then
write what you did and what you are unsure about, and end your turn.

Do not wait for CI. Do not merge anything. Do not move the request file. The
dispatcher blocks on CI and decides, because the apps job takes thirteen to
seventeen minutes and three of four builders ended their turn rather than sit
through it — and in headless mode there is no next turn to come back on.

Commit as soon as a part passes and push as you go, so a pull request that
stops halfway is visibly half rather than gone.
PROMPT
}

# Kill a builder and make sure the thing doing the work is actually dead.
#
# The old watchdog used `$$` inside `( )`, which bash 3.2 does not rebind
# — so it tested the DISPATCHER (always alive, it was in `wait`) and then
# `pkill -P $$` TERMed every sibling builder including healthy ones,
# while the real `claude -p` was a grandchild and survived as an orphan
# still writing to the branch after the log said KILLED.
# Depth-first, because `claude -p` is a grandchild of the shell we hold the
# pid of and killing only the parent leaves it running — and anything it still
# has open holds the log pipe with it, so the dispatcher then waits out the
# orphan it just logged as KILLED.
kill_tree() {
  local pid="$1" sig="$2" kid
  for kid in $(pgrep -P "$pid" 2>/dev/null); do kill_tree "$kid" "$sig"; done
  kill "-$sig" "$pid" 2>/dev/null
  return 0
}

stop_builder() {
  local pid="$1" branch="$2" left p
  kill_tree "$pid" TERM
  sleep 5
  kill_tree "$pid" KILL
  left="$(pgrep -f "claude .*$branch" 2>/dev/null | tr '\n' ' ')"
  if [ -n "$left" ]; then
    say "  a claude for $branch outlived its parent: $left — killing it too"
    for p in $left; do kill_tree "$p" KILL; done
    sleep 2
  fi
  left="$(pgrep -f "claude .*$branch" 2>/dev/null | tr '\n' ' ')"
  [ -n "$left" ] && say "  STILL ALIVE after SIGKILL: $left"
  return 0
}

# Watch one builder: the cap, the first commit, and the request itself.
#
# MAX_MINUTES is four hours, and the run that provoked all of this went
# 108 minutes with zero commits and thirty-two dirty files — so
# wall-clock alone is not a signal. The first-commit threshold applies to
# the FIRST commit only; later ones can legitimately be far apart.
#
# It also watches the request file, because `requests/README.md` and the
# builder's own step 1 both promise that deleting it stops the build, and
# both were false: the builder reads the dispatcher's private copy.
watch_builder() {
  local pid="$1" name="$2" where="$3" branch="$4" file="$5"
  local waited=0 mins
  while kill -0 "$pid" 2>/dev/null; do
    sleep "$WATCH_SECONDS"
    waited=$((waited + WATCH_SECONDS))
    mins=$((waited / 60))

    if [ ! -f "$REQUESTS/$file" ] && [ ! -f "$REQUESTS/done/$file" ]; then
      say "$name was withdrawn while building — stopping its builder"
      tell "$name was withdrawn; its builder has been stopped"
      stop_builder "$pid" "$branch"
      return 0
    fi

    if [ "$mins" -ge "$FIRST_COMMIT_MINUTES" ] && [ "$(commits_on "$where")" -eq 0 ]; then
      say "$name KILLED — ${mins}m and not one commit"
      salvage "$where" "$name"
      stop_builder "$pid" "$branch"
      return 0
    fi

    if [ "$mins" -ge "$MAX_MINUTES" ]; then
      say "$name KILLED after ${mins}m — past the cap"
      salvage "$where" "$name"
      stop_builder "$pid" "$branch"
      return 0
    fi
  done
  return 0
}

# INTAKE_DRY_RUN=1 prints what would be launched instead of launching it.
run() {
  if [ -n "${INTAKE_DRY_RUN:-}" ]; then say "DRY RUN would launch: $*"; return 0; fi
  "$@"
}

# One request, from launch to merged-or-explained. Runs in a subshell.
build_one() {
  local file="$1" name="$2" branch="$3" where="$4" claim="$5" handed="$6"
  local code pipe="" logger="" cpid watchdog

  pipe="$STATE/$name.pipe"
  rm -f "$pipe"
  mkfifo "$pipe" 2>/dev/null || pipe=""

  if [ -n "$pipe" ]; then
    # One readable line per event instead of a 0-byte file until the end.
    # `claude -p` with the default text format buffers everything, so a
    # builder an hour in looked exactly like a hung one.
    (
      while IFS= read -r line; do
        printf '%s\n' "$line" >> "$STATE/$name.jsonl"
        printf '%s %s\n' "$(date '+%H:%M:%S')" "$line" \
          | /usr/bin/sed -E 's/\{"type":"([a-z_]+)".*/[\1]/' \
          | cut -c1-200 >> "$STATE/$name.log"
      done < "$pipe"
    ) &
    logger=$!
  fi

  # Backgrounded DIRECTLY, so `$!` is the process doing the work rather
  # than the tail of a pipeline. The watchdog has to be able to kill the
  # thing that is writing to the branch.
  (
    cd "$where" || exit 1
    run claude -p "$(builder_prompt "$name" "$branch" "$handed")" \
      --model opus \
      --permission-mode bypassPermissions \
      --output-format stream-json --verbose --include-partial-messages
  ) > "${pipe:-$STATE/$name.jsonl}" 2>>"$LOG" &
  cpid=$!

  watch_builder "$cpid" "$name" "$where" "$branch" "$file" >>"$LOG" 2>&1 &
  watchdog=$!

  wait "$cpid"; code=$?
  kill_tree "$watchdog" TERM
  [ -n "$logger" ] && wait "$logger" 2>/dev/null
  [ -n "$pipe" ] && rm -f "$pipe"

  # An exit code says nothing about whether it finished. `claude -p` ends
  # when the model stops producing text, and one builder started the
  # suites, wrote "Red runs for core and web are in progress", and ended
  # its turn — exit 0, nothing committed, no pull request. The dispatcher
  # read 0 as success and deleted the worktree with the work in it.
  local prline pr="" prstate="" open=false merged=false commits pushed=false
  prline="$(pr_for "$branch")"
  if [ -n "$prline" ]; then
    pr="${prline%% *}"
    prstate="${prline##* }"
    [ "$prstate" = "OPEN" ] && open=true
    [ "$prstate" = "MERGED" ] && merged=true
  fi
  commits="$(commits_on "$where")"
  pushed_up "$where" "$branch" && pushed=true

  ask done-verdict "$code" "$open" "$merged" "$commits" "$pushed"
  local verdict="$ASK_OUT"

  case "$verdict" in
    keep:*)
      say "$name NOT FINISHED — ${verdict#keep:}"
      salvage "$where" "$name"
      say "  branch $branch, slot $(basename "$where"), log $STATE/$name.log"
      say "  it needs re-dispatching; nothing was thrown away"
      tell "$name did not finish: ${verdict#keep:}"
      ;;
    merged)
      say "$name done: #$pr was already merged"
      file_as_done "$file" "Merged as #$pr."
      ;;
    open)
      say "$name built: #$pr is open with $commits commit(s) — waiting on CI"
      wait_for_ci "$branch"
      local green=false mode action
      all_checks_green "$pr" && green=true
      ask merge "$file"; mode="$ASK_OUT"
      ask after-ci "$green" "$mode"; action="$ASK_OUT"
      case "$action" in
        merge)
          # No `--delete-branch`: it makes gh check out the base branch in
          # the builder's own worktree to delete the local ref, and the
          # stale tree that leaves made a fully merged request read as
          # unfinished. No `--admin` either — it bypasses every required
          # check, and this token has the power to do it.
          if ghx pr merge "$pr" --squash >/dev/null; then
            say "$name done: MERGED #$pr"
            file_as_done "$file" "Merged as #$pr."
            tell "$name merged as #$pr"
          else
            say "$name NOT FINISHED — #$pr is green but would not merge"
            tell "$name is green but #$pr would not merge"
          fi
          ;;
        notify)
          say "$name green and left for you (merge: $mode), #$pr"
          tell "$name is green: #$pr is waiting for you ($mode)"
          ;;
        fix)
          say "$name has a red #$pr — it needs a fix-only builder"
          tell "$name: CI is red on #$pr"
          printf '%s\n' "$pr" > "$STATE/$name.fixme"
          ;;
      esac
      ;;
    *)
      say "$name could not be judged (verdict was '$verdict'); keeping its slot"
      ;;
  esac

  rm -rf "$claim"
  return 0
}

# ---------------------------------------------------------------------
# What triage leaves behind, and what to do when it leaves nothing
# ---------------------------------------------------------------------

triage_pass() {
  TRIAGE_WROTE=0
  list_untriaged
  [ -n "$UNTRIAGED" ] || return 0

  local todo="" f n tri file still wrote=0
  while IFS= read -r f; do
    [ -n "$f" ] || continue
    n="$(cat "$STATE/${f%.md}.triage-tries" 2>/dev/null || echo 0)"
    if [ "${n:-0}" -ge "$TRIAGE_GIVE_UP" ]; then
      say "giving up on triaging $f after $n attempts — it needs Matt"
      tell "triage cannot plan $f after $n tries"
      continue
    fi
    todo="$todo$f
"
  done <<EOF
$UNTRIAGED
EOF
  [ -n "$(printf '%s' "$todo")" ] || return 0

  say "triage over: $(printf '%s' "$todo" | tr '\n' ' ')"

  # In its own throwaway worktree, not Matt's live checkout. Triage runs
  # with acceptEdits and auto-approves writes to any path, so it used to
  # interleave with whatever he had in progress — and its output, the
  # plans for every pending request, was never committed, so a
  # `git checkout`, `git stash` or `git clean` silently discarded them
  # and they reverted to untriaged with no record.
  tri="$WT_ROOT/triage"
  if [ ! -e "$tri/.git" ]; then
    git -C "$REPO" worktree prune >>"$LOG" 2>&1
    git -C "$REPO" worktree add -q --detach "$tri" "$BASE" >>"$LOG" 2>&1 \
      || { say "triage could not get a worktree, skipping this pass"; return 0; }
  fi
  mkdir -p "$tri/requests"
  while IFS= read -r file; do
    [ -n "$file" ] || continue
    cp "$REQUESTS/$file" "$tri/requests/$file" 2>/dev/null
  done <<EOF
$todo
EOF

  # No wrapper hop. The old prompt said "invoke the mtg skill first, then
  # run the request-triage agent", which loaded the skill once in the
  # wrapper and again in the spawned agent. And the model is passed
  # explicitly, because a subagent definition's frontmatter is not a
  # top-level session's config.
  (
    cd "$tri" || exit 1
    run claude -p "You are the request-triage agent. Follow .claude/agents/request-triage.md \
exactly. The untriaged files under requests/ are: $(printf '%s' "$todo" | tr '\n' ' '). \
Rewrite each one in place with the frontmatter and the Plan/Tests/Done-when \
sections. Do not write production code, do not open a branch, and do not touch \
requests/done/." \
      --model opus \
      --permission-mode acceptEdits
  ) >> "$LOG" 2>&1

  # Copy the plans back and COMMIT them, so nothing a five-minute opus
  # run produced depends on an uncommitted file in a throwaway tree.
  while IFS= read -r file; do
    [ -n "$file" ] || continue
    if [ -f "$tri/requests/$file" ] && ! cmp -s "$tri/requests/$file" "$REQUESTS/$file"; then
      cp "$tri/requests/$file" "$REQUESTS/$file"
      wrote=$((wrote + 1))
    fi
  done <<EOF
$todo
EOF
  if [ "$wrote" -gt 0 ]; then
    git -C "$REPO" add -A -- requests >>"$LOG" 2>&1
    git -C "$REPO" \
      -c user.name=intake -c user.email=intake@localhost \
      commit -q -m "requests: triage wrote $wrote plan(s)" -- requests >>"$LOG" 2>&1
    say "triage wrote $wrote plan(s), committed in the real repo"
    TRIAGE_WROTE=$wrote
  fi

  # Re-read, and say plainly when a pass produced nothing. Without this,
  # a file triage cannot plan gets a full opus run on every dispatch
  # forever — which is what `release-notes-in-admin-settings.md` was
  # doing.
  list_untriaged
  still="$UNTRIAGED"
  while IFS= read -r file; do
    [ -n "$file" ] || continue
    case "$still" in
      *"$file"*)
        n="$(cat "$STATE/${file%.md}.triage-tries" 2>/dev/null || echo 0)"
        n=$(( ${n:-0} + 1 ))
        printf '%s\n' "$n" > "$STATE/${file%.md}.triage-tries"
        say "TRIAGE PRODUCED NO PLAN FOR: $file (attempt $n of $TRIAGE_GIVE_UP)"
        ;;
    esac
  done <<EOF
$todo
EOF
  return 0
}

# ---------------------------------------------------------------------
# One wave of builders
# ---------------------------------------------------------------------

dispatch_wave() {
  STARTED_THIS_WAVE=0
  local file name branch claim slot n handed key

  list_buildable
  [ -n "$BUILDABLE" ] || return 0

  # `while read` over a here-document, not `for file in $(...)`. Word
  # splitting turned `fix the card page.md` into four bogus requests, one
  # of which got a worktree. The counter has to stay in this shell, which
  # a pipe would not allow.
  while IFS= read -r file; do
    [ -n "$file" ] || continue
    if [ "$STARTED_THIS_WAVE" -ge "$MAX_BUILDERS" ]; then
      say "at $MAX_BUILDERS builder(s); the rest wait for the next wave"
      break
    fi
    name="${file%.md}"
    ask branch "$file"; branch="$ASK_OUT"
    key="$(printf '%s' "$branch" | tr '/' '_')"

    # One attempt per request per dispatch. The loop below re-reads the
    # folder after every wave, and without this a request that ends NOT
    # FINISHED would be retried forever inside one run.
    if [ -e "$TRIED/$key" ]; then
      continue
    fi

    # Keyed on the BRANCH, not the filename. Two filenames used to
    # collapse onto one branch with neither claim blocking the other, and
    # two builders then shared a ref.
    claim="$STATE/$key.building"
    if [ -d "$claim" ]; then
      if claim_alive "$claim"; then
        say "$name already has a builder, skipping"
        continue
      fi
      say "$name had a builder that died, retrying"
      rm -rf "$claim"
    fi
    mkdir -p "$claim" || continue
    # The pid goes in before the claim can be read as abandoned. There
    # was no pid for the whole worktree-setup window, so a concurrent
    # dispatcher read a LIVE builder as dead and removed the tree it was
    # writing into.
    stamp_claim "$claim" "$$"
    : > "$TRIED/$key"

    # Nothing branches off a stale base. Nothing fetched before, so
    # builder B branched from a local origin/main that never moved after
    # A's merge, B's checks passed against a tree with none of A's work,
    # both merged clean, and release.yml shipped a signed APK from a main
    # that no suite had ever run against.
    git -C "$REPO" fetch --quiet origin "${BASE#origin/}" >>"$LOG" 2>&1 \
      || say "  could not fetch ${BASE} — building against what is already here"

    if ! n="$(take_slot)"; then
      say "$name: no free slot, waiting for the next wave"
      rm -rf "$claim"
      break
    fi
    slot="$WT_ROOT/slot$n"
    say "building $name on $branch in slot$n ($slot)"

    if ! prepare_slot "$slot" "$branch" "$name"; then
      say "$name could not get slot$n, skipping"
      rm -rf "$claim" "$STATE/slot$n.claim"
      continue
    fi

    # A worktree with no skill, no agent definition, no CLAUDE.md and no
    # guard produces a builder that works blind. That happened twice:
    # `.claude/` was only on the branch that introduced it, the builders
    # were told to invoke the mtg skill, found nothing, and built without
    # the parity rule. Nothing failed, which is what made it bad.
    if ! equipped "$slot"; then
      ask equipped "$slot" || true
      say "$name NOT STARTED: $slot is missing its instructions"
      say "  they have to be on $BASE first"
      rm -rf "$claim" "$STATE/slot$n.claim"
      continue
    fi

    # Handed over from outside requests/. The builder used to be given
    # `requests/<name>.md` inside its worktree and committed it onto the
    # branch it merged, so main permanently carried finished requests and
    # every new worktree was seeded with them — which is the fuel for the
    # recursive hook. `requests/kayla-account-owns-her-cards.md` is on
    # origin/main right now while requests/done/ holds one file.
    mkdir -p "$STATE/handed"
    handed="$STATE/handed/$file"
    cp "$REQUESTS/$file" "$handed"

    (
      # Whatever happens in here, the claim and the slot are released. A
      # stale claim used to wedge the request permanently.
      trap 'rm -rf "$claim" "$STATE/slot'"$n"'.claim"' EXIT
      build_one "$file" "$name" "$branch" "$slot" "$claim" "$handed"
    ) </dev/null >>"$LOG" 2>&1 &
    stamp_claim "$claim" "$!"
    STARTED_THIS_WAVE=$((STARTED_THIS_WAVE + 1))
  done <<EOF
$BUILDABLE
EOF

  # The dispatcher owns the pull request now, so it waits for this wave
  # before reading the folder again.
  wait
  return 0
}

# ---------------------------------------------------------------------

main() {
  REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
  REQUESTS="$REPO/requests"
  # Deliberately NOT inside requests/. launchd's WatchPaths fires on any
  # change under the folder it watches, so a log written in there makes
  # the dispatcher trigger itself, forever.
  STATE="$REPO/.intake"
  LOG="$STATE/intake.log"
  LOCK="$STATE/dispatch.lock"
  ASK_OUT=""
  PENDING=""; UNTRIAGED=""; BUILDABLE=""; HELD=""
  STARTED_THIS_WAVE=0; TRIAGE_WROTE=0

  # The off switch. `launchctl unload` is not enough on its own: the
  # PostToolUse hook is committed, so an edit to any requests/*.md from
  # any Claude session in this repo still fired it. `uninstall.sh`
  # touches this file.
  [ -f "$STATE/disabled" ] && exit 0

  # Never from a linked worktree.
  #
  # The hook is committed, so it is checked out in every builder
  # worktree. The builder was told to edit its own request file, which
  # fired the hook, which started a SECOND dispatcher rooted at the
  # worktree — its own lock, its own `.intake/`, its own MAX_BUILDERS, so
  # the global cap was gone — and that dispatcher ran a triage agent
  # inside the live builder's tree, rewriting files it was about to
  # commit, then launched nested builders. Recursively. It is masked
  # today only because scripts/intake.mjs is not yet on main, so the
  # hook's `node` call fails. Merging that arms it.
  local gd gc
  gd="$(cd "$REPO" && git rev-parse --git-dir 2>/dev/null)"
  gc="$(cd "$REPO" && git rev-parse --git-common-dir 2>/dev/null)"
  if [ "$gd" != "$gc" ]; then
    mkdir -p "$STATE" 2>/dev/null
    printf '%s  refusing to dispatch from a linked worktree (%s)\n' \
      "$(date '+%Y-%m-%d %H:%M:%S')" "$REPO" >> "$LOG" 2>/dev/null
    exit 0
  fi

  DEBOUNCE="${INTAKE_DEBOUNCE:-15}"
  SETTLE="${INTAKE_SETTLE:-5}"
  # A builder may not run unwatched forever. Four hours is a cap, not a
  # target.
  MAX_MINUTES="${INTAKE_MAX_MINUTES:-240}"
  # And wall-clock alone is not a signal: the run that provoked this went
  # 108 minutes with zero commits. This threshold is the FIRST commit
  # only.
  FIRST_COMMIT_MINUTES="${INTAKE_FIRST_COMMIT_MINUTES:-40}"
  WATCH_SECONDS="${INTAKE_WATCH_SECONDS:-60}"
  # Two, now that the Gradle mutex in scripts/guard.mjs exists. True
  # Gradle occupancy in the one complete transcript was about 32% of
  # wall-clock; the other 68% was reading, editing, npm ci and polling,
  # none of which contends.
  MAX_BUILDERS="${INTAKE_MAX_BUILDERS:-2}"
  BASE="${INTAKE_BASE:-origin/main}"
  # Outside the repo, so `git clean -fdx` in the primary tree cannot
  # destroy a live builder and leave the stale `.git/worktrees/` entries
  # that wedge the next attempt at the same branch.
  WT_ROOT="${INTAKE_WORKTREE_ROOT:-$HOME/.cache/mtg-intake/wt}"
  TRIAGE_GIVE_UP="${INTAKE_TRIAGE_GIVE_UP:-3}"
  TRIED="$STATE/tried.$$"

  mkdir -p "$STATE" "$WT_ROOT" "$TRIED"
  touch "$LOG"

  take_lock || exit 0
  trap 'rm -rf "$TRIED"; drop_lock' EXIT
  trap 'say "stopped by a signal"; exit 1' TERM INT

  # Stale admin entries are what make a later `worktree add` fail for a
  # branch whose tree somebody deleted by hand.
  git -C "$REPO" worktree prune >>"$LOG" 2>&1

  list_pending
  [ -n "$PENDING" ] || { say "nothing pending"; exit 0; }

  # Let a burst of files land before anything looks, so triage can see
  # that three of them are the same request and fold them together.
  say "waiting ${DEBOUNCE}s for the folder to settle"
  sleep "$DEBOUNCE"
  local before
  while :; do
    before="$PENDING"
    sleep "$SETTLE"
    list_pending
    [ "$PENDING" = "$before" ] && break
    say "still arriving, waiting again"
  done

  cd "$REPO" || exit 1
  [ -n "$PENDING" ] || { say "every request withdrawn while waiting"; exit 0; }

  local wave=0
  while :; do
    wave=$((wave + 1))

    # Dispatch what already has a plan BEFORE triage. Triage measured 5.3
    # minutes and used to sit unconditionally ahead of the builder loop,
    # so a request that was already ready waited it out for nothing.
    dispatch_wave

    # Then triage whatever has no plan.
    triage_pass

    # And come back for anything triage just made buildable, plus
    # anything that landed while we were busy. `buildable` used to be
    # read once; the dispatcher then just `wait`ed, holding the lock for
    # up to four hours, while launchd discarded every WatchPaths event
    # fired during the run — `.intake/intake.log` shows an hour of them,
    # and the header comment claiming it re-read the folder was false.
    list_buildable
    if [ -n "$BUILDABLE" ] && { [ "$STARTED_THIS_WAVE" -gt 0 ] || [ "$TRIAGE_WROTE" -gt 0 ]; }; then
      say "re-reading the folder after wave $wave"
      continue
    fi
    break
  done

  list_held
  if [ -n "$HELD" ]; then
    say "held back: $(printf '%s' "$HELD" | tr '\n' ' ')"
    local h why
    while IFS= read -r h; do
      [ -n "$h" ] || continue
      ask state "$h"; why="$(printf '%s' "$ASK_OUT" | cut -f2)"
      tell "$h needs you: $why"
    done <<EOF
$HELD
EOF
  fi

  # Released before the final wait, so a request dropped while CI is
  # being watched gets its own dispatcher instead of waiting hours.
  drop_lock
  wait
  say "dispatch finished"
}

main "$@"
