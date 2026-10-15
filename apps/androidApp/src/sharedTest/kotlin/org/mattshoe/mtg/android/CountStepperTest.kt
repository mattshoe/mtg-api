package org.mattshoe.mtg.android

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mattshoe.mtg.core.Account
import org.mattshoe.mtg.core.Admin
import org.mattshoe.mtg.core.AppState
import org.mattshoe.mtg.core.CardDetail
import org.mattshoe.mtg.core.CardRef
import org.mattshoe.mtg.core.CountWrite
import org.mattshoe.mtg.core.Deck
import org.mattshoe.mtg.core.DeckCard
import org.mattshoe.mtg.core.Direction
import org.mattshoe.mtg.core.Printing
import org.mattshoe.mtg.core.Route
import org.mattshoe.mtg.core.View
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * "- 3 +" on the carousel and on the card page, the phone's half.
 *
 * Matt: "i want it to just be like a plus/minus view [...] if i have
 * 3 copies of something it would say "- 3 +" but actual button and UI
 * not text". Through `AppShell`, and the claims are measured: minus
 * left of the number, plus right of it, both big enough for a thumb
 * and both actual buttons. The website's `CountStepperTest` is the
 * same claims in a real browser.
 */
@RunWith(AndroidJUnit4::class)
class CountStepperTest {

    @get:Rule
    val rule = createComposeRule()

    private lateinit var held: androidx.compose.runtime.MutableState<AppState>
    private val sent = mutableListOf<CountWrite>()

    private val me = Admin().signIn(Account("matt0001"), "t")

    private fun card(name: String, qty: Int, owned: Int) = DeckCard(
        name = name,
        qty = qty,
        role = null,
        owned = owned,
        nameNorm = name.lowercase(),
        typeLine = "Artifact",
        scryfallId = "abcdef12-3456",
    )

    private fun onADeck() = AppState(admin = me)
        .navigate(Route(View.DECKS, "alela"))
        .let {
            it.copy(
                decks = it.decks
                    .loaded(listOf(Deck("alela", "Alela", "matt0001", null, "UW", 3, null)))
                    .opened("alela", listOf(card("Cultivate", 1, 1), card("Sol Ring", 2, 5))),
            )
        }

    private fun onTheCard() = AppState(admin = me)
        .openCard(CardRef("sol ring"), "Sol Ring")
        .copy(
            card = CardDetail(
                name = "Sol Ring",
                nameNorm = "sol ring",
                printings = listOf(
                    Printing(1, "m3c", null, "409", "nonfoil", 3, null, owner = "matt0001"),
                    Printing(2, "cmr", null, "1", "nonfoil", 4, null, owner = "k4yy0003"),
                ),
            ),
        )

