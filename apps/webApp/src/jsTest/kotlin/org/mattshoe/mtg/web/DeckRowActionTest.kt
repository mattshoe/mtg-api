package org.mattshoe.mtg.web

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import org.jetbrains.compose.web.renderComposable
import org.mattshoe.mtg.core.Deck
import org.mattshoe.mtg.core.DeckCard
import org.mattshoe.mtg.core.DecksState
import org.mattshoe.mtg.core.TokenCard
import org.mattshoe.mtg.core.Tweak
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.KeyboardEventInit
import org.w3c.dom.get
import kotlin.js.Promise
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The "⋯" on a card's row in an open deck.
 *
 * It is the only way into the tweak sheet, it is admin-only, and it
 * sits inside a row that is itself a button — so every one of these is
 * a thing that silently does the wrong thing if it is not held down:
 * the press falling through to the card drawer, the wrong card being
 * handed to the sheet, the mark showing up for a reader who cannot
 * change anything, a screen reader hearing "⋯", or a 34px target on a
 * thumb.
 */
class DeckRowActionTest {

    private val roots = mutableListOf<HTMLElement>()

    @BeforeTest
    fun loadTheStylesheet() = Stylesheet.load()

    @AfterTest
    fun cleanUp() {
        roots.forEach { it.remove() }
        roots.clear()
    }

    private suspend fun settle() = repeat(4) {
        Promise<Unit> { r, _ -> window.requestAnimationFrame { r(Unit) } }.await()
    }

    private fun mount(width: Int, block: @androidx.compose.runtime.Composable () -> Unit): HTMLElement {
        val frame = document.createElement("div") as HTMLElement
        frame.style.width = "${width}px"
        frame.style.position = "absolute"
        frame.style.left = "0px"
        document.body!!.appendChild(frame)
        roots += frame
        renderComposable(root = frame) { block() }
        return frame
    }

    private fun HTMLElement.all(css: String): List<HTMLElement> =
        querySelectorAll(css).let { n -> (0 until n.length).mapNotNull { n[it] as? HTMLElement } }

    private fun deck() = Deck("a", "Alela", "matt", "Alela, Artful Provocateur (ELD) 324", "UW", 3, null)

    private fun card(name: String, type: String?, role: String? = null, owned: Int = 1) =
        DeckCard(
            name, qty = 1, role = role, owned = owned,
            nameNorm = name.lowercase(), typeLine = type, scryfallId = "abcdef12-3456",
        )

    /** Six real cards, one of them the commander and one of them unowned. */
    private fun opened() = DecksState()
        .loaded(listOf(deck()))
        .opened(
            "a",
            listOf(
                card("Alela, Artful Provocateur", "Legendary Creature — Faerie", "commander"),
                card("Sol Ring", "Artifact"),
                card("Zulaport Cutthroat", "Creature — Human Rogue"),
                card("Birds of Paradise", "Creature — Bird"),
                card("Rhystic Study", "Enchantment", owned = 0),
                card("Island", "Basic Land — Island"),
            ),
        )

    private fun withTokens() = opened().withTokens(
        listOf(
            TokenCard("abcdef12-3456", "Bird", "Token Creature — Bird", "1", "1", "W", madeBy = 2),
            TokenCard("ccdef123-4567", "Clue", "Token Artifact — Clue"),
        ),
    )

    /** The row the named card is on. */
    private fun HTMLElement.row(name: String) =
        all("div.deck-line").first { it.textContent.orEmpty().contains(name) }

    /** Its "⋯", or a failure that names the card rather than an index. */
    private fun HTMLElement.action(name: String) =
        row(name).all("button.row-act").firstOrNull() ?: error("no ⋯ on the $name row")

    private fun HTMLElement.press(key: String) = dispatchEvent(
        KeyboardEvent("keydown", KeyboardEventInit(key = key, bubbles = true, cancelable = true)),
    )

    // -------------------------------------------------- locked and unlocked

    @Test
    fun theActionIsNotThereAtAllWhenAdminIsLocked() = runTest {
        val frame = mount(1000) { DecksPage(opened(), {}, {}, admin = false) }
        settle()
        assertEquals(
            0, frame.all("button.row-act").size,
            "a reader who cannot change the deck is being offered the change button",
        )
        // Hidden is not absent: nothing of it may be in the tree.
        assertEquals(0, frame.all("[aria-label^='Change ']").size)
        // And the row is otherwise untouched.
        assertTrue(frame.textContent.orEmpty().contains("Sol Ring"))
        assertEquals(6, frame.all("div.deck-line span.num").size, "the quantities went with it")
    }

