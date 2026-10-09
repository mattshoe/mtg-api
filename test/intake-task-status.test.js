import { describe, it, expect } from 'vitest'
import { outcome, titleOf, report, reconcile } from '../scripts/intake/task-status.mjs'

/**
 * The dispatcher's half of "task status lives in D1".
 *
 * Matt: "The system is just looking at fucking branch names?!?!" The app
 * used to infer every status from GitHub. Now the dispatcher, which is
 * the thing that starts a builder and sees it end, writes each transition
 * to the task's row as it happens: `building` when it starts one, and
 * what the pull request came to when the builder exits.
 */
describe('what the dispatcher writes', () => {
  const pr = (state, n) => ({ state, url: `https://github.com/mattshoe/mtg-api/pull/${n}` })

  it('a merged pull request is done, and names it', () => {
    expect(outcome([pr('MERGED', 7)])).toEqual({ status: 'done', pr: 'https://github.com/mattshoe/mtg-api/pull/7' })
  })

  it('an open one is in review, a closed one closed', () => {
    expect(outcome([pr('OPEN', 8)]).status).toBe('in review')
    expect(outcome([pr('CLOSED', 9)]).status).toBe('closed')
  })

  it('a request built twice is at the furthest its pull requests got', () => {
    expect(outcome([pr('CLOSED', 1), pr('MERGED', 2), pr('OPEN', 3)])).toEqual({
      status: 'done', pr: 'https://github.com/mattshoe/mtg-api/pull/2',
    })
  })

  it('a builder that ended with no pull request stopped, rather than building forever', () => {
    expect(outcome([])).toEqual({ status: 'stopped', pr: null })
  })

  it('the title is the request file\'s heading, after its frontmatter', () => {
    expect(titleOf('---\nstatus: ready\n---\n\n# Bigger buttons\n\nThey are small.\n')).toBe('Bigger buttons')
    expect(titleOf('no heading here')).toBe('')
  })

  it('posts the transition to the Worker with the agent token, by the request\'s name', async () => {
    const calls = []
    const fetch = async (url, init) => { calls.push({ url, init }); return new Response('{}', { status: 200 }) }
    await report(
      { name: 'bigger-buttons', status: 'building', title: 'Bigger buttons' },
      { token: 't0k', base: 'https://api.test', fetch },
    )
    expect(calls).toHaveLength(1)
    expect(calls[0].url).toBe('https://api.test/tasks/status')
    expect(calls[0].init.method).toBe('POST')
    expect(calls[0].init.headers.authorization).toBe('Bearer t0k')
    expect(JSON.parse(calls[0].init.body)).toEqual({ name: 'bigger-buttons', status: 'building', title: 'Bigger buttons' })
  })

  it('a refusal is a thrown sentence, not a silent nothing', async () => {
    const fetch = async () => new Response('{"error":"that needs the admin role"}', { status: 403 })
    await expect(report({ name: 'x', status: 'building' }, { token: 't', base: 'https://api.test', fetch }))
      .rejects.toThrow('the Worker refused x building: that needs the admin role')
  })

  it('a builder whose request was withdrawn while it ran is cancelled, and says so rather than stopped', () => {
    expect(outcome([], { withdrawn: true })).toEqual({ status: 'cancelled', pr: null })
    expect(outcome([pr('OPEN', 4)], { withdrawn: true }).status).toBe('in review')
  })
})

/**
 * What the dispatcher can see on the laptop on every run, held against
 * what D1 says. Matt, on four stopped tasks all reading `building`: "Make
 * god damn sure that gets fixed in the port." `building` means an agent is
 * alive on it right now; anything else the row says out loud.
 */
describe('what the dispatcher puts right on every run', () => {
  const row = (name, status) => ({ key: 'k-' + name, name, status })
  const seen = (over = {}) => ({ rows: [], ready: [], held: [], filed: [], stalled: [], ...over })

  it('a request held back from building is paused', () => {
    expect(reconcile(seen({ held: ['card-page'], rows: [row('card-page', 'queued')] })))
      .toEqual([{ name: 'card-page', status: 'paused' }])
  })

  it('a ready request nobody has a row for is queued, and a paused one let go is queued again', () => {
    expect(reconcile(seen({ ready: ['new-one', 'let-go'], rows: [row('let-go', 'paused')] })))
      .toEqual([{ name: 'new-one', status: 'queued' }, { name: 'let-go', status: 'queued' }])
  })

  it('a building row with no agent alive in its worktree is stopped', () => {
    expect(reconcile(seen({ ready: ['died'], stalled: ['died'], rows: [row('died', 'building')] })))
      .toEqual([{ name: 'died', status: 'stopped' }])
  })

  it('a stalled one with its pull request open stays in review', () => {
    expect(reconcile(seen({ ready: ['waiting'], stalled: ['waiting'], rows: [row('waiting', 'in review')] })))
      .toEqual([])
  })

  it('a request whose file is gone is cancelled, and a finished one is left alone', () => {
    expect(reconcile(seen({ rows: [row('gone', 'queued'), row('shipped', 'done'), row('dropped', 'cancelled')] })))
      .toEqual([{ name: 'gone', status: 'cancelled' }])
  })

  it('a filed request nobody has a row for is done', () => {
    expect(reconcile(seen({ filed: ['old'] }))).toEqual([{ name: 'old', status: 'done' }])
  })

  it('a task the laptop has not collected yet has no name, and is not cancelled for having no file', () => {
    expect(reconcile(seen({ rows: [{ key: 'k1', name: null, status: 'queued' }] }))).toEqual([])
  })

  it('a row that already says the right thing is not written again', () => {
    expect(reconcile(seen({ held: ['h'], ready: ['r'], rows: [row('h', 'paused'), row('r', 'queued')] }))).toEqual([])
  })
})
