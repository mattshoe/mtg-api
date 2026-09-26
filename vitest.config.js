import { defineWorkersConfig } from '@cloudflare/vitest-pool-workers/config';

// The suite runs the real Worker inside workerd against a real local D1.
// Isolated storage means each test gets the fixture state back, so a test
// that adds a card cannot leak into the next one.
export default defineWorkersConfig({
  test: {
    poolOptions: {
      workers: {
        isolatedStorage: true,
        singleWorker: true,
        wrangler: { configPath: './wrangler.toml' },
        miniflare: {
          compatibilityFlags: ['nodejs_compat'],
          // The real password is a Worker secret. Tests get their own, so
          // the suite never depends on production config.
          bindings: { ADMIN_PASSWORD: 'test-password' },
        },
      },
    },
    setupFiles: ['./test/setup.js'],
  },
});
