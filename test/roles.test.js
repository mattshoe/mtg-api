import { env } from 'cloudflare:test'
import { describe, it, expect } from 'vitest'
import { post, postAs, postAnon, call, sql, stubScryfall } from './helpers.js'
import { signIn, newSession } from '../src/accounts.js'

/**
 * Two roles, and only two.
 *
 * Matt: "2 roles: user and admin. Every new account gets the user role.
 * Only SPECIFIC accounts that I DECIDE get the admin role. Anyone with
 * the user role can only edit their own cards. Anyone with the admin
 * role will be able to do whatever they want, from modify others cards
 * to giving other users admin etc etc."
 *
 * So `user` is the floor and `admin` is the ceiling, with nothing in
 * between and nothing outside. The floor is the default and the
 * ceiling is handed out by somebody who already has it — which is the
 * one rule that has to hold even when it is inconvenient, because an
 * account that could promote itself is not a role system.
 *
 * `admin` edits anybody's cards again here. It did until this morning,
 * then it did not for an hour: "NOBODY GETS FUCKING ADMIN
 * PERMISSIONS!!!!!! YOU JUST GET TO MODIFY YOUR OWN FUCKING CARDS BY
 * DEFAULT!!!!!" was about the default, not about what the role means
 * once it is granted. Both sentences are true at once: nobody is admin
 * unless Matt says so, and admin does what it likes.
 */
