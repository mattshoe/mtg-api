#!/usr/bin/env python3
"""Stamp frontend asset URLs with a version, so a deploy actually lands.

GitHub Pages serves every file with `Cache-Control: max-age=600` and gives
us no way to change that. Without a version in the URL an open browser
keeps running the old files for ten minutes after a deploy, and worse,
each file expires on its own clock — so it can end up running new
index.html against an old bundle, or the reverse.

This rewrites, in place, the copy of the frontend that is about to be
uploaded. There are two pages to stamp:

  index.html   the shared Kotlin build: one bundle, the stylesheet and
               the manifest
  classic.html the hand-written app it replaced, kept as a rollback: an
               import map covering every ES module, plus the entry
               script, the stylesheet and the manifest

Each page is still cached for ten minutes, but it is now the only thing
that is: whichever copy a browser holds pins one coherent set of
everything else, and when it does refresh, all of it refreshes at once.

    python3 scripts/stamp_assets.py <dir> <version>
"""
import json
import re
import sys
from pathlib import Path


def stamp_shared(root: Path, ver: str) -> int:
    """index.html: one bundle, one stylesheet, one manifest."""
    page = root / "index.html"
    html = page.read_text()
    before = html

    html = html.replace('src="kmp/mtg.js"', f'src="kmp/mtg.js?v={ver}"')
    html = html.replace('href="css/app.css"', f'href="css/app.css?v={ver}"')
    # The manifest too. Android builds the installed app by fetching
    # this, and Pages serves it with the same ten minute cache as
    # everything else — so reinstalling shortly after a deploy built the
    # app from the previous manifest, silently, and every share_target
    # change sat on the server without ever reaching the phone.
    html = html.replace(
        'href="manifest.webmanifest"', f'href="manifest.webmanifest?v={ver}"')

    if html == before:
        sys.exit("nothing was stamped in index.html - it does not look as expected")
    page.write_text(html)
    return 1


def stamp_classic(root: Path, ver: str) -> int:
    """classic.html: the module graph, through an import map."""
    page = root / "classic.html"
    if not page.exists():
        return 0

    modules = sorted(p.relative_to(root).as_posix() for p in root.glob("js/*.js"))
    if not modules:
        sys.exit("classic.html is here but there are no modules to stamp")

    # URL-like keys are matched after the specifier resolves, so mapping
    # the absolute path catches './util.js' imported from inside js/ as
    # well as the entry point referenced from the page.
    imports = {f"/{m}": f"/{m}?v={ver}" for m in modules}
    imports.update({f"./{m}": f"./{m}?v={ver}" for m in modules})

    html = page.read_text()
    before = html

    html = html.replace(
        '<script type="importmap">{"imports":{}}</script>',
        '<script type="importmap">'
        + json.dumps({"imports": imports}, separators=(",", ":"))
        + "</script>",
    )
    html = html.replace('src="js/app.js"', f'src="js/app.js?v={ver}"')
    html = html.replace('href="css/app.css"', f'href="css/app.css?v={ver}"')
    html = html.replace(
        'href="manifest.webmanifest"', f'href="manifest.webmanifest?v={ver}"')

    if html == before:
        sys.exit("nothing was stamped in classic.html - it does not look as expected")
    page.write_text(html)
    return len(modules)


def main():
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    root = Path(sys.argv[1])
    ver = re.sub(r"[^A-Za-z0-9._-]", "", sys.argv[2])[:40]
    if not ver:
        sys.exit("empty version")
    if not (root / "index.html").exists():
        sys.exit(f"missing {root / 'index.html'}")
    if not (root / "kmp" / "mtg.js").exists():
        sys.exit("the shared bundle is missing - was :webApp:jsBrowserDistribution run?")

    shared = stamp_shared(root, ver)
    classic = stamp_classic(root, ver)
    print(f"stamped the shared bundle and {classic} rollback modules as v={ver}")


if __name__ == "__main__":
    main()
