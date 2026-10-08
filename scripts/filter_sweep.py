#!/usr/bin/env python3
"""Run every filter against the real collection and report what happened.

`FilterSweepDump` in :core writes one query per filter to
`apps/core/build/filter-sweep.json` — the panel's whole surface, one
case at a time and a couple of combinations. This posts each of them at
`/query` and prints the row count, so a filter that errors, or that
matches everything, or that matches nothing, says so out loud.

Not a test: the suite never touches the network. This is the thing that
proves the SQL those tests assert on is SQL the database accepts.

    python3 scripts/filter_sweep.py [--base URL]
"""
import argparse
import json
import sys
import urllib.error
import urllib.request
from pathlib import Path

BASE = "https://mtg-api.mattshoe81.workers.dev"
DUMP = Path(__file__).resolve().parent.parent / "apps/core/build/filter-sweep.json"


def ask(base, sql, params):
    body = json.dumps({"sql": sql, "params": params}).encode()
    # Cloudflare answers 403 "error code: 1010" to urllib's default
    # user agent before the Worker ever sees the request.
    req = urllib.request.Request(
        f"{base}/query",
        data=body,
        headers={
            "content-type": "application/json",
            "user-agent": "mtg-filter-sweep/1.0 (+https://mtg.mattshoe.org)",
        },
    )
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            return json.load(r), None
    except urllib.error.HTTPError as e:
        detail = e.read().decode()[:300]
        try:
            detail = json.loads(detail).get("error", detail)
        except Exception:
            pass
        return None, f"HTTP {e.code}: {detail}"
    except Exception as e:
        return None, str(e)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default=BASE)
    args = ap.parse_args()

    if not DUMP.exists():
        sys.exit(f"missing {DUMP} — run :core:jvmTest --tests '*FilterSweepDump*' first")
    cases = json.loads(DUMP.read_text())

    total, _ = ask(args.base, "SELECT COUNT(*) FROM (SELECT 1 FROM cards GROUP BY owner_id, name_norm)", [])
    everything = total["rows"][0][0] if total else None
    print(f"{len(cases)} filters against {everything} grouped rows\n")

    broken, empty, wide = [], [], []
    for case in cases:
        res, err = ask(args.base, case["count"], case["params"])
        if err:
            broken.append((case["name"], err))
            print(f"  BROKEN  {case['name']:<34} {err}")
            continue
        n = res["rows"][0][0]
        mark = " "
        if n == 0:
            empty.append(case["name"])
            mark = "!"
        elif everything and n == everything and case["name"] not in ("no filters",):
            wide.append(case["name"])
            mark = "?"
        print(f"  {mark}       {case['name']:<34} {n}")

    print()
    print(f"{len(broken)} errored, {len(empty)} matched nothing, {len(wide)} matched everything")
    if empty:
        print("  matched nothing: " + ", ".join(empty))
    if wide:
        print("  matched everything: " + ", ".join(wide))
    return 1 if broken else 0


if __name__ == "__main__":
    sys.exit(main())
