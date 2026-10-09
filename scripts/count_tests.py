#!/usr/bin/env python3
"""Count every automated test, from the JUnit XML each runner leaves behind.

Run it over a build tree or over a directory of downloaded CI artifacts:

    python3 scripts/count_tests.py apps
    python3 scripts/count_tests.py results

Exits non-zero if anything failed, so it can be the last step of a
workflow and mean something.
"""
import re
import sys
import xml.etree.ElementTree as ET
from collections import defaultdict
from pathlib import Path

MODULES = ("core", "core-net", "webApp", "androidApp", "sender")

# What to call each runner in the table. Anything not listed is still
# counted, under the raw task name, so a new source of tests shows up
# rather than being silently dropped.
PRETTY = {
    ("core", "jvmTest"): "core (JVM)",
    ("core", "jsNodeTest"): "core (JS)",
    ("core-net", "jvmTest"): "core-net (JVM)",
    ("core-net", "jsNodeTest"): "core-net (JS)",
    ("webApp", "jsBrowserTest"): "web (Chrome)",
    ("androidApp", "connected"): "android (device)",
    ("app", "connected"): "share app (device)",
}


def label_for(path: Path) -> str:
    """Module and runner, from the path. Never from the file name —
    that counted every class as its own suite and tripled the total."""
    parts = path.parts
    module = next((p for p in parts if p in MODULES), None)
    runner = None
    for marker in ("test-results", "androidTest-results"):
        if marker in parts:
            i = parts.index(marker)
            if i + 1 < len(parts):
                runner = parts[i + 1]
            break
    if module and runner:
        return PRETTY.get((module, runner), f"{module} ({runner})")
    # A CI artifact download flattens the tree; the artifact directory
    # name is then the most specific thing there is.
    for part in parts:
        if part.endswith("-test-results"):
            return part.removesuffix("-test-results")
    return module or "other"


def main() -> int:
    root = Path(sys.argv[1] if len(sys.argv) > 1 else ".")
    if not root.exists():
        sys.exit(f"no such directory: {root}")

    counts = defaultdict(lambda: [0, 0, 0.0])
    classes = defaultdict(int)
    # One test can run on several targets — :core's commonTest is
    # executed by the JVM, by Node, and twice more by the Android
    # library's unit-test tasks. Those are real executions and worth
    # counting, but "how many tests are there" is the distinct set.
    distinct = set()
    for xml in root.rglob("*.xml"):
        try:
            suite = ET.parse(xml).getroot()
        except ET.ParseError:
            continue
        if suite.tag != "testsuite":
            continue
        label = label_for(xml)
        counts[label][0] += int(suite.get("tests", 0))
        counts[label][1] += int(suite.get("failures", 0)) + int(suite.get("errors", 0))
        counts[label][2] += float(suite.get("time", 0) or 0)
        classes[label] += 1
        for case in suite.iter("testcase"):
            name = re.sub(r"\[.*?\]$", "", case.get("name", ""))
            cls = re.sub(r"\[.*?\]$", "", case.get("classname", ""))
            distinct.add(f"{cls}.{name}")

    if not counts:
        sys.exit(f"found no JUnit XML under {root} — did the tests run?")

    total = failed = 0
    seconds = 0.0
    print(f"{'suite':<22}{'tests':>7}{'failing':>9}{'classes':>9}{'seconds':>9}")
    print("-" * 56)
    for label, (n, f, t) in sorted(counts.items()):
        print(f"{label:<22}{n:>7}{f:>9}{classes[label]:>9}{t:>9.1f}")
        total += n
        failed += f
        seconds += t
    print("-" * 56)
    print(f"{'executions':<22}{total:>7}{failed:>9}{sum(classes.values()):>9}{seconds:>9.1f}")
    print(f"{'distinct tests':<22}{len(distinct):>7}")

    if failed:
        print(f"\n{failed} failing.")
        return 1
    print("\nAll green.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
