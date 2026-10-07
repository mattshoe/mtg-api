# Backlog

Fire anything at me any time — a bug, an idea, half a sentence. It
lands here verbatim and I keep working on whatever is in front of me.
Nothing needs to wait for me to finish, and nothing gets lost because
you said it while I was mid-build.

**How it works**

- You say it. I append it to **Inbox** in your own words, with the date.
- I work top-down through Inbox unless you say otherwise. "Do X next"
  reorders it; "drop X" deletes it.
- An item moves to **Building** when I start, and to **Shipped** only
  when it is deployed on **both** platforms and verified live — never
  when the code is merely written.
- Shipped items keep their PR number so there is a trail back.

If you want to see the queue, ask for the backlog and I will read it
back. If you want to rearrange it, say so in any words you like.

---

## Inbox

_Nothing waiting._

## Building

- **EDHREC sort reads backwards** — `descending` now means "best first"
  on every column, and the rank column inverts its SQL underneath, so
  the down arrow shows the most-played cards rather than rank 22,000.
  *(said 2026-10-07)*
- **Card details must show EVERYTHING from the database** — the page
  drew nine of forty-odd columns and none of the five child tables.
  `CardFacts` adds artist, layout, frame, border, watermark, security
  stamp, released, set type, types, colours, produced mana, keywords,
  finishes, games, promo types, frame effects, the ten printing flags,
  and the oracle and Scryfall ids. *(said 2026-10-07)*
- **EDHREC rank on the carousel** — a chip on the sheet under the
  carousel, in the deck's and the Library's. *(said 2026-10-07)*
- **EDHREC link on the full details page** — `edhrec.com/cards/<slug>`,
  built from the front face. *(said 2026-10-07)*
- **Double-faced cards need a flip toggle over the image** — the back
  was something you read about under the front rather than looked at.
  *(said 2026-10-07)*
- **Admin Settings needs search and a user detail page** — "WHAT
  HAPPENS WHEN SET HAVE 20 DIFFERENT FUCKING ROLES?!?!" A search box, a
  row per account, and everything you can do to somebody on their own
  page, with the roles listed from `Role.all` so a third one needs no
  new control. *(said 2026-10-07)*
- **The admin screen showed `/c/<slug>`, not the user key** — that is
  not an address anybody can open. Fixed in all four places that
  printed it, including the profile menu. *(said 2026-10-07)*
- **Guild / shard name on the deck-at-a-glance view, and on the deck
  card too** — "I want the 'deck at a glance' view to show which guild
  or whatever you call it. Having that somewhere on the deck card too
  would be nice." *(said 2026-10-07)*

## Shipped

- **An intake process for requests** — this file. *(said 2026-10-07)*
- **The intake audit checks the shape of every column** — every column
  on `decks`, `cards` and `users` has a declared shape; a wrong value
  fails, not just a missing one. *(#34)*
- **Decks made in the app had no colour** — milly-moth and two others
  were the only colourless decks in the database. Derived after the
  write now, and a migration painted the three that existed. *(#33)*
- **Roles at will, a first admin that needs nobody, a slug that
  scales** — the last admin can demote themselves, a migration grants
  the first admin, and a colliding slug takes four random characters
  rather than counting to 999. *(#32)*
- **A role system** — two roles, `admin` does anything, new accounts are
  `user`, and an Admin Settings screen to hand the role out. *(#31)*
- **One way in** — the shared password is gone from both apps; you sign
  in with your account. *(#30)*
- **The Android app link and the profile menu's placement** — the link
  moved into the hamburger, the avatar to the right-hand end of the
  bar. *(#29)*
- **The profile avatar on web, and no fetch before the collection is
  known** — Kayla's decks could appear on your screen in the gap before
  `/auth/me` answered. *(#28)*
- **Nothing asks whose collection it is any more** — the "who" step came
  out of both wizards, the stats switch and the filter panel. *(#27)*
