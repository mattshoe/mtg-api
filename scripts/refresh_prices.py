#!/usr/bin/env python3
"""Push current prices into the collection, from Scryfall's bulk file.

This runs on the Mac rather than in the Worker, and not by preference.
Scryfall rate-limits Cloudflare's shared egress IPs hard: 20 batches of 75
from a Worker and then a 429 that backing off does not clear, reproducibly,
while the same 55 batches from a home IP finish in 37 seconds. Their own
429 says to use the bulk data offering for volume — and prefetch_scryfall.py
already downloads that file three times a day for the deck tooling, so the
prices are sitting on disk with no API call needed at all.

Reads the newest default-cards bulk file, keeps the printings the
collection actually owns, and writes them through POST /query.

    python3 scripts/refresh_prices.py [--dry-run] [--api URL]
"""
import argparse
import glob
import gzip
import json
import os
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

API = os.environ.get("MTG_API", "https://mtg-api.mattshoe81.workers.dev")
CACHE = Path.home() / "Library/Caches/mtg-scryfall"
ROWS_PER_INSERT = 400   # D1 rejects an oversized statement

_token = [None]


def admin_token(api):
    if _token[0]:
        return _token[0]
    pw = os.environ.get("MTG_ADMIN_PASSWORD")
    if not pw:
        sys.exit("MTG_ADMIN_PASSWORD is not set - add it to ~/.mtg-api.env")
    req = urllib.request.Request(
        f"{api}/admin", data=json.dumps({"password": pw}).encode(),
        headers={"Content-Type": "application/json", "User-Agent": "mtg-api-scripts/1.0"})
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            _token[0] = json.load(r)["token"]
    except urllib.error.HTTPError as e:
        sys.exit(f"admin unlock failed ({e.code}) - is MTG_ADMIN_PASSWORD right?")
    return _token[0]


def query(api, sql, params=None, admin=False):
    body = json.dumps({"sql": sql, "params": params or [], "fmt": "rows"}).encode()
    headers = {"Content-Type": "application/json", "User-Agent": "mtg-api-scripts/1.0"}
    if admin:
        headers["Authorization"] = f"Bearer {admin_token(api)}"
    req = urllib.request.Request(f"{api}/query", data=body, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=120) as r:
            return json.load(r)
    except urllib.error.HTTPError as e:
        sys.exit(f"API error {e.code}: {e.read().decode()[:300]}")


def newest_bulk():
    found = sorted(glob.glob(str(CACHE / "default-cards-*.jsonl.gz")))
    if not found:
        sys.exit(f"no Scryfall bulk file in {CACHE} - run prefetch_scryfall.py first")
    return found[-1]


def q(v):
    if v is None:
        return "NULL"
    if isinstance(v, (int, float)):
        return repr(v)
    return "'" + str(v).replace("'", "''") + "'"


def money(v):
    try:
        return float(v) if v not in (None, "") else None
    except (TypeError, ValueError):
        return None


def log_run(api, ok, detail, ms):
    query(api,
          "INSERT INTO maintenance_log (ran_at, task, ok, detail, ms) VALUES (?,?,?,?,?)",
          [datetime.now(timezone.utc).isoformat(), "prices", 1 if ok else 0,
           str(detail)[:500], int(ms)],
          admin=True)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--api", default=API)
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    t0 = time.time()
    wanted = {r[0] for r in query(args.api, "SELECT DISTINCT scryfall_id FROM cards "
                                            "WHERE scryfall_id IS NOT NULL")["rows"]}
    if not wanted:
        print("nothing owned")
        return 0
    print(f"collection has {len(wanted)} distinct printings")

    path = newest_bulk()
    print(f"reading {Path(path).name}")
    found = {}
    scanned = 0
    with gzip.open(path, "rt") as fh:
        for line in fh:
            scanned += 1
            # Cheap reject before parsing: the id is in the first ~60 bytes.
            if len(found) == len(wanted):
                break
            try:
                card = json.loads(line.rstrip().rstrip(","))
            except ValueError:
                continue
            cid = card.get("id")
            if cid in wanted and cid not in found:
                p = card.get("prices") or {}
                found[cid] = (
                    money(p.get("usd")), money(p.get("usd_foil")), money(p.get("usd_etched")),
                    money(p.get("eur")), money(p.get("tix")),
                    (card.get("purchase_uris") or {}).get("tcgplayer"),
                )

    print(f"scanned {scanned} printings, matched {len(found)} of {len(wanted)}")
    missing = len(wanted) - len(found)
    if missing:
        print(f"  {missing} not in this bulk snapshot (new printings, most likely)")

    if args.dry_run:
        sample = list(found.items())[:3]
        for cid, row in sample:
            print(f"  {cid[:8]} usd={row[0]} foil={row[1]}")
        return 0

    now = datetime.now(timezone.utc).isoformat()
    values = [
        f"({q(cid)},{q(r[0])},{q(r[1])},{q(r[2])},{q(r[3])},{q(r[4])},{q(r[5])},{q(now)})"
        for cid, r in found.items()
    ]

    written = 0
    for i in range(0, len(values), ROWS_PER_INSERT):
        chunk = values[i:i + ROWS_PER_INSERT]
        query(args.api,
              "INSERT INTO prices (scryfall_id, usd, usd_foil, usd_etched, eur, tix,"
              " tcg_url, updated_at) VALUES " + ",".join(chunk) +
              " ON CONFLICT(scryfall_id) DO UPDATE SET"
              " usd = excluded.usd, usd_foil = excluded.usd_foil,"
              " usd_etched = excluded.usd_etched, eur = excluded.eur,"
              " tix = excluded.tix, tcg_url = excluded.tcg_url,"
              " updated_at = excluded.updated_at",
              admin=True)
        written += len(chunk)
        print(f"  {written}/{len(values)}", end="\r", flush=True)

    ms = (time.time() - t0) * 1000
    total = query(args.api, "SELECT ROUND(SUM(qty * price), 2) FROM card_prices "
                            "WHERE price IS NOT NULL")["rows"][0][0]
    print(f"\nwrote {written} prices in {ms / 1000:.1f}s")
    print(f"collection value: ${total:,.2f}" if total else "collection value: unknown")
    log_run(args.api, True, f"{{\"priced\":{written},\"missing\":{missing},\"source\":\"bulk\"}}", ms)
    return 0


if __name__ == "__main__":
    sys.exit(main())
