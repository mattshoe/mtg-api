#!/usr/bin/env python3
"""The deployed API, asked whether it works.

Deployed is not the same as working: a Worker uploads happily with a
missing table under it. This runs the handful of reads the app makes on
every page and fails the deploy if any of them does not come back.

Reads only. Nothing here writes to the collection.
"""
import json
import sys
import time
import urllib.error
import urllib.request

BASE = "https://mtg-api.mattshoe81.workers.dev"
# Cloudflare answers 403 to urllib's default user agent.
HEADERS = {"content-type": "application/json", "user-agent": "mtg-smoke/1.0"}

# Slow enough to matter, generous enough not to fail on a cold isolate.
SLOW_SECONDS = 5.0


def query(sql, params=None):
    body = json.dumps({"sql": sql, "params": params or []}).encode()
    req = urllib.request.Request(f"{BASE}/query", body, HEADERS)
    started = time.time()
    try:
        out = json.loads(urllib.request.urlopen(req, timeout=30).read())
    except urllib.error.HTTPError as e:
        out = {"error": e.read().decode()[:200]}
    except Exception as e:  # noqa: BLE001 - any failure is a failed smoke test
        out = {"error": str(e)[:200]}
    return time.time() - started, out


CHECKS = [
    ("the collection is there", "SELECT COUNT(*) AS n FROM cards", 1),
    ("decks load", "SELECT COUNT(*) AS n FROM decks", 1),
    ("the totals view works", "SELECT COUNT(*) AS n FROM totals", 1),
    ("card_usage works", "SELECT COUNT(*) AS n FROM card_usage", 1),
    ("prices are joined", "SELECT COUNT(*) AS n FROM card_prices", 1),
    ("full text search works", "SELECT COUNT(*) AS n FROM card_search WHERE card_search MATCH 'draw'", 1),
    # Not the log: reading it needs an admin token, and a smoke test
    # that carries one is a credential in CI for no gain.
    # The table the deployed code needs and a hand-applied schema once
    # nearly shipped without.
    ("idempotency is available", "SELECT COUNT(*) AS n FROM idempotency", 0),
]


def main():
    failed = []
    for name, sql, least in CHECKS:
        took, out = query(sql)
        if "error" in out:
            print(f"  FAIL  {name}: {out['error']}")
            failed.append(name)
            continue
        n = out["rows"][0][0] if out.get("rows") else None
        if n is None or n < least:
            print(f"  FAIL  {name}: got {n}, wanted at least {least}")
            failed.append(name)
            continue
        flag = "  SLOW" if took > SLOW_SECONDS else "  ok  "
        print(f"{flag}  {name}: {n} in {took:.2f}s")
        if took > SLOW_SECONDS:
            failed.append(f"{name} (took {took:.1f}s)")

    # And the endpoint the whole app is built on, with a real statement.
    took, out = query("SELECT name FROM cards WHERE name_norm LIKE ? ESCAPE '\\' LIMIT 5", ["%bolt%"])
    if "error" in out or not out.get("rows"):
        print(f"  FAIL  a real search: {out.get('error', 'no rows')}")
        failed.append("a real search")
    else:
        print(f"  ok    a real search: {out['n']} rows in {took:.2f}s")

    if failed:
        print(f"\n{len(failed)} check(s) failed: {', '.join(failed)}")
        return 1
    print("\nthe API is up")
    return 0


if __name__ == "__main__":
    sys.exit(main())