describe('roles', () => {
  /** A GET carrying a session, which `helpers` has no shorthand for. */
  const getAs = (path, token) => call(path, { method: 'GET', token })
  const getAnon = (path) => call(path, { method: 'GET' })

  async function account(name, { slug = null, role = null } = {}) {
    const user = await signIn(env.DB, {
      provider: 'google', subject: `sub-${name}`, name, email: `${name}@example.com`,
    })
    if (slug) await env.DB.prepare('UPDATE users SET slug = ?2 WHERE id = ?1').bind(user.id, slug).run()
    if (role) await env.DB.prepare('UPDATE users SET role = ?2 WHERE id = ?1').bind(user.id, role).run()
    return { user, token: await newSession(env.DB, user.id) }
  }

  const list = '1 Sol Ring (M3C) 409'

  // ------------------------------------------------------- the two roles

  it('a new account is a user', async () => {
    const { user } = await account('Fresh')
    const row = (await sql('SELECT role FROM users WHERE id = ?1', user.id))[0]
    expect(row.role).toBe('user')
  })

  it('a user edits only its own cards', async () => {
    const { token } = await account('Someone', { slug: 'someone' })
    expect((await postAs('/cards/add', { owner: 'someone', list, dry_run: true }, token, stubScryfall())).status)
      .toBe(200)
    expect((await postAs('/cards/add', { owner: 'matt', list, dry_run: true }, token, stubScryfall())).status)
      .toBe(403)
  })

  it('an admin edits anybody\'s', async () => {
    const { token } = await account('Boss', { slug: 'boss', role: 'admin' })
    const r = await postAs('/cards/add', { owner: 'matt', list, dry_run: true }, token, stubScryfall())
    expect(r.status).toBe(200)
  })

  // ------------------------------------------------------- who is there

  it('an admin can see every account', async () => {
    await account('Someone', { slug: 'someone' })
    const { token } = await account('Boss', { slug: 'boss', role: 'admin' })
    const r = await getAs('/admin/users', token)
    expect(r.status).toBe(200)
    const slugs = r.body.users.map((u) => u.slug)
    expect(slugs).toContain('someone')
    expect(slugs).toContain('boss')
    const boss = r.body.users.find((u) => u.slug === 'boss')
    expect(boss.role).toBe('admin')
    expect(boss.key).toBeTruthy()
  })

  it('and the list carries no email, because a role list is not a mailing list', async () => {
    const { token } = await account('Boss', { slug: 'boss', role: 'admin' })
    const r = await getAs('/admin/users', token)
    r.body.users.forEach((u) => {
      expect(Object.keys(u)).not.toContain('email')
      expect(JSON.stringify(u)).not.toContain('@example.com')
    })
  })

  it('a user cannot see the list', async () => {
    const { token } = await account('Someone', { slug: 'someone' })
    expect((await getAs('/admin/users', token)).status).toBe(403)
  })

  it('nobody at all cannot see the list', async () => {
    expect((await getAnon('/admin/users')).status).toBe(401)
  })

  // ------------------------------------------------------ handing it out

  it('an admin can promote somebody', async () => {
    const { user } = await account('Someone', { slug: 'someone' })
    const { token } = await account('Boss', { slug: 'boss', role: 'admin' })
    const r = await postAs('/admin/role', { slug: 'someone', role: 'admin' }, token)
    expect(r.status).toBe(200)
    expect((await sql('SELECT role FROM users WHERE id = ?1', user.id))[0].role).toBe('admin')
  })

  it('and demote them again', async () => {
    const { user } = await account('Other', { slug: 'other', role: 'admin' })
    const { token } = await account('Boss', { slug: 'boss', role: 'admin' })
    const r = await postAs('/admin/role', { slug: 'other', role: 'user' }, token)
    expect(r.status).toBe(200)
    expect((await sql('SELECT role FROM users WHERE id = ?1', user.id))[0].role).toBe('user')
  })

  it('a user cannot promote anybody, least of all itself', async () => {
    const { user, token } = await account('Someone', { slug: 'someone' })
    const r = await postAs('/admin/role', { slug: 'someone', role: 'admin' }, token)
    expect(r.status).toBe(403)
    expect((await sql('SELECT role FROM users WHERE id = ?1', user.id))[0].role).toBe('user')
  })

  it('nobody at all cannot', async () => {
    await account('Someone', { slug: 'someone' })
    const r = await postAnon('/admin/role', { slug: 'someone', role: 'admin' })
    expect(r.status).toBe(401)
  })

  it('there is no third role to be given', async () => {
    const { user } = await account('Someone', { slug: 'someone' })
    const { token } = await account('Boss', { slug: 'boss', role: 'admin' })
    for (const role of ['owner', 'superuser', '', 'ADMIN', 'admin ', null]) {
      const r = await postAs('/admin/role', { slug: 'someone', role }, token)
      expect(r.status, `role ${JSON.stringify(role)} was accepted`).toBe(400)
    }
    expect((await sql('SELECT role FROM users WHERE id = ?1', user.id))[0].role).toBe('user')
  })

  it('a role cannot be given to somebody who is not there', async () => {
    const { token } = await account('Boss', { slug: 'boss', role: 'admin' })
    const r = await postAs('/admin/role', { slug: 'nobody-at-all', role: 'admin' }, token)
    expect(r.status).toBe(404)
  })

  /**
   * The one refusal that is about the system rather than the caller.
   *
   * Roles are handed out by somebody who has one. The last admin
   * demoting themselves leaves a database nobody can promote anybody
   * from — recoverable only by `ADMIN_PASSWORD` and raw SQL, which is
   * a bad afternoon rather than a feature.
   */
  it('the last admin cannot demote themselves', async () => {
    const { user, token } = await account('Boss', { slug: 'boss', role: 'admin' })
    const r = await postAs('/admin/role', { slug: 'boss', role: 'user' }, token)
    expect(r.status).toBe(409)
    expect((await sql('SELECT role FROM users WHERE id = ?1', user.id))[0].role).toBe('admin')
  })

  it('but one of two admins can', async () => {
    const { user, token } = await account('Boss', { slug: 'boss', role: 'admin' })
    await account('Other', { slug: 'other', role: 'admin' })
    const r = await postAs('/admin/role', { slug: 'boss', role: 'user' }, token)
    expect(r.status).toBe(200)
    expect((await sql('SELECT role FROM users WHERE id = ?1', user.id))[0].role).toBe('user')
  })

  it('the operator\'s password can still do it with no account at all', async () => {
    // The scripts hold one, and a locked-out database is fixed from
    // there rather than from a browser.
    const { user } = await account('Someone', { slug: 'someone' })
    const r = await post('/admin/role', { slug: 'someone', role: 'admin' })
    expect(r.status).toBe(200)
    expect((await sql('SELECT role FROM users WHERE id = ?1', user.id))[0].role).toBe('admin')
  })

  it('/auth/me says which role you are, so the app can draw the right menu', async () => {
    const { token } = await account('Boss', { slug: 'boss', role: 'admin' })
    const r = await getAs('/auth/me', token)
    expect(r.body.role).toBe('admin')
  })
})
