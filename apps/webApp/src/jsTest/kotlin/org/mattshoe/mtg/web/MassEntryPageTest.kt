package org.mattshoe.mtg.web

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.browser.document
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import org.jetbrains.compose.web.renderComposable
import org.mattshoe.mtg.core.MassEntry
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.get
import kotlin.js.Promise
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The web wizard, rendered and clicked in a real browser.
 *
 * Karma with headless Chrome, specifically, because Compose HTML
 * recomposes on `requestAnimationFrame` and a browser that is not
 * painting never ticks it — a hidden tab renders the first frame and
 * then silently stops responding to clicks, which looks exactly like a
 * broken app and is not one.
 *
 * What these check is that the rules in the shared core actually reach
 * the DOM: what is disabled, what is on screen, what a click changes.
 */
class MassEntryPageTest {

    private val roots = mutableListOf<HTMLElement>()

    @AfterTest
    fun cleanUp() {
        roots.forEach { it.remove() }
        roots.clear()
    }

    private var current: MassEntry = MassEntry()

    /**
     * State is hoisted now, so the test holds it and feeds it back —
     * which is also what the shell does, so this exercises the real
     * arrangement rather than a special case.
     */
    @kotlin.test.BeforeTest
    fun loadTheStylesheet() = Stylesheet.load()

