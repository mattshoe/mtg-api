#!/bin/bash
# Nightly: back the D1 database up to this machine, then backfill tags.
#
# D1 is the source of truth now, so this is the safety net under it —
# alongside D1's own 30-day Time Travel. Installed by launchd at 03:00; see
# com.matt.mtg.apibackup.plist. Adds a job, changes nothing about the old
# mobile-queue or prefetch agents.
#
# Note it does NOT use `wrangler d1 export`: that takes the database offline
# for the duration, and on this database it ran well past ten minutes. The
# backup pages out through the API instead, so nothing is ever locked.
set -uo pipefail

# Not from inside a builder.
#
# This script sources `~/.mtg-api.env` itself — the production Cloudflare
# token and the admin password — and then writes to production with them.
# So a builder running `bash scripts/nightly.sh` reaches the real database
# whatever the deny list does about `wrangler`, and a reviewer did exactly
# that.
#
# **This guard is currently unarmed.** It fires on INTAKE_BUILDER, which
# the old dispatcher set on every agent it launched; the rewrite
# does not set it, and nothing else does. The check is kept because it
# costs nothing and works the moment something sets the variable again —
# but do not read it as protection today. The live defence is that the
# dispatcher unsets the Cloudflare variables and points XDG_CONFIG_HOME at
# an empty directory, which this script defeats by sourcing
# `~/.mtg-api.env` itself.
if [ -n "${INTAKE_BUILDER:-}" ]; then
  echo "nightly.sh: refusing to run inside an intake builder (INTAKE_BUILDER is set)." >&2
  echo "  It holds production credentials. Nothing a builder does needs them." >&2
  exit 1
fi

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BACKUPS="${MTG_BACKUP_DIR:-$HOME/mtg-backups}"
KEEP_DAYS=30
LOG="$BACKUPS/nightly.log"
API="${MTG_API:-https://mtg-api.mattshoe81.workers.dev}"

mkdir -p "$BACKUPS"
exec >> "$LOG" 2>&1
echo "=== $(date '+%Y-%m-%d %H:%M:%S') ==="

# refresh_prices.py and tags.mjs write, so they need the admin password. backup.py is
# read-only and does not.
if [ -f "$HOME/.mtg-api.env" ]; then
  set -a; . "$HOME/.mtg-api.env"; set +a
fi

STAMP=$(date '+%Y%m%d')
OUT="$BACKUPS/mtg-$STAMP.sql.gz"

# backup.py restores its own output into a temporary SQLite file and checks
# the row counts before returning, so a non-zero exit means the dump is not
# trustworthy and should not replace yesterday's.
#
# A failed backup does not stop the steps after it. It used to `exit 1`
# here, and from 30 September every backup failed verification, so prices
# went stale and no card added since was tagged. The failure is the exit
# status at the end instead.
backup_ok=1
echo "-- backing up"
if ! python3 "$REPO/scripts/backup.py" --api "$API" --out "$OUT"; then
  echo "FAIL: backup did not verify"
  rm -f "$OUT"
  backup_ok=0
elif [ ! -s "$OUT" ]; then
  echo "FAIL: backup produced an empty file"
  rm -f "$OUT"
  backup_ok=0
else
  echo "-- saved $(ls -lh "$OUT" | awk '{print $5}') to $OUT"
  find "$BACKUPS" -name 'mtg-*.sql.gz' -mtime "+$KEEP_DAYS" -delete
  echo "-- $(find "$BACKUPS" -name 'mtg-*.sql.gz' | wc -l | tr -d ' ') backups retained"
fi

# Prices come from the Scryfall bulk file prefetch_scryfall.py already
# keeps on disk — no API calls, and the Worker cannot do it because
# Scryfall rate-limits Cloudflare's egress IPs. See refresh_prices.py.
echo "-- refreshing prices"
python3 "$REPO/scripts/refresh_prices.py" --api "$API" || echo "WARN: price refresh failed"

# Tags, the tag tree `otag:` searches, and the cards nobody has tagged
# yet. See tags.mjs.
echo "-- tagging"
node "$REPO/scripts/tags.mjs" --api "$API" || echo "WARN: tagging failed"

echo "-- done"
[ "$backup_ok" = 1 ] || exit 1
