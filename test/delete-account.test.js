import { env } from 'cloudflare:test'
import { describe, it, expect } from 'vitest'
import { postOperator, postAs, postAnon, call, sql, exec, MATT, KAYLA, mattSession } from './helpers.js'
import { signIn, newSession } from '../src/accounts.js'

/**
 * Deleting an account, from Admin Settings.
 *
 * Matt: "i want to be able to delete accounts via admin settings, and
 * all associated database info. deleting an account should NOT have
 * any impact on other users whatsoever. this is ABSOLUTELY CRITICAL."
 *
 * So two things are proved here, and the second matters more:
 *
 * - everything the account owned is gone, table by table
 * - every row anybody else owns is byte for byte what it was
 *
 * The second is proved by dumping every table in the database before
 * and after, not by checking the tables somebody thought of. A table
 * added next month that points at `users` and is not listed in
 * `OWNED` below fails `every table that points at an account is
 * accounted for`, so a delete cannot quietly start leaving rows
 * behind, or quietly start taking somebody else's.
 *
 * The predicates are written here, independently of `deleteAccount`,
 * on purpose: a test that borrowed the implementation's list would
 * agree with it whatever it said.
 */

/** Which rows of each table belong to account `?1`. */
const CARD = 'SELECT id FROM cards WHERE owner_id = ?1'
const DECK = 'SELECT id FROM decks WHERE owner_id = ?1'
const OWNED = {
  users: 'id = ?1',
  identities: 'user_id = ?1',
  sessions: 'user_id = ?1',
  devices: 'user_id = ?1',
  fetch_lists: 'user_id = ?1',
  cards: 'owner_id = ?1',
  card_faces: `card_id IN (${CARD})`,
  card_colors: `card_id IN (${CARD})`,
  card_types: `card_id IN (${CARD})`,
  card_keywords: `card_id IN (${CARD})`,
  card_finishes: `card_id IN (${CARD})`,
  card_games: `card_id IN (${CARD})`,
  card_promo_types: `card_id IN (${CARD})`,
  card_frame_effects: `card_id IN (${CARD})`,
  card_tags: `card_id IN (${CARD})`,
  card_search: `rowid IN (${CARD})`,
  decks: 'owner_id = ?1',
  deck_cards: `deck_id IN (${DECK})`,
  deck_notes: `deck_id IN (${DECK})`,
}

/**
 * Rows that name the account but are not the account's: a task Kayla
 * sent is still a task on the board. It stays, with nobody's name on it.
 */
const NAMED = { task_inbox: 'created_by' }

/** Written by every request, so it cannot be part of "nothing changed". */
const NOISE = new Set(['logs'])

async function tables() {
  const rows = await sql(
    `SELECT name FROM sqlite_master WHERE type = 'table'
       AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'card_search_%'
       AND name NOT LIKE '_cf_%' AND name NOT LIKE 'd1_%' ORDER BY name`,
  )
  return rows.map((r) => r.name).filter((n) => !NOISE.has(n))
}

/** Every row of every table, as a string per row so two dumps compare exactly. */
async function dump(where = () => null, id = null) {
  const out = {}
  for (const t of await tables()) {
    const w = where(t)
    const rows = await sql(
      `SELECT rowid AS _rowid, * FROM ${t}${w ? ` WHERE ${w}` : ''} ORDER BY rowid`,
      ...(w && w.includes('?1') ? [id] : []),
    )
    out[t] = rows.map((r) => JSON.stringify(r, (k, v) => (v instanceof ArrayBuffer ? [...new Uint8Array(v)] : v)))
  }
  return out
}

/** Every row that is not account `id`'s. */
const notTheirs = (id) => dump((t) => (OWNED[t] ? `NOT (${OWNED[t]})` : null), id)

/** Every row that is account `id`'s. */
async function theirs(id) {
  const out = {}
  for (const [t, w] of Object.entries(OWNED)) {
    out[t] = (await sql(`SELECT COUNT(*) AS n FROM ${t} WHERE ${w}`, id))[0].n
  }
  return out
}

