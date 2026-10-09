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

  // Matt: "a details page that shows the whole request and all
  // information about it, including any attachments". The page asks for
  // one task by its key, and its files are still there once the laptop
  // has collected it.
  it('a received task keeps its files, and an admin reads it back by key', async () => {
    const { key } = (await post('/tasks', {
      title: 'Bigger buttons', details: 'Too small.', files: [{ name: 'shot.png', type: 'image/png', data: HELLO }],
    })).body
    await post('/tasks/inbox/received', { keys: [key], names: { [key]: 'bigger-buttons' } })
    const r = await getAs(`/tasks/${key}`, await mattSession())
    expect(r.status).toBe(200)
    expect(r.body).toMatchObject({ key, name: 'bigger-buttons', title: 'Bigger buttons', details: 'Too small.', status: 'pending' })
    expect(r.body.files).toEqual([{ name: 'shot.png', type: 'image/png', data: HELLO }])
  })

  it('a task nobody sent is a 404, and nobody without the role reads one', async () => {
    expect((await getAs('/tasks/zzzzzzzz', await mattSession())).status).toBe(404)
    const { key } = (await post('/tasks', { title: 'Private', details: 'x' })).body
    expect((await getAs(`/tasks/${key}`, await user())).status).toBe(403)
    expect((await call(`/tasks/${key}`, { method: 'GET' })).status).toBe(401)
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

  it('a task sent from the app is pending, and the list says so and since when', async () => {
    const { key } = (await post('/tasks', { title: 'Bigger buttons', details: 'x' })).body
    const r = await getAs('/tasks')
    expect(r.status).toBe(200)
    expect(r.body.tasks).toEqual([expect.objectContaining({ key, title: 'Bigger buttons', status: 'pending' })])
    expect(r.body.tasks[0].status_at).toMatch(/^\d{4}-\d\d-\d\dT/)
  })

  it('the dispatcher writes in progress then merged, and the start survives the finish', async () => {
    const b = await post('/tasks/status', { name: 'deck-colours', title: 'Deck colours', status: 'in progress' })
    expect(b.status).toBe(200)
    const started = (await getAs('/tasks')).body.tasks[0]
    expect(started).toMatchObject({ name: 'deck-colours', title: 'Deck colours', status: 'in progress' })
    expect(started.started_at).toMatch(/^\d{4}-\d\d-\d\dT/)
    expect(started.finished_at).toBeNull()

    await post('/tasks/status', { name: 'deck-colours', status: 'merged', pr: 'https://github.com/mattshoe/mtg-api/pull/9' })
    const tasks = (await getAs('/tasks')).body.tasks
    expect(tasks).toHaveLength(1)
    expect(tasks[0]).toMatchObject({
      status: 'merged', started_at: started.started_at, pr: 'https://github.com/mattshoe/mtg-api/pull/9',
    })
    expect(tasks[0].finished_at).toMatch(/^\d{4}-\d\d-\d\dT/)
  })

  it('every status Matt asked for is one the Worker takes', async () => {
    for (const status of ['pending', 'in progress', 'blocked', 'paused', 'in review', 'merged', 'deployed', 'cancelled']) {
      const r = await post('/tasks/status', { name: 'every-one', title: 'Every one', status })
      expect(r.status, status + ' was refused: ' + r.body.error).toBe(200)
    }
  })

  it('there is no failing and no stopped: red CI is still in review, a dead agent is paused', async () => {
    for (const status of ['failing', 'stopped', 'closed', 'building', 'queued', 'done']) {
      const r = await post('/tasks/status', { name: 'no-churn', title: 'No churn', status })
      expect(r.status, status + ' was taken, and Matt said there is no such state').toBe(400)
    }
  })

  it('the detail rides with the status: why it is blocked, which job is red, and it goes with the state', async () => {
    await post('/tasks/status', { name: 'needs-matt', title: 'Needs Matt', status: 'blocked', note: 'merge: ask, waiting on Matt to merge #12' })
    expect((await getAs('/tasks')).body.tasks[0]).toMatchObject({ status: 'blocked', note: 'merge: ask, waiting on Matt to merge #12' })
    await post('/tasks/status', { name: 'needs-matt', status: 'in review', pr: 'https://github.com/mattshoe/mtg-api/pull/12', note: 'CI running, android is red' })
    expect((await getAs('/tasks')).body.tasks[0]).toMatchObject({ status: 'in review', note: 'CI running, android is red' })
    await post('/tasks/status', { name: 'needs-matt', status: 'merged' })
    expect((await getAs('/tasks')).body.tasks[0]).toMatchObject({ status: 'merged', note: null })
  })

  it('the time in a state is when it entered that state, and writing the same state again does not reset it', async () => {
    await post('/tasks/status', { name: 'clock', title: 'Clock', status: 'in review' })
    expect((await getAs('/tasks')).body.tasks[0].status_at).toMatch(/^\d{4}-/)
    await sql("UPDATE task_inbox SET status_at = '2026-01-01T00:00:00.000Z'")
    await post('/tasks/status', { name: 'clock', status: 'in review', note: 'still waiting on CI' })
    expect((await getAs('/tasks')).body.tasks[0]).toMatchObject({ status_at: '2026-01-01T00:00:00.000Z', note: 'still waiting on CI' })
    await post('/tasks/status', { name: 'clock', status: 'merged' })
    expect((await getAs('/tasks')).body.tasks[0].status_at).not.toBe('2026-01-01T00:00:00.000Z')
  })

  it('a task from the app is one row from sending to merged, not a second one', async () => {
    const { key } = (await post('/tasks', { title: 'One row', details: 'x' })).body
    await post('/tasks/inbox/received', { keys: [key], names: { [key]: 'one-row' } })
    await post('/tasks/status', { name: 'one-row', title: 'One row', status: 'in progress' })
    const tasks = (await getAs('/tasks')).body.tasks
    expect(tasks).toEqual([expect.objectContaining({ key, name: 'one-row', status: 'in progress' })])
  })

  // A request written straight into requests/ has no details from the
  // app, so the dispatcher sends the file's text with its first status.
  it('the request file the dispatcher sends is the details of a task written on the laptop, and only of that one', async () => {
    const text = '---\nstatus: ready\n---\n\n# Deck colours\n\nShow them.\n'
    await post('/tasks/status', { name: 'deck-colours', title: 'Deck colours', status: 'in progress', details: text })
    const [row] = (await getAs('/tasks')).body.tasks
    expect((await getAs(`/tasks/${row.key}`)).body).toMatchObject({ details: text, status: 'in progress' })

    const { key } = (await post('/tasks', { title: 'From the app', details: 'What Matt typed.' })).body
    await post('/tasks/inbox/received', { keys: [key], names: { [key]: 'from-the-app' } })
    await post('/tasks/status', { name: 'from-the-app', status: 'in progress', details: '# From the app\n\nWhat Matt typed.\n' })
    expect((await getAs(`/tasks/${key}`)).body.details).toBe('What Matt typed.')
  })

  it('a cancelled task is never collected', async () => {
    const { key } = (await post('/tasks', { title: 'Never mind', details: 'x' })).body
    expect((await post('/tasks/status', { key, status: 'cancelled' })).status).toBe(200)
    expect((await getAs('/tasks/inbox')).body.tasks).toEqual([])
    expect((await getAs('/tasks')).body.tasks[0].status).toBe('cancelled')
  })

  it('a request held back is paused, and nothing about that reads as in progress', async () => {
    expect((await post('/tasks/status', { name: 'held-one', title: 'Held one', status: 'paused' })).status).toBe(200)
    const [t] = (await getAs('/tasks')).body.tasks
    expect(t).toMatchObject({ name: 'held-one', status: 'paused', started_at: null, finished_at: null })
  })

  it('a status nobody defined is refused, and so is a task nobody named', async () => {
    const r = await post('/tasks/status', { name: 'x', title: 'X', status: 'nearly' })
    expect(r.status).toBe(400)
    expect(r.body.error).toMatch(/^no such status: nearly/)
    const n = await post('/tasks/status', { status: 'in progress' })
    expect(n.status).toBe(400)
    expect(n.body.error).toBe('which task: a key or a name')
    expect(await sql('SELECT * FROM task_inbox')).toEqual([])
  })

  it('nobody without the admin role reads the list or writes a status', async () => {
    const u = await signIn(env.DB, { provider: 'google', subject: 'sub-s', name: 'S', email: 's@example.com' })
    const token = await newSession(env.DB, u.id)
    expect((await getAs('/tasks', token)).status).toBe(403)
    expect((await call('/tasks', { method: 'GET' })).status).toBe(401)
    expect((await postAs('/tasks/status', { name: 'x', title: 'X', status: 'in progress' }, token)).status).toBe(403)
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
