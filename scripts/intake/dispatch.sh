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
    # Loudly to Matt too, not only to a log he does not read. Realistic:
    # the plist carries a substituted PATH rather than a login shell's, so
    # "no node" is one bad install away and the whole queue stops dead with
    # zero notifications.
    tell "intake cannot run: intake.mjs $1 failed ($status). The queue is stopped."
    exit "$status"
  fi
  return 0
}

tidy() { printf '%s\n' "$1" | sed '/^$/d' | sort; }

list_pending()   { ask pending;   PENDING="$(tidy "$ASK_OUT")"; }
list_untriaged() { ask untriaged; UNTRIAGED="$(tidy "$ASK_OUT")"; }
# Minus anything on hold. A request that failed `BUILD_GIVE_UP` times, or
# whose pull request is red after `FIX_GIVE_UP` fixes, keeps its file and
# its branch but stops retaking the only builder slot. Clearing the hold
# file is how you put it back.
list_buildable() {
  ask buildable
  local f keep=""
  while IFS= read -r f; do
    [ -n "$f" ] || continue
    if [ -f "$STATE/${f%.md}.held" ]; then
      say "  $f is held: $(cat "$STATE/${f%.md}.held" 2>/dev/null)"
      continue
    fi
    # A request whose pull request is open and red is NOT buildable. It
    # used to be, so every later dispatch launched a full
    # re-implementation builder on the open branch before it re-judged CI
    # — measured at three full builders and five commits of repeated work
    # for one red pull request, two of those builders told to follow TDD
    # on a tree that already had the feature. `FIX_GIVE_UP` bounded only
    # the fix-only builders, and `bump_attempt` was never reached here.
    if [ -f "$STATE/${f%.md}.fix-pending" ]; then
      say "  $f is waiting on a fix for #$(cat "$STATE/${f%.md}.fixme" 2>/dev/null)"
      continue
    fi
    keep="$keep$f
"
  done <<EOF
$(tidy "$ASK_OUT")
EOF
  BUILDABLE="$(tidy "$keep")"
}

