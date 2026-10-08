import { describe, it, expect } from 'vitest'
import {
  pending, triaged, buildable, branchFor, hookFires, equipped,
  statusOf, mergeMode, REQUIRED,
} from '../scripts/intake.mjs'
import { raise } from '../scripts/suite-floor.mjs'

// What the request watcher decides.
//
// The dispatcher is a shell script because launchd runs it, but every
// decision it makes lives here, where it can be tested. The first draft
// made them with grep and two of them were wrong: `grep -L` with no
// file arguments reads stdin and hangs forever, and a file triage had
// never touched went to a builder with no plan in it.
//
// The expensive failure mode is not a crash. It is a builder spun up on
// a request nobody triaged, or two builders on one file, or a request
// silently never picked up at all.

describe('pending', () => {
  it('is the top-level request files', () => {
    expect(pending(['a.md', 'b.md'])).toEqual(['a.md', 'b.md'])
  })

  it('leaves out the README, which is documentation, not a request', () => {
    expect(pending(['README.md', 'a.md'])).toEqual(['a.md'])
  })

  it('leaves out anything that is not markdown', () => {
    expect(pending(['a.md', '.DS_Store', 'notes.txt'])).toEqual(['a.md'])
  })

  it('leaves out dotfiles, so .state and .gitignore are not requests', () => {
    expect(pending(['.gitignore', '.state', 'a.md'])).toEqual(['a.md'])
  })

  it('is empty when the folder holds only its own furniture', () => {
    expect(pending(['README.md', '.gitignore'])).toEqual([])
  })

  // A finished request was never removed from the live `requests/`. The
  // builder moved its own copy inside its worktree; the real repo is on
  // another branch and never pulls. So `edhrec-sort-backwards.md` and
  // `kayla-account-owns-her-cards.md` were built hours apart and were
  // still listed as buildable — each rebuild starting from a base that
  // already contained the feature, so the TDD red could not reproduce
  // and every pass opened another pull request.
  it('leaves out a request already filed under done/', () => {
    expect(pending(['a.md', 'b.md'], { done: ['b.md'] })).toEqual(['a.md'])
  })

  it('is unchanged when done/ holds nothing it knows about', () => {
    expect(pending(['a.md'], { done: ['other.md'] })).toEqual(['a.md'])
  })

  it('does not mind done/ being missing entirely', () => {
    expect(pending(['a.md'])).toEqual(['a.md'])
  })
})

describe('triaged', () => {
  it('is true once a file has the sections triage promises', () => {
    expect(triaged('# Thing\n\n## Plan\n\nedit X\n\n## Tests\n\nt\n\n## Done when\n\nd\n'))
      .toBe(true)
  })

  it('is false for something Matt just typed', () => {
    expect(triaged('# Thing\n\nmake the tiles smaller\n')).toBe(false)
  })

  it('is not fooled by the word plan in prose', () => {
    expect(triaged('# Thing\n\nI plan to use this later\n')).toBe(false)
  })

  // Triage promises three sections. Testing only for `## Plan` let it
  // write a complete-looking file that was still "untriaged", so it got
  // a full opus triage run on every dispatch forever while being
  // reported as ready at the same time.
  // `requests/release-notes-in-admin-settings.md` was the live instance.
  it('needs every section triage promises, not just a plan', () => {
    expect(triaged('## Plan\n\nx\n')).toBe(false)
    expect(triaged('## Plan\n\nx\n\n## Tests\n\ny\n')).toBe(false)
    expect(triaged('## Plan\n\nx\n\n## Tests\n\ny\n\n## Done when\n\nz\n')).toBe(true)
  })

  it('does not count a heading that only appears inside a code fence', () => {
    const fenced = [
      '# Thing', '',
      'The shape triage leaves behind:', '',
      '```markdown',
      '## Plan', '', 'x', '',
      '## Tests', '', 'y', '',
      '## Done when', '', 'z',
      '```', '',
    ].join('\n')
    expect(triaged(fenced)).toBe(false)
  })

  it('still sees real sections in a file that also has a code fence', () => {
    const text = [
      '## Plan', '', '```sh', '## Tests', '```', '',
      '## Tests', '', 'y', '',
      '## Done when', '', 'z', '',
    ].join('\n')
    expect(triaged(text)).toBe(true)
  })

  it('reads a file a Windows editor saved', () => {
    const crlf = '## Plan\r\n\r\nx\r\n\r\n## Tests\r\n\r\ny\r\n\r\n## Done when\r\n\r\nz\r\n'
    expect(triaged(crlf)).toBe(true)
  })
})

