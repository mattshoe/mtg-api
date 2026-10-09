import { env } from 'cloudflare:test'
import { describe, it, expect } from 'vitest'
import { post, postAnon, postAs, call, sql, mattSession } from './helpers.js'
import { signIn, newSession } from '../src/accounts.js'

/**
 * A new task, written in the app and picked up by the laptop.
 *
 * Matt: "I want the option to add a new task from the admin settings. I
 * want to be able to tap a "new task" button and get a simple but
 * attractive new screen where i can enter the details and upload files".
 *
 * A request only builds once it is a file in `requests/` on the laptop
 * that dispatches, and the app cannot write there. So the app writes it
 * here, into an inbox, and `scripts/intake/inbox.mjs` on the laptop
 * collects it and writes the file. Admin only on both ends: a task is a
 * change to the app itself, which nobody without the role asks for.
 */
describe('tasks', () => {
  const getAs = (path, token) => call(path, { method: 'GET', token })

  async function user() {
    const u = await signIn(env.DB, {
      provider: 'google', subject: 'sub-plain', name: 'Plain', email: 'plain@example.com',
    })
    return newSession(env.DB, u.id)
  }

  // "hello", as base64.
  const HELLO = 'aGVsbG8='

  it('an admin sends a task and the inbox hands it back, files and all', async () => {
    const sent = await post('/tasks', {
      title: 'Bigger buttons',
      details: 'The buttons on the deck page are too small to hit.',
      files: [{ name: 'shot.png', type: 'image/png', data: HELLO }],
    })
    expect(sent.status).toBe(200)
    const { key } = await sent.json()
    expect(key).toMatch(/^[a-z0-9]{8}$/)

    const inbox = await (await getAs('/tasks/inbox', await mattSession())).json()
    expect(inbox.tasks).toHaveLength(1)
    const t = inbox.tasks[0]
    expect(t).toMatchObject({ key, title: 'Bigger buttons', details: 'The buttons on the deck page are too small to hit.' })
    expect(t.created_at).toMatch(/^\d{4}-\d\d-\d\dT/)
    expect(t.files).toEqual([{ name: 'shot.png', type: 'image/png', data: HELLO }])
  })

  it('a task the laptop has received is not handed out again', async () => {
    const { key } = await (await post('/tasks', { title: 'One', details: 'x' })).json()
    expect((await post('/tasks/inbox/received', { keys: [key] })).status).toBe(200)
    const inbox = await (await getAs('/tasks/inbox', await mattSession())).json()
    expect(inbox.tasks).toEqual([])
    const row = await sql('SELECT received_at FROM task_inbox WHERE key = ?1', key)
    expect(row[0].received_at).toBeTruthy()
  })

  it('nobody without the admin role sends one or reads the inbox', async () => {
    const token = await user()
    expect((await postAs('/tasks', { title: 'Mine', details: 'x' }, token)).status).toBe(403)
    expect((await postAnon('/tasks', { title: 'Mine', details: 'x' })).status).toBe(401)
    expect((await getAs('/tasks/inbox', token)).status).toBe(403)
    expect((await postAs('/tasks/inbox/received', { keys: [] }, token)).status).toBe(403)
    expect(await sql('SELECT * FROM task_inbox')).toEqual([])
  })

  it('a task needs a title and some details', async () => {
    const r = await post('/tasks', { title: '  ', details: 'x' })
    expect(r.status).toBe(400)
    expect((await r.json()).error).toBe('a task needs a title')
    const d = await post('/tasks', { title: 'Something', details: '' })
    expect(d.status).toBe(400)
    expect((await d.json()).error).toBe('say what the task is')
  })

  it('a file too big to keep is refused by name, and nothing is written', async () => {
    const big = btoa('x'.repeat(1_500_001))
    const r = await post('/tasks', {
      title: 'Huge', details: 'x', files: [{ name: 'huge.bin', type: 'application/octet-stream', data: big }],
    })
    expect(r.status).toBe(400)
    expect((await r.json()).error).toBe('huge.bin is over 1.5 MB')
    expect(await sql('SELECT * FROM task_inbox')).toEqual([])
  })
})