# Requests whose open pull request is red, which need a fix and not a build.
list_fix_pending() {
  local f out=""
  for f in "$STATE"/*.fix-pending; do
    [ -f "$f" ] || continue
    out="$out$(basename "$f" .fix-pending).md
"
  done
  FIX_PENDING="$(tidy "$out")"
}
list_held()      { ask held;      HELD="$(tidy "$ASK_OUT")"; }

# NOT through `ask`. `intake.mjs equipped` exits 1 BY DESIGN when files
# are missing, and `ask` calls `exit "$status"` on nonzero — which `|| true`
# cannot catch, because it is an exit and not a failure. So the one guard
# written to stop a builder working blind took the whole dispatcher down
# instead, dropping every request behind it in the wave and leaving its
# claim and slot claim on disk. Driven for real with CLAUDE.md removed:
# DISPATCHER EXIT=1 and the three lines after it never ran.
#
# This sets WHY_UNEQUIPPED and returns 1. It never exits.
equipped() {
  WHY_UNEQUIPPED="$( cd "$REPO" && node scripts/intake.mjs equipped "$1" 2>&1 >/dev/null )"
  local status=$?
  [ "$status" -eq 0 ] && return 0
  [ -n "$WHY_UNEQUIPPED" ] || WHY_UNEQUIPPED="intake.mjs equipped exited $status"
  return 1
}

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

# Whether a pid is a process that can still do anything.
#
# `kill -0` is not that question. A killed child whose parent has not
# reaped it is a ZOMBIE, and `kill -0` on a zombie succeeds — so after a
# SIGKILL the dispatcher concluded "SURVIVED SIGKILL" and left the claim
# wedged, about a process that was already dead. Its state is the honest
# question: `Z` is dead, and no row at all is dead.
still_running() {
  local st
  [ -n "${1:-}" ] || return 1
  st="$(ps -o state= -p "$1" 2>/dev/null | tr -d ' ')"
  [ -n "$st" ] || return 1
  case "$st" in Z*) return 1 ;; esac
  return 0
}

# `stat -f %m` is BSD and `stat -c %Y` is GNU, and neither fails usefully
# on the other: GNU's `-f` asks about the FILESYSTEM and exits 0 having
# printed something that is not a timestamp. So the VALUE is checked.
dir_mtime() {
  local when
  when="$(stat -c %Y "$1" 2>/dev/null)"
  case "$when" in ''|*[!0-9]*) when="$(stat -f %m "$1" 2>/dev/null)" ;; esac
  case "$when" in ''|*[!0-9]*) when="" ;; esac
  printf '%s' "$when"
}

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
  local dir="$1" kind="${2:-claim}" pid started now age cmd was mtime
  [ -d "$dir" ] || return 1
  pid="$(lock_pid "$dir")"

  if [ -z "$pid" ]; then
    # No pid yet means the holder is between `mkdir` and `stamp_claim`,
    # which is the double-fire window and must read as LIVE. But it used
    # to read as live FOREVER: a `kill -9` inside that window wedged
    # intake permanently and silently, logging
    # `already running as , leaving it to that one` with an empty pid on
    # every run thereafter. The window is milliseconds, so it is bounded
    # by the directory's own age.
    mtime="$(dir_mtime "$dir")"
    now="$(date +%s)"
    if [ -n "$mtime" ] && [ "$(( now - mtime ))" -ge "$PIDLESS_SECONDS" ]; then
      say "  $(basename "$dir") has had no pid for $(( now - mtime ))s — nobody is coming"
      return 1
    fi
    say "  $(basename "$dir") has no pid yet — live, which is what the double-fire needs"
    return 0
  fi

  still_running "$pid" || { say "  $(basename "$dir") pid $pid is gone"; return 1; }

  # Is it the process it says it is? The reused-pid check used to compare
  # against `*dispatch.sh*|*claude*`, which any `claude` process satisfies
  # — Matt's own session included — while the recorded command sat in
  # `$dir/command` and was never read.
  cmd="$(ps -o command= -p "$pid" 2>/dev/null)"
  was="$(cat "$dir/command" 2>/dev/null)"
  local itself=false
  if [ -n "$was" ]; then
    [ "$cmd" = "$was" ] && itself=true
    if [ "$itself" != true ]; then
      say "  $(basename "$dir") pid $pid runs '$cmd', not the '$was' it recorded"
      return 1
    fi
  else
    case "$cmd" in
      *dispatch.sh*|*claude*) itself=true ;;
      *) say "  $(basename "$dir") pid $pid is '$cmd', not ours — the kernel reused it"
         return 1 ;;
    esac
  fi

  # The age cap, which is NOT evidence of death.
  #
  # R5 stopped the lock being stolen from a live dispatcher. It left the
  # slot claim and the `.building` claim still age-capped — and with the
  # global lock no longer serialising dispatchers, a second one took both
  # and ran `prepare_slot` (salvage, `checkout -B`, `git clean -qxdf`) in
  # the worktree a live builder was writing to, then launched a second
  # builder on the branch. Reachable with no builder misbehaving at all.
  #
  # So the cap is a reason to STOP the holder, never a reason to assume it
  # already stopped. The lock is exempt entirely: a dispatcher holding it
  # for a long time is doing a long job, and there is nothing to kill.
  if [ "$kind" != "lock" ]; then
    started="$(cat "$dir/started" 2>/dev/null)"
    case "$started" in ''|*[!0-9]*) started="" ;; esac
    if [ -n "$started" ]; then
      now="$(date +%s)"
      age=$(( (now - started) / 60 ))
      if [ "$age" -ge "$MAX_MINUTES" ]; then
        say "  $(basename "$dir") has been held ${age}m, past the ${MAX_MINUTES}m cap"

        # Whose process is it? A claim is stamped with the DISPATCHER's pid
        # during worktree setup, before the builder's pid is known — so a
        # dispatcher that is simply doing a long job (it owns the CI wait
        # now) holds old-looking claims. Killing on the cap in that case
        # would have one dispatcher kill another, taking every healthy
        # builder it was running with it. A dispatcher bounds its own
        # builders through `watch_builder`; nothing else needs to.
        case "$cmd" in
          *dispatch.sh*)
            say "  it is held by dispatcher $pid, which bounds its own builders — leaving it"
            return 0 ;;
        esac

        # A builder, though, is exactly what the cap is for.
        say "  its pid $pid is a live builder past the cap — stopping it first"
        kill_tree "$pid" TERM
        sleep 5
        kill_tree "$pid" KILL
        sleep 1
        if still_running "$pid"; then
          say "  pid $pid SURVIVED SIGKILL — leaving the claim alone rather than racing it"
          tell "$(basename "$dir") is past the cap and its process will not die"
          return 0
        fi
        say "  pid $pid is gone; the claim is free"
        return 1
      fi
    fi
  fi

  [ "$itself" = true ] && { say "  $(basename "$dir") pid $pid is alive and is itself"; return 0; }
  return 1
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
  if claim_alive "$LOCK" lock; then
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
  local branch dirty ahead sha tag

  branch="$(git -C "$where" rev-parse --abbrev-ref HEAD 2>/dev/null)"
  if [ -z "$branch" ] || [ "$branch" = "HEAD" ]; then
    # A detached HEAD used to return SUCCESS without saving anything, and
    # `prepare_slot` ignored the return value and destroyed the tree two
    # lines later. Anything uncommitted here has no branch to push to, so
    # it gets a tag, which is a ref like any other.
    dirty="$(git -C "$where" status --porcelain 2>/dev/null | wc -l | tr -d ' ')"
    if [ "${dirty:-0}" -eq 0 ]; then
      say "  $(basename "$where") is detached with nothing in it"
      return 0
    fi
    git -C "$where" add -A >>"$LOG" 2>&1
    git -C "$where" -c user.name=intake -c user.email=intake@localhost \
      commit -q -m "wip: $name (detached, builder did not finish)" >>"$LOG" 2>&1
    tag="intake-salvage/$(date +%s)"
    if git -C "$where" tag "$tag" >>"$LOG" 2>&1 \
       && git -C "$where" push -q origin "refs/tags/$tag" >>"$LOG" 2>&1; then
      say "  salvaged $name from a detached HEAD as tag $tag"
      return 0
    fi
    say "  COULD NOT SAVE $name from a detached HEAD — ${dirty} file(s) only in $where"
    tell "$name: ${dirty} file(s) are in $where and could not be pushed anywhere"
    return 1
  fi

  dirty="$(git -C "$where" status --porcelain 2>/dev/null | wc -l | tr -d ' ')"
  if [ "${dirty:-0}" -gt 0 ]; then
    git -C "$where" add -A >>"$LOG" 2>&1
    git -C "$where" \
      -c user.name=intake -c user.email=intake@localhost \
      commit -q -m "wip: $name (builder did not finish)" >>"$LOG" 2>&1
  fi

  # `rev-list` failing used to return SUCCESS without pushing, because
  # `[ "${ahead:-0}" -gt 0 ] || return 0` cannot tell "nothing to push"
  # from "could not count".
  ahead="$(git -C "$where" rev-list --count "$BASE..HEAD" 2>/dev/null)"
  case "$ahead" in
    '') say "  could not count commits in $(basename "$where") — treating it as unsaved"
        ahead=1 ;;
    *[!0-9]*) ahead=1 ;;
  esac
  if [ "$ahead" -eq 0 ]; then
    [ "${dirty:-0}" -eq 0 ] && return 0
    ahead=1
  fi

  if git -C "$where" push -q --force-with-lease origin "HEAD:refs/heads/$branch" >>"$LOG" 2>&1; then
    sha="$(git -C "$where" rev-parse --short HEAD)"
    say "  salvaged $name as $sha on $branch ($ahead commit(s), ${dirty:-0} file(s) swept up)"
    return 0
  fi

  # The push failed, so "nothing is thrown away without being pushed
  # first" is about to be false — verified against a read-only origin:
  # `salvage` logged COULD NOT PUSH, `prepare_slot` reset the branch one
  # line later, and `git branch -a --contains <sha>` came back empty with
  # only the reflog holding it until the next `git gc`. A tag makes the
  # commit reachable locally even after the branch moves.
  tag="intake-salvage/$(date +%s)"
  git -C "$where" tag -f "$tag" >>"$LOG" 2>&1
  say "  COULD NOT PUSH $name — tagged $tag locally; its work is only in $where"
  tell "$name could not be pushed. Its work is in $where, tagged $tag."
  return 1
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

# Whether resetting this tree onto `$start` would make what is in it
# unreachable.
#
# The question is NOT "is HEAD an ancestor of start" — a slot is routinely
# handed from one request's branch to another's, and the first branch's
# commits are perfectly safe on origin. The question is whether anything
# would stop being reachable at all: from `$start`, from a remote branch,
# or from a tag. That is the invariant worth asserting, and asserting it
# of the tree rather than of the reasoning that produced `$start` is what
# makes it catch a whole class instead of one instance.
would_lose() {
  local where="$1" start="$2" head
  head="$(git -C "$where" rev-parse --verify --quiet HEAD 2>/dev/null)" || return 1

  # Already where it is going.
  if git -C "$where" rev-parse --verify --quiet "$start" >/dev/null 2>&1 \
     && git -C "$where" merge-base --is-ancestor "$head" "$start" 2>/dev/null; then
    return 1
  fi
  # Reachable from something on origin.
  if [ -n "$(git -C "$where" branch -r --contains "$head" 2>/dev/null)" ]; then
    return 1
  fi
  # Or from a tag, which is how a failed salvage keeps its work.
  if [ -n "$(git -C "$where" tag --contains "$head" 2>/dev/null)" ]; then
    return 1
  fi
  return 0
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
  local where="$1" branch="$2" name="$3" stamp start
  mkdir -p "$(dirname "$where")"

  # Salvage FIRST, then decide where the branch starts.
  #
  # This is the third round running with the same data-loss shape, and the
  # general version of the mistake is: a decision computed from a snapshot
  # of state that a LATER step in the same function then changes. `start`
  # used to be chosen from `ls-remote` before `salvage` ran — so when
  # salvage's push was the first time the branch reached origin, `start`
  # was still `$BASE`, the `checkout -B` reset the commit salvage had just
  # made, and the next push force-with-leased over it. The log said
  # "nothing was thrown away"; `git ls-tree -r <branch>` on origin
  # contained neither file, and the work was reachable only from the
  # reflog.
  if [ -e "$where/.git" ]; then
    if ! salvage "$where" "whatever was in $(basename "$where") before"; then
      say "  REFUSING to reset $(basename "$where") — its work is not pushed anywhere"
      return 1
    fi
  fi

  # NOW ask where the branch is, with the salvage push already in it.
  start="$BASE"
  if git -C "$REPO" ls-remote --exit-code origin "refs/heads/$branch" >/dev/null 2>&1; then
    git -C "$REPO" fetch --quiet origin "$branch" >>"$LOG" 2>&1 || true
    if git -C "$REPO" rev-parse --verify --quiet "refs/remotes/origin/$branch" >/dev/null; then
      start="origin/$branch"
      say "  $name: $branch is already pushed, continuing from $start rather than $BASE"
    fi
  fi

  if [ -e "$where/.git" ]; then
    # The general guard. Whatever reasoning produced `start`, this is the
    # question that actually matters, asked of the tree rather than of the
    # reasoning: is anything about to stop being reachable? It catches the
    # whole class, not the one instance of it.
    if would_lose "$where" "$start"; then
      git -C "$where" fetch --quiet origin "$branch" >>"$LOG" 2>&1 || true
      if git -C "$REPO" rev-parse --verify --quiet "refs/remotes/origin/$branch" >/dev/null \
         && ! would_lose "$where" "origin/$branch"; then
        start="origin/$branch"
        say "  $name: starting from $start, which already contains what is here"
      else
        say "  REFUSING to reset $(basename "$where") onto $start — it would orphan $(git -C "$where" rev-parse --short HEAD 2>/dev/null)"
        tell "$name: its slot holds work that is not reachable from $start"
        return 1
      fi
    fi
    if ! git -C "$where" checkout -q -B "$branch" "$start" >>"$LOG" 2>&1; then
      say "  $(basename "$where") would not reset; rebuilding it"
      remove_worktree "$where"
    fi
  fi

  if [ ! -e "$where/.git" ]; then
    git -C "$REPO" worktree prune >>"$LOG" 2>&1
    # No `-B`. Force-updating a branch another worktree holds either
    # orphans its commits or fails outright depending on whether a stale
    # admin entry exists, and both have happened here.
    if ! git -C "$REPO" worktree add -q "$where" -b "$branch" "$start" >>"$LOG" 2>&1; then
      git -C "$REPO" worktree add -q "$where" "$branch" >>"$LOG" 2>&1 || return 1
      git -C "$where" checkout -q -B "$branch" "$start" >>"$LOG" 2>&1 || return 1
    fi
  fi

  git -C "$where" clean -qxdf \
    -e node_modules -e .gradle -e build -e '**/build' >>"$LOG" 2>&1

  # `npm ci` only when the lockfile actually moved. It is 403 seconds.
  #
  # The stamp used to live INSIDE the worktree, four lines after a
  # `git clean -qxdf` that deleted it — so the comparison always failed
  # and `npm ci` ran on every single build, which is the one thing slots
  # exist to avoid. It lives beside the slot claim now.
  stamp="$STATE/$(basename "$where").npm-lock"
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

# Empty, never 0, when it could not count. Returning 0 on failure got a
# healthy builder killed at the forty-minute mark over an index lock, a
# missing `$BASE` after a failed fetch, or a concurrent checkout — and its
# work judged unfinished on the way out.
commits_on() {
  local n
  n="$(git -C "$1" rev-list --count "$BASE..HEAD" 2>/dev/null)"
  case "$n" in ''|*[!0-9]*) printf '' ;; *) printf '%s' "$n" ;; esac
}

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
# Sets PR_LINE to "<n> <STATE>", or '' when there genuinely is no pull
# request. Returns 1 when GitHub could not be asked at all, which is a
# different thing and must never become a verdict: a failing `pr list`
# used to produce "NOT FINISHED — no pull request for its branch" and a
# false notification about a builder that had done everything right.
pr_for() {
  PR_LINE="$(ghx pr list --head "$1" --state all --limit 1 \
    --json number,state --jq '.[] | "\(.number) \(.state)"')"
  local status=$?
  [ "$status" -eq 0 ] || return 1
  return 0
}

