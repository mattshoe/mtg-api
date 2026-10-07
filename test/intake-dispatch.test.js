import { describe, it, expect } from 'vitest'
import {
  pending, triaged, waiting, buildable, branchFor, hookFires, equipped,
  builderDone,
} from '../scripts/intake.mjs'

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
})

describe('triaged', () => {
  it('is true once a file has a plan', () => {
    expect(triaged('# Thing\n\n## Plan\n\nedit X\n')).toBe(true)
  })

  it('is false for something Matt just typed', () => {
    expect(triaged('# Thing\n\nmake the tiles smaller\n')).toBe(false)
  })

  it('is not fooled by the word plan in prose', () => {
    expect(triaged('# Thing\n\nI plan to use this later\n')).toBe(false)
  })
})

describe('waiting', () => {
  it('is true when triage left a question for Matt', () => {
    expect(waiting('---\nstatus: needs-matt\n---\n\n# Thing\n')).toBe(true)
  })

  it('is false when triage called it ready', () => {
    expect(waiting('---\nstatus: ready\n---\n\n# Thing\n')).toBe(false)
  })

  it('only reads the frontmatter, not the body', () => {
    expect(waiting('---\nstatus: ready\n---\n\nstatus: needs-matt\n')).toBe(false)
  })
})

describe('buildable', () => {
  const ready = '---\nstatus: ready\n---\n\n# T\n\n## Plan\n\nx\n'

  it('is a triaged, ready request', () => {
    expect(buildable(ready)).toBe(true)
  })

  it('is not a request with no plan, because a builder would guess', () => {
    expect(buildable('# T\n\nmake it faster\n')).toBe(false)
  })

  it('is not a request waiting on Matt, even with a plan', () => {
    expect(buildable('---\nstatus: needs-matt\n---\n\n## Plan\n\nx\n')).toBe(false)
  })
})

describe('branchFor', () => {
  it('is the file name under request/', () => {
    expect(branchFor('rank-on-tiles.md')).toBe('request/rank-on-tiles')
  })

  it('tolerates whatever Matt named the file', () => {
    expect(branchFor('Fix The Thing!.md')).toBe('request/fix-the-thing')
  })

  it('never produces a branch name git will refuse', () => {
    for (const name of ['..md', '...md', '   .md', '~~~.md', 'a..b.md']) {
      const b = branchFor(name)
      expect(b).toMatch(/^request\/[a-z0-9][a-z0-9-]*$/)
    }
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

  const need = [
    '.claude/skills/mtg/SKILL.md',
    '.claude/agents/request-builder.md',
  ]

  it('is equipped when the skill and the builder definition are both there', () => {
    expect(equipped(need).ok).toBe(true)
  })

  it('is not equipped with no skill, and says which file is missing', () => {
    const v = equipped(['.claude/agents/request-builder.md'])
    expect(v.ok).toBe(false)
    expect(v.missing).toEqual(['.claude/skills/mtg/SKILL.md'])
  })

  it('is not equipped with no builder definition', () => {
    const v = equipped(['.claude/skills/mtg/SKILL.md'])
    expect(v.ok).toBe(false)
    expect(v.missing).toEqual(['.claude/agents/request-builder.md'])
  })

  it('names both when a worktree has neither', () => {
    const v = equipped([])
    expect(v.ok).toBe(false)
    expect(v.missing).toEqual(need)
  })

  it('does not mind other files being present', () => {
    expect(equipped([...need, 'CLAUDE.md', 'src/index.js']).ok).toBe(true)
  })
})

describe('builderDone', () => {
  // A builder's exit code says nothing about whether it finished.
  //
  // `claude -p` ends when the model stops producing text. One builder
  // kicked off the core and web suites, wrote "Red runs for core and web
  // are in progress", and ended its turn — exit 0, no commit, no PR. The
  // dispatcher read 0 as success and deleted the worktree, which threw
  // the work away.
  //
  // So "done" is the PR existing and the request filed under done/, and
  // the exit code is the least interesting of the three.

  it('is done with a PR open and the request filed', () => {
    expect(builderDone({ exitCode: 0, prOpen: true, movedToDone: true }).ok).toBe(true)
  })

  it('is not done on exit 0 with no PR, and says so', () => {
    const v = builderDone({ exitCode: 0, prOpen: false, movedToDone: false })
    expect(v.ok).toBe(false)
    expect(v.why).toContain('no pull request')
  })

  it('keeps the worktree when it is not done', () => {
    expect(builderDone({ exitCode: 0, prOpen: false, movedToDone: false }).keep).toBe(true)
    expect(builderDone({ exitCode: 0, prOpen: true, movedToDone: true }).keep).toBe(false)
  })

  it('is not done when a PR exists but the request was never filed', () => {
    const v = builderDone({ exitCode: 0, prOpen: true, movedToDone: false })
    expect(v.ok).toBe(false)
    expect(v.why).toContain('requests/done')
  })

  it('is not done on a nonzero exit even with a PR', () => {
    const v = builderDone({ exitCode: 1, prOpen: true, movedToDone: true })
    expect(v.ok).toBe(false)
    expect(v.why).toContain('exited 1')
  })

  it('says every reason at once rather than the first', () => {
    const v = builderDone({ exitCode: 2, prOpen: false, movedToDone: false })
    expect(v.why).toContain('exited 2')
    expect(v.why).toContain('no pull request')
    expect(v.why).toContain('requests/done')
  })
})
