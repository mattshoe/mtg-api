package org.mattshoe.mtg.web

import kotlinx.browser.document
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import org.jetbrains.compose.web.renderComposable
import org.mattshoe.mtg.core.Applied
import org.mattshoe.mtg.core.Change
import org.mattshoe.mtg.core.DeckEditState
import org.mattshoe.mtg.core.Direction
import org.mattshoe.mtg.core.DisassembleState
import org.mattshoe.mtg.core.MassEntry
import org.mattshoe.mtg.core.Owner
import org.mattshoe.mtg.core.Step
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.get
import kotlin.js.Promise
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Nothing that writes is pressable while it is writing.
 *
 * Checked one screen at a time until a double-tapped "Add 248
 * printings" added them twice. One screen at a time is how a screen
 * gets missed, so this asks the same question of every panel that can
 * change the collection: put it in its working state, and look for a
 * live button that would start the work again.
 *
 * The gate itself is in the shared core and tested there. This is
 * about the thing under the thumb.
 */
class MutatingButtonsTest {

    private val roots = mutableListOf<HTMLElement>()

    @AfterTest
    fun cleanUp() {
        roots.forEach { it.remove() }
        roots.clear()
    }

    private suspend fun settle() = repeat(4) {
        Promise<Unit> { r, _ -> kotlinx.browser.window.requestAnimationFrame { r(Unit) } }.await()
    }

    private fun mount(content: @androidx.compose.runtime.Composable () -> Unit): HTMLElement {
        val root = document.createElement("div") as HTMLElement
        document.body!!.appendChild(root)
        roots += root
        renderComposable(root = root) { content() }
        return root
    }

    private fun HTMLElement.buttons(): List<HTMLButtonElement> =
        querySelectorAll("button").let { n ->
            (0 until n.length).mapNotNull { n[it] as? HTMLButtonElement }
        }

    /** A button that would start the work again, still pressable. */
    private fun HTMLElement.liveStarters(words: List<String>): List<String> =
        buttons()
            .filter { b -> !b.disabled }
            .map { it.textContent.orEmpty().trim() }
            .filter { label -> words.any { it.lowercase() in label.lowercase() } }

    private val plan = Applied(
        applied = false,
        resolved = 1,
        failed = 0,
        changes = listOf(Change("Sol Ring", "M3C", "409", "nonfoil", 0, 4)),
        errors = emptyList(),
    )

    @Test
    fun massEntryOffersNothingWhileItIsApplying() = runTest {
        val busy = MassEntry(
            step = Step.REVIEW,
            direction = Direction.ADD,
            list = "4 Sol Ring",
            owner = Owner.MATT,
            preview = plan,
        ).working("Applying…")
        val root = mount {
            MassEntryPage(busy, {}, {}, {}, org.mattshoe.mtg.core.EntryHistory(), {}, {}, {})
        }
        settle()
        assertTrue(
            root.liveStarters(listOf("Add ", "Remove ", "Preview")).isEmpty(),
            "mass entry is still offering: ${root.liveStarters(listOf("Add ", "Remove ", "Preview"))}",
        )
    }

    @Test
    fun aDeckSaveOffersNothingWhileItIsSaving() = runTest {
        val busy = DeckEditState(slug = "alela", deckName = "Alela", list = "1 Sol Ring").working()
        val root = mount { DeckEditDialog(busy, {}, {}, {}, {}) }
        settle()
        assertTrue(
            root.liveStarters(listOf("Save", "Review")).isEmpty(),
            "the deck editor is still offering: ${root.liveStarters(listOf("Save", "Review"))}",
        )
    }

    @Test
    fun aDisassembleOffersNothingWhileItIsRunning() = runTest {
        val busy = DisassembleState("alela", "Alela", "matt").working()
        val root = mount { DisassembleDialog(busy, {}, {}) }
        settle()
        assertTrue(
            root.liveStarters(listOf("Disassemble")).isEmpty(),
            "disassemble is still offering: ${root.liveStarters(listOf("Disassemble"))}",
        )
    }
}