# Whether EVERY check on a pull request succeeded.
#
# `gh pr merge` inspects no checks at all, and the instruction that
# "green means every check" was enforced by nothing — `tally` is not even
# a required context, and `concurrency: cancel-in-progress` makes a
# cancelled one easy to produce. A pull request with NO checks reads as
# benign and is exactly what a hundred commits sat in, so zero checks is
# not green either.
# 0 green, 1 not green, 2 could not be asked. Two is not red.
all_checks_green() {
  local pr="$1" out status lines bad
  out="$(gh pr checks "$pr" 2>>"$LOG")"
  status=$?
  # gh exits 1 when a check failed and 8 when one is pending; anything
  # else is gh itself not working, and that is not an answer about CI.
  lines="$(printf '%s\n' "$out" | sed '/^$/d' | wc -l | tr -d ' ')"
  # `gh` exits 1 for "failed for any reason", so an exit 1 with EMPTY
  # stdout is gh not working, not a pull request with no checks. The
  # `0|1|8` whitelist mapped it to "no checks, not green" — a red verdict
  # and a fix-only builder on a healthy pull request, burning one of its
  # two fix attempts. This is the consumer R11 named and round 2 missed.
  if [ "${lines:-0}" -eq 0 ] && [ "$status" -ne 0 ]; then
    say "  gh pr checks said nothing and exited $status — that is gh failing, not CI"
    return 2
  fi
  case "$status" in
    0|1|8) : ;;
    *) say "  gh pr checks exited $status — that is gh failing, not CI"; return 2 ;;
  esac
  if [ "${lines:-0}" -eq 0 ]; then
    say "  #$pr has no checks — that is not green, it is unverified"
    return 1
  fi
  # `skipping` is NOT green. gh buckets NEUTRAL there too, and a required
  # check that reported neutral has told you nothing — while the comment
  # four lines up says "never with a check pending or skipped".
  bad="$(printf '%s\n' "$out" | awk -F'\t' 'NF>1 && $2!="pass" {print $1"="$2}')"
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
# Block on CI for ONE commit. Returns 0 when every run for that sha
# finished, 1 when GitHub could not be asked, 2 when no run ever appeared.
#
# The first version was unsound four ways at once. `--branch` with no
# commit filter returns everything the branch has ever run — verified, 20
# runs over 8 commits, 15 already completed — so it watched old runs and
# returned instantly, and the real gate became `all_checks_green` alone.
# It had no status filter, no deadline, and discarded every result, so a
# failing `run list` read as "CI finished" and a dropped connection parked
# the builder forever with the watchdog already dead.
wait_for_ci() {
  local branch="$1" sha="$2" ids id status waited=0
  local deadline=$((CI_WAIT_MINUTES * 60))

  # A run for this sha may not be registered yet. Wait for one to exist
  # rather than concluding from its absence.
  while :; do
    ids="$(ghx run list --branch "$branch" --commit "$sha" --limit 20 \
            --json databaseId,status --jq '.[].databaseId')"
    status=$?
    [ "$status" -eq 0 ] || return 1
    [ -n "$(printf '%s' "$ids" | sed '/^$/d')" ] && break
    [ "$waited" -ge "$deadline" ] && {
      say "  no CI run appeared for $sha in ${CI_WAIT_MINUTES}m"
      return 2
    }
    sleep "$CI_POLL_SECONDS"
    waited=$((waited + CI_POLL_SECONDS))
  done

  local watched=0
  while IFS= read -r id; do
    [ -n "$id" ] || continue
    say "  gh run watch $id (for $sha)"
    # With a deadline. "The deadline is the watcher's own" was wrong: a
    # GitHub job's default timeout is 360 minutes, 120 PAST MAX_MINUTES,
    # which is what made a live builder's claims age out and get stolen
    # with the builder behaving perfectly. A killed watch is transport
    # trouble, not a verdict.
    local wrc=0
    watch_with_deadline "$id" || wrc=$?
    case "$wrc" in
      0) watched=$((watched + 1)) ;;
      2) say "  gave up watching run $id after ${CI_WATCH_SECONDS}s"
         return 1 ;;
      *) say "  run $id finished red"
         watched=$((watched + 1)) ;;
    esac
  done <<EOF
$ids
EOF
  [ "$watched" -gt 0 ] || return 2
  return 0
}

# 0 green, 1 red, 2 killed on the deadline. `timeout` is not on macOS, so
# the sidecar is a backgrounded sleep that kills the watcher.
watch_with_deadline() {
  local id="$1" pid reaper rc
  gh run watch "$id" --exit-status >>"$LOG" 2>&1 &
  pid=$!
  ( sleep "$CI_WATCH_SECONDS"; kill -TERM "$pid" 2>/dev/null ) >/dev/null 2>&1 &
  reaper=$!
  wait "$pid"; rc=$?
  kill_tree "$reaper" TERM
  wait "$reaper" 2>/dev/null || true
  # 143 is SIGTERM, which here only comes from the sidecar.
  [ "$rc" -eq 143 ] && return 2
  return "$rc"
}

# ---------------------------------------------------------------------
# Filing a finished request
# ---------------------------------------------------------------------

# Whether the real repo is somewhere it is safe to commit requests.
#
# `file_as_done` and the triage commit both ran `git -C "$REPO" commit`
# with no branch check, so they committed onto whatever Matt happened to
# have checked out — and nothing ever pushed, so `origin/main` kept
# carrying finished requests, which this file's own comments call the fuel
# for the recursive hook.
can_commit_requests() {
  local here want="${BASE#origin/}"
  here="$(git -C "$REPO" rev-parse --abbrev-ref HEAD 2>/dev/null)"
  if [ "$here" != "$want" ]; then
    say "  NOT committing requests: $REPO is on '$here', not '$want'"
    tell "intake wanted to file a request but $REPO is on $here, not $want"
    return 1
  fi
  return 0
}

# Commit whatever the dispatcher just did under `requests/`, and push it.
#
# Three things were wrong with doing this inline. It reported "committed
# and pushed" when the commit had failed — with an `index.lock` planted,
# which a concurrent dispatcher produces, the log said so alongside two
# "Unable to create index.lock" errors and `git log` showed no commit. It
# never pulled, so local `main` fell permanently behind after the first
# squash merge on the remote and every later push was rejected, leaving
# origin carrying finished requests — what this file's own comments call
# the fuel for the recursive hook. And `git add -A -- requests` swept the
# user's untracked drafts in and pushed them to main, where they became
# `pending` for every future dispatch and seeded every new slot.
commit_requests() {
  local message="$1"; shift
  local stray p

  # ONLY the paths the dispatcher touched, named explicitly. `git add -A --
  # requests` swept the user's untracked drafts into the intake commit and
  # pushed them to main, where they became `pending` for every future
  # dispatch and seeded every new slot — measured, with
  # `requests/matt-draft.md | 1 +` inside a commit that was supposed to be
  # one move. An allowlist is the only version of this that cannot do that
  # again, so there is no `-A` here at all.
  [ "$#" -gt 0 ] || { say "  commit_requests called with no paths"; return 1; }

  stray="$(git -C "$REPO" status --porcelain -- requests 2>/dev/null \
           | /usr/bin/grep -E '^\?\?' || true)"
  for p in "$@"; do
    # Not the paths this call is for, and not the directory one of them is
    # in — an empty `requests/done/` reads as untracked and is nobody's
    # draft.
    stray="$(printf '%s\n' "$stray" | /usr/bin/grep -vF -- "$p" || true)"
    stray="$(printf '%s\n' "$stray" | /usr/bin/grep -vF -- "$(dirname "$p")/" || true)"
  done
  stray="$(printf '%s\n' "$stray" | sed '/^$/d')"
  if [ -n "$stray" ]; then
    say "  leaving these alone, they are not the dispatcher's:"
    printf '%s\n' "$stray" | while IFS= read -r l; do say "    $l"; done
  fi

  if ! git -C "$REPO" pull --ff-only --quiet origin "${BASE#origin/}" >>"$LOG" 2>&1; then
    say "  could not fast-forward ${BASE#origin/} from origin; not committing onto a stale base"
    tell "intake could not fast-forward ${BASE#origin/}; requests were not filed"
    return 1
  fi

  # Only paths git can be asked about. A request Matt dropped in is
  # UNTRACKED until the dispatcher commits it, so once it has been moved to
  # `done/` its old path neither exists nor is in HEAD — and
  # `git add -A -- <that path>` fails with a pathspec error, which took
  # `file_as_done` down with it and left the request moved on disk but never
  # committed. Found by driving it; every earlier drive happened to have the
  # request file tracked in the base commit, which is why it looked fine.
  local staged=0
  for p in "$@"; do
    if [ -e "$REPO/$p" ] \
       || git -C "$REPO" ls-files --error-unmatch -- "$p" >/dev/null 2>&1; then
      git -C "$REPO" add -A -- "$p" >>"$LOG" 2>&1 \
        || { say "  could not stage $p"; tell "intake could not stage $p"; return 1; }
      staged=$((staged + 1))
    fi
  done
  if [ "$staged" -eq 0 ]; then
    say "  nothing to stage for: $*"
    return 1
  fi
  # Committed from the index, not from a pathspec: a pathspec here would
  # hit the same "did not match" problem for the path that has gone.
  if ! git -C "$REPO" \
       -c user.name=intake -c user.email=intake@localhost \
       commit -q -m "$message" >>"$LOG" 2>&1; then
    say "  COMMIT FAILED: $message"
    tell "intake could not commit requests/ — see .intake/intake.log"
    return 1
  fi
  if ! git -C "$REPO" push -q origin "HEAD:refs/heads/${BASE#origin/}" >>"$LOG" 2>&1; then
    say "  committed but COULD NOT PUSH ${BASE#origin/}"
    tell "intake committed requests/ locally but could not push ${BASE#origin/}"
    return 1
  fi
  return 0
}

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
  if ! can_commit_requests; then
    say "  requests/done/$file is moved on disk but NOT committed"
    return 0
  fi
  rm -f "$STATE/handed/$file"
  commit_requests "requests: $file is done" \
      "requests/$file" "requests/done/$file" \
    && say "  filed requests/done/$file in the real repo, committed and pushed"
}

