---
status: ready
size: large
platforms: worker, web, android
merge: ask
---

# Ownership is an id, addresses are keys, and no slug survives

Matt: "THERE SHOULD NEVER FUCKING BE A COLLISON IN CARDS BY OWNER DUDE
WHAT THE FUCK ARE JOKING?!?!?!?! / NEW ACCOUNTS DON'T HAVE FUCKING
CARDS!!!!!!!! ARE YOU NOT USING A FUCKING USER ID?!?!?!"

He is right, and this is the root cause of the Kayla incident rather than
a separate idea.

`schema.sql:17` — `cards.owner TEXT` with the comment `'matt' | 'kayla'`.
`schema.sql:56` — `decks.owner TEXT`, the same. Neither references
`users.id`. There is no foreign key anywhere between a card and an
account.

So ownership is decided by two strings happening to match. Which produced
exactly the failure you would expect: Kayla signed in with Google, the
account got the slug `kayla-m` off her display name "Kayla M", her 1,283
card rows said `kayla`, nothing matched, and her collection was empty. The
"fix" was an UPDATE to make two strings agree, which is not a fix.

And the request this replaces — whether a new account should *claim*
existing rows — only existed because of the same flaw. A new account has
no cards. There is nothing to claim. Claiming is only conceivable when
ownership is a coincidence of text.

Matt, on the identifiers: "AND THE FUCKING USER ID NEEDS TO BE PRIVATE
AND DIFFERENT FROM USER KEY!!!!! USER KEY IS NOT SUFFICIENT TO MUTATE
DATA!!! ONLY USER ID!!"

## The two identifiers, and the line between them

They already exist and are already different. What is missing is that the
line is enforced rather than merely intended.

**`users.key`** — `bprh3d2s`, 8 random base32 characters. **Public.** It
is what `/c/<key>` carries, what somebody pastes into a chat, and what a
shared link is made of. It names a collection to look at. **It is not a
credential and must never authorise a write.**

**`users.id`** — the integer primary key. **Private.** Never in a URL,
never in a response body, never in a page, never logged. It is the only
thing a mutation is allowed to be decided by, and it is only ever reached
by resolving the session cookie to a row in `sessions` and from there to
`users.id`.

So: possessing a key lets you *read* a collection. Only a session that
resolves to an id lets you *change* one. A request that presents a key
and asks to write is refused, however the key was obtained.

## Plan

Make the card's owner the account's id.

1. **A migration** adding `owner_id INTEGER REFERENCES users(id)` to
   `cards` and to `decks`, backfilled from the existing slugs — today
   that is `matt` -> 1 and `kayla` -> 3, which can be done by joining
   `users.slug`, not hardcoded.
2. **`schema.sql` as well**, in the same commit. Only ever editing one of
   the two files has shipped broken to production twice, and an applied
   migration is never edited — the fix is always another file. See the
   skill.
3. **The Worker** reads and writes `owner_id`. `src/accounts.js`,
   `src/cards.js`, `src/decks.js`, `src/index.js`. `mayEdit` compares
   **ids**, and the id comes only from the session — never from anything
   in the request. Audit every write path for a key or a slug reaching a
   permission decision, and remove it. `GET /c/:key` keeps resolving a
   key to a collection to **read**; it must not hand back an id.
4. **`:core`** stops carrying a slug where it means an owner.
   `AppState.resolvedCollection` is a slug today; the key stays the public
   address and the id becomes the identity. `CardFilters.conditions` keys
   on the id — keep the `WHERE 1=0` behaviour for an unknown scope,
   because an unknown scope is still never every scope.
5. **Delete `users.slug`.** It has no job left. Every reader of it today
   wants one of the other two or wants `display_name`:

   - `accounts.js:289` `canEdit` compares slugs — compares ids instead
   - `accounts.js:133/172` `freeSlug` and its UNIQUE retry at `:167`
     disappear entirely, and with them the whole "what slug does a new
     account get" problem that started this
   - `accounts.js:309-314` `allUsers` orders by slug and falls back to it
     for a name — order by `display_name`, and a row with no display name
     shows its email's local part, not an invented slug
   - `accounts.js:358-363` and `:376` return it to the client — return
     `key`, `display_name`, `avatar_url` and nothing else
   - `index.js:58` puts it in a 403 message — use `display_name`

   Dropping the column is a later migration, after nothing reads it. Do
   not do both in one pull request.

