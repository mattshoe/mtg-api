import { describe, it, expect } from 'vitest'
import { denied } from '../scripts/bash-deny.mjs'

// What a builder is not allowed to run.
//
// Builders run `claude -p --permission-mode bypassPermissions` with the
// full Bash tool, and neither `.claude/settings.json` nor the user
// settings defined any `permissions.deny`. The worktree holds
// `wrangler.toml` with the production D1 id, wrangler's auth lives in
// $HOME, and `npm run deploy` is in package.json — so a remote
// `wrangler d1 execute` against the real collection was one model
// decision away. `gh pr merge --admin` was prevented by the sentence
// "never --admin" in a markdown file and nothing else.
//
// The rules live here with no IO so they can be tested. The PreToolUse
// hook that enforces them is `scripts/intake/deny-bash.mjs`.

const WORKTREE = '/Users/m/.cache/mtg-intake/wt/slot1'
const ok = (cmd, opts) => expect(denied(cmd, { cwd: WORKTREE, ...opts }).deny).toBe(false)
const no = (cmd, opts) => {
  const v = denied(cmd, { cwd: WORKTREE, ...opts })
  expect(v.deny, `${cmd} should have been refused`).toBe(true)
  expect(v.reason.length).toBeGreaterThan(10)
  return v
}

describe('the production database', () => {
  it('refuses a remote d1 execute, in any spelling', () => {
    no('wrangler d1 execute mtg --remote --command "delete from cards"')
    no('npx wrangler d1 execute mtg --command "drop table cards" --remote')
    no('wrangler d1 execute --remote mtg --file=x.sql')
    no('npx --yes wrangler@4 d1 execute mtg --remote --command "x"')
  })

  it('allows a local one, which is what the suite uses', () => {
    ok('wrangler d1 execute mtg --local --command "select 1"')
    ok('npx wrangler d1 execute mtg --command "select 1"')
  })

  it('refuses a remote d1 anything, not only execute', () => {
    no('wrangler d1 migrations apply mtg --remote')
    no('wrangler d1 delete mtg')
  })
})

describe('deploying', () => {
  it('refuses wrangler deploy', () => {
    no('wrangler deploy')
    no('npx wrangler deploy --env production')
    no('cd /x && npx wrangler deploy')
  })

  it('refuses npm run deploy', () => {
    no('npm run deploy')
    no('npm run -s deploy')
    no('npm  run   deploy')
    no('yarn deploy')
    no('pnpm run deploy')
  })

  it('allows the dev server and the test scripts', () => {
    ok('npm run dev')
    ok('npm test')
    ok('npm run test:screens')
    ok('npm run check:floor -- --raise core=x')
  })
})

describe('pushing', () => {
  it('refuses a push that lands on main', () => {
    no('git push origin main')
    no('git push origin HEAD:main')
    no('git push origin HEAD:refs/heads/main')
    no('git push --force origin main')
    no('git push origin master')
    no('git push -f origin +main')
  })

  it('allows a push to its own request branch', () => {
    ok('git push origin HEAD:refs/heads/request/a-thing-abc1234')
    ok('git push -u origin request/a-thing-abc1234')
    ok('git push --force-with-lease origin HEAD:refs/heads/request/x-0000000')
  })

  it('allows a push with no refspec, which goes to the current branch', () => {
    // The builder is on its own branch and never on main: the dispatcher
    // checks it out. A bare `git push` cannot reach main from there.
    ok('git push')
    ok('git push -u origin HEAD')
  })

  it('refuses a push that mentions main anywhere in a refspec', () => {
    no('git push origin mybranch:main')
  })
})

describe('merging', () => {
  it('refuses --admin, which bypasses every required check', () => {
    // The ruleset has required_approving_review_count: 0 with Matt as
    // owner, so the builder's token really does have the power.
    no('gh pr merge 41 --squash --admin')
    no('gh pr merge --admin 41')
    no('gh api -X PUT repos/mattshoe/mtg-api/pulls/41/merge')
  })

  it('allows an ordinary squash merge', () => {
    ok('gh pr merge 41 --squash')
  })
})

describe('git -C', () => {
  it('refuses a path outside the worktree', () => {
    no(`git -C /Users/m/repos/mtg-api status`)
    no(`git -C ~/repos/other commit -am x`)
    no(`git -C /tmp/elsewhere push origin main`)
  })

  it('allows the worktree itself and anything under it', () => {
    ok(`git -C ${WORKTREE} status`)
    ok(`git -C ${WORKTREE}/apps log -1`)
    ok(`git -C . status`)
    ok(`git -C apps status`)
  })

  it('allows git with no -C at all', () => {
    ok('git status')
    ok('git commit -am "x"')
  })
})

describe('what it does not touch', () => {
  it('leaves ordinary work alone', () => {
    ok('ls -la')
    ok('cat requests/a.md')
    ok('./apps/gradlew -p apps :core:jvmTest')
    ok('node scripts/intake.mjs pending')
    ok('rm -rf apps/androidApp/build/test-results')
  })

  it('only judges Bash, and an empty command is nothing', () => {
    expect(denied('', { cwd: WORKTREE }).deny).toBe(false)
    expect(denied(undefined, { cwd: WORKTREE }).deny).toBe(false)
  })
})

describe('a command with several parts', () => {
  it('judges every part, not just the first', () => {
    // `npm test && npm run deploy` is the obvious way past a rule that
    // only reads the start of the line.
    no('npm test && npm run deploy')
    no('echo hi; wrangler deploy')
    no('npm test || gh pr merge 41 --admin')
    no('npm test | tee x && git push origin main')
  })

  it('still allows a harmless chain', () => {
    ok('npm test && git push')
    ok('cd apps && ./gradlew :core:jvmTest')
  })
})
