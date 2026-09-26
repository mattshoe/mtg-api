#!/bin/bash
# Nightly: back the D1 database up to this machine, then backfill tags.
#
# D1 is the source of truth now, so this is the safety net under it —
# alongside D1's own 30-day Time Travel. Installed by launchd at 03:00; see
# com.matt.mtg.apibackup.plist. Adds a job, changes nothing about the old
# mobile-queue or prefetch agents.
set -uo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BACKUPS="${MTG_BACKUP_DIR:-$HOME/mtg-backups}"
KEEP_DAYS=30
LOG="$BACKUPS/nightly.log"
API="${MTG_API:-https://mtg-api.mattshoe81.workers.dev}"

mkdir -p "$BACKUPS"
exec >> "$LOG" 2>&1
echo "=== $(date '+%Y-%m-%d %H:%M:%S') ==="

# Credentials live outside the repo; the repo gitignores *.env anyway.
if [ -f "$HOME/.mtg-api.env" ]; then
  set -a; . "$HOME/.mtg-api.env"; set +a
else
  echo "FAIL: no ~/.mtg-api.env, cannot reach Cloudflare"
  exit 1
fi

cd "$REPO" || exit 1
STAMP=$(date '+%Y%m%d')
OUT="$BACKUPS/mtg-$STAMP.sql"

# D1 allows one export at a time and a previous one can still be running
# server-side, which fails with "Currently processing a long-running export".
# Retry rather than skipping a night's backup over it.
echo "-- exporting"
EXPORTED=0
for attempt in 1 2 3 4 5 6; do
  if npx --yes wrangler d1 export mtg --remote -y --output "$OUT" > /tmp/mtg-export.$$ 2>&1; then
    EXPORTED=1
    break
  fi
  tail -3 /tmp/mtg-export.$$
  echo "-- export attempt $attempt failed, retrying in 60s"
  sleep 60
done
rm -f /tmp/mtg-export.$$

if [ "$EXPORTED" -ne 1 ]; then
  echo "FAIL: export did not succeed after 6 attempts"
  exit 1
fi

if [ ! -s "$OUT" ]; then
  echo "FAIL: export produced an empty file"
  rm -f "$OUT"
  exit 1
fi

# A dump that restores is a backup; a dump that does not is a file. Check it
# before trusting it, and use the restored copy for the row-count comparison.
CHECK="$BACKUPS/.verify-$STAMP.db"
rm -f "$CHECK"
if ! sqlite3 "$CHECK" < "$OUT" 2>&1 | tail -3; then
  echo "FAIL: dump does not restore"
  rm -f "$CHECK"
  exit 1
fi

LOCAL_CARDS=$(sqlite3 "$CHECK" "SELECT COUNT(*) FROM cards")
REMOTE_CARDS=$(curl -fsS -X POST "$API/query" \
  -H 'content-type: application/json' \
  -d '{"sql":"SELECT COUNT(*) FROM cards"}' \
  | sed -n 's/.*"rows":\[\[\([0-9]*\)\]\].*/\1/p')
rm -f "$CHECK"

echo "-- cards: backup $LOCAL_CARDS, live ${REMOTE_CARDS:-unknown}"
if [ -n "$REMOTE_CARDS" ] && [ "$LOCAL_CARDS" != "$REMOTE_CARDS" ]; then
  # Not fatal: a write between the export and this check explains a small
  # difference. Worth seeing in the log if it becomes a pattern.
  echo "WARN: row counts differ"
fi

gzip -f "$OUT"
echo "-- saved $(ls -lh "$OUT.gz" | awk '{print $5}') to $OUT.gz"

find "$BACKUPS" -name 'mtg-*.sql.gz' -mtime "+$KEEP_DAYS" -delete
echo "-- $(find "$BACKUPS" -name 'mtg-*.sql.gz' | wc -l | tr -d ' ') backups retained"

echo "-- backfilling tags"
python3 "$REPO/scripts/backfill.py" --api "$API" || echo "WARN: backfill failed"

echo "-- done"