6. **`decks.slug` goes too.** Matt: "WHY THE FUCK WOULD KEEP THE
   SLUG?!?!?!?! WE'RE GOING TO HAVE FUCKING COLLISIONS IN URLS ALL OVER
   THE FUCKING PLACE". He is right, and an earlier version of this file
   said to keep it, which was wrong.

   `schema.sql:54` is `slug TEXT UNIQUE` — **globally** unique, across
   every account. And every lookup in `src/decks.js` is
   `WHERE slug = ?` with no owner in it, at `:44`, `:336` and elsewhere.
   So two consequences, both bad:

   - Matt has a deck called Milly Moth. Kayla creates one with the same
     name and the INSERT violates the UNIQUE constraint. Two accounts
     cannot own a deck with the same name, which is an absurd thing to
     tell somebody.
   - The slug is the global identifier, so a deck is addressed without
     reference to who owns it. That is the same mistake as `cards.owner`,
     one table over.

   A deck gets the same two identifiers an account gets: a private
   `id`, and a **public key** generated the way `users.key` is —
   Crockford-ish base32, 8 characters, random, no modulo bias. The key is
   what a deck URL carries. The name is free text that anybody may reuse
   and may contain anything.

   Deck addresses change shape, so say so in the PR. Matt's rule that
   renaming a deck updates its name and slug together stops applying,
   because there is no slug — renaming a deck changes its name and nothing
   else, and its link keeps working. That is strictly better.
7. **No column named `slug` is left anywhere.** Matt: "GET RID OF THE GOD
   DAMN SLUG ALTOGETHER GOD FUCKING DAMMIT!!!" There are four, and after
   this there are none:

   - `users.slug` (`schema.sql:306`) — deleted, step 5
   - `decks.slug` (`schema.sql:54`) — deleted, step 6, replaced by a key
   - `tags.slug` (`schema.sql:94`) — **renamed to `tag`**
   - `card_tags.tag_slug` (`schema.sql:92`) — **renamed to `tag`**, with
     `idx_tags_slug` at `:192` renamed to match

   The last two are not addresses and not ours. They hold Scryfall
   Tagger's vocabulary — `card-draw`, `ramp` — which arrives from an
   external bulk file, is unique because Scryfall says so, is never typed
   by a person and never appears in a URL. So there is nothing to replace
   them with: inventing our own ids for someone else's taxonomy would be
   worse than the problem. What is wrong with them is the **word**, which
   implies they are an address of ours. Rename the columns and the index,
   change nothing about the values.

   Grep for it afterwards. `/usr/bin/grep -rn 'slug' schema.sql
   migrations/ src/ apps/*/src/` should find nothing but prose, and the
   comment at `schema.sql:129` calling an event name a "route slug" should
   say "route name".

8. **Keep the id out of every response.** Check what `/auth/me`,
   `/admin/users`, `/c/:key` and the log return today, and make sure none
   of them leaks it. The web and Android shells must not hold an id at
   all — they hold a key and a session.

Dropping the old `owner` column can be a later migration. Do not do both
in one pull request.

## Tests

- `test/migrations.test.js` holds `schema.sql` and `migrations/` to each
  other; a new migration name is ordinary, a changed hash on an existing
  one is the mistake.
- A Worker test that a card written by account 3 is invisible to account
  1's edits and visible to its own, asserted on ids.
- A Worker test that an account's identity survives a display-name
  change: rename the account, and what it owns and may edit is unchanged.
  That is the test that would have caught the Kayla bug, and with the slug
  gone there is nothing left to go wrong in the first place.
- A `:core` test that an unknown scope still produces `1=0`.
- **A Worker test that two accounts can each own a deck with the same
  name.** Create "Milly Moth" for account 1 and for account 3; both
  succeed, each gets its own key, and each account sees only its own.
  This is impossible today and is the collision Matt is describing.
- A Worker test that renaming a deck does not change its address.
- Parity: both shells still show the right collection and open a deck by
  key. `apps/webApp/src/jsTest/` and `apps/androidApp/src/sharedTest/`.

## Done when

`/usr/bin/grep -rn 'slug' schema.sql migrations/ src/ apps/*/src/` finds
nothing but prose.

A new account gets an empty collection because it owns no rows. Two
accounts can each have a deck called Milly Moth without either of them
knowing the other exists. Renaming anything never changes its address.

A collection key opens a collection to read and can do nothing else. No
response anywhere contains a user id.

Both platforms still show the right collection, deployed and verified in
`mtg.js` and in the shipped APK.