    private fun shell(start: AppState) {
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface {
                    held = androidx.compose.runtime.remember {
                        androidx.compose.runtime.mutableStateOf(start)
                    }
                    AppShell(
                        state = held.value,
                        onState = { held.value = it },
                        onSearch = {},
                        onOpenDeck = {},
                        onPreviewEntry = {},
                        onApplyEntry = {},
                        onCount = { sent += it },
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    private fun part(tag: String, under: String) =
        rule.onNode(hasTestTag(tag) and hasAnyAncestor(hasTestTag(under)), useUnmergedTree = true)

    /** Minus, the number, plus: left to right, on one line, and buttons. */
    private fun assertLaidOutAsMinusCountPlus(under: String, count: String) {
        val minus = part("step-minus", under)
        val number = part("step-count", under)
        val plus = part("step-plus", under)
        runCatching { number.performScrollTo() }
        rule.waitForIdle()

        val said = number.fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)
            ?.joinToString("") { it.text }
        assertEquals(count, said, "the stepper says the wrong number")
        listOf("minus" to minus, "plus" to plus).forEach { (name, node) ->
            assertEquals(
                Role.Button,
                node.fetchSemanticsNode().config.getOrNull(SemanticsProperties.Role),
                "$name is not a button",
            )
        }

        val m = minus.getUnclippedBoundsInRoot()
        val n = number.getUnclippedBoundsInRoot()
        val p = plus.getUnclippedBoundsInRoot()
        // The height of every other button on the sheet, which is what
        // Matt asked for, and `theCarouselsStepperIsNoTallerThanTheButtonsUnderIt`
        // holds it to. This only says it is still a button and not a dot.
        assertTrue(m.width >= 24.dp && m.height >= 24.dp, "minus is ${m.width}x${m.height}, too small to press")
        assertTrue(p.width >= 24.dp && p.height >= 24.dp, "plus is ${p.width}x${p.height}, too small to press")
        assertTrue(m.right <= n.left, "minus (right ${m.right}) is not left of the number (left ${n.left})")
        assertTrue(n.right <= p.left, "the number (right ${n.right}) is not left of plus (left ${p.left})")
        val mid = { top: androidx.compose.ui.unit.Dp, bottom: androidx.compose.ui.unit.Dp -> (top + bottom) / 2 }
        assertTrue(
            (mid(m.top, m.bottom) - mid(p.top, p.bottom)).value.let { kotlin.math.abs(it) < 2f },
            "minus and plus are not on one line",
        )
    }

    private fun openSolRing() {
        shell(onADeck())
        rule.onNode(hasText("Sol Ring") and hasAnyAncestor(hasTestTag("deck-detail"))).let {
            runCatching { it.performScrollTo() }
            it.performClick()
        }
        rule.waitForIdle()
        assertEquals("Sol Ring", held.value.peeked?.title, "the carousel did not open on Sol Ring")
    }

    private fun onTheSheet(text: String, substring: Boolean = false) = rule.onAllNodes(
        hasText(text, substring = substring) and hasAnyAncestor(hasTestTag("carousel-sheet")),
        useUnmergedTree = true,
    ).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun theCarouselsStepperIsNoTallerThanTheButtonsUnderIt() {
        // Matt: "the plus minus view looks terrible why is it bigger
        // than everything else?"
        openSolRing()
        val swap = rule.onNode(
            hasText("Swap") and hasAnyAncestor(hasTestTag("carousel-sheet")),
            useUnmergedTree = true,
        ).getUnclippedBoundsInRoot()
        listOf("minus" to "step-minus", "plus" to "step-plus").forEach { (name, tag) ->
            val b = part(tag, "carousel-sheet").getUnclippedBoundsInRoot()
            assertTrue(
                kotlin.math.abs((b.height - swap.height).value) < 1f,
                "$name is ${b.height} tall and Swap beside it is ${swap.height}",
            )
        }
    }

    @Test
    fun theCarouselSaysTheCountOnceAndSetsItOnce() {
        // Matt: "why is there still s count button?!?! [...] same with
        // remove?! same with the "1x in deck"?!?!?!"
        openSolRing()
        assertTrue(onTheSheet("Swap"), "Swap went too, and the stepper cannot swap")
        listOf("Count", "Remove").forEach {
            assertTrue(!onTheSheet(it), "the sheet still offers \"$it\" beside the stepper")
        }
        // Remove is a bin now, so it is looked for by what it is called.
        assertTrue(
            rule.onAllNodes(hasContentDescription("Remove") and hasAnyAncestor(hasTestTag("carousel-sheet"))).fetchSemanticsNodes().isEmpty(),
            "the sheet still offers Remove beside the stepper",
        )
        assertTrue(!onTheSheet("× in deck", substring = true), "the sheet still says how many beside the stepper")
    }

    @Test
    fun theDecksCarouselStepsTheDecksCountWithRealButtons() {
        openSolRing()

        // 2 in the deck, 5 in the collection: the deck's number.
        assertLaidOutAsMinusCountPlus("carousel-sheet", "2")

        part("step-plus", "carousel-sheet").performClick()
        rule.waitForIdle()
        val write = assertIs<CountWrite.DeckList>(sent.singleOrNull(), "plus sent $sent")
        assertTrue("3 Sol Ring" in write.list.lines(), "plus did not ask for a third:\n${write.list}")
    }

    @Test
    fun theCardPageStepsTheCollection() {
        shell(onTheCard())
        // 3 of Matt's, and Kayla's 4 are not his to count.
        assertLaidOutAsMinusCountPlus("card-sheet", "3")

        part("step-minus", "card-sheet").performClick()
        rule.waitForIdle()
        val write = assertIs<CountWrite.Cards>(sent.singleOrNull(), "minus sent $sent")
        assertEquals(Direction.REMOVE, write.direction)
        assertEquals("1 Sol Ring (M3C) 409", write.line)
    }
}
