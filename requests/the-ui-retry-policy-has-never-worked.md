---
status: ready
---

# The 3-retry policy on UI tests has never retried anything

Matt: "didn't we already implement a 5-retry policy per-test??"

There is one, in `apps/androidApp/src/sharedTest/.../Retry.kt`, and it
has never worked for a Compose test. Which is nearly all of them.

`Retry` is a `TestRule` declared outermost — `@get:Rule(order =
Int.MIN_VALUE)` — and it retries by calling `base.evaluate()` again.
JUnit builds that statement once, against the test instance that
already exists, so the second attempt reuses the SAME
`createComposeRule()` object. Compose refuses:

    Only a single call to `runTest` can be performed

That exception is then the LAST failure in the list, so it is the one
reported. Every flaky UI test for as long as this rule has existed has
come back saying something about coroutines and nothing about what
actually broke. It cost two full CI rounds today being read as the
cause, on `ArtCropAlignmentParityTest`.

A stopgap has landed: `Retry` now notices that error and rethrows the
FIRST failure instead, so the message is honest. The retrying still
does not happen.

What a real fix needs: a fresh test instance per attempt, so the
rules are rebuilt. That is runner-level, not rule-level — a custom
JUnit runner that retries `runChild`, which does call `createTest()`
again. It has to work under both runners the suite uses, Robolectric
on the JVM and the instrumentation runner on the device, which is why
the original note rejected Gradle's test-retry plugin.

Two things to keep while changing it:

- A retried test must still be ONE result in the XML.
  `check-test-count.mjs` and the suite floors count rows, and a test
  that reports four times would quietly raise a floor nobody can meet.
- A failed assumption is a skip, not a failure. `needsRealRendering`
  tests must not run four times on the JVM to be skipped four times.

And `EveryUiTestRetriesTest` asserts every `AndroidJUnit4` class
declares the rule. Whatever replaces it needs the same guard, or the
next test written will quietly opt out.
