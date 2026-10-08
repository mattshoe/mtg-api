---
status: ready
size: large
platforms: web, android
merge: auto
---

# Versioned release notes, reachable from Admin Settings

"I want versioned release notes for every build. It should be accessible
via admin settings"

Every build, so each release has its own entry rather than one rolling
page. Reachable from Admin Settings.

## Matt has answered. Do not ask him again

"I DON'T FUCKING CARE WHAT THE RELEASE NOTES LOOK LIKE I JUST WANT TO
FUCKING SEE THEM IN THE ADMIN SETTINGS!!!!!"

Both earlier questions are yours to decide. Decide them and get it on
screen:

- **Written by hand, one file per release.** A generated note is only as
  true as the PR text, and #35's PR text described four features its diff
  did not contain. A human-written line saying what shipped is the point
  of the screen.
- **One entry per merge to `main`**, which is already what cuts a
  release: `release.yml` runs on every push to main and `pages.yml`
  publishes the site. The release tag is the version.

The screen matters more than the format. A list of versions, newest
first, each with its date and what changed, reachable from Admin Settings
on **both** platforms.

If `versionName` being stuck at `2.1.0` makes the running build hard to
name, use the release tag and say so in the PR. Do not stop over it.
