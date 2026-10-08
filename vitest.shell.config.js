import { defineConfig } from 'vitest/config'

// The intake shell tests run the real bash scripts against a throwaway git
// repo, so they need `node:child_process` and a real filesystem. The worker
// suite runs in workerd, which has neither — hence a second config rather
// than a second pool option.
export default defineConfig({
  test: {
    include: ['test/intake-shell.test.js', 'test/release-note.test.js'],
    environment: 'node',
    // Each test builds its own git repo and temp dir, so they are
    // independent, but they fork `git` and `bash` a lot. Four at a time
    // keeps a laptop responsive and still finishes in seconds.
    maxConcurrency: 4,
    testTimeout: 120_000,
    hookTimeout: 120_000,
  },
})
