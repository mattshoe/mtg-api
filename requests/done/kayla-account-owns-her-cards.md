---
status: hold
size: small
platforms: worker
merge: ask
---

# Kayla's account has to own Kayla's cards, in a migration

"I need you to assign kaylas cards to her account kayla.maloy18@gmail.com"

This was already done by hand against production, which was wrong — it
exists as an ad-hoc UPDATE nobody can see. It needs to be a migration so
a database built from scratch, and the test fixtures, agree with the live
one.

What was run:

    UPDATE users SET slug = 'kayla' WHERE id = 3

Her 1,283 card rows and 7 decks were already tagged `owner = 'kayla'`;
the account created from her Google sign-in had taken the slug `kayla-m`
off her display name "Kayla M", so nothing matched and she would have
signed in to an empty collection.

The underlying bug is the interesting part: `freeSlug` derives a slug
from the display name, and nothing ties a new account to card rows that
already carry an owner. The next person in the same position gets the
same empty collection.

---

**Split.** This file is now only the migration. The underlying bug in the
last paragraph is its own job with a design decision in it, and is in
`requests/new-account-claims-existing-collection.md` (`needs-matt`). The
migration ships without waiting on that answer.

Checked against `requests/orphan-second-matt-account.md`: no overlap in
code. That file is a note in the `mtg` skill about account id 2
(`matthew-shoemaker`, the 277 test account). This migration must not
touch that account or any other, and must not delete anything.

## Plan

Why `worker` only: no screen changes. `users.slug` is already the join to
`cards.owner` and `decks.owner` (header comment in `src/accounts.js`), so
once the slug is `kayla` both apps show her collection through code that
exists. The live database already has the change, so on production this
migration is a no-op whose job is to make the change visible and
reproducible.

1. New `migrations/0006_kayla_owns_her_cards.sql`. Header comment in the
   style of `0004_first_admin.sql`: Matt's quote, what was run by hand,
   and why it is here. Do **not** copy the hand-run statement. `WHERE id
   = 3` is only Kayla on the live database; on any other database id 3
   is whoever signed in third. Key it on what identifies her and guard it
   so it can never fail or collide:

   ```sql
   UPDATE users SET slug = 'kayla'
    WHERE slug = 'kayla-m'
      AND email = 'kayla.maloy18@gmail.com'
      AND NOT EXISTS (SELECT 1 FROM users WHERE slug = 'kayla');
   ```

   The `NOT EXISTS` matters: `users.slug` is `UNIQUE`
   (`migrations/0002_accounts.sql`), and a migration that throws in
   `worker.yml`'s "Apply migrations" step stops the deploy.
2. Add its SHA-256 to `test/fixtures/migration-hashes.json`, so the
   "an applied migration is never edited again" test in
   `test/migrations.test.js` guards it from the first day.
3. Nothing in `schema.sql`, which creates tables and holds no rows.
   Nothing in `test/fixtures/seed.sql` either, it has no `users` rows
   (`scripts/make_fixture.py` does not dump them) and its Kayla rows
   already say `owner = 'kayla'`.
4. Do not touch `freeSlug` or `taken` in `src/accounts.js`. The test
   "a slug cannot collide with a collection that already exists" in
   `test/accounts.test.js` is the reason a stranger named Kayla cannot
   walk into these cards, and it has to stay green.

## Tests

Worker vitest, `npm test`. New `test/kayla-migration.test.js` (or a
`describe` in `test/migrations.test.js`) that imports the migration file
`?raw` the way `migrations.test.js` already does and runs it against
`env.DB`:

- Kayla's case: insert a `kayla-m` user with her email and cards with
  `owner = 'kayla'`, run the migration, and `canEdit` for that user on
  owner `kayla` is true and `/c/<her key>` answers slug `kayla`.
- Not by id: three unrelated sign-ins via `signIn`, so a stranger has
  id 3, run the migration, and the third account's slug is unchanged.
  Write this one first and run it against the hand-run `WHERE id = 3`
  statement; it must go red with the stranger's slug in the message.
  That is the red to record.
- Already applied: a database where an account already holds `kayla`
  runs the migration without throwing and changes nothing.
- Fresh database, no users: runs without throwing, zero rows changed.
- Account id 2 / any account not matching both slug and email is never
  touched.

The "Kayla's case" test will likely pass against a `WHERE id = 3`
version on a fixture where she happens to be id 3. Say in the commit
which tests were red and which are guards.

## Done when

- `migrations/0006_kayla_owns_her_cards.sql` is on `main` and the
  `worker` workflow's "Apply migrations" step listed it and succeeded.
- `npx wrangler d1 migrations list mtg --remote` shows 0006 applied.
- On the live API, `GET /c/<Kayla's key>` answers slug `kayla`, and
  `SELECT COUNT(*) FROM cards WHERE owner = 'kayla'` is non-zero.
  Signed in as Kayla, the site and the app open on her cards (nothing
  shipped to either app, this is the existing join working).
- Account id 2 still has slug `matthew-shoemaker`.

Why `merge: ask`: it changes who owns a collection on the live database.
It should be a no-op there, but it is a write to `users` that runs on
deploy with nobody watching.
