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
