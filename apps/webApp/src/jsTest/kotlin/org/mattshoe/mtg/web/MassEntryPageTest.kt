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
    private fun mount(initial: MassEntry = MassEntry()): HTMLElement {
        val root = document.createElement("div") as HTMLElement
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

    private fun HTMLElement.button(label: String): HTMLButtonElement =
        buttons().firstOrNull { it.textContent?.trim() == label }
            ?: throw AssertionError("no button '$label'; saw ${buttons().map { it.textContent?.trim() }}")

    private fun HTMLElement.labels() = buttons().map { it.textContent?.trim() to it.disabled }

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
        assertTrue(root.button("Pick one to continue").disabled)
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
        assertTrue(root.textContent!!.contains("2 cards already in the box"))

        root.button("Add to the collection").click()
        settle()
        root.button("Continue →").click()
        settle()
        assertTrue(root.textContent!!.contains("What are you adding?"), root.textContent!!)

        root.button("Continue →").click()
        settle()
        assertTrue(root.textContent!!.contains("Whose collection?"), root.textContent!!)
        assertTrue(root.button("Pick one to continue").disabled, "an owner was assumed")
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

        val labels = root.labels().map { it.first.orEmpty() }
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
}
