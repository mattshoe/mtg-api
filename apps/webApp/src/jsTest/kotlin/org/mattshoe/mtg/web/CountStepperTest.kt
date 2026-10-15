package org.mattshoe.mtg.web

import androidx.compose.runtime.mutableStateOf
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import org.jetbrains.compose.web.renderComposable
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
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.get
import kotlin.js.Promise
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * "- 3 +" on the carousel and on the card page, the website's half.
 *
 * Matt: "i want it to just be like a plus/minus view [...] if i have
 * 3 copies of something it would say "- 3 +" but actual button and UI
 * not text". So the claims are about real buttons the browser laid
 * out: minus left of the number, plus right of it, on one line, and
 * a press that sends the right write. The phone's `CountStepperTest`
 * is the same claims through its `AppShell`.
 */
class CountStepperTest {

    private val roots = mutableListOf<HTMLElement>()
    private lateinit var held: androidx.compose.runtime.MutableState<AppState>
    private val sent = mutableListOf<CountWrite>()

    @BeforeTest
    fun loadTheStylesheet() = Stylesheet.load()

    @AfterTest
    fun cleanUp() {
        roots.forEach { it.remove() }
        roots.clear()
        sent.clear()
    }

    private suspend fun settle() = repeat(4) {
        Promise<Unit> { r, _ -> window.requestAnimationFrame { r(Unit) } }.await()
    }

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

    private suspend fun mount(start: AppState) {
        val frame = document.createElement("div") as HTMLElement
        document.body!!.appendChild(frame)
        roots += frame
        renderComposable(root = frame) {
            held = androidx.compose.runtime.remember { mutableStateOf(start) }
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
        settle()
    }

    private fun el(sel: String): HTMLElement =
        assertNotNull(document.querySelector(sel) as? HTMLElement, "nothing on the page matches $sel")

    /** Minus, the number, plus: left to right, on one line, and buttons. */
    private fun assertLaidOutAsMinusCountPlus(scope: String, count: String) {
        val minus = assertIs<HTMLButtonElement>(el("$scope .stepper .step-minus"), "minus is not a button")
        val plus = assertIs<HTMLButtonElement>(el("$scope .stepper .step-plus"), "plus is not a button")
        val number = el("$scope .stepper .step-count")
        assertEquals(count, number.textContent?.trim(), "the stepper says the wrong number")
        val m = minus.getBoundingClientRect()
        val n = number.getBoundingClientRect()
        val p = plus.getBoundingClientRect()
        // The size of every other button beside it, which is what Matt
        // asked for; `.btn.sm` on a desktop is the smallest that gets.
        assertTrue(m.width >= 24 && m.height >= 24, "minus is ${m.width}x${m.height}, too small to press")
        assertTrue(p.width >= 24 && p.height >= 24, "plus is ${p.width}x${p.height}, too small to press")
        assertTrue(m.right <= n.left + 0.5, "minus (right ${m.right}) is not left of the number (left ${n.left})")
        assertTrue(n.right <= p.left + 0.5, "the number (right ${n.right}) is not left of plus (left ${p.left})")
        val mid = { r: org.w3c.dom.DOMRect -> r.top + r.height / 2 }
        assertTrue(kotlin.math.abs(mid(m) - mid(p)) < 2, "minus and plus are not on one line")
    }

    private suspend fun openSolRing() {
        mount(onADeck())
        document.querySelectorAll(".deck-line").let { l -> (0 until l.length).map { l[it] as HTMLElement } }
            .first { it.textContent?.contains("Sol Ring") == true }
            .click()
        settle()
        assertEquals("Sol Ring", held.value.peeked?.title, "the carousel did not open on Sol Ring")
    }

    private fun sheetButton(label: String): HTMLButtonElement? =
        document.querySelectorAll(".peek-sheet button").let { l -> (0 until l.length).map { l[it] } }
            .filterIsInstance<HTMLButtonElement>()
            .firstOrNull { it.textContent?.trim() == label || it.getAttribute("aria-label") == label }

    @Test
    fun theCarouselsStepperIsNoTallerThanTheButtonsUnderIt() = runTest {
        // Matt: "the plus minus view looks terrible why is it bigger
        // than everything else?"
        openSolRing()
        val swap = assertNotNull(sheetButton("Swap"), "no Swap on the sheet").getBoundingClientRect()
        listOf("minus" to ".step-minus", "plus" to ".step-plus").forEach { (name, sel) ->
            val b = el(".peek-sheet .stepper $sel").getBoundingClientRect()
            assertTrue(
                kotlin.math.abs(b.height - swap.height) < 1,
                "$name is ${b.height} tall and Swap beside it is ${swap.height}",
            )
        }
    }

    @Test
    fun theCarouselSaysTheCountOnceAndSetsItOnce() = runTest {
        // Matt: "why is there still s count button?!?! [...] same with
        // remove?! same with the "1x in deck"?!?!?!"
        openSolRing()
        assertNotNull(sheetButton("Swap"), "Swap went too, and the stepper cannot swap")
        listOf("Count", "Remove").forEach {
            assertEquals(null, sheetButton(it), "the sheet still offers \"$it\" beside the stepper")
        }
        val said = el(".peek-sheet .peek-tags").textContent.orEmpty()
        assertTrue("in deck" !in said, "the sheet still says how many beside the stepper: $said")
    }

    @Test
    fun theDecksCarouselStepsTheDecksCountWithRealButtons() = runTest {
        openSolRing()

        // 2 in the deck, 5 in the collection: the deck's number.
        assertLaidOutAsMinusCountPlus(".peek-sheet", "2")

        el(".peek-sheet .stepper .step-plus").click()
        settle()
        val write = assertIs<CountWrite.DeckList>(sent.singleOrNull(), "plus sent ${sent}")
        assertTrue("3 Sol Ring" in write.list.lines(), "plus did not ask for a third:\n${write.list}")
    }

    @Test
    fun theCardPageStepsTheCollection() = runTest {
        mount(onTheCard())
        // 3 of Matt's, and Kayla's 4 are not his to count.
        assertLaidOutAsMinusCountPlus(".card-page", "3")

        el(".card-page .stepper .step-minus").click()
        settle()
        val write = assertIs<CountWrite.Cards>(sent.singleOrNull(), "minus sent ${sent}")
        assertEquals(Direction.REMOVE, write.direction)
        assertEquals("1 Sol Ring (M3C) 409", write.line)
    }
}