async function account(name, { role = null } = {}) {
  const user = await signIn(env.DB, {
    provider: 'google', subject: `sub-${name}`, name, email: `${name}@example.com`,
  })
  if (role) await exec('UPDATE users SET role = ?2 WHERE id = ?1', user.id, role)
  return { user, token: await newSession(env.DB, user.id) }
}

/**
 * Give Kayla (id 3) and Matt (id 1) one of everything an account can
 * own, so a delete that missed a table, or reached past one, has
 * something to miss or reach.
 *
 * Both of them get the same things on purpose: a search row for every
 * card, a device, saved Fetch lists, a session, a sign-in, a task, and
 * a deck note. Two accounts holding the same printing, the same card
 * name and the same kind of row is exactly where a `WHERE` that
 * matched on a name instead of an id would take the wrong one.
 */
async function furnish() {
  await mattSession()
  // Once a test: the refusal loops below call it per attempt.
  if ((await sql("SELECT 1 FROM devices WHERE token = 'fcm-kayla'")).length) return
  await exec(`INSERT INTO card_search (rowid, name, type_line, oracle_text, flavor_text, keywords, tags)
              SELECT id, name, type_line, oracle_text, flavor_text, '', '' FROM cards
               WHERE id NOT IN (SELECT rowid FROM card_search)`)
  for (const [id, who] of [[1, 'matt'], [3, 'kayla']]) {
    await exec(`INSERT INTO identities (provider, subject, user_id, created_at)
                VALUES ('google', ?2, ?1, datetime('now'))`, id, `sub-${who}`)
    await newSession(env.DB, id)
    await exec(`INSERT INTO devices (token, user_id, platform, created_at, seen_at)
                VALUES (?2, ?1, 'android', datetime('now'), datetime('now'))`, id, `fcm-${who}`)
    await exec(`INSERT INTO fetch_lists (user_id, lists, updated_at) VALUES (?1, '[]', datetime('now'))`, id)
    await exec(`INSERT INTO task_inbox (key, title, details, created_by, created_at)
                VALUES (?2, 'a task', 'details', ?1, datetime('now'))`, id, `task${who}`)
    await exec(`INSERT INTO deck_notes (deck_id, section, body)
                SELECT id, 'Gameplan', 'win' FROM decks WHERE owner_id = ?1`, id)
    await exec(`INSERT INTO deck_cards (deck_id, qty, name, name_norm, role)
                SELECT id, 1, 'Sol Ring', 'sol ring', 'spell' FROM decks WHERE owner_id = ?1`, id)
    await exec(`INSERT OR IGNORE INTO card_tags (card_id, tag, kind)
                SELECT id, 'ramp', 'oracle' FROM cards WHERE owner_id = ?1`, id)
  }
  // Kayla holds a printing Matt holds too, under her own id.
  await exec(`INSERT INTO cards (owner_id, qty, finish, scryfall_id, name, name_norm)
              SELECT 3, 1, finish, scryfall_id, name, name_norm FROM cards
               WHERE owner_id = 1 AND NOT EXISTS (
                 SELECT 1 FROM cards k WHERE k.owner_id = 3 AND k.scryfall_id = cards.scryfall_id
                   AND k.finish = cards.finish)
               LIMIT 1`)
}

const remove = (key, token) => postAs('/admin/users/delete', { key }, token)

