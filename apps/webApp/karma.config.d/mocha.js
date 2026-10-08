// Mocha's default is two seconds, which is not enough for a test that
// mounts the whole app: the search is debounced by 250ms and the
// driver suite opens and closes a drawer three times in one case.
//
// And three retries before a failure counts, which is what the
// Android rule `Retry` does too. Matt: "20 fucking minutes of waiting
// just for a flaky test. Your UI tests should have 3 retries for
// failures." `RetryIsWiredTest` fails its first run, so it is red if
// this stops reaching Mocha.
config.set({
  client: {
    mocha: { timeout: 60000, retries: 3 },
  },
  browserNoActivityTimeout: 120000,
});

// One class or one case, for the TDD cycle: Karma takes no `--tests`,
// so `WEB_TEST_GREP=CardDetailsTest ./apps/gradlew -p apps :webApp:jsTest`
// hands the pattern to Mocha instead. Unset, everything runs.
if (process.env.WEB_TEST_GREP) {
  config.client.mocha.grep = process.env.WEB_TEST_GREP;
}
