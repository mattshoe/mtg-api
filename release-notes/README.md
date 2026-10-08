# Release notes

One file per pull request, written by hand, saying what shipped in words
Matt would use. Name it after the branch or the request:
`release-notes/<name>.md`. Plain text or markdown, a line or a few.

Every merge to `main` cuts a GitHub release (`release.yml`), and the
note the merge *added* becomes that release's body
(`scripts/release-note.mjs`). Admin Settings lists those releases,
newest first, on the website and on the phone. The release tag is the
version: `android-v2.1.0-296` shows as `2.1.0 (296)`.

## The version

Releases are semver, and the note says how big the change is, in
frontmatter at the top:

```
---
bump: minor
---
Release notes are on Admin Settings.
```

- `patch` — a fix, or anything nobody would call new. The default: no
  frontmatter, or no note at all, is a patch.
- `minor` — something new a person can see or do.
- `major` — something that changes or takes away what was there.

`scripts/next-version.mjs` bumps the newest `android-vX.Y.Z-N` tag by
that much, so `2.1.4` and a `minor` ships as `2.2.0`. Two notes in one
merge take the bigger bump. The frontmatter is left out of the release
body.

A merge with no note still gets a release, and Admin Settings says
"No note was written for this build." Editing a note that already
shipped changes nothing on the release it went out with.
