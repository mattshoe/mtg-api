---
status: cancelled
---

# Pictures for Matt to look at must never be able to fail CI

Matt: "Then why the fuck are you flunking ci on Screenshots for me??"

He is right, and it had already cost three full device runs in one
evening — twenty-plus minutes each, on a day CI was the bottleneck.

What happened: `ArtCropAlignmentParityTest` took a screenshot, the
emulator's graphics stack could not hand back a drawn window inside the
two seconds `captureToImage` allows itself, and the job went red. The
test had already proved everything it set out to prove. The emulator
logged "Failed to find ColorBuffer" and "invalid color buffer" either
side of it.

The immediate hole is closed: `Parity.shoot` now catches a failed
capture, prints why, and returns null. That covers all 41 `.shoot(` call
sites, because they all go through it. `shoot`'s own docstring already
said "Evidence is not an assertion, so its absence is not a failure" and
the JVM path already behaved that way; only the device path threw.

What is left is the shape of it. Five tests exist ONLY to take a
picture and assert nothing:

- `ArtCropAlignmentParityTest.theDeckTileIsPhotographedForACropReview`
- `ArtCropAlignmentParityTest.theHeroAndTheCardRowArePhotographedForACropReview`
- `BottomNavParityTest.theUnlockedBarIsPhotographed`
- `DeckCardCarouselParityTest.theLibrarysCarouselIsPhotographed`
- `DeckCardCarouselParityTest.theCarouselIsPhotographed`

They are review material sitting inside a blocking suite. They cost
emulator minutes on every pull request and they can only ever cost
them — there is no assertion that could catch a regression.

Decide what they should be and make them that. Options worth weighing,
not a prescription: move them behind a tag or a gradle property so they
run when somebody wants pictures; or keep them where they are but
uploaded as artifacts from a non-blocking job; or delete the ones whose
pictures nobody has looked at since they were written.

Whatever is chosen, the rule stays: nothing whose output is a picture
for a person may fail a build. The other 36 calls sit beside real
assertions and those tests stay exactly where they are.
