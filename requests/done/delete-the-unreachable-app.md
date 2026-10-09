---
status: ready
---

# Delete the app that could never be installed

Re-queued from PR #9, which was closed as stale at 52 commits behind main
rather than as wrong. The problem is still live on main today:

`:app` and `:androidApp` both declare `applicationId "org.mattshoe.mtg.share"`
(apps/app/build.gradle.kts:11 and apps/androidApp/build.gradle.kts:19), so
Android can only ever hold one of them. `:androidApp` took that id
deliberately so it would upgrade the old build in place — which made `:app`
unreachable from that moment, not the rollback its comment claims. It has sat
there since as a second copy of the icon, the share intents and a launcher
entry nothing could run.

`:app` is still in apps/settings.gradle.kts:20.
