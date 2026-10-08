import { describe, it, expect } from 'vitest';
import { env } from 'cloudflare:test';
import { get, sql, exec } from './helpers.js';
import { signIn, canEdit } from '../src/accounts.js';
import migration from '../migrations/0006_kayla_owns_her_cards.sql?raw';

/**
 * `0006_kayla_owns_her_cards.sql`, run against a real database.
 *
 * Matt: "I need you to assign kaylas cards to her account
 * kayla.maloy18@gmail.com". It was done by hand on production as
 * `UPDATE users SET slug = 'kayla' WHERE id = 3`, which is only Kayla
 * on that one database. Everywhere else id 3 is whoever signed in
 * third, so the migration keys on her slug and her email, and holds
 * back when `kayla` is already taken, because `users.slug` is UNIQUE
 * and a migration that throws stops the deploy.
 */
describe('0006: Kayla owns her cards', () => {
  const KAYLA = { provider: 'google', subject: 'kayla-google', email: 'kayla.maloy18@gmail.com', name: 'Kayla M' };

  /** The migration as wrangler runs it. Returns how many rows it changed. */
  const migrate = async () => (await env.DB.prepare(migration).run()).meta.changes;

  const account = (key, slug, email) => exec(
    `INSERT INTO users (key, slug, email, role, created_at) VALUES (?1, ?2, ?3, 'user', datetime('now'))`,
    key, slug, email,
  );

  const slugs = async () => (await sql('SELECT id, slug FROM users ORDER BY id'))
    .map((u) => `${u.id}:${u.slug}`);

  it('her Google account, which took kayla-m off her name, ends up owning kayla', async () => {
    expect(await sql("SELECT 1 FROM cards WHERE owner = 'kayla' LIMIT 1"), 'the fixture has no Kayla cards to own').toHaveLength(1);
    const before = await signIn(env.DB, KAYLA);
    expect(before.slug, 'the sign-in did not reproduce the empty collection').toBe('kayla-m');

    await migrate();

    const after = await signIn(env.DB, KAYLA);
    expect(canEdit({ user: after }, 'kayla'), `her account is ${after.slug}, so it cannot edit kayla's cards`).toBe(true);
    const page = await get(`/c/${after.key}`);
    expect(page.status).toBe(200);
    expect(page.body.slug).toBe('kayla');
  });

  it('is not keyed on id: whoever signed in third keeps their own slug', async () => {
    await signIn(env.DB, { provider: 'google', subject: 'a', email: 'a@example.com', name: 'Ann' });
    await signIn(env.DB, { provider: 'google', subject: 'b', email: 'b@example.com', name: 'Bob' });
    const third = await signIn(env.DB, { provider: 'google', subject: 'c', email: 'c@example.com', name: 'Stranger' });
    expect(third.id, 'the stranger is not id 3, so this proves nothing about WHERE id = 3').toBe(3);

    await migrate();

    const [row] = await sql('SELECT slug FROM users WHERE id = ?', third.id);
    expect(row.slug, `the stranger at id 3 was handed Kayla's cards: slug is now ${row.slug}`).toBe('stranger');
  });

  it('does nothing, and does not throw, when an account already holds kayla', async () => {
    await account('k1', 'kayla', 'kayla.maloy18@gmail.com');
    await account('k2', 'kayla-m', 'kayla.maloy18@gmail.com');
    await account('k3', 'someone', 'someone@example.com');
    const before = await slugs();

    expect(await migrate()).toBe(0);
    expect(await slugs()).toEqual(before);
  });

  it('runs on a database with no accounts and changes nothing', async () => {
    expect(await sql('SELECT 1 FROM users')).toHaveLength(0);
    expect(await migrate()).toBe(0);
  });

  it('leaves alone every account that is not kayla-m with her email', async () => {
    await account('k1', 'matt', 'mattshoe81@gmail.com');
    // Matt's test account, id 2 on the live database.
    await account('t4pee71g', 'matthew-shoemaker', 'matthew.shoemaker.277@gmail.com');
    await account('k3', 'kayla-m', 'someone-else@example.com');
    await account('k4', 'kayla-x', 'kayla.maloy18@gmail.com');
    const before = await slugs();

    expect(await migrate()).toBe(0);
    expect(await slugs()).toEqual(before);
  });
});
