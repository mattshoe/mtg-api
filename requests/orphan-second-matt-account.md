---
status: ready
size: small
platforms: none
merge: ask
---

# The 277 account is Matt's test account — leave it alone

Matt: "No don't delete accounts unless i ask you. Im using the 277
account to test"

`matthew.shoemaker.277@gmail.com`, account id 2, slug
`matthew-shoemaker`, key `t4pee71g`, role `user`, owns no cards and no
decks. That is correct and expected.

## Plan

Nothing to build. This file exists so the next agent that notices an
account with an empty collection does not propose deleting it.

Write it down where it will actually be read: a line in the `mtg` skill's
accounts section saying the 277 account is Matt's test account, owns
nothing by design, and that **no account is ever deleted unless Matt asks
for that account by name**.

## Tests

None. This is a documentation change.

## Done when

The `mtg` skill says the 277 account is a test account and that accounts
are never deleted unasked.
