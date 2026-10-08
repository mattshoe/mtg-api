---
status: ready
---

# CI takes 18 minutes and should take 5-10

Why is the ci for this 18 minutes? That seems high. I would expect 5-10 for a
small project like this.

Measured, for whoever picks this up: the `android` job runs compile (1:36),
the Compose screens suite (5:56) and the emulator (8:55) in series and is the
critical path, while `web` finishes at 3:46 and `shared` at 3:18 and then idle
for 13 minutes. The screens suite runs its 42 test classes one at a time
because `maxParallelForks` is unset on a 10-core runner. The emulator
downloads its system image and cold-boots every run with nothing cached. There
is no Gradle build cache, no configuration cache and no parallel execution, so
six modules are configured from scratch on every invocation, and all seven CI
Gradle calls pass `--no-daemon`.

`forkEvery(1)` in androidApp is load-bearing — ComposeRootRegistry state
accumulates across test classes — so it controls JVM reuse, not concurrency,
and must stay.