    @Test
    fun everyCardRowGetsExactlyOneActionWhenAdminIsUnlocked() = runTest {
        val frame = mount(1000) { DecksPage(opened(), {}, {}, admin = true) }
        settle()
        val rows = frame.all("div.deck-line")
        assertEquals(6, rows.size)
        assertEquals(6, frame.all("button.row-act").size, "not one ⋯ per card")
        assertTrue(
            rows.all { it.all("button.row-act").size == 1 },
            "a row with none or with two",
        )
        // Including the commander and including a card nobody owns —
        // both are cards the deck really has.
        assertEquals("Change Alela, Artful Provocateur", frame.action("Alela").getAttribute("aria-label"))
        assertEquals("Change Rhystic Study", frame.action("Rhystic Study").getAttribute("aria-label"))
    }

    @Test
    fun aTokenRowHasNoActionBecauseTheDeckDoesNotHaveThatCard() = runTest {
        val frame = mount(1000) { DecksPage(withTokens(), {}, {}, admin = true) }
        settle()
        val tokens = frame.all("div.panel").first { it.textContent.orEmpty().startsWith("Tokens") }
        assertEquals(
            0, tokens.all("button.row-act").size,
            "a token the deck makes is being offered a change to the deck list",
        )
        // Still six, so the count did not quietly move to the tokens.
        assertEquals(6, frame.all("button.row-act").size)
    }

    // ------------------------------------------------------- what it does

    @Test
    fun pressingItAsksToTweakThatCardAndNoOther() = runTest {
        var asked: Pair<String, Tweak?>? = null
        val frame = mount(1000) {
            DecksPage(opened(), {}, {}, admin = true, onTweak = { c, t -> asked = c.nameNorm to t })
        }
        settle()
        frame.action("Sol Ring").click()
        settle()
        assertEquals("sol ring", asked?.first, "the sheet opened on the wrong card")
        // Nothing is decided yet: the sheet asks what to do.
        assertNull(asked?.second, "the row picked the change instead of asking")

        frame.action("Island").click()
        settle()
        assertEquals("island", asked?.first, "the second press reopened the first card")
    }

    @Test
    fun pressingItDoesNotAlsoOpenTheCardDetail() = runTest {
        var openedCard: String? = null
        var tweaked: String? = null
        val frame = mount(1000) {
            DecksPage(
                opened(), {}, {}, admin = true,
                onOpenCard = { c, _ -> openedCard = c.nameNorm },
                onTweak = { c, _ -> tweaked = c.nameNorm },
            )
        }
        settle()
        frame.action("Sol Ring").click()
        settle()
        assertEquals("sol ring", tweaked)
        assertNull(openedCard, "the press fell through the button and opened the card page as well")
    }

    @Test
    fun theRowStillOpensTheCardWithTheActionSittingOnIt() = runTest {
        var openedCard: String? = null
        var tweaked: String? = null
        val frame = mount(1000) {
            DecksPage(
                opened(), {}, {}, admin = true,
                onOpenCard = { c, owner -> openedCard = "${c.nameNorm}/$owner" },
                onTweak = { c, _ -> tweaked = c.nameNorm },
            )
        }
        settle()
        frame.row("Sol Ring").click()
        settle()
        assertEquals("sol ring/matt", openedCard, "the admin button broke the row it sits on")
        assertNull(tweaked, "opening the card also opened the sheet")
    }

    // ---------------------------------------------------------- keyboard

    @Test
    fun theRowItselfIsStillOpenedByTheKeyboard() = runTest {
        var openedCard: String? = null
        val frame = mount(1000) {
            DecksPage(opened(), {}, {}, admin = true, onOpenCard = { c, _ -> openedCard = c.nameNorm })
        }
        settle()
        frame.row("Sol Ring").press("Enter")
        settle()
        assertEquals("sol ring", openedCard)
        openedCard = null
        frame.row("Island").press(" ")
        settle()
        assertEquals("island", openedCard, "space does not work on a row that says it is a button")
    }

    @Test
    fun theKeyboardOnTheActionDoesNotOpenTheCardDetail() = runTest {
        var openedCard: String? = null
        val frame = mount(1000) {
            DecksPage(opened(), {}, {}, admin = true, onOpenCard = { c, _ -> openedCard = c.nameNorm })
        }
        settle()
        // Tab to the ⋯ and press it. The row is listening for the same
        // keys, and a keydown bubbles, so without the button keeping
        // the press this opens the card page behind the sheet.
        frame.action("Sol Ring").press("Enter")
        settle()
        assertNull(openedCard, "Enter on the ⋯ opened the card page too")
        frame.action("Sol Ring").press(" ")
        settle()
        assertNull(openedCard, "space on the ⋯ opened the card page too")
    }

    @Test
    fun theActionIsARealButtonTheKeyboardCanReach() = runTest {
        val frame = mount(1000) { DecksPage(opened(), {}, {}, admin = true) }
        settle()
        val act = frame.action("Sol Ring")
        assertEquals("BUTTON", act.tagName, "a div cannot be pressed with a keyboard")
        assertTrue(act.tabIndex >= 0, "it is taken out of the tab order (tabindex ${act.tabIndex})")
        act.focus()
        assertEquals(act, document.activeElement, "it cannot take focus")
    }

