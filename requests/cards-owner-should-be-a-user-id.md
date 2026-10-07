---
status: ready
size: large
platforms: worker, web, android
merge: ask
---

# cards.owner should be users.id, and users.slug should not exist

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

   **`decks.slug` is a different thing and stays.** A deck's slug is its
   address, it is in every deck link, and renaming a deck updates its name
   and slug together. Do not touch it.
6. **Keep the id out of every response.** Check what `/auth/me`,
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
- Parity: both shells still show the right collection. `apps/webApp/src/jsTest/`
  and `apps/androidApp/src/sharedTest/`.

## Done when

Nothing reads `users.slug`. There is no "what slug does this account get"
question, because there is no slug — a new account gets an empty
collection because it owns no rows.

A collection key opens a collection to read and can do nothing else. No
response anywhere contains a user id.

Both platforms still show the right collection, deployed and verified in
`mtg.js` and in the shipped APK.
