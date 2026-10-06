package org.mattshoe.mtg.web

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.browser.document
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import org.jetbrains.compose.web.renderComposable
import org.mattshoe.mtg.core.Applied
import org.mattshoe.mtg.core.Change
import org.mattshoe.mtg.core.Direction
import org.mattshoe.mtg.core.MassEntry
import org.mattshoe.mtg.core.Owner
import org.mattshoe.mtg.core.Step
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.get
import kotlin.js.Promise
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The two ends of the wizard: "Whose collection?" and "Applied".
 *
 * `MassEntryPageTest` walks as far as the owner step and stops there,
 * and nothing ever rendered the done step in a browser at all — which
 * is how a title that says "Applied" over nothing at all survived.
 *
 * Everything here is clicked rather than reasoned about. Compose HTML
 * recomposes on `requestAnimationFrame`, so every assertion comes
 * after `settle()`.
 */
class MassEntryStepsTest {

    private val roots = mutableListOf<HTMLElement>()
    private var current: MassEntry = MassEntry()

    @BeforeTest
    fun loadTheStylesheet() = Stylesheet.load()

    @AfterTest
    fun cleanUp() {
        roots.forEach { it.remove() }
        roots.clear()
    }

    /**
     * A phone, near enough.
     *
     * Headless Chrome will not take a viewport under 500px, so the
     * container is narrowed instead — and narrowed past the 400px the
     * rule is written for, because none of these rules are keyed to
     * the viewport and a tighter box is the harder test.
     */
    private val PHONE = 358

    private fun mount(initial: MassEntry, width: Int? = null): HTMLElement {
        val root = document.createElement("div") as HTMLElement
        width?.let {
            root.style.width = "${it}px"
            root.style.position = "absolute"
            root.style.left = "0px"
        }
        document.body!!.appendChild(root)
        roots += root
        current = initial
        renderComposable(root = root) {
            val s = remember { mutableStateOf(initial) }
            MassEntryPage(
                state = s.value,
                onState = { current = it; s.value = it },
                onPreview = {},
                onApply = {},
            )
        }
        return root
    }

    /** Let Compose paint. Two frames, because a click schedules the next. */
    private suspend fun settle() {
        repeat(3) {
            Promise<Unit> { resolve, _ ->
                kotlinx.browser.window.requestAnimationFrame { resolve(Unit) }
            }.await()
        }
    }

    private fun HTMLElement.buttons(): List<HTMLButtonElement> {
        val found = querySelectorAll("button")
        return (0 until found.length).mapNotNull { found[it] as? HTMLButtonElement }
    }

    private fun HTMLButtonElement.says(): String =
        (querySelector(".opt-label")?.textContent ?: textContent).orEmpty().trim()

    private fun HTMLElement.button(label: String): HTMLButtonElement =
        buttons().firstOrNull { it.says() == label }
            ?: throw AssertionError("no button '$label'; saw ${buttons().map { it.says() }}")

    private fun HTMLElement.maybeButton(label: String): HTMLButtonElement? =
        buttons().firstOrNull { it.says() == label }

    private fun HTMLElement.all(css: String): List<HTMLElement> =
        querySelectorAll(css).let { n -> (0 until n.length).mapNotNull { n[it] as? HTMLElement } }

    /** Anything sticking out past the edge of what holds it. */
    private fun HTMLElement.overflowing(): List<String> {
        val limit = getBoundingClientRect().right + 1
        return all("*").filter { it.getBoundingClientRect().right > limit }
            .map { it.tagName.lowercase() + "." + it.className }
    }

    private fun HTMLElement.title(): String =
        all("div.panel-head h2").firstOrNull()?.textContent.orEmpty().trim()

    private fun HTMLElement.words(): String = textContent.orEmpty()

    // ----------------------------------------------------- the states

    /** A list, a direction, and nobody named yet: the owner step. */
    private fun atWho(direction: Direction = Direction.ADD): MassEntry =
        MassEntry(direction = direction)
            .type("4 Lightning Bolt\n1 Sol Ring")
            .goTo(Step.WHO)

    private fun change(i: Int, before: Int = 1, after: Int = 2) = Change(
        name = "A Card With Quite A Long Name Number $i",
        set = "fra",
        collectorNumber = "$i",
        finish = "nonfoil",
        before = before,
        after = after,
    )

    /** The wizard after a real write came back. */
    private fun done(
        applied: Boolean = true,
        changes: List<Change> = listOf(change(1), change(2, before = 0, after = 1)),
        failed: Int = 0,
        errors: List<String> = emptyList(),
        direction: Direction = Direction.ADD,
    ): MassEntry = MassEntry(direction = direction)
        .type("1 A Card With Quite A Long Name Number 1\n1 A Card With Quite A Long Name Number 2")
        .assign(Owner.MATT)
        .previewed(Applied(dryRun = true, resolved = changes.size, changes = changes))
        .finished(
            Applied(
                applied = applied,
                resolved = changes.size,
                failed = failed,
                changes = changes,
                errors = errors,
            ),
        )

