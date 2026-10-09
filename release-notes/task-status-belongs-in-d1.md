---
bump: minor
---
Task status lives in the database now, written when each thing actually happens, not guessed from GitHub branch names. Tasks say pending, in progress, blocked, paused, in review, merged, deployed or cancelled, with why and for how long, and which pull request. Merged, deployed and cancelled fold into Done. Tasks and release notes both come from the API, so GitHub's rate limit can't take Admin Settings down any more.
