-- Kayla's account owns Kayla's cards.
--
-- Matt: "I need you to assign kaylas cards to her account
-- kayla.maloy18@gmail.com"
--
-- Her cards and decks have said `owner = 'kayla'` since before there
-- were accounts. Her Google sign-in took the slug `kayla-m` off her
-- display name "Kayla M", so nothing matched and she signed in to an
-- empty collection. On the live database this was fixed by hand with
--
--     UPDATE users SET slug = 'kayla' WHERE id = 3
--
-- which nobody could see. This is that change, where it can be seen,
-- so a database built from scratch agrees with the live one. There it
-- is a no-op.
--
-- Not by id: id 3 is Kayla only on the live database, and anywhere
-- else it is whoever signed in third. By her slug and her email
-- instead, and only while nobody holds `kayla` already, because
-- `users.slug` is UNIQUE and a migration that throws stops the deploy.
UPDATE users SET slug = 'kayla'
 WHERE slug = 'kayla-m'
   AND email = 'kayla.maloy18@gmail.com'
   AND NOT EXISTS (SELECT 1 FROM users WHERE slug = 'kayla');