describe('statusOf', () => {
  // Anything the frontmatter did not say exactly was treated as ready to
  // build: `status: blocked`, `status: done`, `Needs-Matt`, a value with
  // a trailing comment, and a file with no `status:` key at all were all
  // buildable. There is to be no fourth, implicit state.

  it('is the frontmatter value', () => {
    expect(statusOf('---\nstatus: ready\n---\n')).toBe('ready')
    expect(statusOf('---\nstatus: needs-matt\n---\n')).toBe('needs-matt')
  })

  it('does not care about case or surrounding space', () => {
    expect(statusOf('---\nstatus:   Needs-Matt  \n---\n')).toBe('needs-matt')
  })

  it('drops a trailing comment rather than letting it hide the value', () => {
    expect(statusOf('---\nstatus: needs-matt  # waiting on Matt\n---\n')).toBe('needs-matt')
  })

  it('drops quotes', () => {
    expect(statusOf('---\nstatus: "ready"\n---\n')).toBe('ready')
  })

  it('is empty when there is no status key', () => {
    expect(statusOf('---\nsize: small\n---\n')).toBe('')
    expect(statusOf('# no frontmatter at all\n')).toBe('')
  })

  it('reads frontmatter a Windows editor saved', () => {
    // The parser required `---\n` at byte zero, so a CRLF file lost its
    // whole frontmatter — while the old `triaged()` still matched,
    // because its `\s*` ate the `\r`. The one request triage had
    // flagged as needing Matt was therefore the one that got built.
    expect(statusOf('---\r\nstatus: needs-matt\r\n---\r\n\r\n# T\r\n')).toBe('needs-matt')
  })

  it('only reads the frontmatter, not the body', () => {
    expect(statusOf('---\nstatus: ready\n---\n\nstatus: needs-matt\n')).toBe('ready')
  })
})

describe('buildable', () => {
  const ready = '---\nstatus: ready\n---\n\n# T\n\n## Plan\n\nx\n'
    + '\n## Tests\n\ny\n\n## Done when\n\nz\n'

  it('is a triaged, ready request', () => {
    expect(buildable(ready)).toBe(true)
  })

  it('is not a request with no plan, because a builder would guess', () => {
    expect(buildable('# T\n\nmake it faster\n')).toBe(false)
  })

  it('is not a request waiting on Matt, even with a plan', () => {
    expect(buildable('---\nstatus: needs-matt\n---\n\n## Plan\n\nx\n')).toBe(false)
  })

  it('needs status to be exactly ready, so unknown means held', () => {
    const plan = '\n\n# T\n\n## Plan\n\nx\n\n## Tests\n\ny\n\n## Done when\n\nz\n'
    for (const v of ['blocked', 'done', 'Needs-Matt', 'in progress', 'readyish']) {
      expect(buildable(`---\nstatus: ${v}\n---${plan}`), v).toBe(false)
    }
    expect(buildable(`---\nstatus: ready\n---${plan}`)).toBe(true)
  })

  it('is not buildable with no status key at all', () => {
    expect(buildable('---\nsize: small\n---\n\n## Plan\n\nx\n\n## Tests\n\ny\n\n## Done when\n\nz\n'))
      .toBe(false)
  })

  it('is buildable when a Windows editor saved it', () => {
    const crlf = '---\r\nstatus: ready\r\n---\r\n\r\n## Plan\r\n\r\nx\r\n\r\n'
      + '## Tests\r\n\r\ny\r\n\r\n## Done when\r\n\r\nz\r\n'
    expect(buildable(crlf)).toBe(true)
  })
})

describe('mergeMode', () => {
  // Whether a green pull request gets merged is now the dispatcher's
  // decision, and it is read from the file rather than left to the
  // model. Four of five live requests said `merge: ask`, so a green
  // pull request and silence was the normal outcome.

  it('is auto when the file says so', () => {
    expect(mergeMode('---\nmerge: auto\n---\n')).toBe('auto')
  })

  it('is ask when the file says so', () => {
    expect(mergeMode('---\nmerge: ask\n---\n')).toBe('ask')
  })

  it('is ask when the file does not say, because a person wrote that file', () => {
    // This pinned the opposite, and the opposite was wrong: a
    // hand-written `status: ready` request with no `merge:` line was
    // squash-merged unattended. Triage always writes `merge: auto`
    // explicitly, so a file without it did not come from triage.
    expect(mergeMode('---\nstatus: ready\n---\n')).toBe('ask')
    expect(mergeMode('# no frontmatter at all\n')).toBe('ask')
  })

  it('is ask for anything it does not recognise, which is the safe way to be wrong', () => {
    expect(mergeMode('---\nmerge: maybe\n---\n')).toBe('ask')
    expect(mergeMode('---\nmerge: AUTO-ish\n---\n')).toBe('ask')
  })

  it('ignores a trailing comment and reads a CRLF file', () => {
    expect(mergeMode('---\nmerge: ask # schema change\n---\n')).toBe('ask')
    expect(mergeMode('---\r\nmerge: ask\r\n---\r\n')).toBe('ask')
  })
})