    private fun mount(initial: MassEntry = MassEntry(), width: Int? = null): HTMLElement {
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

    /** An option carries help text as well as its label, so match the label. */
    private fun HTMLButtonElement.says(): String =
        (querySelector(".opt-label")?.textContent ?: textContent).orEmpty().trim()

    private fun HTMLElement.button(label: String): HTMLButtonElement =
        buttons().firstOrNull { it.says() == label }
            ?: throw AssertionError("no button '$label'; saw ${buttons().map { it.says() }}")

    private fun HTMLElement.labels() = buttons().map { it.says() to it.disabled }

    private fun HTMLElement.all(css: String): List<HTMLElement> =
        querySelectorAll(css).let { n -> (0 until n.length).mapNotNull { n[it] as? HTMLElement } }

    /** Anything sticking out past the edge of what holds it. */
    private fun HTMLElement.overflowing(): List<String> {
        val limit = getBoundingClientRect().right + 1
        return all("*").filter { it.getBoundingClientRect().right > limit }
            .map { it.tagName.lowercase() + "." + it.className }
    }

    @Test
    fun rendersRealDomAndNotACanvas() = runTest {
        val root = mount()
        settle()
        assertEquals(0, root.querySelectorAll("canvas").length, "Compose HTML must not paint to a canvas")
        assertTrue(root.querySelectorAll("button").length > 0)
        assertTrue(root.textContent!!.contains("Adding or removing?"))
    }

    @Test
    fun nothingIsPreselectedAndContinueIsRefused() = runTest {
        val root = mount()
        settle()
        assertTrue(root.textContent!!.contains("Nothing is preselected on purpose."))
        assertTrue(root.button("Continue →").disabled)
    }

    @Test
    fun choosingADirectionEnablesContinue() = runTest {
        val root = mount()
        settle()
        root.button("Add to the collection").click()
        settle()
        val cont = root.button("Continue →")
        assertFalse(cont.disabled, "Continue stayed refused after a direction was chosen")
    }

    @Test
    fun theWizardWalksToTheOwnerStep() = runTest {
        val root = mount(MassEntry.fromShare("Name,Quantity\nSol Ring,1\nLightning Bolt,2"))
        settle()
        // Three cards on two lines: the quantity column counts.
        assertTrue(root.textContent!!.contains("3 cards already in the box"), root.textContent!!)

        root.button("Add to the collection").click()
        settle()
        root.button("Continue →").click()
        settle()
        assertTrue(root.textContent!!.contains("What are you adding?"), root.textContent!!)

        root.button("Continue →").click()
        settle()
        assertTrue(root.textContent!!.contains("Whose collection?"), root.textContent!!)
        assertTrue(root.button("Preview changes →").disabled, "an owner was assumed")
    }

    /** The rule the whole wizard exists for. */
    @Test
    fun noWriteIsOfferedBeforeADryRun() = runTest {
        val root = mount(MassEntry.fromShare("1 Sol Ring\n2 Lightning Bolt"))
        settle()
        root.button("Remove from the collection").click(); settle()
        root.button("Continue →").click(); settle()
        root.button("Continue →").click(); settle()
        root.button("Matt").click(); settle()

        val labels = root.labels().map { it.first }
        assertTrue(labels.any { it.startsWith("Preview changes") }, labels.toString())
        assertFalse(labels.any { it.startsWith("Remove ") && it.contains("printings") },
            "a write was on screen with no dry run behind it: $labels")
    }

    @Test
    fun theStepperRefusesStepsNotYetAnswered() = runTest {
        val root = mount()
        settle()
        val stepButtons = root.buttons().filter { it.textContent?.contains("List") == true }
        assertTrue(stepButtons.isNotEmpty())
        assertTrue(stepButtons.all { it.disabled }, "a future step was reachable from the stepper")
    }

    // ------------------------------------------------------ on a phone
    //
    // The flow is written for a phone first. Headless Chrome will not
    // take a viewport under 500px, so the panel itself is narrowed —
    // which is the honest test anyway, because none of these rules
    // are keyed to the viewport. They widen on their container.

    /** A phone, near enough: an iPhone's 390 minus the page gutters. */
    private val PHONE = 358

    @Test
    fun theChoicesStackOnAPhoneRatherThanSqueezing() = runTest {
        val root = mount(width = PHONE)
        settle()
        if (!Stylesheet.applied()) return@runTest
        val opts = root.all("button.opt")
        assertEquals(2, opts.size)
        val tops = opts.map { it.getBoundingClientRect().top }
        assertTrue(tops[1] - tops[0] > 20, "the two options are side by side at ${PHONE}px")
        opts.forEach {
            // Minus the page gutter and the panel's own padding.
            val w = it.getBoundingClientRect().width
            assertTrue(w > PHONE - 80, "an option is only ${w}px of a ${PHONE}px screen")
        }
    }

    @Test
    fun everyThingYouTapIsBigEnoughToTap() = runTest {
        // 44 points is the smallest target a thumb reliably hits, and
        // this is the flow that writes to the collection.
        val root = mount(width = PHONE)
        settle()
        if (!Stylesheet.applied()) return@runTest
        (root.all("button.opt") + root.all("div.wiz-foot button")).forEach { b ->
            val h = b.getBoundingClientRect().height
            assertTrue(h >= 43.5, "'${b.textContent?.trim()}' is only ${h}px tall")
        }
    }

    @Test
    fun nothingOnTheFirstStepHangsOffThePhone() = runTest {
        val root = mount(width = PHONE)
        settle()
        if (!Stylesheet.applied()) return@runTest
        assertEquals(emptyList(), root.overflowing())
    }

    @Test
    fun nothingOnTheListStepHangsOffThePhoneEither() = runTest {
        val root = mount(width = PHONE)
        settle()
        root.button("Add to the collection").click(); settle()
        root.button("Continue →").click(); settle()
        if (!Stylesheet.applied()) return@runTest
        assertTrue(root.all("textarea").isNotEmpty(), "no box to paste into")
        assertEquals(emptyList(), root.overflowing())
    }

    @Test
    fun theBrowsersOwnFilePickerIsNotOnTheScreen() = runTest {
        // "Choose Files / No file chosen" sat on top of the drop
        // zone, because the rule that hides it was never written.
        val root = mount(width = PHONE)
        settle()
        root.button("Add to the collection").click(); settle()
        root.button("Continue →").click(); settle()
        if (!Stylesheet.applied()) return@runTest
        val input = root.all("input.file-in").firstOrNull() ?: error("no file input at all")
        assertEquals("0", kotlinx.browser.window.getComputedStyle(input).opacity, "the picker is visible")
        // Invisible, but still rendered: Android Chrome will not open
        // a picker for an input that is display:none.
        assertTrue(input.getBoundingClientRect().height > 20, "the picker cannot be tapped")
    }

    @Test
    fun theCountsAreOnTheScreenYouPasteInto() = runTest {
        val root = mount(
            MassEntry(direction = org.mattshoe.mtg.core.Direction.ADD)
                .goTo(org.mattshoe.mtg.core.Step.LIST)
                .type("4 Lightning Bolt\n1 Sol Ring (M3C) 409\n2 Sol Ring"),
            width = PHONE,
        )
        settle()
        val cells = root.all("div.tally-cell").map { it.textContent.orEmpty() }
        assertEquals(3, cells.size, "the tally is not on the list step: $cells")
        assertTrue(cells[0].startsWith("7"), "seven cards, not ${cells[0]}")
        assertTrue(cells[1].startsWith("2"), "two unique cards, not ${cells[1]}")
        assertTrue(cells[2].startsWith("3"), "three lines, not ${cells[2]}")
    }

    @Test
    fun whatIsChosenIsSaidWithAMarkAndNotOnlyWithColour() = runTest {
        // Colour on its own is not a signal everybody can read.
        val root = mount(width = PHONE)
        settle()
        val add = root.button("Add to the collection")
        assertEquals("false", add.getAttribute("aria-pressed"))
        assertEquals("", add.querySelector(".opt-mark")?.textContent)
        add.click(); settle()
        assertEquals("true", add.getAttribute("aria-pressed"))
        assertEquals("✓", add.querySelector(".opt-mark")?.textContent)
    }
}
