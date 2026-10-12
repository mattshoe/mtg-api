-- Matt: "Give mattshoe81@gmail.com the admin role back."
--
-- `/admin/role` is how a role is normally handed out, and it needs an
-- account that already holds one. Nobody the intake agent could act as
-- did, and 0004 keyed on `users.slug = 'matt'`, which 0007 retired. So
-- this names him the way he named himself: by his email, in any case.
-- Nobody else is touched, and on a database where he has never signed
-- in it changes nothing.
UPDATE users SET role = 'admin' WHERE lower(email) = 'mattshoe81@gmail.com';