describe('branchFor', () => {
  it('is the file name under request/, with a digest of the exact name', () => {
    expect(branchFor('rank-on-tiles.md')).toMatch(/^request\/rank-on-tiles-[0-9a-f]{7}$/)
  })

  it('tolerates whatever Matt named the file', () => {
    expect(branchFor('Fix The Thing!.md')).toMatch(/^request\/fix-the-thing-[0-9a-f]{7}$/)
  })

  it('never produces a branch name git will refuse', () => {
    for (const name of ['..md', '...md', '   .md', '~~~.md', 'a..b.md']) {
      const b = branchFor(name)
      expect(b).toMatch(/^request\/[a-z0-9][a-z0-9-]*$/)
    }
  })

  // The old test here asserted the output matched the character set its
  // own `replace()` had just produced, so it could not fail. What it
  // should have caught: two different request files landing on one
  // branch, and `gh pr list --head "$branch"` then answering about the
  // WRONG pull request — so the dispatcher files one request under
  // requests/done because a different request's PR had merged.
  it('never puts two different requests on one branch', () => {
    const names = [
      'deck_page.md', 'deck-page.md', 'Deck Page.md', 'deck.page.md',
      'деки.md', '日本語.md', '....md', '~~~.md',
      'a-b.md', 'a--b.md', 'a_b.md',
    ]
    const seen = new Map()
    for (const n of names) {
      const b = branchFor(n)
      expect(seen.has(b), `${n} and ${seen.get(b)} both give ${b}`).toBe(false)
      seen.set(b, n)
    }
  })

  // `build_one` names the branch once and then asks GitHub about it with
  // `gh pr list --head "$branch"`. An unstable name would ask about a
  // branch no builder ever pushed, and every outcome would read as "no
  // pull request at all".
  it('is stable, because the dispatcher asks GitHub about it by name', () => {
    expect(branchFor('deck-page.md')).toBe(branchFor('deck-page.md'))
    expect(branchFor('/r/requests/deck-page.md')).toBe(branchFor('deck-page.md'))
  })

  it('stays inside the length a ref can be', () => {
    const b = branchFor(`${'x'.repeat(400)}.md`)
    expect(b.length).toBeLessThanOrEqual(100)
    expect(b).toMatch(/^request\/[a-z0-9][a-z0-9-]*$/)
  })

  it('gives a non-ASCII name something better than request/unnamed', () => {
    expect(branchFor('деки.md')).not.toBe('request/unnamed')
    expect(branchFor('日本語.md')).not.toBe('request/unnamed')
  })
})

describe('hookFires', () => {
  it('fires for a request file', () => {
    expect(hookFires('/r/mtg-api/requests/rank-on-tiles.md')).toBe(true)
  })

  it('does not fire for the README', () => {
    expect(hookFires('/r/mtg-api/requests/README.md')).toBe(false)
  })

  it('does not fire for a finished request', () => {
    expect(hookFires('/r/mtg-api/requests/done/old.md')).toBe(false)
  })

  it('does not fire for ordinary work', () => {
    expect(hookFires('/r/mtg-api/src/index.js')).toBe(false)
    expect(hookFires('/r/mtg-api/apps/core/src/commonMain/kotlin/X.kt')).toBe(false)
  })

  it('does not fire for a path that merely mentions requests', () => {
    expect(hookFires('/r/mtg-api/src/requests-handler.js')).toBe(false)
  })

  it('does not fire for nothing at all', () => {
    expect(hookFires('')).toBe(false)
    expect(hookFires(undefined)).toBe(false)
  })
})

