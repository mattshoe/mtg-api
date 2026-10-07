---
status: needs-matt
size: large
platforms: web, android
merge: ask
---

# Versioned release notes, reachable from Admin Settings

"I want versioned release notes for every build. It should be accessible
via admin settings"

Every build, so each release has its own entry rather than one rolling
page. Reachable from Admin Settings.

## Open question

Two things decide the whole design and I cannot tell which you want.

1. **Where do the words come from?**
   - (a) Written by hand. Every PR adds one file under `release-notes/`
     saying what changed, in plain words, and CI fails a PR that changes
     `apps/`, `src/` or `frontend/` without one. Builder agents write
     theirs from `git diff --cached --stat`.
   - (b) Generated from the merged PR titles and bodies.

   I would pick (a). #35's body described four features its diff did not
   contain, so (b) would have published that as release notes.

2. **What is a "build"?** Every merge to `main` produces one APK
   (`release.yml`, versionCode = commit count + 100) and usually one web
   deploy (`pages.yml`). Is one entry per merge to `main`, numbered by
   that versionCode and shared by the site and the APK, what you mean?
   Today `versionName` is stuck at `2.1.0` in
   `apps/androidApp/build.gradle.kts` and the site shows no version at
   all, so either way the app has to learn what build it is.

## Plan

Assuming (a) and one entry per merge. Revisit if the answers differ.

What exists: nothing. No version is shown anywhere in either app (no
`version` in `:core`, the shells or `src/index.js`). Admin Settings is
`View.ADMIN` in `apps/core/.../core/Shell.kt` (`operator = true`), drawn
by `AdminPage.kt` (web) and `AdminScreen.kt` (Android) as a people list
and a person page.

1. **Notes in the repo.** `release-notes/` with one Markdown file per PR.
   A script (`scripts/release-notes.mjs`) stamps each unstamped file with
   the build number at merge time and folds them into one
   `release-notes.json`, newest first, `[{ build, date, sha, notes }]`.
   CI check in `apps.yml`: a PR touching `apps/`, `src/` or `frontend/`
   must add a file.
2. **Every build knows its number.** `release.yml` and `pages.yml` both
   compute the same build number from the merge commit and pass it in:
   `MTG_VERSION_CODE` already reaches Android, add a `BuildConfig` field
   read through a small `Build` value handed to `:core`. Web gets it the
   way `scripts/stamp_assets.py` already stamps the sha. Fix
   `versionName` to say something that changes, or drop it from the
   display and show the build number.
3. **Serving the notes.** The site publishes `release-notes.json` beside
   `mtg.js` from `pages.yml`. Both apps fetch it from
   `https://mtg.mattshoe.org/release-notes.json`, so a phone running an
   old APK still reads notes for builds newer than itself. No worker
   change, which is why `worker` is not in platforms.
4. **`:core`.** `ReleaseNotes.kt`: decode the JSON, a `ReleaseNotes`
   piece of state on `AppState` (loading/failed/loaded, like `People`),
   and "this is the build you are running" marked on the matching entry.
   A new `View` or an Admin sub-route for the notes page, `operator =
   true`, reached from a "Release notes" row at the top of Admin
   Settings.
5. **Shells.** `ReleaseNotesPage.kt` (web) and `ReleaseNotesScreen.kt`
   (Android): the running build at the top, then one entry per build,
   number, date and notes. A row in `AdminPage.kt` / `AdminScreen.kt`
   to get there.

## Tests

- `apps/core/src/commonTest/`: `ReleaseNotesTest` for the decode, the
  order, the running-build marker, and that the view is operator-gated
  (an account with role `user` cannot reach it, `Shell.kt`).
- Worker-style vitest in `test/` for `scripts/release-notes.mjs`:
  stamping, folding, and that an already-stamped entry is never
  rewritten (same rule as an applied migration).
- Web, `apps/webApp/src/jsTest/`: through the real shell, Admin Settings
  shows the row, it opens the page, entries render from a `FakeServer`
  answer shaped like the real JSON.
- Android, `apps/androidApp/src/sharedTest/`, through `AppShell`: same
  facts. One journey in `e2e/JourneyTest.kt` signing in as admin and
  opening the notes.
- The CI check that a PR without a notes file fails, shown red on a
  throwaway branch before it is trusted.

## Done when

Signed in as admin on the live site and in the shipped APK, Admin
Settings has "Release notes", it opens a list with one entry per build,
the newest is the build you are running and says so, and an account
without the admin role cannot reach it.

Risky part: build plumbing in `release.yml` and `pages.yml`, which have
no tests and only run on merge, so a mistake shows up after it ships. And
it is an operator-gated view, so the gate has to be tested, not assumed.
