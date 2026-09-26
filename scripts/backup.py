#!/usr/bin/env python3
"""Dump the live database to a local .sql file, over the API.

`wrangler d1 export` is the obvious tool and the wrong one here: it takes the
database offline for the duration ("your D1 database will be unavailable to
serve queries"), and on this database that ran for well over ten minutes. A
nightly backup should not cost a nightly outage.

So this pages every table out through /query instead. Read-only, no lock, no
wrangler, no credentials — just HTTP. The output restores into plain SQLite:

    gunzip -c mtg-20260926.sql.gz | sqlite3 restored.db

    python3 scripts/backup.py [--out FILE] [--api URL]
"""
import argparse
import gzip
import json
import os
import sqlite3
import sys
import tempfile
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

API = os.environ.get("MTG_API", "https://mtg-api.mattshoe81.workers.dev")
PAGE = 2000          # rows per request; keeps each response comfortably small
RETRIES = 5

# Views are derived and rebuilt by schema.sql, so only real tables are dumped.
# card_search is rebuilt from cards at the end, exactly as the old builder did.
# A hardcoded list silently misses a table added later, so this is checked
# against the live schema at run time — see verify_covers_everything().
TABLES = [
    "cards", "card_faces", "card_colors", "card_types", "card_keywords",
    "card_finishes", "card_games", "card_promo_types", "card_frame_effects",
    "aliases", "decks", "deck_cards", "deck_notes",
    "card_tags", "tags", "legalities", "rulings",
    "prices", "maintenance_log", "logs",
]

# Rebuilt on restore rather than dumped.
DERIVED = {"card_search"}


def verify_covers_everything(api):
    """Fail loudly if the database grew a table this script does not know."""
    live = {r[0] for r in query(api,
            "SELECT name FROM sqlite_master WHERE type='table' "
            "AND name NOT LIKE 'sqlite_%' AND name NOT LIKE '_cf_%' "
            "AND name NOT LIKE 'd1_%' AND name NOT LIKE 'card_search_%'")["rows"]}
    unknown = live - set(TABLES) - DERIVED
    if unknown:
        sys.exit(f"backup would miss {sorted(unknown)} - add them to TABLES")


def query(api, sql, params=None):
    body = json.dumps({"sql": sql, "params": params or [], "limit": 50000}).encode()
    last = None
    for attempt in range(RETRIES):
        req = urllib.request.Request(
            f"{api}/query", data=body,
            # Cloudflare's bot protection answers urllib's default
            # User-Agent with a 403 (error 1010), so say who we are.
            headers={"Content-Type": "application/json",
                     "User-Agent": "mtg-api-backup/1.0"})
        try:
            with urllib.request.urlopen(req, timeout=120) as r:
                return json.load(r)
        except (urllib.error.HTTPError, urllib.error.URLError, TimeoutError) as e:
            last = e
            detail = e.read().decode()[:200] if isinstance(e, urllib.error.HTTPError) else str(e)
            if attempt < RETRIES - 1:
                print(f"  retry {attempt + 1}: {detail}", file=sys.stderr)
                time.sleep(5 * (attempt + 1))
    sys.exit(f"API unreachable after {RETRIES} attempts: {last}")


