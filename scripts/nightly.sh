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

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BACKUPS="${MTG_BACKUP_DIR:-$HOME/mtg-backups}"
KEEP_DAYS=30
LOG="$BACKUPS/nightly.log"
API="${MTG_API:-https://mtg-api.mattshoe81.workers.dev}"

mkdir -p "$BACKUPS"
exec >> "$LOG" 2>&1
echo "=== $(date '+%Y-%m-%d %H:%M:%S') ==="

STAMP=$(date '+%Y%m%d')
OUT="$BACKUPS/mtg-$STAMP.sql.gz"

# backup.py restores its own output into a temporary SQLite file and checks
# the row counts before returning, so a non-zero exit means the dump is not
# trustworthy and should not replace yesterday's.
echo "-- backing up"
if ! python3 "$REPO/scripts/backup.py" --api "$API" --out "$OUT"; then
  echo "FAIL: backup did not verify"
  rm -f "$OUT"
  exit 1
fi

if [ ! -s "$OUT" ]; then
  echo "FAIL: backup produced an empty file"
  rm -f "$OUT"
  exit 1
fi

echo "-- saved $(ls -lh "$OUT" | awk '{print $5}') to $OUT"

find "$BACKUPS" -name 'mtg-*.sql.gz' -mtime "+$KEEP_DAYS" -delete
echo "-- $(find "$BACKUPS" -name 'mtg-*.sql.gz' | wc -l | tr -d ' ') backups retained"

echo "-- backfilling tags"
python3 "$REPO/scripts/backfill.py" --api "$API" || echo "WARN: backfill failed"

echo "-- done"
