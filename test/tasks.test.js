import { env } from 'cloudflare:test'
import { describe, it, expect } from 'vitest'
import { post, postAnon, postAs, call, sql, exec, mattSession } from './helpers.js'
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
    const { key } = sent.body
    expect(key).toMatch(/^[a-z0-9]{8}$/)

    const inbox = (await getAs('/tasks/inbox', await mattSession())).body
    expect(inbox.tasks).toHaveLength(1)
    const t = inbox.tasks[0]
    expect(t).toMatchObject({ key, title: 'Bigger buttons', details: 'The buttons on the deck page are too small to hit.' })
    expect(t.created_at).toMatch(/^\d{4}-\d\d-\d\dT/)
    expect(t.files).toEqual([{ name: 'shot.png', type: 'image/png', data: HELLO }])
  })

  it('a task the laptop has received is not handed out again', async () => {
    const { key } = (await post('/tasks', { title: 'One', details: 'x' })).body
    expect((await post('/tasks/inbox/received', { keys: [key] })).status).toBe(200)
    const inbox = (await getAs('/tasks/inbox', await mattSession())).body
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
    expect(r.body.error).toBe('a task needs a title')
    const d = await post('/tasks', { title: 'Something', details: '' })
    expect(d.status).toBe(400)
    expect(d.body.error).toBe('say what the task is')
  })

  it('a file too big to keep is refused by name, and nothing is written', async () => {
    const big = btoa('x'.repeat(1_500_001))
    const r = await post('/tasks', {
      title: 'Huge', details: 'x', files: [{ name: 'huge.bin', type: 'application/octet-stream', data: big }],
    })
    expect(r.status).toBe(400)
    expect(r.body.error).toBe('huge.bin is over 1.5 MB')
    expect(await sql('SELECT * FROM task_inbox')).toEqual([])
  })

  it('the open /query cannot read a task, any more than it can read the log', async () => {
    await post('/tasks', { title: 'Private', details: 'not for strangers' })
    const g = await call(`/query?sql=${encodeURIComponent('SELECT details FROM task_inbox')}`, { method: 'GET' })
    expect(g.status).toBe(401)
    const p = await postAnon('/query', { sql: 'SELECT name FROM task_files' })
    expect(p.status).toBe(401)
  })
})

/**
 * Where a task is, kept in D1 and written as it happens.
 *
 * Matt: "The system is just looking at fucking branch names?!?!" The
 * status used to be inferred in the app from GitHub's branches and pull
 * requests, unauthenticated from the phone at sixty asks an hour. Now the
 * dispatcher on the laptop writes each transition to the same row the
 * inbox already keeps, and the app asks the Worker and nothing else.
 */
