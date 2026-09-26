#!/usr/bin/env python3
"""Fill in what the API cannot fetch on its own: Scryfall Tagger tags.

Tags come from a bulk file with no per-card endpoint, so a card added through
the API arrives untagged. This reads the local Scryfall index that
prefetch_scryfall.py already keeps warm, finds oracle ids in D1 with no tags,
and pushes the missing rows through /query.

Read-only against the index. Never touches the collection shards.

    python3 scripts/backfill.py [--dry-run] [--api URL]
"""
import argparse
import glob
import json
import os
import sqlite3
import sys
import urllib.error
import urllib.request
from pathlib import Path

API = os.environ.get("MTG_API", "https://mtg-api.mattshoe81.workers.dev")
CACHE = Path.home() / "Library/Caches/mtg-scryfall"
BATCH = 400   # rows per INSERT; D1 rejects an oversized statement

# This script writes, so it needs an admin token. The password lives in
# ~/.mtg-api.env alongside the Cloudflare credentials, never in the repo.
_token = [None]


def admin_token(api):
    if _token[0]:
        return _token[0]
    pw = os.environ.get("MTG_ADMIN_PASSWORD")
    if not pw:
        sys.exit("MTG_ADMIN_PASSWORD is not set - add it to ~/.mtg-api.env")
    req = urllib.request.Request(
        f"{api}/admin", data=json.dumps({"password": pw}).encode(),
        headers={"Content-Type": "application/json",
                 "User-Agent": "mtg-api-scripts/1.0"})
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            _token[0] = json.load(r)["token"]
    except urllib.error.HTTPError as e:
        sys.exit(f"admin unlock failed ({e.code}) - is MTG_ADMIN_PASSWORD right?")
    return _token[0]


def query(api, sql, params=None, fmt="rows"):
    body = json.dumps({"sql": sql, "params": params or [], "fmt": fmt}).encode()
    req = urllib.request.Request(
        f"{api}/query", data=body,
        # Cloudflare's bot protection answers urllib's default User-Agent
        # with a 403 (error 1010), so say who we are.
        headers={"Content-Type": "application/json",
                 "User-Agent": "mtg-api-scripts/1.0",
                 "Authorization": f"Bearer {admin_token(api)}"})
    try:
        with urllib.request.urlopen(req, timeout=120) as r:
            return json.load(r)
    except urllib.error.HTTPError as e:
        detail = e.read().decode()[:400]
        sys.exit(f"API error {e.code}: {detail}")


def newest_index():
    found = sorted(glob.glob(str(CACHE / "index-v*-*.db")))
    if not found:
        sys.exit(f"no Scryfall index in {CACHE} - run prefetch_scryfall.py first")
    return found[-1]


def quote(v):
    if v is None:
        return "NULL"
    return "'" + str(v).replace("'", "''") + "'"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--api", default=API)
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    idx = sqlite3.connect(f"file:{newest_index()}?mode=ro", uri=True)

    # Cards in the collection whose oracle card has no tags at all. A card
    # that genuinely has no tags will be retried each night and cost one
    # lookup; that is cheaper than tracking which ones we have checked.
    rows = query(args.api, """
        SELECT DISTINCT c.oracle_id, c.id
          FROM cards c
         WHERE c.oracle_id IS NOT NULL
           AND NOT EXISTS (SELECT 1 FROM card_tags t WHERE t.card_id = c.id)
    """)["rows"]

    if not rows:
        print("tags: nothing to backfill")
        return

    print(f"tags: {len(rows)} card rows with no tags")

    # slug -> label/description, so a new slug arrives with its metadata.
    known = {r[0] for r in query(args.api, "SELECT slug FROM tags")["rows"]}

    tag_values = []
    new_tags = {}
    for oracle_id, card_id in rows:
        slugs = [r[0] for r in idx.execute(
            "SELECT slug FROM tagging WHERE oracle_id = ?", (oracle_id,))]
        for slug in slugs:
            tag_values.append(f"({card_id},{quote(slug)},'oracle')")
            if slug not in known and slug not in new_tags:
                meta = idx.execute("SELECT json FROM tag WHERE slug = ?", (slug,)).fetchone()
                if meta:
                    try:
                        d = json.loads(meta[0])
                    except (ValueError, TypeError):
                        d = {}
                    new_tags[slug] = (d.get("category") or "oracle",
                                      d.get("name") or slug, d.get("description"))

    if not tag_values:
        print("tags: index has nothing for these cards")
        return

    print(f"tags: {len(tag_values)} card_tags rows, {len(new_tags)} new slugs")
    if args.dry_run:
        return

    for slug, (kind, label, desc) in new_tags.items():
        query(args.api,
              "INSERT INTO tags (slug, kind, label, description) VALUES (?,?,?,?)",
              [slug, kind, label, desc])

    written = 0
    for i in range(0, len(tag_values), BATCH):
        chunk = tag_values[i:i + BATCH]
        query(args.api,
              "INSERT OR IGNORE INTO card_tags (card_id, tag_slug, kind) VALUES "
              + ",".join(chunk))
        written += len(chunk)
        print(f"  {written}/{len(tag_values)}", end="\r", flush=True)

    print(f"\ntags: wrote {written} rows")

    # The FTS row for a freshly tagged card was written before its tags
    # existed, so refresh the tags column for exactly those cards.
    ids = sorted({cid for _, cid in rows})
    for i in range(0, len(ids), BATCH):
        chunk = ids[i:i + BATCH]
        inlist = ",".join(str(c) for c in chunk)
        query(args.api, f"DELETE FROM card_search WHERE rowid IN ({inlist})")
        query(args.api, f"""
            INSERT INTO card_search (rowid, name, type_line, oracle_text,
                                     flavor_text, keywords, tags)
            SELECT c.id, c.name, c.type_line, c.oracle_text, c.flavor_text,
                   (SELECT GROUP_CONCAT(keyword,' ') FROM card_keywords k
                     WHERE k.card_id = c.id),
                   (SELECT GROUP_CONCAT(tag_slug,' ') FROM card_tags t
                     WHERE t.card_id = c.id)
              FROM cards c WHERE c.id IN ({inlist})""")
    print(f"search: reindexed {len(ids)} cards")


if __name__ == "__main__":
    main()
