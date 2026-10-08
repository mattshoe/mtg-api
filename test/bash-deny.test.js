import { describe, it, expect } from 'vitest'
import { denied } from '../scripts/bash-deny.mjs'

// What a builder is not allowed to run.
//
// The first version of these tests was theatre. Every case was a spelling
// the rules already handled, none of the obvious evasions was probed, and
// one of them — `npx wrangler d1 execute mtg --command "select 1"` as an
// ALLOW — enshrined a hole as intended behaviour, because a `d1 execute`
// with no `--remote` still reaches production.
//
// So this file is the attack. The eighteen cases under "the ways around
// it" are the ones a reviewer constructed against the first
// implementation; all eighteen got through, and each one is here as a
// test that went red.
//
// None of this is the primary defence. That is
// `scripts/intake/dispatch.sh:no_creds`, which launches every agent with
// the Cloudflare credentials stripped and `WRANGLER_HOME` pointed at an
// empty directory. Verified by hand: with a token in the environment,
// `wrangler d1 execute --remote` reaches the Cloudflare API; through the
// scrub it refuses with "it's necessary to set a CLOUDFLARE_API_TOKEN
// environment variable". String matching is the second layer.

const WORKTREE = '/Users/m/.cache/mtg-intake/wt/slot1'
const ok = (cmd) => expect(
  denied(cmd, { cwd: WORKTREE }).deny,
  `${cmd} should have been allowed`,
).toBe(false)
const no = (cmd) => {
  const v = denied(cmd, { cwd: WORKTREE })
  expect(v.deny, `${cmd} should have been refused`).toBe(true)
  expect(v.reason.length).toBeGreaterThan(10)
}

describe('the ways around it', () => {
  // Eighteen constructed bypasses. All eighteen were allowed by the first
  // implementation; the run that proved it printed
  // "18 of 18 bypasses got through".

  it('refuses a program reached through a variable', () => {
    no('W=wrangler; $W deploy')
    no('G=gh; $G pr merge 42 --admin')
  })

  it('refuses eval and every shell -c, rather than trying to parse them', () => {
    no('eval "wrangler deploy"')
    no("bash -c 'wrangler deploy'")
    no('sh -c "npx wrangler d1 execute mtg --remote --command \\"delete from cards\\""')
    no('zsh -c "npm run deploy"')
    no('bash -lc "wrangler deploy"')
  })

  it('refuses a refspec with a quote hidden in it', () => {
    // The old quote-strip only took quotes off the ENDS of a token, so a
    // quote in the middle survived and killed the regex anchor.
    no('git push origin HEAD:"main"')
    no("git push origin HEAD:refs/heads/ma'in'")
  })

  it('refuses a program name broken up by empty quotes', () => {
    no('wrangler dep""loy')
    no("npm run dep''loy")
  })

  it('refuses --admin however it is spelled', () => {
    // `includes('--admin')` was exact equality, so any `=` form walked past.
    no('gh pr merge 42 --admin=true')
    no('gh pr merge 42 --admin=1')
    no('gh pr merge --admin 42')
  })

  it('refuses a merge done through the API or GraphQL', () => {
    no('gh api graphql -f query="mutation { mergePullRequest(input: {pullRequestId: \\"x\\"}) { clientMutationId } }"')
    no('gh api -X PUT repos/o/r/pulls/42/merge?x=1')
    no('gh api -X PUT repos/o/r/pulls/42/merge')
    no('gh api graphql -f query="mutation { enablePullRequestAutoMerge(input: {}) { x } }"')
  })

  it('refuses a d1 command with no --remote, because that still reaches production', () => {
    // This was the worst one: the old rules only fired on `--remote`, and
    // the old test suite asserted the no-flag form was FINE.
    no('wrangler d1 execute mtg --command "DROP TABLE cards"')
    no('npx wrangler d1 execute mtg --command "select 1"')
    no('wrangler d1 execute mtg --remote=true --command "x"')
  })

  it('refuses a git repository named by a relative path out of the worktree', () => {
    // `escapesWorktree` only fired on a path starting `/` or `~`.
    no('git -C ../../repos/mtg-api reset --hard')
    no('git -C ../.. status')
  })

  it('refuses --git-dir and --work-tree, which need no -C at all', () => {
    no('git --git-dir=/Users/m/repos/mtg-api/.git --work-tree=/Users/m/repos/mtg-api checkout main')
    no('git --git-dir /Users/m/repos/mtg-api/.git log -1')
  })

  it('refuses a cd out of the worktree, which the hook cannot see through', () => {
    // The hook's cwd is the session's, not the one after the cd.
    no('cd /Users/m/repos/mtg-api && git reset --hard origin/main')
    no('cd ../../repos/mtg-api && git push origin HEAD:main')
  })

  it('refuses a push target smuggled through git -c', () => {
    no('git -c remote.origin.push=HEAD:refs/heads/main push origin')
  })

  it('refuses npm --prefix pointed somewhere else', () => {
    no('npm --prefix /Users/m/repos/mtg-api run deploy')
    no('npm --prefix /Users/m/repos/mtg-api test')
  })
})

