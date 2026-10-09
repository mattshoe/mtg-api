import { describe, it, expect } from 'vitest'
import { outcome, titleOf, report, reconcile, requestOfBranch } from '../scripts/intake/task-status.mjs'

/**
 * The dispatcher's half of "task status lives in D1".
 *
 * Matt: "The system is just looking at fucking branch names?!?!" The app
 * used to infer every status from GitHub. Now the dispatcher, which is
 * the thing that starts a builder and sees it end, writes each transition
 * to the task's row as it happens: `in progress` when it starts one, and
 * what the pull request came to when the builder exits.
 */
describe('what the dispatcher writes', () => {
  const pr = (state, n) => ({ state, url: 'https://github.com/mattshoe/mtg-api/pull/' + n })

  it('a merged pull request is merged, and names it', () => {
    expect(outcome([pr('MERGED', 7)])).toEqual({ status: 'merged', pr: 'https://github.com/mattshoe/mtg-api/pull/7' })
  })

  it('an open one is in review, whatever its CI is doing', () => {
    expect(outcome([pr('OPEN', 8)])).toEqual({ status: 'in review', pr: 'https://github.com/mattshoe/mtg-api/pull/8' })
  })

  it('a pull request closed without merging is paused, and says so', () => {
    expect(outcome([pr('CLOSED', 9)])).toEqual({
      status: 'paused', pr: 'https://github.com/mattshoe/mtg-api/pull/9', note: 'pull request closed without merging',
    })
  })

  it('a request built twice is at the furthest its pull requests got', () => {
    expect(outcome([pr('CLOSED', 1), pr('MERGED', 2), pr('OPEN', 3)])).toEqual({
      status: 'merged', pr: 'https://github.com/mattshoe/mtg-api/pull/2',
    })
  })

  it('a builder that ended with no pull request is paused, with why, rather than in progress forever', () => {
    expect(outcome([])).toEqual({
      status: 'paused', pr: null, note: 'the agent stopped with no pull request; its work is kept in the worktree',
    })
  })

  it('the title is the request file\'s heading, after its frontmatter', () => {
    expect(titleOf('---\nstatus: ready\n---\n\n# Bigger buttons\n\nThey are small.\n')).toBe('Bigger buttons')
    expect(titleOf('no heading here')).toBe('')
  })

  it('posts the transition to the Worker with the agent token, by the request\'s name', async () => {
    const calls = []
    const fetch = async (url, init) => { calls.push({ url, init }); return new Response('{}', { status: 200 }) }
    await report(
      { name: 'bigger-buttons', status: 'in progress', title: 'Bigger buttons' },
      { token: 't0k', base: 'https://api.test', fetch },
    )
    expect(calls).toHaveLength(1)
    expect(calls[0].url).toBe('https://api.test/tasks/status')
    expect(calls[0].init.method).toBe('POST')
    expect(calls[0].init.headers.authorization).toBe('Bearer t0k')
    expect(JSON.parse(calls[0].init.body)).toEqual({ name: 'bigger-buttons', status: 'in progress', title: 'Bigger buttons' })
  })

  it('a refusal is a thrown sentence, not a silent nothing', async () => {
    const fetch = async () => new Response('{"error":"that needs the admin role"}', { status: 403 })
    await expect(report({ name: 'x', status: 'in progress' }, { token: 't', base: 'https://api.test', fetch }))
      .rejects.toThrow('the Worker refused x in progress: that needs the admin role')
  })

  it('a builder whose request was withdrawn while it ran is cancelled, and says so rather than paused', () => {
    expect(outcome([], { withdrawn: true })).toEqual({ status: 'cancelled', pr: null, note: 'request withdrawn' })
    expect(outcome([pr('OPEN', 4)], { withdrawn: true }).status).toBe('in review')
  })
})

/**
 * What the dispatcher can see on the laptop on every run, held against
 * what D1 says. Matt, on four stopped tasks all reading `building`: "Make
 * god damn sure that gets fixed in the port." `in progress` means an agent
 * is alive on it right now; anything else the row says out loud, with why.
 */
describe('what the dispatcher puts right on every run', () => {
  const row = (name, status) => ({ key: 'k-' + name, name, status })
  const seen = (over = {}) => ({ rows: [], ready: [], held: [], filed: [], stalled: [], ...over })

  it('a request held back from building is paused, held by Matt', () => {
    expect(reconcile(seen({ held: ['card-page'], rows: [row('card-page', 'pending')] })))
      .toEqual([{ name: 'card-page', status: 'paused', note: 'held by Matt' }])
  })

  it('a ready request nobody has a row for is pending, and a paused one let go is pending again', () => {
    expect(reconcile(seen({ ready: ['new-one', 'let-go'], rows: [row('let-go', 'paused')] })))
      .toEqual([{ name: 'new-one', status: 'pending' }, { name: 'let-go', status: 'pending' }])
  })

  it('an in progress row with no agent alive in its worktree is paused, and says the agent stopped', () => {
    expect(reconcile(seen({ ready: ['died'], stalled: ['died'], rows: [row('died', 'in progress')] })))
      .toEqual([{ name: 'died', status: 'paused', note: 'the agent stopped; its work is kept in the worktree' }])
  })

  it('a stalled one already paused is not let go to pending while its worktree waits', () => {
    expect(reconcile(seen({ ready: ['kept'], stalled: ['kept'], rows: [row('kept', 'paused')] }))).toEqual([])
  })

  it('a stalled one with its pull request open stays in review', () => {
    expect(reconcile(seen({ ready: ['waiting'], stalled: ['waiting'], rows: [row('waiting', 'in review')] })))
      .toEqual([])
  })

  it('a request whose file is gone is cancelled, and a finished one is left alone', () => {
    expect(reconcile(seen({
      rows: [row('gone', 'pending'), row('shipped', 'merged'), row('live', 'deployed'), row('dropped', 'cancelled')],
    }))).toEqual([{ name: 'gone', status: 'cancelled', note: 'request withdrawn' }])
  })

  it('a filed request nobody has a row for is merged', () => {
    expect(reconcile(seen({ filed: ['old'] }))).toEqual([{ name: 'old', status: 'merged' }])
  })

  it('a task the laptop has not collected yet has no name, and is not cancelled for having no file', () => {
    expect(reconcile(seen({ rows: [{ key: 'k1', name: null, status: 'pending' }] }))).toEqual([])
  })

  it('a row that already says the right thing is not written again', () => {
    expect(reconcile(seen({ held: ['h'], ready: ['r'], rows: [row('h', 'paused'), row('r', 'pending')] }))).toEqual([])
  })

  it('an agent at work is left alone', () => {
    expect(reconcile(seen({ ready: ['busy'], rows: [row('busy', 'in progress')] }))).toEqual([])
  })
})

