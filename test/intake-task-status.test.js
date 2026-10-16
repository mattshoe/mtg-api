import { describe, it, expect } from 'vitest'
import { spawn, execFileSync } from 'node:child_process'
import { outcome, titleOf, started, report, reconcile, requestOfBranch, cancels, cancelFile, stop, whoIsWorking } from '../scripts/intake/task-status.mjs'
import { statusOf, buildable, branchFor } from '../scripts/intake.mjs'

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

  // The task's own page in the app shows the whole request, and one
  // written straight into requests/ is only on this laptop until now.
  it('in progress carries the request file whole, so the app can show it', () => {
    const text = '---\nstatus: ready\n---\n\n# Bigger buttons\n\nThey are small.\n'
    expect(started('bigger-buttons', text)).toEqual({
      name: 'bigger-buttons', status: 'in progress', title: 'Bigger buttons', details: text,
    })
    expect(started('gone', '')).toEqual({ name: 'gone', status: 'in progress' })
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

  // `blocked` was sticky: once written, nothing cleared it. The
  // dispatcher writes it for a worktree with no agent in it, and two
  // tasks then sat reading `blocked` when one had already MERGED and the
  // other had an agent working on it. A state that cannot be left is not
  // a status, it is a dead end.
  // Reversed deliberately. Matt: "A task should NEVER enter blocked
  // unless the process is COMPLETELY unrecoverable... It is NOT a
  // temporary status, it is a PERMANENT, COMPLETELY UNRESOLVABLE
  // status."
  //
  // This used to assert that a live agent took a task back out of
  // `blocked`, which is what let it flap: the dispatcher wrote it for a
  // stalled worktree, reconcile cleared it, the next tick wrote it
  // again — once a minute, with a push notification each time.
  it('is not cleared by an agent running: blocked is terminal', () => {
    expect(reconcile(seen({ alive: ['card-page'], ready: ['card-page'], rows: [row('card-page', 'blocked')] })))
      .toEqual([])
  })

  it('is not cleared by the request simply being ready again', () => {
    expect(reconcile(seen({ ready: ['card-page'], rows: [row('card-page', 'blocked')] })))
      .toEqual([])
  })

  it('a merge clears blocked', () => {
    const wrote = reconcile(seen({
      ready: ['card-page'],
      rows: [row('card-page', 'blocked')],
      prs: { 'card-page': [{ state: 'MERGED', url: 'u', mergedAt: '2026-10-12T00:00:00Z' }] },
    }))
    expect(wrote.map((w) => w.status)).toEqual(['merged'])
  })

  it('a request held back from building is paused, held by Matt', () => {
    expect(reconcile(seen({ held: ['card-page'], rows: [row('card-page', 'pending')] })))
      .toEqual([{ name: 'card-page', status: 'paused', note: 'held by Matt' }])
  })

  it('a ready request nobody has a row for is queued, and a paused one let go is queued again', () => {
    expect(reconcile(seen({ ready: ['new-one', 'let-go'], rows: [row('let-go', 'paused')] })))
      .toEqual([{ name: 'new-one', status: 'queued' }, { name: 'let-go', status: 'queued' }])
  })

  it('an in progress row with no agent alive in its worktree is paused, and says the agent stopped', () => {
    expect(reconcile(seen({ ready: ['died'], stalled: ['died'], rows: [row('died', 'in progress')] })))
      .toEqual([{ name: 'died', status: 'paused', note: 'the agent stopped; its work is kept in the worktree' }])
  })

  it('a stalled one already paused is not let go to queued while its worktree waits', () => {
    expect(reconcile(seen({ ready: ['kept'], stalled: ['kept'], rows: [row('kept', 'paused')] }))).toEqual([])
  })

  it('a stalled one with its pull request open stays in review', () => {
    expect(reconcile(seen({ ready: ['waiting'], stalled: ['waiting'], rows: [row('waiting', 'in review')] })))
      .toEqual([])
  })

  it('a request whose file this laptop cannot see is paused, not cancelled', () => {
    // It used to be cancelled, and `cancel` then rewrote the request
    // file and KILLED the agent on it. That happened to two of Matt's
    // tasks, one of them mid-build: "Dude why did you cancel 2 of my
    // tasks?!"
    //
    // "I cannot see a request file" means the file is gone OR the
    // checkout is somewhere else OR a branch moved under us, and only
    // the first is a withdrawal. A guess that destroys a ticket is not
    // worth making. A real cancel comes from Matt in the app.
    expect(reconcile(seen({ rows: [row('gone', 'queued'), row('shipped', 'merged'), row('live', 'deployed'), row('dropped', 'cancelled')] })))
      .toEqual([{ name: 'gone', status: 'paused', note: 'no request file on this laptop right now; not cancelled, just not found' }])
  })

  it('a filed request nobody has a row for is merged', () => {
    expect(reconcile(seen({ filed: ['old'] }))).toEqual([{ name: 'old', status: 'merged' }])
  })

  it('a task the laptop has not collected yet has no name, and is not cancelled for having no file', () => {
    expect(reconcile(seen({ rows: [{ key: 'k1', name: null, status: 'queued' }] }))).toEqual([])
  })

  it('a row that already says the right thing is not written again', () => {
    expect(reconcile(seen({ held: ['h'], ready: ['r'], rows: [row('h', 'paused'), row('r', 'queued')] }))).toEqual([])
  })

  it('an agent at work is left alone', () => {
    // At work means in `alive` — a process whose argv names the
    // request. That is the only evidence anything is running.
    expect(reconcile(seen({ ready: ['busy'], alive: ['busy'], rows: [row('busy', 'in progress')] }))).toEqual([])
  })

  it('a row that says in progress with nothing running is put back in the queue', () => {
    // This used to be the case above, and it asserted the opposite: a
    // row reading `in progress` was trusted whether or not anything was
    // running. That is the lie Matt caught — "a row read `in progress`
    // for three hours after I had switched the system off".
    //
    // With one task at a time it is worse than a lie, it is the queue
    // reading as busy when it is idle. No agent and no worktree means
    // ready and waiting, which is `queued`.
    expect(reconcile(seen({ ready: ['busy'], rows: [row('busy', 'in progress')] })))
      .toEqual([{ name: 'busy', status: 'queued' }])
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

  it('a task with an agent alive on it is in progress, not queued', () => {
    expect(reconcile(seen({ ready: ['card-page'], alive: ['card-page'], rows: [row('card-page', 'queued')] })))
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

  it('a branch with no request file anywhere still has a row, and it says so without cancelling', () => {
    // The row has to exist — a branch with nothing behind it is worth
    // seeing — but it is paused, not cancelled, because the dispatcher
    // kills agents on cancelled rows and this is a guess.
    expect(reconcile(seen({ branches: ['stray'], rows: [] })))
      .toEqual([{ name: 'stray', status: 'paused', note: 'no request file on this laptop right now; not cancelled, just not found' }])
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

/**
 * Matt: "What the fuck is the deployed status of nothing fucking uses
 * it?!?!" A merge runs `pages`, `release` and `worker` on its merge
 * commit, by path. Reconcile reads those runs and moves the row on: all
 * of them green is deployed, one still going or one red says so on the
 * merged row, and a merge nothing deployed says that instead of waiting.
 */
describe('deployed is written from the deploy runs of the merge commit', () => {
  const row = (name, status, more = {}) => ({ key: 'k-' + name, name, status, started_at: '2026-10-09T08:00:00Z', ...more })
  const seen = (over = {}) => ({
    rows: [], ready: [], held: [], filed: [], stalled: [], alive: [], prs: {}, branches: [], now: '2026-10-09T12:00:00Z', ...over,
  })
  const merged = (n, sha, mergedAt = '2026-10-09T10:00:00Z') => ({
    state: 'MERGED', url: 'https://github.com/mattshoe/mtg-api/pull/' + n,
    createdAt: '2026-10-09T09:00:00Z', mergedAt, mergeCommit: { oid: sha },
  })
  const run = (workflowName, headSha, conclusion, createdAt = '2026-10-09T10:00:05Z') => ({
    workflowName, headSha, createdAt, status: conclusion ? 'completed' : 'in_progress', conclusion: conclusion || '',
  })
  const times = { started_at: '2026-10-09T09:00:00Z', finished_at: '2026-10-09T10:00:00Z' }
  const url = (n) => 'https://github.com/mattshoe/mtg-api/pull/' + n

  it('a merge whose every deploy run went green is deployed, and says what went live', () => {
    const runs = [run('pages', 'abc', 'success'), run('release', 'abc', 'success'), run('test', 'abc', 'failure')]
    expect(reconcile(seen({ filed: ['shipped'], prs: { shipped: [merged(80, 'abc')] }, runs, rows: [row('shipped', 'merged')] })))
      .toEqual([{ name: 'shipped', status: 'deployed', ...times, pr: url(80), note: 'live: the site, the APK' }])
  })

  it('a merge with a deploy still running stays merged, and says it is deploying', () => {
    const runs = [run('worker', 'abc', 'success'), run('release', 'abc', null)]
    expect(reconcile(seen({ filed: ['going'], prs: { going: [merged(81, 'abc')] }, runs, rows: [row('going', 'merged')] })))
      .toEqual([{ name: 'going', status: 'merged', ...times, pr: url(81), note: 'deploying: the APK' }])
  })

  it('a merge whose deploy failed stays merged, and names the one that failed', () => {
    const runs = [run('pages', 'abc', 'failure'), run('release', 'abc', 'success')]
    expect(reconcile(seen({ filed: ['broke'], prs: { broke: [merged(82, 'abc')] }, runs, rows: [row('broke', 'merged')] })))
      .toEqual([{ name: 'broke', status: 'merged', ...times, pr: url(82), note: 'deploy failed: the site' }])
  })

  it('a deploy cancelled for a newer one that went green counts as live', () => {
    const runs = [
      run('pages', 'new', 'success', '2026-10-09T10:05:00Z'),
      run('pages', 'abc', 'cancelled'), run('release', 'abc', 'success'),
    ]
    expect(reconcile(seen({ filed: ['superseded'], prs: { superseded: [merged(83, 'abc')] }, runs, rows: [row('superseded', 'merged')] })))
      .toEqual([{ name: 'superseded', status: 'deployed', ...times, pr: url(83), note: 'live: the site, the APK' }])
  })

  it('a failed deploy that a later green one carried live stops reading merged forever', () => {
    // Six guide tasks sat at `merged` with "deploy failed: the site"
    // for a day. They were on main and live: the `pages` workflow had
    // been broken for eight hours — GitHub Pages was switched off on the
    // repo — and once it was fixed, a later run published main, these
    // merges included.
    //
    // Nothing moved them, because this only ever looked at runs whose
    // headSha is exactly the merge commit. A deploy is cumulative: main
    // is published whole, so a green deploy of any commit that CONTAINS
    // the merge means the merge is live. Without that, one bad afternoon
    // of CI pins a task at `merged` permanently and it never buzzes the
    // phone it was supposed to.
    const runs = [
      run('pages', 'abc', 'failure'),
      run('release', 'abc', 'success'),
      // Later, from a commit further along main that has 'abc' behind it.
      run('pages', 'later', 'success', '2026-10-09T11:00:00Z'),
    ]
    const contains = (ancestor, descendant) => ancestor === 'abc' && descendant === 'later'
    expect(reconcile(seen({
      filed: ['rescued'], prs: { rescued: [merged(84, 'abc')] }, runs, contains,
      rows: [row('rescued', 'merged')],
    })))
      .toEqual([{ name: 'rescued', status: 'deployed', ...times, pr: url(84), note: 'live: the site, the APK' }])
  })

  it('does not call it live off a later deploy of something that does not contain it', () => {
    // The guard on the above. A green deploy from a sibling branch, or
    // from a commit that predates the merge, says nothing about it.
    const runs = [
      run('pages', 'abc', 'failure'),
      run('release', 'abc', 'success'),
      run('pages', 'elsewhere', 'success', '2026-10-09T11:00:00Z'),
    ]
    const contains = () => false
    expect(reconcile(seen({
      filed: ['still-broken'], prs: { 'still-broken': [merged(85, 'abc')] }, runs, contains,
      rows: [row('still-broken', 'merged')],
    })))
      .toEqual([{ name: 'still-broken', status: 'merged', ...times, pr: url(85), note: 'deploy failed: the site' }])
  })

  it('a merge that set off no deploy at all says so rather than waiting at merged forever', () => {
    const runs = [run('release', 'other', 'success', '2026-10-09T07:00:00Z')]
    expect(reconcile(seen({ filed: ['docs'], prs: { docs: [merged(84, 'abc')] }, runs, rows: [row('docs', 'merged')] })))
      .toEqual([{ name: 'docs', status: 'merged', ...times, pr: url(84), note: 'nothing to deploy: no deploy ran for this merge' }])
  })

  it('a merge a minute old with no runs yet is not called undeployable', () => {
    const runs = [run('release', 'other', 'success', '2026-10-09T07:00:00Z')]
    const prs = { fresh: [merged(85, 'abc', '2026-10-09T11:59:00Z')] }
    expect(reconcile(seen({ filed: ['fresh'], prs, runs, rows: [row('fresh', 'merged')] }))).toEqual([])
  })

  it('a merge older than every run reconcile could see is left as it is', () => {
    const runs = [run('release', 'other', 'success', '2026-10-09T11:00:00Z')]
    expect(reconcile(seen({ filed: ['ancient'], prs: { ancient: [merged(86, 'abc')] }, runs, rows: [row('ancient', 'merged')] }))).toEqual([])
  })

  it('a deployed row that already says so is not written again', () => {
    const runs = [run('release', 'abc', 'success')]
    expect(reconcile(seen({
      filed: ['done'], prs: { done: [merged(87, 'abc')] }, runs, rows: [row('done', 'deployed', { note: 'live: the APK' })],
    }))).toEqual([])
  })

  it('a merged pull request whose request is still in the folder is deployed all the same', () => {
    const runs = [run('worker', 'abc', 'success'), run('release', 'abc', 'success')]
    expect(reconcile(seen({ ready: ['unfiled'], prs: { unfiled: [merged(88, 'abc')] }, runs, rows: [row('unfiled', 'in review')] })))
      .toEqual([{ name: 'unfiled', status: 'deployed', ...times, pr: url(88), note: 'live: the APK, the API' }])
  })
})

/**
 * Matt: "i want the ability to cancel/abort tasks and any running agent
 * should be immediately killed. prs, branches etc can stay just need to
 * kill the agent". The app writes `cancelled` to the row; the laptop,
 * on its next tick, marks the request file `status: cancelled` so it is
 * never built again, and kills whatever agent is working on it.
 */
describe('a task cancelled in the app', () => {
  const row = (name, status) => ({ key: 'k-' + name, name, status })
  const ready = '---\nstatus: ready\n---\n\n# Bigger buttons\n\nThey are too small.\n'

  it('marks its request file cancelled and kills its agent, and leaves every other task alone', () => {
    expect(cancels({
      rows: [row('dropped', 'cancelled'), row('kept', 'in progress'), row('waiting', 'cancelled')],
      files: { dropped: ready, kept: ready, waiting: ready },
      alive: ['dropped', 'kept'],
    })).toEqual({ files: ['dropped', 'waiting'], kill: ['dropped'] })
  })

  it('a request file already marked is not marked again, and an agent already gone is not hunted', () => {
    expect(cancels({
      rows: [row('done-with', 'cancelled')],
      files: { 'done-with': cancelFile(ready) },
      alive: [],
    })).toEqual({ files: [], kill: [] })
  })

  it('a request file marked cancelled says so in its frontmatter and keeps every word of the request', () => {
    const out = cancelFile(ready)
    expect(statusOf(out)).toBe('cancelled')
    expect(buildable(out)).toBe(false)
    expect(out).toContain('# Bigger buttons\n\nThey are too small.')
  })

  it('a request file with no frontmatter gets one that says cancelled', () => {
    const out = cancelFile('# Loose\n\nwords\n')
    expect(statusOf(out)).toBe('cancelled')
    expect(out).toContain('# Loose\n\nwords')
  })

  it('a builder killed for a cancel settles cancelled, not paused, and its open pull request stays named', () => {
    const open = { state: 'OPEN', url: 'https://github.com/mattshoe/mtg-api/pull/9' }
    expect(outcome([], { cancelled: true })).toEqual({ status: 'cancelled', pr: null, note: 'cancelled; the agent was stopped' })
    expect(outcome([open], { cancelled: true })).toEqual({
      status: 'cancelled', pr: 'https://github.com/mattshoe/mtg-api/pull/9', note: 'cancelled; the agent was stopped',
    })
  })

  it('reconcile keeps a cancelled request cancelled, even with an agent still alive in it', () => {
    const seen = { rows: [row('dropped', 'cancelled'), row('by-hand', 'in progress')], ready: [], held: [], filed: [], stalled: [], prs: {} }
    expect(reconcile({ ...seen, cancelled: ['dropped', 'by-hand'], alive: ['dropped', 'by-hand'] }))
      .toEqual([{ name: 'by-hand', status: 'cancelled', note: 'cancelled; the agent was stopped' }])
  })

  it('kills the agent named for the request, and everything it started, and nothing else', async () => {
    const name = `cancel-probe-${process.pid}-${Date.now()}`
    // An "agent" whose command line names the request, as `claude -p` does,
    // with a child of its own that does not, as a Bash tool call would be.
    const agent = spawn(process.execPath, ['-e', `
      const c = require('child_process').spawn(process.execPath, ['-e', 'setInterval(() => {}, 1000)'], { stdio: 'ignore' })
      console.log(c.pid); setInterval(() => {}, 1000)
    `, `requests/${name}.md`], { stdio: ['ignore', 'pipe', 'ignore'] })
    const bystander = spawn(process.execPath, ['-e', 'setInterval(() => {}, 1000)', `requests/${name}-other.md`], { stdio: 'ignore' })
    const child = Number(await new Promise((done) => agent.stdout.once('data', (d) => done(String(d).trim()))))
    // A zombie is dead: killed, and waiting for a parent that may never
    // reap it, as in a CI container whose pid 1 reaps nothing.
    const alive = (pid) => {
      try {
        return !execFileSync('ps', ['-o', 'stat=', '-p', String(pid)], { encoding: 'utf8' }).trim().startsWith('Z')
      } catch { return false }
    }
    // A SIGKILL lands within moments, not at once. Two seconds is plenty.
    const dies = async (pid) => {
      for (let i = 0; i < 40 && alive(pid); i++) await new Promise((r) => setTimeout(r, 50))
      return !alive(pid)
    }
    const gone = new Promise((done) => agent.once('exit', done))
    try {
      expect(alive(agent.pid) && alive(child), 'the probe never started, so this proves nothing').toBe(true)
      const killed = stop(name)
      await gone
      expect(killed).toEqual(expect.arrayContaining([agent.pid, child]))
      expect(await dies(agent.pid), 'the agent is still running').toBe(true)
      expect(await dies(child), 'the agent died but what it started is still running').toBe(true)
      expect(alive(bystander.pid), 'an agent on another request was killed too').toBe(true)
    } finally {
      for (const p of [agent.pid, child, bystander.pid]) { try { process.kill(p, 'SIGKILL') } catch { /* gone */ } }
    }
  })
})

/**
 * Who is working, and on what.
 *
 * Matt: "My task has been queued for almost 10 minutes now and nothing
 * is running." Something WAS running — it had an open pull request and
 * was inside the gate — but the board said `in review`, which reads as
 * waiting on a person, so the queue looked stalled behind a task that
 * looked idle.
 *
 * The cause: this asked `.intake/wt` which tasks had a directory and
 * checked only those. That directory stopped existing when the
 * per-task worktrees did, so the list was always empty and nothing was
 * ever seen as running. A detector that depends on where the work
 * happens breaks when the work moves.
 */
describe('who is working', () => {
  const run = (names) => (name) => names.includes(name)
  // The branch a request really gets, hash and all: `requestOfBranch`
  // checks the hash, so a made-up name is not a request branch.
  const requestBranch = (name) => branchFor(`${name}.md`)

  it('sees an agent on any known task, not only one with a directory', () => {
    const { alive } = whoIsWorking({
      repo: '/nowhere', known: ['building', 'waiting'], run: run(['building']), branch: '',
    })
    expect(alive).toEqual(['building'])
  })

  it('sees nobody when nothing names a request', () => {
    expect(whoIsWorking({ repo: '/nowhere', known: ['a', 'b'], run: run([]), branch: '' }).alive).toEqual([])
  })

  it('does not call the tree stalled while its agent is alive', () => {
    const { alive, stalled } = whoIsWorking({
      repo: '/nowhere', known: ['busy'], run: run(['busy']), branch: requestBranch('busy'),
    })
    expect(alive).toEqual(['busy'])
    expect(stalled, 'a live agent was called stalled').toEqual([])
  })

  it('calls the tree stalled when it sits on a request branch with nobody on it', () => {
    expect(whoIsWorking({
      repo: '/nowhere', known: ['left-behind'], run: run([]), branch: requestBranch('left-behind'),
    }).stalled).toEqual(['left-behind'])
  })

  it('says nothing is stalled when the tree is not on a request branch', () => {
    expect(whoIsWorking({ repo: '/nowhere', known: ['a'], run: run([]), branch: 'main' }).stalled).toEqual([])
  })

  it('says nothing is stalled for a branch whose hash does not match its name', () => {
    // `requestOfBranch` checks the hash, so a hand-written branch name
    // is not a request branch and must not be read as a stalled task.
    expect(whoIsWorking({
      repo: '/nowhere', known: ['a-thing'], run: run([]), branch: 'request/a-thing-0000000',
    }).stalled).toEqual([])
  })
})