describe('the production database', () => {
  it('refuses a remote d1 execute, in any spelling', () => {
    no('wrangler d1 execute mtg --remote --command "delete from cards"')
    no('wrangler d1 execute --remote mtg --file=x.sql')
    no('npx --yes wrangler@4 d1 execute mtg --remote --command "x"')
  })

  it('allows a local one, which is the only form that proves anything', () => {
    ok('wrangler d1 execute mtg --local --command "select 1"')
  })

  it('refuses a remote d1 anything, not only execute', () => {
    no('wrangler d1 migrations apply mtg --remote')
    no('wrangler d1 delete mtg')
    no('wrangler d1 info mtg')
  })
})

describe('deploying', () => {
  it('refuses wrangler deploy', () => {
    no('wrangler deploy')
    no('npx wrangler deploy --env production')
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
    no('git push origin mybranch:main')
  })

  it('allows a push to its own request branch', () => {
    ok('git push origin HEAD:refs/heads/request/a-thing-abc1234')
    ok('git push -u origin request/a-thing-abc1234')
    ok('git push --force-with-lease origin HEAD:refs/heads/request/x-0000000')
  })

  it('does not mistake a branch with main in its name for main', () => {
    ok('git push origin request/fix-main-page-abc1234')
    ok('git push -u origin request/main-menu-0000000')
  })

  it('allows a push with no refspec, which goes to the current branch', () => {
    ok('git push')
    ok('git push -u origin HEAD')
  })

  it('refuses a bare --force, which is how a branch loses its own commits', () => {
    no('git push --force origin HEAD:refs/heads/request/a-thing-abc1234')
    ok('git push --force-with-lease origin HEAD:refs/heads/request/a-thing-abc1234')
  })
})

describe('merging', () => {
  it('refuses --admin, which bypasses every required check', () => {
    no('gh pr merge 41 --squash --admin')
  })

  it('allows an ordinary squash merge, which is what the dispatcher does', () => {
    ok('gh pr merge 41 --squash')
    ok('gh pr checks 41')
    ok('gh run watch 101 --exit-status')
  })
})

describe('naming another tree', () => {
  it('allows the worktree itself and anything under it', () => {
    ok(`git -C ${WORKTREE} status`)
    ok(`git -C ${WORKTREE}/apps log -1`)
    ok('git -C . status')
    ok('git -C apps status')
    ok('cd apps && ./gradlew :core:jvmTest')
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
    ok('npx vitest run test/decks.test.js -t \'one thing\'')
  })

  it('an empty command is nothing', () => {
    expect(denied('', { cwd: WORKTREE }).deny).toBe(false)
    expect(denied(undefined, { cwd: WORKTREE }).deny).toBe(false)
    expect(denied('   ', { cwd: WORKTREE }).deny).toBe(false)
  })
})

describe('a command with several parts', () => {
  it('judges every part, not just the first', () => {
    no('npm test && npm run deploy')
    no('echo hi; wrangler deploy')
    no('npm test || gh pr merge 41 --admin')
    no('npm test | tee x && git push origin main')
  })

  it('still allows a harmless chain', () => {
    ok('npm test && git push')
    ok('rm -rf apps/core/build/test-results && npm run test:core')
  })
})