    // =============================================== whose collection?

    @Test
    fun theOwnerStepOffersBothAndPresumesNeither() = runTest {
        val root = mount(atWho())
        settle()
        assertEquals("Whose collection?", root.title())

        val opts = root.all("button.opt")
        assertEquals(2, opts.size, "both owners are not on the screen: ${opts.map { it.textContent }}")
        assertEquals(
            listOf("Matt", "Kayla"),
            opts.map { (it as HTMLButtonElement).says() },
            "the two collections are not the two collections",
        )
        opts.forEach {
            assertEquals("false", it.getAttribute("aria-pressed"), "'${it.textContent}' came preselected")
        }
        assertEquals(0, root.all("button.opt.on").size, "something was already chosen")
        assertNull(current.owner, "the state arrived with an owner in it")
    }

    @Test
    fun thePreviewIsRefusedUntilSomebodyIsNamed() = runTest {
        val root = mount(atWho())
        settle()
        assertTrue(root.button("Preview changes →").disabled, "a dry run was offered with no owner")
        assertTrue(
            root.words().contains("Pick whose collection this goes to."),
            "nothing said why the button is dead: ${root.words()}",
        )

        root.button("Matt").click()
        settle()
        assertFalse(root.button("Preview changes →").disabled, "naming an owner did not free the button")
        assertFalse(
            root.words().contains("Pick whose collection this goes to."),
            "the hint stayed up after the question was answered",
        )
    }

    @Test
    fun theChosenOwnerIsMarkedByShapeAndNotOnlyByColour() = runTest {
        // Colour on its own is not a signal everybody can read, so the
        // mark has to carry a glyph as well as a fill.
        val root = mount(atWho())
        settle()
        val matt = root.button("Matt")
        assertEquals("", matt.querySelector(".opt-mark")?.textContent, "an empty choice already wore a tick")

        matt.click()
        settle()
        assertEquals("true", root.button("Matt").getAttribute("aria-pressed"))
        assertEquals(
            "✓",
            root.button("Matt").querySelector(".opt-mark")?.textContent,
            "the choice is said by colour alone",
        )
    }

    @Test
    fun namingTheOtherOwnerMovesTheMarkRatherThanAddingOne() = runTest {
        val root = mount(atWho())
        settle()
        root.button("Matt").click()
        settle()
        assertEquals(Owner.MATT, current.owner)

        root.button("Kayla").click()
        settle()
        assertEquals(Owner.KAYLA, current.owner, "the second tap did not take")
        assertEquals(1, root.all("button.opt.on").size, "both collections are selected at once")
        assertEquals("true", root.button("Kayla").getAttribute("aria-pressed"))
        assertEquals("false", root.button("Matt").getAttribute("aria-pressed"), "the first choice is still marked")
        assertEquals("", root.button("Matt").querySelector(".opt-mark")?.textContent, "two ticks on screen")
    }

    @Test
    fun steppingBackAndForwardKeepsWhoYouSaid() = runTest {
        val root = mount(atWho())
        settle()
        root.button("Kayla").click(); settle()
        root.button("← Back").click(); settle()
        assertEquals(Step.LIST, current.step, "Back did not go back")

        root.button("Continue →").click(); settle()
        assertEquals("Whose collection?", root.title())
        assertEquals(Owner.KAYLA, current.owner, "the owner was forgotten on the way back")
        assertEquals("true", root.button("Kayla").getAttribute("aria-pressed"), "the mark did not come back")
        assertFalse(root.button("Preview changes →").disabled, "the kept answer did not count")
    }

    @Test
    fun changingTheOwnerThrowsAwayTheDryRun() = runTest {
        // A dry run describes a list going to one collection. Pointed
        // at another it is not an answer to anything.
        val root = mount(
            MassEntry(
                step = Step.WHO,
                direction = Direction.ADD,
                list = "1 A Card With Quite A Long Name Number 1",
                owner = Owner.MATT,
                preview = Applied(dryRun = true, resolved = 1, changes = listOf(change(1))),
            ),
        )
        settle()
        root.button("Kayla").click()
        settle()
        assertEquals(Owner.KAYLA, current.owner)
        assertNull(current.preview, "the dry run taken for Matt survived being pointed at Kayla")
        assertFalse(current.canApply, "a write was still reachable off a stale dry run")
    }

    @Test
    fun theOwnerStepFitsAPhone() = runTest {
        val root = mount(atWho(), width = PHONE)
        settle()
        if (!Stylesheet.applied()) return@runTest
        assertEquals(emptyList(), root.overflowing(), "something hangs off a ${PHONE}px screen")
        (root.all("button.opt") + root.all("div.wiz-foot button")).forEach { b ->
            val h = b.getBoundingClientRect().height
            assertTrue(h >= 43.5, "'${b.textContent?.trim()}' is only ${h}px tall")
        }
        // One per row. Two 170px-wide names is not a choice anybody can hit.
        val tops = root.all("button.opt").map { it.getBoundingClientRect().top }
        assertTrue(tops[1] - tops[0] > 20, "the two collections are side by side at ${PHONE}px")
    }

