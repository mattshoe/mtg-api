# Release notes

One file per pull request, written by hand, saying what shipped in words
Matt would use. Name it after the branch or the request:
`release-notes/<name>.md`. Plain text or markdown, a line or a few.

Every merge to `main` cuts a GitHub release (`release.yml`), and the
note the merge *added* becomes that release's body
(`scripts/release-note.mjs`). Admin Settings lists those releases,
newest first, on the website and on the phone. The release tag is the
version: `android-v2.1.0-296` shows as `2.1.0 (296)`.

A merge with no note still gets a release, and Admin Settings says
"No note was written for this build." Editing a note that already
shipped changes nothing on the release it went out with.
