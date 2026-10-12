import { describe, it, expect, beforeEach } from 'vitest';
import { env } from 'cloudflare:test';
import { call, sql, exec } from './helpers.js';
import { signIn, newSession } from '../src/accounts.js';
import migration from '../migrations/0012_matt_is_admin_again.sql?raw';

/**
 * `0012_matt_is_admin_again.sql`, run against a real database.
 *
 * Matt: "Give mattshoe81@gmail.com the admin role back."
 *
 * `/admin/role` is the normal way, and it needs somebody who already
 * holds the role. The intake agent's token reads `role: user` on the
 * live Worker, so it could not, and 0004 keys on `users.slug = 'matt'`,
 * which 0007 retired. So this keys on his email, the way he named it.
 */
describe('0012: mattshoe81@gmail.com is an admin again', () => {
  const MATT = { provider: 'google', subject: 'matt-google', email: 'mattshoe81@gmail.com', name: 'Matt Shoemaker' };

  beforeEach(async () => {
    await exec('UPDATE cards SET owner_id = NULL');
    await exec('UPDATE decks SET owner_id = NULL');
    await exec('DELETE FROM sessions');
    await exec('DELETE FROM identities');
    await exec('DELETE FROM users');
  });

  const migrate = async () => (await env.DB.prepare(migration).run()).meta.changes;

  it('his account, demoted to user, reads admin on /auth/me after it runs', async () => {
    const matt = await signIn(env.DB, MATT);
    expect(matt.role, 'a fresh sign-in already made him admin, so this proves nothing').toBe('user');

    await migrate();

    const token = await newSession(env.DB, matt.id);
    const me = await call('/auth/me', { method: 'GET', token });
    expect(me.body.role, 'mattshoe81@gmail.com is still not an admin after 0012').toBe('admin');
  });

  it('matches his email whatever case Google sent it in', async () => {
    const matt = await signIn(env.DB, { ...MATT, email: 'MattShoe81@Gmail.com' });
    await migrate();
    const [row] = await sql('SELECT role FROM users WHERE id = ?', matt.id);
    expect(row.role, 'a capitalised address was missed').toBe('admin');
  });

  it('hands the role to nobody else', async () => {
    await signIn(env.DB, MATT);
    const other = await signIn(env.DB, { provider: 'google', subject: 'x', email: 'someone@example.com', name: 'Someone' });
    const test = await signIn(env.DB, { provider: 'google', subject: 't', email: 'matthew.shoemaker.277@gmail.com', name: 'Matt test' });
    await migrate();
    const rows = await sql('SELECT id, role FROM users WHERE id IN (?, ?)', other.id, test.id);
    expect(rows.map((r) => r.role), 'an account that is not mattshoe81@gmail.com was made admin').toEqual(['user', 'user']);
  });
});
