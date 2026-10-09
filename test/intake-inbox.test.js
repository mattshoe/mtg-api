import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import { mkdtempSync, mkdirSync, writeFileSync, readFileSync, readdirSync, existsSync, rmSync } from 'node:fs'
import { join } from 'node:path'
import { tmpdir } from 'node:os'
import { collect, requestFor } from '../scripts/intake/inbox.mjs'

/**
 * The laptop's half of "add a new task from the admin settings".
 *
 * The app writes a task into the Worker's inbox; this collects it into
 * `requests/`, where writing the file is what starts a build. Its files
 * go under `.intake/attachments/`, which is gitignored, so a builder can
 * read them and can never commit them by accident.
 */
describe('collecting tasks from the app', () => {
  let repo
  beforeEach(() => {
    repo = mkdtempSync(join(tmpdir(), 'intake-inbox-'))
    mkdirSync(join(repo, 'requests', 'done'), { recursive: true })
    mkdirSync(join(repo, '.intake'), { recursive: true })
    writeFileSync(join(repo, '.intake', 'enabled'), '')
  })
  afterEach(() => rmSync(repo, { recursive: true, force: true }))

  const TASK = {
    key: 'ab12cd34',
    title: 'Bigger buttons on the deck page',
    details: 'They are too small to hit with a thumb.',
    created_at: '2026-10-08T20:00:00.000Z',
    files: [{ name: 'shot.png', type: 'image/png', data: Buffer.from('png bytes').toString('base64') }],
  }

  /** A Worker with these tasks in its inbox, recording what it was told. */
  function worker(tasks) {
    const calls = []
    const fetch = async (url, init = {}) => {
      calls.push({ url, method: init.method || 'GET', auth: init.headers?.authorization, body: init.body })
      if (url.endsWith('/tasks/inbox')) return new Response(JSON.stringify({ tasks }), { status: 200 })
      if (url.endsWith('/tasks/inbox/received')) return new Response('{}', { status: 200 })
      return new Response('{}', { status: 404 })
    }
    return { fetch, calls }
  }

  it('writes a ready request with the title, the details and where its files are', async () => {
    const w = worker([TASK])
    const got = await collect({ repo, token: 't0k', base: 'https://api.test', fetch: w.fetch })
    expect(got).toEqual(['bigger-buttons-on-the-deck-page.md'])

    const text = readFileSync(join(repo, 'requests', 'bigger-buttons-on-the-deck-page.md'), 'utf8')
    expect(text).toMatch(/^---\nstatus: ready\n---\n/)
    expect(text).toContain('# Bigger buttons on the deck page')
    expect(text).toContain('They are too small to hit with a thumb.')
    const file = join(repo, '.intake', 'attachments', 'bigger-buttons-on-the-deck-page', 'shot.png')
    expect(text).toContain(file)
    expect(readFileSync(file, 'utf8')).toBe('png bytes')
  })

  it('tells the Worker it has them, with the admin token, only after writing', async () => {
    const w = worker([TASK])
    await collect({ repo, token: 't0k', base: 'https://api.test', fetch: w.fetch })
    expect(w.calls.map((c) => [c.method, c.url])).toEqual([
      ['GET', 'https://api.test/tasks/inbox'],
      ['POST', 'https://api.test/tasks/inbox/received'],
    ])
    expect(w.calls.every((c) => c.auth === 'Bearer t0k')).toBe(true)
    expect(JSON.parse(w.calls[1].body)).toEqual({ keys: ['ab12cd34'] })
  })

  it('never overwrites a request with the same name, live or done', async () => {
    writeFileSync(join(repo, 'requests', 'done', 'bigger-buttons-on-the-deck-page.md'), 'old')
    const got = await collect({ repo, token: 't', base: 'https://api.test', fetch: worker([TASK]).fetch })
    expect(got).toEqual(['bigger-buttons-on-the-deck-page-ab12cd34.md'])
    expect(readFileSync(join(repo, 'requests', 'done', 'bigger-buttons-on-the-deck-page.md'), 'utf8')).toBe('old')
  })

  it('keeps a file name from walking out of its folder', () => {
    const r = requestFor({ ...TASK, files: [{ name: '../../evil.sh', type: 'text/plain', data: '' }] }, { repo, taken: () => false })
    expect(r.files.map((f) => f.path)).toEqual([
      join(repo, '.intake', 'attachments', 'bigger-buttons-on-the-deck-page', 'evil.sh'),
    ])
  })

  it('does nothing at all while the intake is switched off', async () => {
    writeFileSync(join(repo, '.intake', 'disabled'), '')
    const w = worker([TASK])
    expect(await collect({ repo, token: 't', base: 'https://api.test', fetch: w.fetch })).toEqual([])
    expect(w.calls).toEqual([])
    expect(readdirSync(join(repo, 'requests'))).toEqual(['done'])
  })

  it('a refused inbox is a thrown sentence, and nothing is acknowledged', async () => {
    const fetch = async () => new Response('{"error":"that needs the admin role"}', { status: 403 })
    await expect(collect({ repo, token: 't', base: 'https://api.test', fetch }))
      .rejects.toThrow('the inbox refused: that needs the admin role')
    expect(existsSync(join(repo, '.intake', 'attachments'))).toBe(false)
  })
})
