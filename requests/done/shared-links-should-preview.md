---
status: ready
---

# A shared link should preview as the thing it points at

Re-queued from PR #10, which was closed as stale at 52 commits behind main
rather than as wrong.

Pasting a deck link into Discord or Slack shows the bare site, whatever you
shared. That is not a missing meta tag: the app routes on a hash, and a URL
fragment is never sent to a server, so the crawler requests `/` and nothing
in that request says which deck you meant. No tag on `index.html` could have
fixed it, and GitHub Pages cannot render a page per deck.

Note for whoever picks this up: deck addresses are keys now, not slugs, so
the shape of the answer has changed since that PR was written.