def quote(v):
    if v is None:
        return "NULL"
    if isinstance(v, bool):
        return "1" if v else "0"
    if isinstance(v, (int, float)):
        return repr(v)
    return "'" + str(v).replace("'", "''") + "'"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--api", default=API)
    ap.add_argument("--out", default=None, help="default: mtg-<date>.sql.gz here")
    ap.add_argument("--no-verify", action="store_true",
                    help="skip restoring the dump to prove it works")
    args = ap.parse_args()

    stamp = datetime.now(timezone.utc).strftime("%Y%m%d")
    out = Path(args.out) if args.out else Path(f"mtg-{stamp}.sql.gz")

    schema = Path(__file__).resolve().parent.parent / "schema.sql"
    if not schema.exists():
        sys.exit(f"missing {schema}")

    verify_covers_everything(args.api)

    opener = gzip.open if out.name.endswith(".gz") else open
    total = 0

    with opener(out, "wt") as f:
        f.write(f"-- mtg collection backup, {datetime.now(timezone.utc).isoformat()}\n")
        f.write(f"-- from {args.api}\n")
        f.write("-- restore: gunzip -c this.sql.gz | sqlite3 restored.db\n\n")
        f.write("PRAGMA foreign_keys=OFF;\nBEGIN TRANSACTION;\n\n")
        f.write(schema.read_text())
        f.write("\n")

        for table in TABLES:
            offset = 0
            n = 0
            cols = None
            while True:
                # ORDER BY rowid so paging is stable across requests.
                res = query(args.api,
                            f"SELECT * FROM {table} ORDER BY rowid "
                            f"LIMIT {PAGE} OFFSET {offset}")
                rows = res["rows"]
                if cols is None:
                    cols = res["cols"]
                if not rows:
                    break
                collist = ",".join(cols)
                values = ",\n".join("(" + ",".join(quote(v) for v in r) + ")" for r in rows)
                f.write(f"INSERT INTO {table} ({collist}) VALUES\n{values};\n")
                n += len(rows)
                offset += PAGE
                if len(rows) < PAGE:
                    break
            f.write("\n")
            total += n
            print(f"  {table}: {n}")

        f.write("-- Full-text index, rebuilt from the rows above.\n")
        f.write(
            "INSERT INTO card_search (rowid, name, type_line, oracle_text,"
            " flavor_text, keywords, tags)\n"
            "SELECT c.id, c.name, c.type_line, c.oracle_text, c.flavor_text,\n"
            "       (SELECT GROUP_CONCAT(keyword,' ') FROM card_keywords k"
            " WHERE k.card_id = c.id),\n"
            "       (SELECT GROUP_CONCAT(tag_slug,' ') FROM card_tags t"
            " WHERE t.card_id = c.id)\n"
            "FROM cards c;\n\n"
        )
        f.write("COMMIT;\n")

    print(f"\n{out}: {total} rows, {out.stat().st_size / 1e6:.1f} MB")

    if args.no_verify:
        return 0

    # A dump that restores is a backup; a dump that does not is a file.
    # Done here in Python rather than by shelling out, because the sqlite3
    # that wins $PATH on this machine is Android platform-tools and has no
    # FTS5, which would fail the restore for the wrong reason.
    print("verifying the dump restores")
    with tempfile.TemporaryDirectory() as tmp:
        path = Path(tmp) / "restore.db"
        db = sqlite3.connect(path)
        try:
            opener2 = gzip.open if out.name.endswith(".gz") else open
            with opener2(out, "rt") as f:
                db.executescript(f.read())
        except sqlite3.Error as e:
            print(f"FAIL: dump does not restore: {e}", file=sys.stderr)
            return 1

        counts = {}
        for t in TABLES + list(DERIVED):
            counts[t] = db.execute(f"SELECT COUNT(*) FROM {t}").fetchone()[0]

        if counts["cards"] == 0:
            print("FAIL: restored backup has no cards", file=sys.stderr)
            return 1
        if counts["card_search"] != counts["cards"]:
            print(f"FAIL: {counts['card_search']} search rows for "
                  f"{counts['cards']} cards", file=sys.stderr)
            return 1

        # The views come from schema.sql and must execute against real rows.
        for v in ["totals", "card_usage", "bulk_cards", "deck_gaps",
                  "deck_conflicts", "decks_not_built"]:
            db.execute(f"SELECT * FROM {v} LIMIT 1").fetchone()

        live = query(args.api, "SELECT COUNT(*) FROM cards")["rows"][0][0]
        print(f"restored: {counts['cards']} cards, {counts['card_search']} "
              f"search rows; live has {live}")
        if live != counts["cards"]:
            # Not fatal: a write between the dump and this check explains it.
            print("WARN: live row count moved during the backup")

    return 0


if __name__ == "__main__":
    sys.exit(main())