/**
 * Matt, on the live list after #73 and #74: "Both of those are done
 * already??!! And what hairbrush to the task details page?! And the
 * fucking elapsed time is gone from the completed ones!!!" Four ways the
 * list lied, each from reconcile looking at less than it could see.
 */
describe('reconcile tells the truth', () => {
  const row = (name, status, more = {}) => ({ key: 'k-' + name, name, status, ...more })
  const seen = (over = {}) => ({ rows: [], ready: [], held: [], filed: [], stalled: [], alive: [], prs: {}, branches: [], ...over })
  const pr = (state, n, more = {}) => ({ state, url: 'https://github.com/mattshoe/mtg-api/pull/' + n, ...more })

  it('a task with an agent alive on it is in progress, not pending', () => {
    expect(reconcile(seen({ ready: ['card-page'], alive: ['card-page'], rows: [row('card-page', 'pending')] })))
      .toEqual([{ name: 'card-page', status: 'in progress' }])
  })

  it('an agent alive on a task nobody has a row for gives it one, in progress', () => {
    expect(reconcile(seen({ ready: ['charts'], alive: ['charts'] })))
      .toEqual([{ name: 'charts', status: 'in progress' }])
  })

  it('a request filed under done/ is never cancelled: done by hand, it is merged', () => {
    expect(reconcile(seen({ filed: ['admin-role'], rows: [row('admin-role', 'cancelled', { note: 'request withdrawn' })] })))
      .toEqual([{ name: 'admin-role', status: 'merged' }])
  })

  it('a request filed under done/ while its row still says pending is merged', () => {
    expect(reconcile(seen({ filed: ['by-hand'], rows: [row('by-hand', 'pending')] })))
      .toEqual([{ name: 'by-hand', status: 'merged' }])
  })

  it('a worktree with no agent and no row gets one, paused, saying the agent stopped', () => {
    expect(reconcile(seen({ ready: ['details'], stalled: ['details'], branches: ['details'] })))
      .toEqual([{ name: 'details', status: 'paused', note: 'the agent stopped; its work is kept in the worktree' }])
  })

  it('a pull request nobody has a row for gives it one, at what the pull request came to', () => {
    expect(reconcile(seen({ ready: ['open-one'], prs: { 'open-one': [pr('OPEN', 80)] } })))
      .toEqual([{ name: 'open-one', status: 'in review', pr: 'https://github.com/mattshoe/mtg-api/pull/80' }])
  })

  it('a branch with no request file anywhere still has a row, and it says withdrawn', () => {
    expect(reconcile(seen({ branches: ['orphan'] })))
      .toEqual([{ name: 'orphan', status: 'cancelled', note: 'request withdrawn' }])
  })

  it('a merged task with no start is written again with its pull request\'s times', () => {
    const prs = { shipped: [pr('MERGED', 71, { createdAt: '2026-10-08T09:00:00Z', mergedAt: '2026-10-08T10:15:00Z' })] }
    expect(reconcile(seen({ filed: ['shipped'], prs, rows: [row('shipped', 'merged', { started_at: null })] })))
      .toEqual([{
        name: 'shipped', status: 'merged', pr: 'https://github.com/mattshoe/mtg-api/pull/71',
        started_at: '2026-10-08T09:00:00Z', finished_at: '2026-10-08T10:15:00Z',
      }])
  })

  it('a merged task that already has its start is left alone', () => {
    const prs = { shipped: [pr('MERGED', 71, { createdAt: '2026-10-08T09:00:00Z', mergedAt: '2026-10-08T10:15:00Z' })] }
    expect(reconcile(seen({
      filed: ['shipped'], prs, rows: [row('shipped', 'merged', { started_at: '2026-10-08T08:00:00Z' })],
    }))).toEqual([])
  })

  it('a branch the dispatcher made names its request, and any other branch names none', () => {
    expect(requestOfBranch('request/task-details-page-da60d2b')).toBe('task-details-page')
    expect(requestOfBranch('request/task-details-page-0000000')).toBeNull()
    expect(requestOfBranch('recover/card-page-shows-everything')).toBeNull()
  })

  it('what a merged pull request settles to carries its times', () => {
    expect(outcome([pr('MERGED', 5, { createdAt: '2026-10-08T09:00:00Z', mergedAt: '2026-10-08T10:00:00Z' })])).toEqual({
      status: 'merged', pr: 'https://github.com/mattshoe/mtg-api/pull/5',
      started_at: '2026-10-08T09:00:00Z', finished_at: '2026-10-08T10:00:00Z',
    })
  })
})
