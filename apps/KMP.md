# The multiplatform branch

What is on `kmp`, what it proved, what it costs, and how to put it live or
throw it away.

`main` is untouched. So is `mtg-api`. Nothing here is deployed.

---

## What exists

```
core/          KMP. Models, API client, decklist parsing, and the wizard
               state machine. Targets jvm, android, js, iosArm64,
               iosSimulatorArm64, iosX64 — iOS compiles today.
androidApp/    Compose UI on core. applicationId .share.next, so it sits
               beside the shipping app rather than replacing it.
webApp/        Compose HTML on core. Real DOM, no canvas.

sender/        The ManaBox stand-in for tests. Untouched.
```

### Tests, all green

| Suite | Tests | What it covers |
|---|---|---|
| `core:jvmTest` | 36 | parser, state machine, API client |
| `core:jsNodeTest` | 36 | the same file, compiled for the browser |
| `webApp:jsBrowserTest` | 6 | the real DOM, clicked, in headless Chrome |
| `app:connectedAndroidTest` | 28 | the shipping app, unchanged and still passing |

The first two matter most: one source, two platforms, identical results.
That is the mechanism that stops the drift, and it is not a convention
anybody has to remember.

### Proven on a device

The Compose app received a real share from another app holding a real
FileProvider grant and rendered the wizard from the shared core — same
step labels, same "3 cards already in the box", same "Nothing is
preselected on purpose" as the web build.

---

## The two findings that should shape the decision

### 1. The bundle is the problem, and most of it is Ktor

Measured, production webpack, gzipped:

| | gz |
|---|---|
| Today's whole hand-written frontend, all 18 modules | **66 KB** |
| Compose HTML + state machine + parser, no network | **107 KB** |
| The same plus Ktor and kotlinx-serialization | **260 KB** |

Ktor is **153 KB of the 260**. More than half the bundle, to make HTTP
calls a browser already knows how to make.

Two things follow. Ktor has to go on the web target — an `expect`/`actual`
HTTP layer with `fetch` behind it on JS, OkHttp on Android, NSURLSession
on iOS. Three small implementations instead of one large dependency.
And even then the floor is ~107 KB against today's 66 KB, because the
Compose runtime ships whether you use one screen or twenty.

The saving grace: that 107 KB is nearly all fixed cost. One screen costs
107 KB; eighteen screens cost not much more. Today's 66 KB grows with
every feature. Somewhere around the full app the two lines cross.

**I would not migrate the web frontend until the fetch swap is done and
re-measured.** If it lands near 120 KB, that is a fair price for
compiler-enforced parity. If it stays near 260 KB, it is not.

### 2. Compose HTML stops rendering in a background tab

It recomposes on `requestAnimationFrame`, which Chrome pauses when a tab
is not visible. The first frame paints and then clicks do nothing. This
cost an hour of chasing a bug that was not there, and it means anything
automated has to run somewhere frames actually happen — hence Karma with
headless Chrome for `webApp:jsBrowserTest`.

It is standard behaviour for a frame-driven UI and today's hand-written
page does not have it, because it writes to the DOM on the event itself.

---

## Rolling it out

Two users and a static site with twenty second deploys, so this is not a
staged migration, it is a switch with an undo.

1. **Point `:app` at `core`** and delete its private copies of the
   parsing rules. No UI change, no deploy, kills the real duplication.
2. **Swap Ktor for `fetch` on the web target and re-measure.** If the
   bundle lands near 120 KB, publish it over `#/entry`. If it stays near
   260 KB, stop — the parity argument is not worth four times the weight.

Rollback is `git revert` and one deploy, because the old page is a
directory of plain files that never went anywhere. Worst case is
`git checkout main`, which is instant, because nothing on this branch was
ever deployed.

## What I would actually do

Step 1 now. It is free and fixes something that is already wrong.

Step 2 only when the bundle number justifies it.

And worth keeping in view: the parity problem this solves is a future
one. What bit tonight was a platform boundary, and none of this would
have helped with that.
