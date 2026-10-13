import { defineWorkersConfig } from '@cloudflare/vitest-pool-workers/config';

// The suite runs the real Worker inside workerd against a real local D1.
// Isolated storage means each test gets the fixture state back, so a test
// that adds a card cannot leak into the next one.
export default defineWorkersConfig({
  test: {
    poolOptions: {
      workers: {
        isolatedStorage: true,
        // One worker for the whole suite. Parallel was 39s against 287s
        // serial and ran that way for weeks, until 2026-10-13 around
        // 16:50 UTC when GitHub's runners slowed to about a quarter of
        // their speed and every parallel run on every branch died the
        // same way: "Network connection lost" out of
        // `updateStackedStorage`, then "table cards already exists" for
        // the rest of that file. The same commit run serial on CI was
        // green (run 38444970028, 532s). Slower and green beats fast
        // and red on hardware nobody controls.
        singleWorker: true,
        wrangler: { configPath: './wrangler.toml' },
        miniflare: {
          compatibilityFlags: ['nodejs_compat'],
          // The real password is a Worker secret. Tests get their own, so
          // the suite never depends on production config.
          bindings: {
            ADMIN_PASSWORD: 'test-password',
            // The real pair are Worker secrets. The tests sign their
            // own tokens against their own key pair, so the only thing
            // that has to match is the audience.
            GOOGLE_CLIENT_ID: 'test-client.apps.googleusercontent.com',
            GOOGLE_CLIENT_SECRET: 'test-client-secret',
            SITE_URL: 'https://mtg.mattshoe.org',
            // The sign-in tests assert on *why* a token was refused;
            // production says only that it was.
            AUTH_DEBUG: '1',
            // The edge cache is real and shared; leaving it on would let
            // one test's fetch satisfy the next test's assertion.
            DISABLE_PRICE_CACHE: '1',
          },
        },
      },
    },
    // Only this checkout's tests. An agent worktree under
    // `.claude/worktrees/` is a whole copy of the repo, tests and
    // all, and vitest's default glob happily collected every one of
    // them: eight copies of `decks.test.js` ran in a single `npm
    // test`, which is why a one-minute suite took over ten and
    // looked for all the world like it had hung.
    include: ['test/**/*.test.js'],
    // `intake-shell.test.js` drives the real bash scripts and needs
    // node:child_process, which workerd does not have. It runs under
    // `vitest.shell.config.js` instead.
    exclude: [
      '**/node_modules/**', '.claude/**', '**/.wrangler/**',
      // Same reason: drives a real script against a throwaway git repo.
      'test/intake-shell.test.js', 'test/release-note.test.js', 'test/tags-script.test.js',
      'test/next-version.test.js', 'test/intake-inbox.test.js', 'test/intake-task-status.test.js',
    ],
    setupFiles: ['./test/setup.js'],
    // A stuck test fails; it does not hang the run. Without these a
    // single test waiting on something that never arrives holds the
    // whole suite open indefinitely, and the output looks exactly
    // like a slow suite. `scripts/guard.mjs` is the outer backstop;
    // these are what make one test's problem stay one test's problem.
    testTimeout: 20_000,
    hookTimeout: 30_000,
    teardownTimeout: 20_000,
  },
});