    // ========================================================== applied

    @Test
    fun theDoneStepSaysAppliedWhenSomethingActuallyMoved() = runTest {
        val root = mount(done())
        settle()
        assertEquals("Applied", root.title())
        assertTrue(root.words().contains("matt"), "the done screen does not say whose collection it wrote")
    }

    @Test
    fun theDoneStepSaysNothingAppliedWhenTheServerDidNothing() = runTest {
        val root = mount(done(applied = false, changes = emptyList()))
        settle()
        assertEquals("Nothing applied", root.title())
    }

    @Test
    fun anEmptyWriteIsNotCalledApplied() = runTest {
        // The server reports `applied` for the call, not for the cards.
        // A removal of printings somebody already removed comes back
        // applied and empty, and "Applied" over nothing is a lie.
        val root = mount(done(applied = true, changes = emptyList(), direction = Direction.REMOVE))
        settle()
        assertEquals("Nothing applied", root.title())
        val cells = root.all("div.tally-cell").map { it.textContent.orEmpty() }
        assertTrue(cells[0].startsWith("0"), "it claimed to have moved ${cells[0]}")
    }

    @Test
    fun theFiguresAreTheOnesThatCameBack() = runTest {
        val changes = listOf(
            change(1, before = 1, after = 3),   // +2
            change(2, before = 0, after = 1),   // +1, new
            change(3, before = 0, after = 2),   // +2, new
        )
        val root = mount(done(changes = changes))
        settle()
        val cells = root.all("div.tally-cell").map { it.textContent.orEmpty() }
        assertEquals(3, cells.size, "no summary on the done step: $cells")
        assertTrue(cells[0].startsWith("3"), "three printings moved, not ${cells[0]}")
        assertTrue(cells[1].startsWith("5"), "five copies moved, not ${cells[1]}")
        assertTrue(cells[2].startsWith("2"), "two of them were new, not ${cells[2]}")
        assertEquals(3, root.all("div.chg").size, "a printing went missing from the receipt")
    }

    @Test
    fun whatTheServerCouldNotDoIsSaidOutLoud() = runTest {
        val root = mount(done(failed = 2, errors = listOf("Sol Rong: no such card")))
        settle()
        assertTrue(
            root.words().contains("2 could not be resolved"),
            "two cards were dropped quietly: ${root.words()}",
        )
        assertTrue(root.words().contains("Sol Rong: no such card"), "the server's own words were swallowed")
    }

    @Test
    fun enterMoreEmptiesTheWizardWithoutLeavingIt() = runTest {
        val root = mount(done())
        settle()
        root.button("Enter more").click()
        settle()
        assertEquals("", current.list, "the old list is still in the box")
        assertNull(current.result, "the old receipt is still in the state")
        assertNull(current.preview, "the old dry run is still in the state")
        assertEquals(Step.WHICH, current.step, "it did not go back to the start of the wizard")
        // Still the wizard, not somewhere else.
        assertEquals("What are you doing?", root.title())
        assertFalse(root.words().contains("Applied"), "the receipt is still on the screen")
        assertTrue(root.all("div.chg").isEmpty(), "the old rows are still listed")
    }

    @Test
    fun theDoneStepCannotBeReachedWithoutAResult() = runTest {
        val ready = MassEntry(direction = Direction.ADD)
            .type("1 Sol Ring")
            .assign(Owner.MATT)
        assertTrue(ready.canPreview, "the fixture cannot ask for a dry run")
        assertFalse(ready.reachable(Step.DONE), "a done step with nothing done")

        val root = mount(ready.goTo(Step.DONE))
        settle()
        assertEquals(Step.REVIEW, current.step, "it landed on the receipt with no receipt")
        assertEquals("Preview — nothing written yet", root.title())
        assertNull(root.maybeButton("Enter more"), "the done step rendered with no result behind it")
    }

    @Test
    fun theDoneStepFitsAPhone() = runTest {
        val root = mount(
            done(changes = (1..8).map { change(it, before = if (it % 3 == 0) 0 else 1, after = 2) }),
            width = PHONE,
        )
        settle()
        if (!Stylesheet.applied()) return@runTest
        assertEquals(emptyList(), root.overflowing(), "the receipt hangs off a ${PHONE}px screen")
        root.all("div.wiz-foot button").forEach { b ->
            val h = b.getBoundingClientRect().height
            assertTrue(h >= 43.5, "'${b.textContent?.trim()}' is only ${h}px tall")
        }
        root.all("div.chg").forEach { row ->
            val r = row.getBoundingClientRect()
            val n = row.all("div.chg-n").first().getBoundingClientRect()
            assertTrue(n.right <= r.right + 1, "the count ran off the row")
        }
    }
}