# ---------------------------------------------------------------------
# The builder, and what happens after it
# ---------------------------------------------------------------------

builder_prompt() {
  local name="$1" branch="$2" handed="$3" marker="$4"
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

ONE MORE THING, and it is the only way anybody can tell your change actually
shipped. Write a single short string to $marker — one line, no quotes — that
appears VERBATIM in your own diff and will therefore appear in the built web
bundle and, if you touched Android, in the APK. A string literal you added, a
new test id, a new semantics tag: something a \`grep -F\` would find in the
shipped artifact and would not have found before your change.

The dispatcher greps the deployed bundle and the released APK for it after
merging. Without it the only check available is "the site responded", which
cannot tell a successful deploy from the previous build still being served —
and that is exactly what it was doing for two rounds. If your change genuinely
adds no greppable string, write nothing and say why in the pull request.
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
  # Anchored. Matching the branch as a bare substring of `claude`'s argv
  # also matched every branch that has this one as a PREFIX — verified
  # with two live processes, both returned — and `stop_builder` then
  # killed both. That is the `pkill -P $$` regression the header claims to
  # have fixed, latent only because MAX_BUILDERS is 1.
  left="$(claude_pids "$branch")"
  if [ -n "$left" ]; then
    say "  a claude for $branch outlived its parent: $left — killing it too"
    for p in $left; do kill_tree "$p" KILL; done
    sleep 2
  fi
  left="$(claude_pids "$branch")"
  [ -n "$left" ] && say "  STILL ALIVE after SIGKILL: $left"
  return 0
}

# Every `claude` working on exactly this branch, and no branch that merely
# starts with it.
# The exclusion class `[!a-zA-Z0-9_-]` let `/` and `.` through, so one
# branch still matched `request/foo/deep` and `request/foo.old`. The prompt
# always has a space after the branch, so a space on both sides is the
# whole rule and there is no class to get wrong.
claude_pids() {
  pgrep -f "claude " 2>/dev/null | while IFS= read -r p; do
    case " $(ps -o command= -p "$p" 2>/dev/null) " in
      *" $1 "*) printf '%s ' "$p" ;;
    esac
  done
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
      # So `build_one` does not then report it as an unfinished build
      # needing re-dispatch, two lines after reporting it as withdrawn —
      # and does not leave `.attempts` behind for a request that may be
      # dropped in again later.
      : > "$STATE/$name.withdrawn"
      stop_builder "$pid" "$branch"
      return 0
    fi

    local n
    n="$(commits_on "$where")"
    if [ -z "$n" ]; then
      say "$name: could not count its commits — not killing it on a guess"
    elif [ "$mins" -ge "$FIRST_COMMIT_MINUTES" ] && [ "$n" -eq 0 ]; then
      say "$name KILLED — ${mins}m and not one commit"
      salvage "$where" "$name"
      stop_builder "$pid" "$branch"
      return 0
    fi

    if [ "$waited" -ge "$MAX_SECONDS" ]; then
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

# Launch an agent with no way to reach production.
#
# The deny list in `.claude/settings.json` and the PreToolUse hook are
# string matching, and string matching is a SECOND line of defence. It was
# attacked with eighteen spellings a model would reach for first — `eval`,
# `bash -c`, `W=wrangler; $W deploy`, `--admin=true`, `wrangler dep""loy`,
# and a `wrangler d1 execute` with no `--remote` at all, which still
# reaches production — and every one of them got through.
#
# The first line of defence is not holding the keys. Wrangler finds
# credentials in exactly two places: these environment variables, and its
# own config under `WRANGLER_HOME`. Both are taken away here, so
# `wrangler d1 execute --remote` fails on its own with "not authenticated"
# whatever the agent types and whatever the matcher misses.
#
# The local suite does not need any of this: it runs the Worker in
# workerd against a local D1 through miniflare.
no_creds() {
  local void="$STATE/no-cloudflare-auth" k unsets
  mkdir -p "$void"

  # By PATTERN, not by name. A fixed `-u` list meant tracking wrangler's
  # releases, and it was already missing `CF_EMAIL`,
  # `CLOUDFLARE_API_USER_SERVICE_KEY`, `WRANGLER_CF_AUTHORIZATION_TOKEN`,
  # `CLOUDFLARE_ACCESS_CLIENT_*`, `CLOUDFLARE_CONFIG_FILE` and
  # `CLOUDFLARE_API_BASE_URL`. Anything that looks like a Cloudflare or
  # wrangler credential goes, whatever it is called.
  #
  # `WRANGLER_HOME` was in that list and is not a variable wrangler 4.141
  # reads at all; the real config path comes from `XDG_CONFIG_HOME` or
  # `~/Library/Preferences/.wrangler`, plus a Keychain backend. So
  # `XDG_CONFIG_HOME` and `XDG_CACHE_HOME` are pointed at an empty
  # directory and the Keychain backend is turned off.
  unsets=""
  while IFS='=' read -r k _; do
    case "$k" in
      CLOUDFLARE*|CF_*|WRANGLER*|XDG_CONFIG_HOME|XDG_CACHE_HOME)
        unsets="$unsets -u $k" ;;
    esac
  done <<ENVLIST
$(env)
ENVLIST

  # Not `env -i` with an allowlist. That is what the review asked for and
  # it is the more thorough answer, but an allowlist that is missing one
  # variable produces a builder that cannot run `gh`, `git` or `claude` at
  # all — and there is no way to verify the real list without a real
  # builder. A pattern-based unset achieves the stated purpose, which was
  # to stop tracking wrangler's variable names, with no way to break the
  # builder by omission. The residual risk is named in the pull request
  # body.
  #
  # shellcheck disable=SC2086
  env $unsets \
    XDG_CONFIG_HOME="$void" XDG_CACHE_HOME="$void" \
    CLOUDFLARE_AUTH_USE_KEYRING=false \
    CLOUDFLARE_API_TOKEN= CLOUDFLARE_ACCOUNT_ID= \
    INTAKE_BUILDER=1 \
    "$@"
}

# A persistent per-request attempt counter, and a hold when it runs out.
#
# `TRIED` is per-process (`tried.$$`), so it resets on every dispatch —
# which means a request that always ends NOT FINISHED was retried forever,
# once per launchd event, with nothing counting. `TRIAGE_GIVE_UP` existed
# for triage and nothing existed for builds.
bump_attempt() {
  local name="$1" why="$2" n
  n="$(cat "$STATE/$name.attempts" 2>/dev/null || echo 0)"
  n=$(( ${n:-0} + 1 ))
  printf '%s\n' "$n" > "$STATE/$name.attempts"
  if [ "$n" -ge "$BUILD_GIVE_UP" ]; then
    # A hold, not a deletion. `buildable` consults it, `status.sh` prints
    # it, the end-of-run needs-you list names it every time, and the
    # request leaves the live folder — which `requests/README.md` promises
    # happens on every outcome and which did not happen here.
    printf '%s\n' "$why" > "$STATE/$name.held"
    rm -f "$STATE/$name.fix-pending"
    say "  GIVING UP on $name after $n attempts — held until you clear $STATE/$name.held"
    file_as_done "$name.md" "Held after $n attempts: $why. Not merged." \
      || say "  could not file $name under done/"
    tell "$name failed $n times and is now held: $why"
  else
    say "  it needs re-dispatching (attempt $n of $BUILD_GIVE_UP); nothing was thrown away"
    tell "$name did not finish (attempt $n of $BUILD_GIVE_UP): $why"
  fi
}

# A fix-only builder, with the failing log in its hands.
#
# This did not exist. `build_one` wrote `$STATE/<name>.fixme` and the only
# reader anywhere was `status.sh`, which printed it — while
# `request-builder.md` and `requests/README.md` both promised the feature
# and the pull request body claimed it. A documented feature that does not
# exist is worse than none.
dispatch_fix() {
  local name="$1" file="$2" branch="$3" where="$4" pr="$5" sha="$6"
  local n logfile runid handed marker
  # The request itself, so a fix builder can tell a missing platform half
  # from a test that needs correcting — it used to get the failure and
  # nothing else, and was told it could "correct the test", so a parity
  # miss became a weakened assertion instead of the missing Android half.
  handed="$STATE/handed/$file"
  [ -f "$handed" ] || { mkdir -p "$STATE/handed"; cp "$REQUESTS/$file" "$handed" 2>/dev/null; }
  marker="$STATE/$name.marker"

  n="$(cat "$STATE/$name.fix-attempts" 2>/dev/null || echo 0)"
  n=$(( ${n:-0} + 1 ))
  printf '%s\n' "$n" > "$STATE/$name.fix-attempts"
  printf '%s\n' "$pr" > "$STATE/$name.fixme"

  if [ "$n" -gt "$FIX_GIVE_UP" ]; then
    say "  $name has been fixed $FIX_GIVE_UP times and is still red — stopping"
    printf 'CI red on #%s after %s fix attempts\n' "$pr" "$FIX_GIVE_UP" > "$STATE/$name.held"
    rm -f "$STATE/$name.fix-pending"
    file_as_done "$file" "CI is red on #$pr after $FIX_GIVE_UP fix attempts. Not merged."
    tell "$name: #$pr is still red after $FIX_GIVE_UP fix attempts"
    return 0
  fi

  # The failing job's log, which is the whole point: without it a fix
  # builder re-derives the failure from scratch or guesses.
  logfile="$STATE/$name.ci-failure.log"
  runid="$(ghx run list --branch "$branch" --commit "$sha" --limit 20 \
            --json databaseId,conclusion \
            --jq '[.[] | select(.conclusion=="failure")][0].databaseId')"
  if [ $? -ne 0 ]; then
    # A `gh` failure here used to produce a log saying "No failing run was
    # identified" and a fix builder launched to read it — burning one of
    # its two attempts on a file containing no failure.
    say "  could not ask GitHub which run failed; not dispatching a fix on a guess"
    printf '%s\n' "$(( n - 1 ))" > "$STATE/$name.fix-attempts"
    bump_attempt "$name" "GitHub would not say which run failed on #$pr"
    return 0
  fi
  if [ -z "$runid" ] || [ "$runid" = "null" ]; then
    say "  no failing run for $sha; leaving the marker and re-checking next dispatch"
    printf '%s\n' "$(( n - 1 ))" > "$STATE/$name.fix-attempts"
    return 0
  fi
  # Trimmed as it is read, not after. A multi-megabyte log landed in
  # `$STATE` in full before `tail` ever saw it.
  if ! gh run view "$runid" --log-failed 2>>"$LOG" | tail -400 > "$logfile"; then
    say "  could not read the failing log for run $runid"
    printf '%s\n' "$(( n - 1 ))" > "$STATE/$name.fix-attempts"
    bump_attempt "$name" "the failing log for #$pr could not be read"
    return 0
  fi

  say "  dispatching a fix-only builder for $name (attempt $n of $FIX_GIVE_UP), log in $logfile"
  tell "$name: CI red on #$pr, sending a fix-only builder"

  # Watched exactly as a full builder is. It had no watchdog, no
  # MAX_MINUTES and no kill, so a stalled fix builder ran unbounded, held
  # its claims past the cap, and the claim break above then reset the
  # worktree under it — two FIX-START pids were measured in one slot, with
  # "salvaged whatever was in slot1 before" logged while the first was
  # still writing.
  local code fpid fdog
  (
    cd "$where" || exit 1
    run no_creds claude -p "Invoke the mtg skill first, then read CLAUDE.md.

You are a FIX-ONLY builder. A pull request already exists for this work — #$pr
on branch $branch — and CI is red on it. You are not implementing the request
again and you are not re-reading it: the code is already written and already
pushed.

Your whole job is to make CI green. The failing job's log is at $logfile. Read
it, find the cause, write or correct the test that proves it, fix it, run that
ONE test narrowly to watch it go red then green, commit, and push to $branch.

Do not open another pull request. Do not merge. Do not touch the request file.
Do not rewrite anything the log does not implicate. If the failure is a flake,
say so plainly rather than changing code to hide it.

The request this pull request implements is at $handed. Read it: the plan and
the Tests section are the contract. PARITY IS NOT NEGOTIABLE — if the failure
is a missing Android or web half, add the missing half. Do NOT weaken or delete
an assertion the plan requires in order to go green; that is the one way to
make this worse than leaving it red.

If your fix changes what the shipped artifact contains, write the new greppable
string to $marker (one line, no quotes), replacing what is there." \
      --model opus \
      --permission-mode bypassPermissions \
      --output-format stream-json --verbose --include-partial-messages \
      >> "$STATE/$name.fix.jsonl" 2>>"$LOG"
  ) </dev/null >>"$LOG" 2>&1 &
  fpid=$!
  watch_builder "$fpid" "$name" "$where" "$branch" "$file" >>"$LOG" 2>&1 &
  fdog=$!
  wait "$fpid"; code=$?
  kill_tree "$fdog" TERM
  wait "$fdog" 2>/dev/null || true
  kill_tree "$fpid" KILL
  say "  fix-only builder for $name exited $code; the next dispatch re-checks CI"
  return 0
}

# A green deploy workflow is not proof the change is live.
#
# The first version of this verified nothing at all. It was a 1000-byte
# floor on a URL that was already serving the PREVIOUS build, so a 404
# page and the prior build passed identically. It keyed on the pull
# request's head sha — which a squash merge never produces — so it matched
# the pull request's own CI runs, already watched by `wait_for_ci`, while
# `pages.yml` and `release.yml` run against the new squash commit and
# never matched at all. Its return value was discarded, the request had
# already been filed, and Matt got two contradictory notifications in a
# row. Measured: a curl returning a thousand zeroes produced "deploys for
# #7 were green and the site answered".
#
# What it does now:
#   - resolves the real merge commit from `gh pr view --json mergeCommit`
#   - watches only the `pages` and `release` runs for THAT sha, by name,
#     with a deadline
#   - greps the shipped web bundle for a marker the builder wrote, so
#     "served" and "carries the change" stop being the same question
#   - greps the released APK's dex for the same marker, when there is a
#     release to download — and reports `apk: unavailable` rather than
#     passing when there is not
#   - and says plainly that it verified nothing when there is no marker,
#     rather than reporting success
#
# Sets SHIPPED_NOTE, which goes into the filed request. Returns 0 only
# when something was actually checked and passed.
verify_shipped() {
  local name="$1" pr="$2" marker="$3"
  local mergesha ids line wf id bad=0 checked=0
  SHIPPED_NOTE=""

  mergesha="$(ghx pr view "$pr" --json mergeCommit --jq '.mergeCommit.oid')"
  if [ -z "$mergesha" ] || [ "$mergesha" = "null" ]; then
    say "  could not resolve the merge commit for #$pr; not claiming anything about the deploy"
    SHIPPED_NOTE="Merged as #$pr. The merge commit could not be resolved, so the deploy was not verified."
    return 1
  fi
  say "  watching deploy runs for ${mergesha:0:8}"

  # Only the two workflows that actually deploy. The old fallback watched
  # arbitrary recent runs of anything.
  ids="$(ghx run list --commit "$mergesha" --limit 30 \
          --json databaseId,name --jq '.[] | "\(.name) \(.databaseId)"')"
  while IFS= read -r line; do
    [ -n "$line" ] || continue
    wf="${line%% *}"; id="${line##* }"
    case "$wf" in
      pages|release) : ;;
      *) continue ;;
    esac
    say "  gh run watch $id ($wf)"
    checked=$((checked + 1))
    # The status is captured, not read after a `!`. `if ! cmd; then case $?`
    # reads the NEGATION's status, which is 0 when cmd failed — so neither
    # arm matched and a watch that had been killed on its deadline was
    # recorded as a clean green. It cost one red test to notice.
    local wrc=0
    watch_with_deadline "$id" || wrc=$?
    case "$wrc" in
      0) : ;;
      2) say "  gave up watching $wf run $id after ${CI_WATCH_SECONDS}s"; bad=1 ;;
      *) say "  $wf run $id ended badly: $(gh run view "$id" --json conclusion --jq .conclusion 2>/dev/null)"
         bad=1 ;;
    esac
  done <<EOF
$ids
EOF
  [ "$checked" -eq 0 ] && say "  no pages or release run for ${mergesha:0:8}"

  # The artifact itself.
  if [ -z "$marker" ]; then
    say "  no marker was recorded for $name, so NOTHING about the shipped artifact was checked"
    SHIPPED_NOTE="Merged as #$pr. The builder left no marker, so the shipped artifact was NOT verified."
    tell "$name merged as #$pr, but it left no marker so nothing shipped was verified"
    return 1
  fi

  local body web=unknown
  body="$(curl -s --compressed --max-time 60 "$SITE_JS" 2>/dev/null)"
  if printf '%s' "$body" | /usr/bin/grep -qF -- "$marker"; then
    say "  $marker is in the shipped web bundle"
    web=yes
  else
    say "  $marker is NOT in the shipped web bundle ($SITE_JS, $(printf '%s' "$body" | wc -c | tr -d ' ') bytes)"
    web=no
    bad=1
  fi

  # And the phone. "Done means deployed on BOTH platforms" is a hard rule
  # in the skill, and for two rounds this function only ever looked at the
  # web — so the rule had no enforcement for the half that ships an APK.
  local apk=skipped tmp
  if command -v unzip >/dev/null 2>&1; then
    tmp="$STATE/apk.$$"
    rm -rf "$tmp"; mkdir -p "$tmp"
    if ghx release download --repo "$REPO_SLUG" --dir "$tmp" --pattern '*.apk' >/dev/null 2>&1 \
       && [ -n "$(ls "$tmp"/*.apk 2>/dev/null)" ]; then
      ( cd "$tmp" && unzip -qo ./*.apk 'classes*.dex' ) >>"$LOG" 2>&1
      if /usr/bin/grep -a -qF -- "$marker" "$tmp"/classes*.dex 2>/dev/null; then
        say "  $marker is in the released APK's dex"
        apk=yes
      else
        say "  $marker is NOT in the released APK's dex"
        apk=no
        bad=1
      fi
    else
      say "  no APK could be downloaded for the release; the phone half is unchecked"
      apk=unavailable
    fi
    rm -rf "$tmp"
  else
    say "  no unzip on this machine; the phone half is unchecked"
    apk=unavailable
  fi

  if [ "$bad" -eq 0 ]; then
    SHIPPED_NOTE="Merged as #$pr. Deploys green; $marker found in the web bundle and the APK dex."
    say "  deploys for #$pr are green and both artifacts carry $marker"
    return 0
  fi
  SHIPPED_NOTE="Merged as #$pr. DEPLOY NOT VERIFIED — web: $web, apk: $apk. See .intake/intake.log."
  return 1
}

# One request, from launch to merged-or-explained. Runs in a subshell.
build_one() {
  local file="$1" name="$2" branch="$3" where="$4" claim="$5" handed="$6"
  local code pipe="" logger="" cpid watchdog lreaper=""

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
    run no_creds claude -p "$(builder_prompt "$name" "$branch" "$handed" "$STATE/$name.marker")" \
      --model opus \
      --permission-mode bypassPermissions \
      --output-format stream-json --verbose --include-partial-messages
  ) > "${pipe:-$STATE/$name.jsonl}" 2>>"$LOG" &
  cpid=$!

  watch_builder "$cpid" "$name" "$where" "$branch" "$file" >>"$LOG" 2>&1 &
  watchdog=$!

  wait "$cpid"; code=$?
  kill_tree "$watchdog" TERM
  # Reaped quietly: a non-interactive bash otherwise prints
  # "Terminated: 15 watch_builder ..." into the log on every single build.
  wait "$watchdog" 2>/dev/null || true

  # An orphan grandchild holding the fifo open parked the dispatcher in
  # `wait "$logger"` for the whole remaining run, with the slot and the
  # claim held and the watchdog already gone — measured at the full 120
  # seconds. `kill_tree` is what its own comment says it is for, and it was
  # not applied here.
  kill_tree "$cpid" KILL
  if [ -n "$logger" ]; then
    # Bounded, not open-ended. The obvious repair — open and close the
    # write end so the reader sees EOF — is worse than the bug: opening a
    # fifo for writing BLOCKS until a reader appears, so if the logger has
    # already finished it hangs forever instead of for the rest of the run.
    # Verified by driving it: the dispatcher sat there with the builder
    # already committed and pushed. A deadline cannot do that.
    ( sleep "$LOGGER_GRACE"; kill -TERM "$logger" 2>/dev/null ) >/dev/null 2>&1 &
    lreaper=$!
    wait "$logger" 2>/dev/null
    kill_tree "$lreaper" TERM
    wait "$lreaper" 2>/dev/null || true
  fi
  [ -n "$pipe" ] && rm -f "$pipe"

  # An exit code says nothing about whether it finished. `claude -p` ends
  # when the model stops producing text, and one builder started the
  # suites, wrote "Red runs for core and web are in progress", and ended
  # its turn — exit 0, nothing committed, no pull request. The dispatcher
  # read 0 as success and deleted the worktree with the work in it.
  local prline pr="" prstate="" open=false merged=false commits pushed=false
  if ! pr_for "$branch"; then
    say "$name: could not ask GitHub whether a pull request exists — keeping its slot"
    say "  branch $branch, slot $(basename "$where"), log $STATE/$name.log"
    salvage "$where" "$name" || say "  SALVAGE FAILED for $name"
    bump_attempt "$name" "GitHub would not say whether a pull request exists"
    rm -rf "$claim"
    return 0
  fi
  prline="$PR_LINE"
  if [ -n "$prline" ]; then
    pr="${prline%% *}"
    prstate="${prline##* }"
    [ "$prstate" = "OPEN" ] && open=true
    # `--state all --limit 1` surfaces a PREVIOUSLY merged pull request on
    # the same branch, and `builderDone` accepts `prMerged` — so a builder
    # that committed, pushed and opened no pull request was filed as done
    # off somebody else's merge, with "Merged as #7" committed while its
    # own commits sat unmerged. The `--state all` fix opened this, so the
    # fix needs a second question: is this work actually in the base?
    if [ "$prstate" = "MERGED" ]; then
      # Against a fresh ref. The merge happened on the remote, so the
      # slot's own `origin/main` is whatever it was when the slot was
      # prepared — asking a stale ref would call every merged pull
      # request unmerged.
      git -C "$where" fetch --quiet origin "${BASE#origin/}" >>"$LOG" 2>&1 || true
      if git -C "$where" merge-base --is-ancestor HEAD "$BASE" 2>/dev/null; then
        merged=true
      else
        say "  #$pr is MERGED but HEAD is not in $BASE — that is an older pull request"
      fi
    fi
  fi
  commits="$(commits_on "$where")"
  pushed_up "$where" "$branch" && pushed=true

  # Withdrawn is not a verdict. It used to be reported as an unfinished
  # build needing re-dispatch, immediately after being correctly reported
  # as withdrawn, and `.attempts` survived — so re-adding the request later
  # started it two tries down.
  if [ -f "$STATE/$name.withdrawn" ]; then
    say "$name was withdrawn; not judging it and not counting it"
    rm -f "$STATE/$name.withdrawn" "$STATE/$name.attempts" \
          "$STATE/$name.fix-attempts" "$STATE/$name.fix-pending" \
          "$STATE/$name.fixme" "$STATE/handed/$file"
    # Its branch is nobody's now.
    git -C "$REPO" push -q origin --delete "$branch" >>"$LOG" 2>&1 \
      && say "  removed origin/$branch"
    rm -rf "$claim"
    return 0
  fi

  ask done-verdict "$code" "$open" "$merged" "$commits" "$pushed"
  local verdict="$ASK_OUT"

  case "$verdict" in
    keep:*)
      say "$name NOT FINISHED — ${verdict#keep:}"
      salvage "$where" "$name" || say "  SALVAGE FAILED for $name"
      say "  branch $branch, slot $(basename "$where"), log $STATE/$name.log"
      bump_attempt "$name" "did not finish: ${verdict#keep:}"
      ;;
    merged)
      say "$name done: #$pr was already merged"
      file_as_done "$file" "Merged as #$pr."
      ;;
    open)
      local head
      head="$(git -C "$where" rev-parse HEAD 2>/dev/null)"
      say "$name built: #$pr is open with $commits commit(s) at ${head:0:8} — waiting on CI"
      wait_for_ci "$branch" "$head"
      case $? in
        1)
          say "$name: could not ask GitHub about CI — keeping its slot, not judging it"
          bump_attempt "$name" "GitHub would not answer about CI on #$pr"
          rm -rf "$claim"
          return 0
          ;;
        2)
          say "$name: no CI run ever appeared for ${head:0:8} — keeping its slot"
          bump_attempt "$name" "no CI run appeared for #$pr"
          rm -rf "$claim"
          return 0
          ;;
      esac

      local green=false mode action
      all_checks_green "$pr"
      case $? in
        0) green=true ;;
        1) green=false ;;
        *)
          say "$name: gh could not report the checks — keeping its slot, not judging it"
          bump_attempt "$name" "gh would not report the checks on #$pr"
          rm -rf "$claim"
          return 0
          ;;
      esac

      ask merge "$file"; mode="$ASK_OUT"
      ask after-ci "$green" "$mode"; action="$ASK_OUT"
      case "$action" in
        merge)
          # No `--delete-branch`: it makes gh check out the base branch in
          # the builder's own worktree to delete the local ref, and the
          # stale tree that leaves made a fully merged request read as
          # unfinished. No `--admin` either — it bypasses every required
          # check, and this token has the power to do it.
          rm -f "$STATE/$name.fix-pending"
          if ghx pr merge "$pr" --squash >/dev/null; then
            say "$name done: MERGED #$pr"
            # Verified BEFORE it is filed, and the outcome goes into the
            # note. It used to file first, discard this function's return
            # value, and send an unconditional "merged" notification right
            # after whatever this said — two contradictory messages in a
            # row.
            if verify_shipped "$name" "$pr" "$(cat "$STATE/$name.marker" 2>/dev/null)"; then
              tell "$name merged as #$pr and the shipped artifacts carry it"
            else
              tell "$name merged as #$pr but the deploy was NOT verified"
            fi
            file_as_done "$file" "$SHIPPED_NOTE"
            rm -f "$STATE/$name.marker"
          else
            say "$name NOT FINISHED — #$pr is green but would not merge"
            bump_attempt "$name" "#$pr is green but would not merge"
          fi
          ;;
        notify)
          rm -f "$STATE/$name.fix-pending"
          # A request must leave the live queue on EVERY terminal outcome,
          # not only the merged one. It did not, and the consequence was
          # concrete: run 1 left a `merge: ask` request live and buildable,
          # run 2 launched a second builder on the same branch,
          # `prepare_slot` reset it to BASE, and `salvage` force-pushed the
          # duplicate OVER the first builder's commit — destroying the pull
          # request Matt had been asked to review.
          say "$name green and left for you (merge: $mode), #$pr"
          file_as_done "$file" "Green and waiting for you as #$pr (merge: $mode). Not merged."
          tell "$name is green: #$pr is waiting for you ($mode)"
          ;;
        fix)
          say "$name has a red #$pr"
          : > "$STATE/$name.fix-pending"
          dispatch_fix "$name" "$file" "$branch" "$where" "$pr" "$head"
          ;;
      esac
      ;;
    unknown)
      # Not a verdict. Something transient stopped git answering, and the
      # slot is kept without anything being concluded from it.
      say "$name: its commit count could not be read; keeping its slot and judging nothing"
      salvage "$where" "$name" || say "  SALVAGE FAILED for $name"
      bump_attempt "$name" "git would not say how many commits it had"
      ;;
    *)
      say "$name could not be judged (verdict was '$verdict'); keeping its slot"
      bump_attempt "$name" "could not be judged: '$verdict'"
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

  # Triage does not run unlocked, ever.
  #
  # The wave now drops the global lock once its builders are backgrounded,
  # which is right — but it left this function running with no lock at
  # all, so two dispatchers ran two `acceptEdits` opus agents in the SAME
  # worktree, rewriting each other's request files. Stopping exactly that
  # is the lock's only purpose. Measured at five seconds of overlap, and
  # one reviewer watched a merged-and-filed request come back from the
  # dead when the loser's stale copy was written back and committed.
  if [ "$LOCK_HELD" != true ]; then
    if ! take_lock; then
      say "triage skipped: another dispatcher has the lock and is doing it"
      return 0
    fi
    LOCK_HELD=true
  fi

  local todo="" f n tri file still wrote=0 produced
  CHANGED=""
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
  if ! equipped "$tri"; then
    say "triage NOT STARTED: $tri is missing its instructions — $WHY_UNEQUIPPED"
    tell "triage cannot start: $WHY_UNEQUIPPED"
    return 0
  fi

  # Emptied first. The folder was never reset between passes, so a
  # leftover could be written back over a live request — and copying the
  # whole folder back, which the split/combine fix requires, turns that
  # from possible into certain.
  rm -rf "$tri/requests"
  mkdir -p "$tri/requests"
  local seeded=0
  while IFS= read -r file; do
    [ -n "$file" ] || continue
    if cp "$REQUESTS/$file" "$tri/requests/$file" 2>>"$LOG"; then
      seeded=$((seeded + 1))
    else
      say "  could not hand $file to triage; leaving it for the next pass"
    fi
  done <<EOF
$todo
EOF
  if [ "$seeded" -eq 0 ]; then
    say "triage had nothing it could read; skipping this pass"
    return 0
  fi

  # No wrapper hop. The old prompt said "invoke the mtg skill first, then
  # run the request-triage agent", which loaded the skill once in the
  # wrapper and again in the spawned agent. And the model is passed
  # explicitly, because a subagent definition's frontmatter is not a
  # top-level session's config.
  (
    cd "$tri" || exit 1
    run no_creds claude -p "You are the request-triage agent. Follow .claude/agents/request-triage.md \
exactly. The untriaged files under requests/ are: $(printf '%s' "$todo" | tr '\n' ' '). \
Rewrite each one in place with the frontmatter and the Plan/Tests/Done-when \
sections. Do not write production code, do not open a branch, and do not touch \
requests/done/." \
      --model opus \
      --permission-mode acceptEdits
  ) >> "$LOG" 2>&1

  # Copy the WHOLE folder back, not the handed list, and honour what
  # triage deleted.
  #
  # `request-triage.md` tells triage to split one request into several NEW
  # files and to combine two into one NEW file, deleting the originals.
  # Iterating the handed-over filenames meant every new file stayed in the
  # triage worktree forever, every original triage deleted was NOT deleted
  # in `requests/`, it was re-triaged on every dispatch, and after
  # TRIAGE_GIVE_UP it was abandoned as "needs Matt" — so the user's
  # request was simply gone. Measured: three requests in, the triage tree
  # held `card-page-combined.md`, `split-part-one.md` and
  # `split-part-two.md`, the real folder still held the three untouched
  # originals, and the log read TRIAGE PRODUCED NO PLAN FOR all three.
  local withdrawn=0 base
  for produced in "$tri"/requests/*.md; do
    [ -f "$produced" ] || continue
    base="$(basename "$produced")"
    [ "$base" = "README.md" ] && continue
    # Never resurrect. Matt withdraws a request by deleting it, triage
    # takes about five minutes, and `cmp -s` against a path that no longer
    # exists fails — so the copy-back used to restore the file, commit it
    # to main and dispatch a builder, which is the exact opposite of what
    # `requests/README.md` promises. A file that was handed over and has
    # since gone was withdrawn; a file triage invented is new and belongs.
    case "$todo" in
      *"$base"*)
        if [ ! -f "$REQUESTS/$base" ]; then
          say "  $base was withdrawn while triage was running; not writing it back"
          withdrawn=$((withdrawn + 1))
          continue
        fi
        ;;
    esac
    if ! cmp -s "$produced" "$REQUESTS/$base"; then
      cp "$produced" "$REQUESTS/$base" && {
        wrote=$((wrote + 1))
        CHANGED="$CHANGED requests/$base"
      }
    fi
  done

  # What triage deleted, it meant to delete: a combine absorbs its
  # originals and a split replaces its source.
  while IFS= read -r file; do
    [ -n "$file" ] || continue
    if [ ! -f "$tri/requests/$file" ] && [ -f "$REQUESTS/$file" ]; then
      say "  triage folded $file into something else; removing it"
      rm -f "$REQUESTS/$file"
      wrote=$((wrote + 1))
      CHANGED="$CHANGED requests/$file"
    fi
  done <<EOF
$todo
EOF
  [ "$withdrawn" -gt 0 ] && say "  $withdrawn request(s) were withdrawn while triage ran"

  if [ "$wrote" -gt 0 ]; then
    TRIAGE_WROTE=$wrote
    if can_commit_requests; then
      # shellcheck disable=SC2086
      commit_requests "requests: triage wrote $wrote plan(s)" $CHANGED \
        && say "triage wrote $wrote plan(s), committed in the real repo"
    else
      say "triage wrote $wrote plan(s) on disk but could NOT commit them"
    fi
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

# Re-check CI for every request waiting on a fix, and send another
# fix-only builder if it is still red.
#
# This is the path that replaced "launch a full re-implementation builder
# and let it rediscover that a pull request already exists".
fix_wave() {
  local file name branch key pr where prline head n
  list_fix_pending
  [ -n "$FIX_PENDING" ] || return 0

  while IFS= read -r file; do
    [ -n "$file" ] || continue
    name="${file%.md}"
    [ -f "$REQUESTS/$file" ] || {
      say "$name was withdrawn while waiting on a fix; dropping the marker"
      rm -f "$STATE/$name.fix-pending" "$STATE/$name.fixme"
      continue
    }
    ask branch "$file"; branch="$ASK_OUT"
    key="$(printf '%s' "$branch" | tr '/' '_')"
    [ -d "$STATE/$key.building" ] && claim_alive "$STATE/$key.building" && {
      say "$name already has something working on it"
      continue
    }

    if ! pr_for "$branch"; then
      say "$name: could not ask GitHub about #$(cat "$STATE/$name.fixme" 2>/dev/null)"
      bump_attempt "$name" "GitHub would not answer while a fix was pending"
      continue
    fi
    prline="$PR_LINE"
    pr="${prline%% *}"
    case "$prline" in
      *MERGED*)
        say "$name: #$pr merged while it was waiting on a fix"
        rm -f "$STATE/$name.fix-pending"
        file_as_done "$file" "Merged as #$pr."
        continue ;;
      '' )
        say "$name: #$(cat "$STATE/$name.fixme" 2>/dev/null) is gone; clearing the fix marker"
        rm -f "$STATE/$name.fix-pending" "$STATE/$name.fixme"
        continue ;;
    esac

    all_checks_green "$pr"
    case $? in
      0)
        say "$name: #$pr is green now"
        rm -f "$STATE/$name.fix-pending"
        local mode action
        ask merge "$file"; mode="$ASK_OUT"
        ask after-ci true "$mode"; action="$ASK_OUT"
        if [ "$action" = merge ] && ghx pr merge "$pr" --squash >/dev/null; then
          say "$name done: MERGED #$pr"
          if verify_shipped "$name" "$pr" "$(cat "$STATE/$name.marker" 2>/dev/null)"; then
            tell "$name merged as #$pr and the shipped artifacts carry it"
          else
            tell "$name merged as #$pr but the deploy was NOT verified"
          fi
          file_as_done "$file" "$SHIPPED_NOTE"
          rm -f "$STATE/$name.marker"
        else
          say "$name green and left for you (merge: $mode), #$pr"
          file_as_done "$file" "Green and waiting for you as #$pr (merge: $mode). Not merged."
          tell "$name is green: #$pr is waiting for you ($mode)"
        fi
        continue ;;
      2)
        say "$name: gh would not report the checks on #$pr"
        bump_attempt "$name" "gh would not report the checks while a fix was pending"
        continue ;;
    esac

    # Still red. Another fix-only builder, in a slot, bounded.
    local claim slotn where
    claim="$STATE/$key.building"
    mkdir "$claim" 2>/dev/null || { say "$name is claimed; leaving the fix to that one"; continue; }
    stamp_claim "$claim" "$$"
    if ! slotn="$(take_slot)"; then
      say "$name: no free slot for its fix"
      rm -rf "$claim"
      continue
    fi
    where="$WT_ROOT/slot$slotn"
    if ! prepare_slot "$where" "$branch" "$name"; then
      say "$name could not get slot$slotn for its fix"
      tell "$name: its slot could not be prepared for a fix"
      bump_attempt "$name" "slot$slotn could not be prepared"
      rm -rf "$claim" "$STATE/slot$slotn.claim"
      continue
    fi
    head="$(git -C "$where" rev-parse HEAD 2>/dev/null)"
    dispatch_fix "$name" "$file" "$branch" "$where" "$pr" "$head"
    rm -rf "$claim" "$STATE/slot$slotn.claim"
  done <<EOF
$FIX_PENDING
EOF
  return 0
}

# Everything waiting on Matt, from both sources.
#
# `intake.mjs held` reads frontmatter and knows nothing about a hold file,
# so a held request was notified exactly once and then silently skipped
# forever while `status.sh` printed it as ready.
report_held() {
  local h why hf
  list_held
  for hf in "$STATE"/*.held; do
    [ -f "$hf" ] || continue
    h="$(basename "$hf" .held).md"
    case "$HELD" in *"$h"*) ;; *) HELD="$HELD$h
" ;; esac
  done
  HELD="$(tidy "$HELD")"
  [ -n "$HELD" ] || return 0
  say "held back: $(printf '%s' "$HELD" | tr '\n' ' ')"
  while IFS= read -r h; do
    [ -n "$h" ] || continue
    if [ -f "$STATE/${h%.md}.held" ]; then
      why="held: $(cat "$STATE/${h%.md}.held" 2>/dev/null)"
    else
      ask state "$h"; why="$(printf '%s' "$ASK_OUT" | cut -f2)"
    fi
    say "  $h — $why"
    tell "$h needs you: $why"
  done <<EOF
$HELD
EOF
  return 0
}

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
    # Plain `mkdir`, not `-p`. `mkdir -p` never fails on an existing
    # directory, so the claim provided no mutual exclusion at all — the
    # pid-ordering fix above it was correct and guarded nothing.
    mkdir "$claim" 2>/dev/null || { say "$name was claimed between the check and the claim"; continue; }
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
      # R10 made this safe and silently inert: the refusal to reset an
      # unsaved slot was right, and then nothing counted it, nothing was
      # held and nothing was notified — so with MAX_BUILDERS=1 every
      # request failed identically forever with the only record a line in
      # a log nobody reads. A fix that moves the failure rather than
      # removing it does not count.
      say "$name could not get slot$n, skipping"
      tell "$name: slot$n could not be prepared — its work may be unpushed"
      bump_attempt "$name" "slot$n could not be prepared"
      rm -rf "$claim" "$STATE/slot$n.claim"
      continue
    fi

    # A worktree with no skill, no agent definition, no CLAUDE.md and no
    # guard produces a builder that works blind. That happened twice:
    # `.claude/` was only on the branch that introduced it, the builders
    # were told to invoke the mtg skill, found nothing, and built without
    # the parity rule. Nothing failed, which is what made it bad.
    if ! equipped "$slot"; then
      say "$name NOT STARTED: $slot is missing its instructions — $WHY_UNEQUIPPED"
      say "  they have to be on $BASE first"
      tell "$name cannot start: $WHY_UNEQUIPPED"
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

  # Let go of the global lock before waiting.
  #
  # The dispatcher now owns the CI wait, so a wave is a builder run plus
  # 13-17 minutes of `apps` — and the lock was held through all of it,
  # right to the end of `main`. So A15's dropped-event bug was fully
  # intact: launchd discards WatchPaths events fired while the job runs,
  # and a second dispatcher that could have picked up a newly dropped
  # request exited on the lock instead. For up to four hours.
  #
  # Per-request exclusion does not depend on the global lock: the
  # `.building` claim and the slot claim are both atomic `mkdir`s, and a
  # second dispatcher reads them. The lock only ever existed to stop two
  # dispatchers triaging at once.
  if [ "$STARTED_THIS_WAVE" -gt 0 ]; then
    drop_lock
    LOCK_HELD=false
    say "lock released; $STARTED_THIS_WAVE builder(s) still running, so a new request can be picked up now"
  fi

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
  STARTED_THIS_WAVE=0; TRIAGE_WROTE=0; LOCK_HELD=false

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
  # Comparing `--git-dir` against `--git-common-dir` is defeated by
  # `GIT_DIR`/`GIT_WORK_TREE` in the environment — both then answer the
  # same thing and the guard passes — and a submodule passes for the same
  # reason. A linked worktree's `.git` is a FILE containing a gitdir
  # pointer, never a directory, and nothing in the environment changes
  # that. So the shape is tested first and the rev-parse is the backstop.
  if [ ! -d "$REPO/.git" ]; then
    mkdir -p "$STATE" 2>/dev/null
    printf '%s  refusing to dispatch from a linked worktree (%s has no .git directory)\n' \
      "$(date '+%Y-%m-%d %H:%M:%S')" "$REPO" >> "$LOG" 2>/dev/null
    exit 0
  fi
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
  # The same cap in seconds, which is what the watchdog actually compares.
  # A test that needs to watch the cap fire should not have to burn a real
  # minute doing it.
  MAX_SECONDS="${INTAKE_MAX_SECONDS:-$((MAX_MINUTES * 60))}"
  # And wall-clock alone is not a signal: the run that provoked this went
  # 108 minutes with zero commits. This threshold is the FIRST commit
  # only.
  FIRST_COMMIT_MINUTES="${INTAKE_FIRST_COMMIT_MINUTES:-40}"
  WATCH_SECONDS="${INTAKE_WATCH_SECONDS:-60}"
  # One, still. Two is the plan and the measurement supports it — true
  # Gradle occupancy in the one complete transcript was about 32% of
  # wall-clock — but it is only safe once the `lockf` Gradle mutex exists
  # in scripts/guard.mjs, and that is deferred. Gradle does not share:
  # `~/.gradle` is locked and `forkEvery(1)` spawns a JVM per test class,
  # so two builders without the mutex thrash. The slot machinery below
  # already handles N; this is the only line that has to change.
  MAX_BUILDERS="${INTAKE_MAX_BUILDERS:-1}"
  BASE="${INTAKE_BASE:-origin/main}"
  # Outside the repo, so `git clean -fdx` in the primary tree cannot
  # destroy a live builder and leave the stale `.git/worktrees/` entries
  # that wedge the next attempt at the same branch.
  WT_ROOT="${INTAKE_WORKTREE_ROOT:-$HOME/.cache/mtg-intake/wt}"
  TRIAGE_GIVE_UP="${INTAKE_TRIAGE_GIVE_UP:-3}"
  # How long a claim may have no pid before nobody is coming for it. The
  # window between `mkdir` and `stamp_claim` is milliseconds.
  PIDLESS_SECONDS="${INTAKE_PIDLESS_SECONDS:-30}"
  # A request that keeps failing is held rather than retried forever.
  # `TRIED` is per-process, so it counted nothing across dispatches.
  BUILD_GIVE_UP="${INTAKE_BUILD_GIVE_UP:-3}"
  FIX_GIVE_UP="${INTAKE_FIX_GIVE_UP:-2}"
  # How long to wait for a CI run to appear for a sha before giving up on
  # it, and how often to look.
  CI_WAIT_MINUTES="${INTAKE_CI_WAIT_MINUTES:-10}"
  CI_POLL_SECONDS="${INTAKE_CI_POLL_SECONDS:-20}"
  # A cap on each `gh run watch`. A GitHub job can run for 360 minutes,
  # which is 120 past MAX_MINUTES.
  CI_WATCH_MINUTES="${INTAKE_CI_WATCH_MINUTES:-45}"
  CI_WATCH_SECONDS="${INTAKE_CI_WATCH_SECONDS:-$((CI_WATCH_MINUTES * 60))}"
  # How long to let the log reader drain after the builder is gone.
  LOGGER_GRACE="${INTAKE_LOGGER_GRACE:-10}"
  # What the shipped website serves, for the post-merge artifact check.
  SITE_JS="${INTAKE_SITE_JS:-https://mtg.mattshoe.org/kmp/mtg.js}"
  REPO_SLUG="${INTAKE_REPO_SLUG:-mattshoe/mtg-api}"
  SHIPPED_NOTE=""
  TRIED="$STATE/tried.$$"

  mkdir -p "$STATE" "$WT_ROOT"
  touch "$LOG"

  # The trap goes on BEFORE the lock attempt, and `$TRIED` is created after
  # it. `tried.<pid>` directories leaked forever otherwise: `mkdir -p` ran
  # before `take_lock || exit 0` and the cleanup trap was installed after
  # it, so every lost lock race left one behind.
  trap 'rm -rf "$TRIED"; drop_lock' EXIT
  take_lock || exit 0
  LOCK_HELD=true
  mkdir -p "$TRIED"

  # Whatever earlier runs leaked before that was true.
  local leaked lpid
  for leaked in "$STATE"/tried.*; do
    [ -d "$leaked" ] || continue
    lpid="${leaked##*.}"
    case "$lpid" in
      ''|*[!0-9]*) rm -rf "$leaked"; continue ;;
    esac
    [ "$lpid" = "$$" ] && continue
    kill -0 "$lpid" 2>/dev/null || { say "clearing $(basename "$leaked") left by a dead run"; rm -rf "$leaked"; }
  done

  # A hold is cleared by re-dropping the request. Without this the hold was
  # dead on arrival: the same filename could never be built again.
  local hf hname
  for hf in "$STATE"/*.held; do
    [ -f "$hf" ] || continue
    hname="$(basename "$hf" .held)"
    if [ -f "$REQUESTS/$hname.md" ] && [ "$REQUESTS/$hname.md" -nt "$hf" ]; then
      say "$hname was dropped in again; clearing its hold and its counters"
      rm -f "$hf" "$STATE/$hname.attempts" "$STATE/$hname.fix-attempts" \
            "$STATE/$hname.fix-pending" "$STATE/$hname.fixme" \
            "$STATE/$hname.triage-tries" "$STATE/$hname.withdrawn"
    fi
  done
  trap 'say "stopped by a signal"; exit 1' TERM INT

  # Stale admin entries are what make a later `worktree add` fail for a
  # branch whose tree somebody deleted by hand.
  git -C "$REPO" worktree prune >>"$LOG" 2>&1

  list_pending
  if [ -z "$PENDING" ]; then
    say "nothing pending"
    # A hold still needs saying. After a give-up the request is filed, so
    # the folder is empty and this early exit used to skip the needs-you
    # list entirely — the hold was notified exactly once, ever.
    report_held
    exit 0
  fi

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

    # Anything whose pull request is open and red gets a FIX pass, before
    # any builder is considered. These are deliberately not in `buildable`.
    fix_wave

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
      # The wave dropped the lock. Take it back before triaging again, or
      # two dispatchers end up triaging the same folder.
      if [ "$LOCK_HELD" != true ]; then
        take_lock || { say "another dispatcher has the lock; leaving the rest to it"; break; }
        LOCK_HELD=true
      fi
      say "re-reading the folder after wave $wave"
      continue
    fi
    break
  done

  report_held

  drop_lock
  LOCK_HELD=false
  wait
  say "dispatch finished"
}

main "$@"
