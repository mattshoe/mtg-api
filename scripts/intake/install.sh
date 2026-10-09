#!/bin/bash
# Loads the request watcher. Run once; it survives reboots after that.
#
# The plist is a template. It used to be a literal file with four
# hardcoded paths pointing straight at a script inside a mutable working
# tree on a feature branch, so a checkout, a `git clean -xfd` or the repo
# moving left the watcher loaded and pointing at nothing — while
# `status.sh` only tailed intake.log and the dashboard showed a healthy
# idle queue with every request quietly lost.
#
# So two things are installed outside the tree: the plist with this
# repo's path substituted in, and a launcher that knows where the repo is
# and writes a loud line to a log outside the tree when the dispatcher is
# not where it should be.
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
LABEL="com.matt.mtg.intake"
PLIST="$HOME/Library/LaunchAgents/$LABEL.plist"
SUPPORT="$HOME/Library/Application Support/mtg-intake"
RUNNER="$SUPPORT/run-intake.sh"
# Outside the tree on purpose: a log inside .intake/ disappears with the
# checkout it was recording the loss of.
LOGDIR="$SUPPORT/logs"

mkdir -p "$SUPPORT" "$LOGDIR" "$HOME/Library/LaunchAgents" "$REPO/.intake"

cat > "$RUNNER" <<RUNNER_EOF
#!/bin/bash
# Written by scripts/intake/install.sh. Do not edit; reinstall instead.
#
# launchd points here rather than at the working tree, so a missing
# dispatcher is a logged fact instead of a watcher firing into nothing.
REPO="$REPO"
LOGDIR="$LOGDIR"
DISPATCH="\$REPO/scripts/intake/dispatch.sh"
if [ ! -x "\$DISPATCH" ]; then
  printf '%s  NO DISPATCHER at %s — wrong branch, a git clean, or the repo moved\n' \\
    "\$(date '+%Y-%m-%d %H:%M:%S')" "\$DISPATCH" >> "\$LOGDIR/launchd.log"
  exit 0
fi
# Start a dispatcher and get out of the way. launchd will not run a second
# copy of a job while the first is alive, and a dispatcher stays alive for
# the whole build — so exec here meant StartInterval could not fire again
# until the current task finished. One task at a time, and anything Matt
# submitted from the app waited behind it. Observed: a dispatcher 22
# minutes old while a freshly collected request sat at \`pending\`.
nohup /bin/bash "\$DISPATCH" >> "\$LOGDIR/launchd.log" 2>&1 &
exit 0
RUNNER_EOF
chmod +x "$RUNNER"

# The PATH launchd gives a job is almost nothing, and `node` not being on
# it is one of the ways the dispatcher used to exit 0 with a full queue.
NODE_DIR="$(dirname "$(command -v node 2>/dev/null || echo /opt/homebrew/bin/node)")"
JOB_PATH="$HOME/.local/bin:$NODE_DIR:/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin"

sed -e "s|@REPO@|$REPO|g" \
    -e "s|@RUNNER@|$RUNNER|g" \
    -e "s|@LOGDIR@|$LOGDIR|g" \
    -e "s|@PATH@|$JOB_PATH|g" \
    "$REPO/scripts/intake/$LABEL.plist" > "$PLIST"

if /usr/bin/grep -q '@[A-Z]*@' "$PLIST"; then
  echo "install: a placeholder survived substitution in $PLIST" >&2
  /usr/bin/grep -n '@[A-Z]*@' "$PLIST" >&2
  exit 1
fi

# `.intake/enabled` is what arms the machine, and installing is the one moment
# it should go away.
rm -f "$REPO/.intake/disabled"
touch "$REPO/.intake/enabled"

launchctl bootout "gui/$(id -u)/$LABEL" 2>/dev/null || true
# `launchctl load` is the legacy call and fails quietly in a user domain —
# after one reinstall the job was simply gone, and the timer with it. Use
# bootstrap, and say so if the job is not listed afterwards.
launchctl bootstrap "gui/$(id -u)" "$PLIST" 2>/dev/null || launchctl load "$PLIST" 2>/dev/null || true
launchctl list | grep -q "$LABEL" || echo "  WARNING: $LABEL did not load"

# The second job: tasks written in the app, collected from the Worker's
# inbox into requests/ every five minutes. See scripts/intake/inbox.mjs.
INBOX_LABEL="com.matt.mtg.intake-inbox"
INBOX_PLIST="$HOME/Library/LaunchAgents/$INBOX_LABEL.plist"
sed -e "s|@REPO@|$REPO|g" \
    -e "s|@NODE@|$NODE_DIR/node|g" \
    -e "s|@LOGDIR@|$LOGDIR|g" \
    -e "s|@PATH@|$JOB_PATH|g" \
    "$REPO/scripts/intake/$INBOX_LABEL.plist" > "$INBOX_PLIST"
launchctl bootout "gui/$(id -u)/$INBOX_LABEL" 2>/dev/null || true
launchctl load "$INBOX_PLIST"

echo "loaded $LABEL"
echo "  watching  $REPO/requests"
echo "  runner    $RUNNER"
echo "  inbox     $INBOX_LABEL, every five minutes"
echo "  logs      $LOGDIR/launchd.log and $REPO/.intake/intake.log"
echo "  off       bash $REPO/scripts/intake/uninstall.sh"