describe('deleting an account', () => {
  it('the account is set up with something in every table it can own, or this proves nothing', async () => {
    await furnish()
    const have = await theirs(3)
    for (const [t, n] of Object.entries(have)) {
      expect(n, `Kayla owns no ${t} rows, so deleting her proves nothing about ${t}`).toBeGreaterThan(0)
    }
    const matt = await theirs(1)
    for (const [t, n] of Object.entries(matt)) {
      expect(n, `Matt owns no ${t} rows, so his surviving it proves nothing about ${t}`).toBeGreaterThan(0)
    }
  })

  it('an admin deletes an account and everything it owned goes', async () => {
    await furnish()
    const r = await remove(KAYLA, await mattSession())
    expect(r.status, JSON.stringify(r.body)).toBe(200)
    const left = await theirs(3)
    for (const [t, n] of Object.entries(left)) expect(n, `${n} of Kayla's ${t} rows survived`).toBe(0)
  })

  it('and not one row belonging to anybody else changes', async () => {
    await furnish()
    const other = await account('Other')
    await exec('INSERT INTO fetch_lists (user_id, lists, updated_at) VALUES (?1, ?2, datetime(\'now\'))',
      other.user.id, '[{"name":"x"}]')
    const before = await notTheirs(3)
    const r = await remove(KAYLA, await mattSession())
    expect(r.status, JSON.stringify(r.body)).toBe(200)
    const after = await dump()

    for (const t of Object.keys(before)) {
      if (NAMED[t]) continue
      expect(after[t], `${t} is not what it was for everybody else`).toEqual(before[t])
    }
  })

  it('a task the account sent stays on the board, with nobody named on it', async () => {
    await furnish()
    await remove(KAYLA, await mattSession())
    const rows = await sql("SELECT key, created_by FROM task_inbox WHERE key IN ('taskkayla', 'taskmatt') ORDER BY key")
    expect(rows).toEqual([{ key: 'taskkayla', created_by: null }, { key: 'taskmatt', created_by: 1 }])
    const others = (await notTheirs(3)).task_inbox
    expect(others.length).toBeGreaterThan(0)
  })

  it('the deleted account\'s session no longer signs anybody in', async () => {
    const { user, token } = await account('Gone')
    expect((await call('/auth/me', { method: 'GET', token })).body.key).toBe(user.key)
    expect((await remove(user.key, await mattSession())).status).toBe(200)
    const me = await call('/auth/me', { method: 'GET', token })
    expect(me.body.key ?? null, 'a deleted account is still signed in').toBeNull()
  })

  it('it says what it took', async () => {
    await furnish()
    const want = await theirs(3)
    const r = await remove(KAYLA, await mattSession())
    expect(r.body.key).toBe(KAYLA)
    expect(r.body.deleted.cards).toBe(want.cards)
    expect(r.body.deleted.decks).toBe(want.decks)
  })

  it('it is gone from the admin list and from the open collection lookup', async () => {
    await remove(KAYLA, await mattSession())
    const list = await call('/admin/users', { method: 'GET', token: await mattSession() })
    expect(list.body.users.map((u) => u.key)).not.toContain(KAYLA)
    expect((await call(`/c/${KAYLA}`, { method: 'GET' })).status).toBe(404)
  })

  it('signing in again with the same Google account makes a new, empty account', async () => {
    const { user } = await account('Back')
    await exec(`INSERT INTO cards (owner_id, qty, finish, scryfall_id, name, name_norm)
                VALUES (?1, 1, 'nonfoil', 'x', 'Sol Ring', 'sol ring')`, user.id)
    await remove(user.key, await mattSession())
    const again = await signIn(env.DB, { provider: 'google', subject: 'sub-Back', name: 'Back' })
    expect(again.key).not.toBe(user.key)
    expect((await sql('SELECT COUNT(*) AS n FROM cards WHERE owner_id = ?1', again.id))[0].n).toBe(0)
  })

  it('the operator\'s password may delete one too', async () => {
    const { user } = await account('Gone')
    expect((await postOperator('/admin/users/delete', { key: user.key })).status).toBe(200)
    expect((await sql('SELECT COUNT(*) AS n FROM users WHERE id = ?1', user.id))[0].n).toBe(0)
  })

  it('an admin may delete another admin', async () => {
    const { user } = await account('Other', { role: 'admin' })
    expect((await remove(user.key, await mattSession())).status).toBe(200)
  })

  // ------------------------------------------------------------ refusals

  /** Every refusal below leaves the whole database as it was. */
  async function refusedWithNothingChanged(send, status) {
    await furnish()
    const before = await dump()
    const r = await send()
    expect(r.status, JSON.stringify(r.body)).toBe(status)
    expect(r.body.error, 'a refusal says why').toBeTruthy()
    expect(await dump(), 'a refused delete changed the database').toEqual(before)
  }

  it('a user cannot delete anybody', async () => {
    const { token } = await account('Someone')
    await refusedWithNothingChanged(() => remove(KAYLA, token), 403)
  })

  it('a user cannot delete itself through here either', async () => {
    const { user, token } = await account('Someone')
    await refusedWithNothingChanged(() => remove(user.key, token), 403)
  })

  it('the build agent cannot delete anybody', async () => {
    const { token } = await account('Agent', { role: 'agent' })
    await refusedWithNothingChanged(() => remove(KAYLA, token), 403)
  })

  it('nobody at all cannot', async () => {
    await refusedWithNothingChanged(() => postAnon('/admin/users/delete', { key: KAYLA }), 401)
  })

  it('an admin cannot delete their own account from here', async () => {
    await refusedWithNothingChanged(async () => remove(MATT, await mattSession()), 409)
  })

  it('a key nobody has deletes nothing', async () => {
    await refusedWithNothingChanged(async () => remove('nobody00', await mattSession()), 404)
  })

  it('no key, or a key that is not a string, deletes nothing', async () => {
    for (const key of [undefined, null, '', '   ', 3, ['k4yy0003'], { key: KAYLA }]) {
      await refusedWithNothingChanged(async () => remove(key, await mattSession()), 400)
    }
  })

  it('a key that is a pattern, not a key, deletes nothing', async () => {
    for (const key of ['%', 'k4yy%', '_4yy0003', "k4yy0003' OR '1'='1"]) {
      await refusedWithNothingChanged(async () => remove(key, await mattSession()), 404)
    }
  })

  it('it is a POST', async () => {
    const r = await call('/admin/users/delete', { method: 'GET', token: await mattSession() })
    expect(r.status).toBe(405)
    expect((await sql('SELECT COUNT(*) AS n FROM users WHERE key = ?1', KAYLA))[0].n).toBe(1)
  })

  // ------------------------------------------------------------ the net

  it('an account with nothing at all deletes cleanly and touches nobody', async () => {
    await furnish()
    const { user } = await account('Empty')
    const before = await notTheirs(user.id)
    expect((await remove(user.key, await mattSession())).status).toBe(200)
    const after = await dump()
    for (const t of Object.keys(before)) expect(after[t], `${t} changed`).toEqual(before[t])
  })

  it('cards with no owner at all are never taken for the deleted account\'s', async () => {
    await furnish()
    await exec(`INSERT INTO cards (owner_id, qty, finish, scryfall_id, name, name_norm)
                VALUES (NULL, 1, 'nonfoil', 'orphan', 'Orphan', 'orphan')`)
    await remove(KAYLA, await mattSession())
    expect((await sql("SELECT COUNT(*) AS n FROM cards WHERE scryfall_id = 'orphan'"))[0].n).toBe(1)
  })

  it('every table that points at an account is accounted for', async () => {
    const missing = []
    for (const t of await tables()) {
      if (OWNED[t] || NAMED[t]) continue
      const fks = await sql(`PRAGMA foreign_key_list(${t})`)
      const cols = (await sql(`PRAGMA table_info(${t})`)).map((c) => c.name)
      const pointsAtUsers = fks.some((f) => f.table === 'users')
        || cols.some((c) => /^(user_id|owner_id|created_by)$/.test(c))
      // Pointing at a table that belongs to an account counts too.
      const pointsAtOwned = fks.some((f) => OWNED[f.table] && f.table !== 'users')
        || cols.some((c) => /^(card_id|deck_id)$/.test(c))
      if (pointsAtUsers || pointsAtOwned) missing.push(t)
    }
    expect(missing, 'a table points at an account and deleting one does not know about it').toEqual([])
  })
})
