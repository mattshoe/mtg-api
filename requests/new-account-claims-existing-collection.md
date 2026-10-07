---
status: needs-matt
size: large
platforms: worker, web, android
merge: ask
---

# A new account can be given a collection that already exists

Split out of `requests/kayla-account-owns-her-cards.md`, which keeps the
one-off migration for Kayla. This is the underlying bug from that file.

Matt: "I need you to assign kaylas cards to her account kayla.maloy18@gmail.com"

From the original request, verbatim:

The underlying bug is the interesting part: `freeSlug` derives a slug
from the display name, and nothing ties a new account to card rows that
already carry an owner. The next person in the same position gets the
same empty collection.

## Open question

How should an account and a collection that predates it get joined?
This is the line between "her cards" and "a stranger walks into her
cards", so it is yours to call, not mine.

Today the refusal is deliberate. `taken` in `src/accounts.js` counts a
slug already in `cards.owner` or `decks.owner` as spoken for, so a new
account can never land on it, and `test/accounts.test.js` pins that
("a slug cannot collide with a collection that already exists", so the
first Matthew to sign in is not handed Matt's cards). Any fix keeps that
test green.

- **(a) You do it in Admin Settings.** On a person's page, the
  "Collection" fact becomes something an admin can change, to an owner
  that has cards or decks and no account. You did Kayla by hand; this
  is that, without needing me. Matches "I want to be able to assign and
  remove roles at will!!!! I don't want to need you for it!!!"
- **(b) Reserve a collection for an email ahead of time.** An admin
  writes "`kayla` is for kayla.maloy18@gmail.com", and the first sign-in
  with that Google-verified email takes the slug. Automatic, but it is
  the thing the comment on `signIn` says never to do, trust an email
  address, and it needs a new table, so a schema change.
- **(c) Nothing to build.** Collections that predate accounts are
  rare, and each one that turns up gets a migration like Kayla's.

I would pick (a). A second question only if (a): an account that has
already added cards under its own slug (say Kayla had added cards as
`kayla-m`), should changing its collection refuse, or move those rows
across too? I would refuse, with the reason on screen, so nothing is
merged silently.

Not affected either way: `requests/orphan-second-matt-account.md`. The
277 account owns nothing by design, none of these options deletes or
merges an account, and it is never offered as a target.

## Plan

Assuming (a) with refuse. Revisit if the answers differ.

1. **Worker, `src/accounts.js`.**
   - `unclaimedCollections(db)`: distinct `owner` from `cards` and
     `decks` with no `users.slug` equal to it. Never returns an empty
     owner (the "unknown scope is never every scope" rule).
   - `giveCollection(db, slug, owner)`, same `{ ok }` / `{ error,
     status }` shape as `setRole`: 404 for no such account, 409 for an
     owner that already belongs to an account, 404 for an owner with no
     rows, 409 if the account already owns rows under its current slug.
     Otherwise `UPDATE users SET slug = ?owner`. `users.key` does not
     change, so every shared `/c/<key>` link keeps working.
   - Routes in `src/index.js`, admin only, the same gate as
     `POST /admin/role`: `GET /admin/unclaimed`, `POST /admin/collection`.
     Add both to the route index comment at the top of the file.
   - No schema change.
2. **`:core`, `Roles.kt`.** `People` gains `unclaimed: List<String>`
   and the in-flight and refused states for a collection change, shaped
   like `changing` / `changed` / `refused` for roles. Decoders for both
   new responses. `App.kt`: `Load.needs` for `View.ADMIN` adds the
   unclaimed list. After a successful change, if the changed account is
   the signed-in one, `/auth/me` is asked again so `admin.account.slug`
   and `viewing` move with it.
3. **Shells.** `PersonPage` in `apps/webApp/.../web/AdminPage.kt` and
   its sibling in `apps/androidApp/.../android/AdminScreen.kt`: under
   the "Collection" fact, a picker of the unclaimed owners, offered only
   when there are any, with the refusal text from `People` when the
   server says no. No logic in either shell.

## Tests

Each red first, failure text in the commit.

- Worker vitest, `npm test`, new `test/give-collection.test.js`:
  admin gives `kayla-m` the `kayla` collection and `canEdit` follows; a
  `user` gets 403; an owner held by an account is refused; an account
  with rows under its own slug is refused; the key is unchanged after;
  the unclaimed list never contains an owner with an account, nor `''`.
  "a slug cannot collide with a collection that already exists" in
  `test/accounts.test.js` stays green untouched.
- `:core`, `apps/core/src/commonTest/.../RolesTest.kt`: decode, the
  change states, and that the signed-in account changing its own
  collection re-asks who it is.
- Web, `apps/webApp/src/jsTest/`, through the real shell with
  `FakeServer`: admin opens a person, picks an unclaimed collection, the
  "Collection" fact reads the new owner. A `user` never sees the picker.
- Android, `apps/androidApp/src/sharedTest/`, through `AppShell`: the
  same facts.
- One journey in `apps/androidApp/src/androidTest/.../e2e/` against
  `FakeWorker`, since the seed has `owner = 'kayla'` rows: sign in as
  admin, give a fresh account `kayla`, open that account's collection by
  key and see her cards.

## Done when

Signed in as admin, on the live site and in the shipped APK: a person's
page in Admin Settings offers the collections nobody owns, picking one
gives it to them, and that person's collection link then shows those
cards. A signed-in `user` cannot see or call it, and picking an owned
collection is refused with the reason on screen.

Risky part: this is the one place that decides who owns whose cards. A
wrong guard hands a collection to the wrong person, and the admin-only
check has to be tested on the worker, not just hidden in the shells.
