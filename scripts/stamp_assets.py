#!/usr/bin/env python3
"""Stamp frontend asset URLs with a version, so a deploy actually lands.

GitHub Pages serves every file with `Cache-Control: max-age=600` and gives
us no way to change that. Without a version in the URL an open browser
keeps running the old modules for ten minutes after a deploy, and worse,
each file expires on its own clock — so it can end up running new
index.html against old search.js, or the reverse.

This rewrites, in place, the copy of the frontend that is about to be
uploaded:

  - every ES module gets an import-map entry pointing at `<path>?v=<ver>`,
    which covers the relative imports inside the modules themselves
  - the entry <script> and the stylesheet get the same query

index.html is still cached for ten minutes, but it is now the only thing
that is: whichever copy of it a browser holds pins one coherent set of
everything else, and when it does refresh, all of it refreshes at once.

    python3 scripts/stamp_assets.py <dir> <version>
"""
import json
import re
import sys
from pathlib import Path


def main():
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    root = Path(sys.argv[1])
    ver = re.sub(r"[^A-Za-z0-9._-]", "", sys.argv[2])[:40]
    if not ver:
        sys.exit("empty version")

    index = root / "index.html"
    if not index.exists():
        sys.exit(f"missing {index}")

    modules = sorted(p.relative_to(root).as_posix() for p in root.glob("js/*.js"))
    if not modules:
        sys.exit("found no modules to stamp")

    # URL-like keys are matched after the specifier resolves, so mapping the
    # absolute path catches './util.js' imported from inside js/ as well as
    # the entry point referenced from index.html.
    imports = {f"/{m}": f"/{m}?v={ver}" for m in modules}
    imports.update({f"./{m}": f"./{m}?v={ver}" for m in modules})

    html = index.read_text()
    before = html

    html = html.replace(
        '<script type="importmap">{"imports":{}}</script>',
        '<script type="importmap">'
        + json.dumps({"imports": imports}, separators=(",", ":"))
        + "</script>",
    )
    html = html.replace('src="js/app.js"', f'src="js/app.js?v={ver}"')
    html = html.replace('href="css/app.css"', f'href="css/app.css?v={ver}"')

    if html == before:
        sys.exit("nothing was stamped - index.html does not look as expected")

    index.write_text(html)
    print(f"stamped {len(modules)} modules + css as v={ver}")


if __name__ == "__main__":
    main()