describe('equipped', () => {
  // A builder gets a worktree branched off main. The skill and the agent
  // definitions live in `.claude/`, and for a while `.claude/` was only
  // on the branch that introduced it — so two builders started in
  // worktrees with no skill and no instructions, were told to invoke the
  // `mtg` skill, found nothing, and built without the parity rule or the
  // TDD discipline. Nothing failed. They just quietly worked blind.
  //
  // So a worktree says whether it is equipped before anything runs in it.

  const need = REQUIRED

  it('is equipped when the skill and the builder definition are both there', () => {
    expect(equipped(need).ok).toBe(true)
  })

  it('is not equipped with no skill, and says which file is missing', () => {
    const v = equipped(need.filter((f) => f !== '.claude/skills/mtg/SKILL.md'))
    expect(v.ok).toBe(false)
    expect(v.missing).toEqual(['.claude/skills/mtg/SKILL.md'])
  })

  it('is not equipped with no builder definition', () => {
    const v = equipped(need.filter((f) => f !== '.claude/agents/request-builder.md'))
    expect(v.ok).toBe(false)
    expect(v.missing).toEqual(['.claude/agents/request-builder.md'])
  })

  it('names both when a worktree has neither', () => {
    const v = equipped([])
    expect(v.ok).toBe(false)
    expect(v.missing).toEqual(need)
  })

  it('does not mind other files being present', () => {
    expect(equipped([...need, 'BACKLOG.md', 'src/index.js']).ok).toBe(true)
  })

  // The REQUIRED list named the skill and the agent definition and
  // stopped there — while the builder is told CLAUDE.md is the law that
  // overrides the skill, told to run every suite through
  // `scripts/guard.mjs`, told the count check is what proves a suite
  // ran, and told never to let a floor drop. A worktree missing any of
  // those produces a builder that works blind in exactly the way the
  // guard was written to prevent.
  it('needs CLAUDE.md, which the builder is told overrides the skill', () => {
    expect(REQUIRED).toContain('CLAUDE.md')
  })

  it('needs the scripts the builder is told to run everything through', () => {
    expect(REQUIRED).toContain('scripts/guard.mjs')
    expect(REQUIRED).toContain('scripts/check-test-count.mjs')
    expect(REQUIRED).toContain('test/suite-floors.json')
  })

  it('names every missing one at once rather than the first', () => {
    const v = equipped(['CLAUDE.md'])
    expect(v.ok).toBe(false)
    expect(v.missing).toEqual(REQUIRED.filter((f) => f !== 'CLAUDE.md'))
  })
})

describe('raise', () => {
  // Two builders raising the same floor produced a one-line conflict in
  // `suite-floors.json` that blocked the second merge, and any manual
  // resolution landed a floor BELOW main's real count — so a later
  // deletion of up to twenty tests would pass unnoticed.

  it('takes the higher of what is on disk and what ran', () => {
    expect(raise({ core: 2310 }, { core: 2400 }, ['core'])).toEqual({ core: 2400 })
    expect(raise({ core: 2500 }, { core: 2400 }, ['core'])).toEqual({ core: 2500 })
  })

  it('ignores a result for a suite nobody named', () => {
    // Every other `raise` test passes `named` undefined, which makes the
    // `if (asked && ...)` branch dead — so this is the only one that can
    // fail if the argument stops being honoured.
    expect(raise({}, { web: 999 }, ['core'])).toEqual({})
    expect(raise({ core: 1 }, { core: 2, web: 999 }, ['core'])).toEqual({ core: 2 })
  })

  it('writes only the suites named on the command line', () => {
    // A run can leave XML behind for a suite nobody asked about — a
    // previous task's results are still on disk, which is exactly the
    // shape a killed Gradle run leaves.
    // Stale XML on disk reads as a real count, so a suite that was not
    // asked about must not be able to move its own floor — in either
    // direction.
    expect(raise({ core: 2310, web: 398 }, { core: 2400, web: 999 }, ['core']))
      .toEqual({ core: 2400, web: 398 })
  })

  it('leaves every suite nobody named exactly as it found it', () => {
    const floors = { core: 2310, screens: 466, web: 398 }
    expect(raise(floors, { core: 2400, screens: 1 }, ['core'])).toEqual({
      core: 2400, screens: 466, web: 398,
    })
  })

  it('adds a suite that had no floor yet', () => {
    expect(raise({ core: 1 }, { web: 10 }, ['web'])).toEqual({ core: 1, web: 10 })
  })

  it('cannot lower a floor however it is called', () => {
    expect(raise({ core: 2310 }, { core: 5 }, ['core'])).toEqual({ core: 2310 })
  })
})