    // ------------------------------------------------------ what it says

    @Test
    fun itSaysWhichCardItActsOnRatherThanJustEllipsis() = runTest {
        val frame = mount(1000) { DecksPage(opened(), {}, {}, admin = true) }
        settle()
        val act = frame.action("Zulaport Cutthroat")
        val name = act.getAttribute("aria-label").orEmpty()
        assertTrue(
            name.contains("Zulaport Cutthroat"),
            "a screen reader hears \"${act.textContent}\" with nothing saying which card: '$name'",
        )
        assertTrue(name.length > 3, "the accessible name is '$name'")
        // Two rows, two different names, so it is not one label reused.
        assertTrue(
            frame.all("button.row-act").mapNotNull { it.getAttribute("aria-label") }.distinct().size == 6,
            "the six buttons do not have six names",
        )
        // And a pointer gets the same answer on hover.
        assertTrue(act.getAttribute("title").orEmpty().isNotBlank(), "no tooltip")
    }

    // ----------------------------------------------------- on a phone

    @Test
    fun itIsAFortyFourPixelTapTargetOnAPhone() = runTest {
        val frame = mount(400) { DecksPage(opened(), {}, {}, admin = true) }
        settle()
        if (!Stylesheet.applied()) return@runTest
        val r = frame.action("Sol Ring").getBoundingClientRect()
        assertTrue(r.width >= 44, "the ⋯ is ${r.width}px wide, which is smaller than a thumb")
        assertTrue(r.height >= 44, "the ⋯ is ${r.height}px tall, which is smaller than a thumb")
    }

    @Test
    fun noTouchScreenRuleShrinksTheTapTargetBelowFortyFour() = runTest {
        if (!Stylesheet.applied()) return@runTest
        val sizes = rowActSizes()
        assertTrue(sizes.isNotEmpty(), "no .row-act sizing found in the stylesheet at all")
        assertTrue(
            sizes.all { it >= 44 },
            "a rule sizes the ⋯ at ${sizes.filter { it < 44 }}px — on a touch screen it is the " +
                "coarse-pointer rule that wins",
        )
    }

    @Test
    fun itDoesNotOverlapTheNameOrPushTheQuantityOffAPhone() = runTest {
        val frame = mount(400) { DecksPage(opened(), {}, {}, admin = true) }
        settle()
        if (!Stylesheet.applied()) return@runTest
        val row = frame.row("Zulaport Cutthroat")
        val rowBox = row.getBoundingClientRect()
        val act = row.all("button.row-act").first().getBoundingClientRect()
        val num = row.all("span.num").first().getBoundingClientRect()
        val name = row.all("span.t-name").first().getBoundingClientRect()

        assertTrue(act.left >= name.right - 0.5, "the ⋯ sits on top of the card's name")
        assertTrue(act.left >= num.right - 0.5, "the ⋯ sits on top of the quantity")
        assertTrue(act.right <= rowBox.right + 0.5, "the ⋯ is ${act.right - rowBox.right}px off the row")
        assertTrue(num.right <= rowBox.right + 0.5, "the quantity is pushed off a 400px screen")
        assertTrue(name.width > 40, "the name is squeezed to ${name.width}px to make room")
        assertTrue(
            row.scrollWidth <= row.clientWidth + 1,
            "the row scrolls sideways: ${row.scrollWidth} in ${row.clientWidth}",
        )
    }

    /**
     * Every width/height the stylesheet gives `.row-act`, media queries
     * included.
     *
     * `Stylesheet.topLevelRules()` stops at the top level on purpose,
     * and the rule that matters here is the one inside
     * `@media (pointer: coarse)` — the only one that applies on the
     * device the 44px is for.
     */
    private fun rowActSizes(): List<Double> {
        val out = mutableListOf<Double>()
        val sheets = document.styleSheets
        for (i in 0 until sheets.length) {
            val sheet = sheets.item(i) ?: continue
            if (sheet.href?.endsWith("app.css") != true) continue
            val rules = try { sheet.asDynamic().cssRules } catch (e: Throwable) { null } ?: continue
            collect(rules, out)
        }
        return out
    }

    private fun collect(rules: dynamic, out: MutableList<Double>) {
        val length = (rules.length as? Int) ?: return
        for (n in 0 until length) {
            val rule = rules[n]
            // A `@media` block is a rule with rules in it; everything
            // else is a rule with a selector. `rule.cssRules` on a
            // plain style rule is `undefined`, which is not `null`.
            val selector = rule.selectorText as? String
            if (selector == null) {
                if (jsTypeOf(rule.cssRules) == "object") collect(rule.cssRules, out)
                continue
            }
            if (!selector.split(",").any { it.trim().endsWith(".row-act") }) continue
            val style = rule.style
            listOf("width", "height").forEach { prop ->
                val v = style.getPropertyValue(prop) as? String
                v?.removeSuffix("px")?.toDoubleOrNull()?.let { out += it }
            }
        }
    }
}