describe('task status', () => {
  const getAs = async (path, token) => call(path, { method: 'GET', token: token ?? await mattSession() })

  it('a task sent from the app is queued, and the list says so', async () => {
    const { key } = (await post('/tasks', { title: 'Bigger buttons', details: 'x' })).body
    const r = await getAs('/tasks')
    expect(r.status).toBe(200)
    expect(r.body.tasks).toEqual([expect.objectContaining({ key, title: 'Bigger buttons', status: 'queued' })])
  })

  it('the dispatcher writes building then done, and the start survives the finish', async () => {
    const b = await post('/tasks/status', { name: 'deck-colours', title: 'Deck colours', status: 'building' })
    expect(b.status).toBe(200)
    const started = (await getAs('/tasks')).body.tasks[0]
    expect(started).toMatchObject({ name: 'deck-colours', title: 'Deck colours', status: 'building' })
    expect(started.started_at).toMatch(/^\d{4}-\d\d-\d\dT/)
    expect(started.finished_at).toBeNull()

    await post('/tasks/status', { name: 'deck-colours', status: 'done', pr: 'https://github.com/mattshoe/mtg-api/pull/9' })
    const tasks = (await getAs('/tasks')).body.tasks
    expect(tasks).toHaveLength(1)
    expect(tasks[0]).toMatchObject({
      status: 'done', started_at: started.started_at, pr: 'https://github.com/mattshoe/mtg-api/pull/9',
    })
    expect(tasks[0].finished_at).toMatch(/^\d{4}-\d\d-\d\dT/)
  })

  it('a task from the app is one row from sending to done, not a second one', async () => {
    const { key } = (await post('/tasks', { title: 'One row', details: 'x' })).body
    await post('/tasks/inbox/received', { keys: [key], names: { [key]: 'one-row' } })
    await post('/tasks/status', { name: 'one-row', title: 'One row', status: 'building' })
    const tasks = (await getAs('/tasks')).body.tasks
    expect(tasks).toEqual([expect.objectContaining({ key, name: 'one-row', status: 'building' })])
  })

  it('a cancelled task is never collected', async () => {
    const { key } = (await post('/tasks', { title: 'Never mind', details: 'x' })).body
    expect((await post('/tasks/status', { key, status: 'cancelled' })).status).toBe(200)
    expect((await getAs('/tasks/inbox')).body.tasks).toEqual([])
    expect((await getAs('/tasks')).body.tasks[0].status).toBe('cancelled')
  })

  it('a status nobody defined is refused, and so is a task nobody named', async () => {
    const r = await post('/tasks/status', { name: 'x', title: 'X', status: 'nearly' })
    expect(r.status).toBe(400)
    expect(r.body.error).toMatch(/^no such status: nearly/)
    const n = await post('/tasks/status', { status: 'building' })
    expect(n.status).toBe(400)
    expect(n.body.error).toBe('which task: a key or a name')
    expect(await sql('SELECT * FROM task_inbox')).toEqual([])
  })

  it('nobody without the admin role reads the list or writes a status', async () => {
    const u = await signIn(env.DB, { provider: 'google', subject: 'sub-s', name: 'S', email: 's@example.com' })
    const token = await newSession(env.DB, u.id)
    expect((await getAs('/tasks', token)).status).toBe(403)
    expect((await call('/tasks', { method: 'GET' })).status).toBe(401)
    expect((await postAs('/tasks/status', { name: 'x', title: 'X', status: 'building' }, token)).status).toBe(403)
    expect(await sql('SELECT * FROM task_inbox')).toEqual([])
  })
})

/**
 * The release notes, through the Worker. GitHub is still the record —
 * `release.yml` writes the note into the release — but the app no longer
 * asks it directly: the Worker keeps the last answer in D1 and asks
 * GitHub again at most every few minutes, so sixty asks an hour from a
 * phone's address stops mattering and a refusal serves the last copy.
 */
describe('release notes', () => {
  const RELEASES = [{ tag_name: 'android-v1.2.0-300', published_at: '2026-10-08T10:00:00Z', body: 'Bigger buttons.' }]

  function github(answer = RELEASES, status = 200) {
    const asked = []
    const impl = async (url) => {
      asked.push(String(url))
      return new Response(JSON.stringify(answer), { status, headers: { 'content-type': 'application/json' } })
    }
    return { impl, asked }
  }

  const releases = (gh) => call('/releases', { method: 'GET', githubFetch: gh.impl })

  it('comes from GitHub once, then from D1', async () => {
    const gh = github()
    const r = await releases(gh)
    expect(r.status).toBe(200)
    expect(r.body).toEqual([expect.objectContaining({ tag_name: 'android-v1.2.0-300', body: 'Bigger buttons.' })])
    expect(gh.asked).toEqual(['https://api.github.com/repos/mattshoe/mtg-api/releases?per_page=50'])
    await releases(gh)
    expect(gh.asked).toHaveLength(1)
  })

  it('a refusal from GitHub serves the last copy rather than an error', async () => {
    await releases(github())
    await exec("UPDATE github_releases SET fetched_at = '2000-01-01T00:00:00Z'")
    const r = await releases(github({ message: 'API rate limit exceeded' }, 403))
    expect(r.status).toBe(200)
    expect(r.body[0].tag_name).toBe('android-v1.2.0-300')
  })

  it('a refusal with no copy kept says what GitHub said', async () => {
    const r = await releases(github({ message: 'API rate limit exceeded' }, 403))
    expect(r.status).toBe(502)
    expect(r.body.error).toBe('API rate limit exceeded')
  })
})
