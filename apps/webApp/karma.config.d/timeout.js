// Mocha's default is two seconds, which is not enough for a test that
// mounts the whole app: the search is debounced by 250ms and the
// driver suite opens and closes a drawer three times in one case.
config.set({
  client: {
    mocha: { timeout: 60000 },
  },
  browserNoActivityTimeout: 120000,
});
