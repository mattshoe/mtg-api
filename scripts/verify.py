#!/usr/bin/env python3
"""SPENT — the migration parity check, kept for the record.

Compare the live API against the old collection shards, row for row.

It was meaningful on migration day. The shards are frozen and the API has
taken every write since, so every difference it reports now is expected and
none of them are informative.

The unit suite proves the Worker is correct against a fixture. This proves
the migration landed the real data. Read-only on both sides, safe to run any
time — though the two will legitimately diverge once writes start going to
the API, so treat a difference as information rather than as a failure.

    python3 scripts/verify.py [--api URL]
"""
import argparse
import json
import sqlite3
import sys
import urllib.error
import urllib.request
from pathlib import Path

API = "https://mtg-api.mattshoe81.workers.dev"
MTG = Path.home() / ("Library/CloudStorage/GoogleDrive-mattshoe81@gmail.com/"
                     "My Drive/claude-sandbox/mtg")
SHARDS = ["core", "text", "tags", "legal", "rulings"]

# table -> the shard prefix it lived under, since local SQL still needs it.
TABLES = {
    "cards": "core", "card_faces": "core", "card_colors": "core",
    "card_types": "core", "card_keywords": "core", "card_finishes": "core",
    "card_games": "core", "card_promo_types": "core",
    "card_frame_effects": "core", "aliases": "core", "decks": "core",
    "deck_cards": "core", "deck_notes": "core", "totals": "core",
    "card_usage": "core", "bulk_cards": "core", "deck_gaps": "core",
    "decks_not_built": "core", "deck_conflicts": "core",
    "card_search": "text", "card_tags": "tags", "tags": "tags",
    "legalities": "legal", "rulings": "rulings",
}


def remote(api, sql):
    body = json.dumps({"sql": sql}).encode()
    req = urllib.request.Request(
        f"{api}/query", data=body,
        # Cloudflare's bot protection answers urllib's default User-Agent
        # with a 403 (error 1010), so say who we are.
        headers={"Content-Type": "application/json",
                 "User-Agent": "mtg-api-scripts/1.0"})
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            return json.load(r)["rows"]
    except urllib.error.HTTPError as e:
        sys.exit(f"API error {e.code}: {e.read().decode()[:300]}")


def local_db():
    if not MTG.exists():
        sys.exit(f"collection not found at {MTG}")
    db = sqlite3.connect(":memory:")
    for shard in SHARDS:
        p = MTG / f"collection-{shard}.db"
        db.execute(f"ATTACH DATABASE ? AS {shard}", (f"file:{p}?mode=ro&immutable=1",))
    return db


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--api", default=API)
    args = ap.parse_args()

    db = local_db()
    failures = 0

    print(f"{'table':>20}  {'shards':>8}  {'api':>8}")
    print(f"{'-' * 20}  {'-' * 8}  {'-' * 8}")

    for table, shard in sorted(TABLES.items()):
        want = db.execute(f"SELECT COUNT(*) FROM {shard}.{table}").fetchone()[0]
        got = remote(args.api, f"SELECT COUNT(*) FROM {table}")[0][0]
        mark = "" if want == got else "   <-- DIFFERS"
        if want != got:
            failures += 1
        print(f"{table:>20}  {want:>8}  {got:>8}{mark}")

    # Row counts can match while the contents do not. Spot-check the numbers
    # that summarise the whole collection.
    print()
    checks = [
        ("physical cards", "SELECT SUM(qty) FROM cards", "SELECT SUM(qty) FROM core.cards"),
        ("cards free", "SELECT SUM(free) FROM bulk_cards", "SELECT SUM(free) FROM core.bulk_cards"),
        ("matt's uniques", "SELECT COUNT(*) FROM totals WHERE owner='matt'",
         "SELECT COUNT(*) FROM core.totals WHERE owner='matt'"),
        ("kayla's uniques", "SELECT COUNT(*) FROM totals WHERE owner='kayla'",
         "SELECT COUNT(*) FROM core.totals WHERE owner='kayla'"),
        ("distinct oracle ids", "SELECT COUNT(DISTINCT oracle_id) FROM cards",
         "SELECT COUNT(DISTINCT oracle_id) FROM core.cards"),
    ]
    for label, rsql, lsql in checks:
        want = db.execute(lsql).fetchone()[0]
        got = remote(args.api, rsql)[0][0]
        mark = "" if want == got else "   <-- DIFFERS"
        if want != got:
            failures += 1
        print(f"{label:>20}  {want:>8}  {got:>8}{mark}")

    # Orphans would not show up in a count comparison at all.
    print()
    for child in ["card_colors", "card_types", "card_faces", "card_keywords",
                  "card_finishes", "card_games", "card_promo_types",
                  "card_frame_effects", "card_tags"]:
        n = remote(args.api, f"SELECT COUNT(*) FROM {child} WHERE card_id NOT IN (SELECT id FROM cards)")[0][0]
        if n:
            failures += 1
            print(f"{child:>20}  {n} orphaned rows   <-- DIFFERS")
    n = remote(args.api, "SELECT COUNT(*) FROM card_search WHERE rowid NOT IN (SELECT id FROM cards)")[0][0]
    if n:
        failures += 1
        print(f"{'card_search':>20}  {n} orphaned rows   <-- DIFFERS")
    if not failures:
        print("no orphaned child rows")

    print()
    if failures:
        print(f"{failures} difference(s). Expected once writes go to the API only;"
              " investigate if the migration is supposed to be fresh.")
        sys.exit(1)
    print("API and shards agree on every table.")


if __name__ == "__main__":
    main()
