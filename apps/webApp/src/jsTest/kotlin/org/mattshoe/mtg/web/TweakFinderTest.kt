package org.mattshoe.mtg.web

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import org.jetbrains.compose.web.renderComposable
import org.mattshoe.mtg.core.Deck
import org.mattshoe.mtg.core.DeckCard
import org.mattshoe.mtg.core.DeckTweak
import org.mattshoe.mtg.core.Found
import org.mattshoe.mtg.core.Tweak
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.Event
import org.w3c.dom.get
import kotlin.js.Promise
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The card finder inside the tweak sheet, typed into and tapped.
 *
 * This is the only way a card gets into a deck from the deck's own
 * page, and none of it had a test. The suite drives it the way a
 * thumb does — type, wait for the hits, tap one — and then looks at
 * the `DeckTweak` that came out and at the DOM that is on screen.
 */
class TweakFinderTest {

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

    private val deck = Deck("alela", "Alela", "matt", "Alela, Artful Provocateur", "UW", 3, null)

    private fun found(
        name: String,
        qty: Int = 0,
        owner: String = "",
        type: String? = "Instant",
        id: Long = 1,
    ) = Found(id, name, "sf-$id", type, qty, owner)

    private val bolt = found("Lightning Bolt", qty = 4, owner = "matt", id = 1)

    // ------------------------------------------------------------ mount

    private class Sheet(val root: HTMLElement, val state: () -> DeckTweak, val push: (DeckTweak) -> Unit) {

        val asked = mutableListOf<String>()

        fun all(css: String): List<HTMLElement> =
            root.querySelectorAll(css).let { n -> (0 until n.length).mapNotNull { n[it] as? HTMLElement } }

        fun box() = root.querySelector("input.field:not(.count)") as HTMLInputElement

        fun type(text: String) {
            val input = box()
            input.value = text
            input.dispatchEvent(Event("input", js("({bubbles: true})")))
        }

        fun hits() = all("button.found-row")

        fun hitText() = hits().map { it.textContent?.trim().orEmpty() }

        fun picked() = root.querySelector(".tweak-pick") as HTMLElement?

        fun button(label: String) = all("button").firstOrNull { it.textContent?.trim() == label } as HTMLButtonElement?

        /** What `94vw` comes to on a 400px phone, so a row can be measured against it. */
        fun phoneWide() {
            (root.querySelector(".palette") as HTMLElement).style.width = "376px"
        }
    }

    private fun mount(start: DeckTweak): Sheet {
        val frame = document.createElement("div") as HTMLElement
        frame.style.width = "400px"
        document.body!!.appendChild(frame)
        roots += frame
        var held = start
        var push: (DeckTweak) -> Unit = {}
        val sheet = Sheet(frame, { held }, { push(it) })
        renderComposable(root = frame) {
            var s by remember { mutableStateOf(start) }
            push = { s = it; held = it }
            DeckTweakSheet(
                state = s,
                onState = { s = it; held = it },
                onFind = { sheet.asked += it },
                onPreview = {},
                onApply = {},
                onClose = {},
            )
        }
        return sheet
    }

    private fun adding() = DeckTweak.add(deck, "Alela, Artful Provocateur")

    // --------------------------------------------------- what is a term

    @Test
    fun aTermShorterThanTheMinimumAsksTheServerNothing() = runTest {
        // One letter matches the whole collection. Asking for it is a
        // round trip whose answer is useless.
        val s = mount(adding())
        settle()
        s.type("l")
        settle()
        assertEquals(emptyList(), s.asked, "one letter went to the server")
        assertEquals("l", s.state().term, "the box did not keep what was typed")
        assertEquals(0, s.hits().size, "a one-letter term listed something")
    }

    @Test
    fun exactlyTheMinimumIsEnoughToAsk() = runTest {
        val s = mount(adding())
        settle()
        s.type("li")
        settle()
        assertEquals(listOf("li"), s.asked, "the minimum term was not asked for")
    }

    @Test
    fun whitespaceAloneIsNotATerm() = runTest {
        val s = mount(adding())
        settle()
        s.type("   ")
        settle()
        assertEquals(emptyList(), s.asked, "three spaces were sent as a search")
        assertEquals(0, s.hits().size, "whitespace listed something")
    }

    @Test
    fun theFinderOnlyAppearsWhenACardIsComingIn() = runTest {
        // Removing a card and changing how many do not need one.
        val card = DeckCard("Sol Ring", qty = 1, role = null, owned = 1, typeLine = "Artifact")
        val s = mount(DeckTweak.on(deck, "x", card, Tweak.REMOVE))
        settle()
        assertEquals(0, s.all("input.field:not(.count)").size, "a removal offered a card finder")

        val a = mount(adding())
        settle()
        assertEquals(1, a.all("input.field:not(.count)").size, "an add has no card finder")
    }

    // ------------------------------------------------------- the results

    @Test
    fun whatComesBackIsListedOnePerHit() = runTest {
        val s = mount(adding())
        settle()
        s.type("lig")
        s.push(s.state().searched(listOf(bolt), listOf("Lightning Helix", "Lightning Strike")))
        settle()
        assertEquals(3, s.hits().size, "the hits are not one row each")
        assertTrue(s.hitText()[0].contains("Lightning Bolt"), "the first hit is not the card found")
    }

    @Test
    fun whatIsOwnedIsListedAheadOfWhatIsNot() = runTest {
        val s = mount(adding())
        settle()
        s.type("lig")
        s.push(s.state().searched(listOf(bolt), listOf("Lightning Helix")))
        settle()
        val text = s.hitText()
        assertTrue(text[0].startsWith("Lightning Bolt"), "the owned card is not first")
        assertTrue(text[1].startsWith("Lightning Helix"), "the unowned card is not second")
    }

    @Test
    fun whetherACardIsOwnedIsSaidInWordsNotOnlyInColour() = runTest {
        // Red-on-grey is not a difference to everyone looking at this.
        val s = mount(adding())
        settle()
        s.type("lig")
        s.push(s.state().searched(listOf(bolt), listOf("Lightning Helix")))
        settle()
        val rows = s.hits()
        assertTrue(rows[0].textContent!!.contains("4×"), "the owned hit does not say how many")
        assertTrue(rows[0].textContent!!.contains("matt"), "the owned hit does not say whose it is")
        assertTrue(rows[1].textContent!!.contains("not owned"), "the unowned hit only differs by colour")
    }

    @Test
    fun aHitSaysEnoughToTellTwoOfTheSameNameApart() = runTest {
        // The collection groups by owner, so the same card can come
        // back twice. If the two rows read identically, picking is a
        // coin toss.
        val mine = found("Sol Ring", qty = 2, owner = "matt", type = "Artifact", id = 7)
        val hers = found("Sol Ring", qty = 1, owner = "kayla", type = "Artifact", id = 8)
        val s = mount(adding())
        settle()
        s.type("sol")
        s.push(s.state().searched(listOf(mine, hers)))
        settle()
        val text = s.hitText()
        assertEquals(2, text.size, "two owners of one card collapsed into one hit")
        assertTrue(text[0] != text[1], "the two hits read exactly the same")
        text.forEach {
            assertTrue(it.contains("Artifact"), "a hit does not say what the card is")
        }
    }

    @Test
    fun theSameCardIsNotOfferedTwice() = runTest {
        // Scryfall knows every card the collection holds, and two
        // rows of one printing are one card to pick.
        val s = mount(adding())
        settle()
        s.type("lig")
        s.push(s.state().searched(listOf(bolt, bolt.copy(id = 9)), listOf("lightning bolt", "Lightning Helix")))
        settle()
        assertEquals(2, s.hits().size, "one card was offered more than once")
        assertEquals(1, s.hitText().count { it.startsWith("Lightning Bolt") }, "Lightning Bolt is listed twice")
    }

    @Test
    fun nothingFoundSaysSoRatherThanShowingAnEmptyVoid() = runTest {
        val s = mount(adding())
        settle()
        s.type("qqqq")
        s.push(s.state().searched(emptyList(), emptyList()))
        settle()
        assertEquals(0, s.hits().size, "something was listed for a term that found nothing")
        val note = s.root.querySelector(".found-none")?.textContent?.trim().orEmpty()
        assertTrue(note.isNotEmpty(), "a search that found nothing said nothing")
    }

    @Test
    fun andSaysNothingBeforeTheAnswerIsBack() = runTest {
        // "No such card" the instant the second letter lands is a lie
        // that then corrects itself.
        val s = mount(adding())
        settle()
        s.type("qq")
        settle()
        assertEquals(null, s.root.querySelector(".found-none"), "it called the card missing before looking")
    }

    // --------------------------------------------------------- picking

    @Test
    fun aHitIsARealButton() = runTest {
        val s = mount(adding())
        settle()
        s.type("lig")
        s.push(s.state().searched(listOf(bolt)))
        settle()
        assertEquals("BUTTON", s.hits().first().tagName, "a hit cannot be reached from a keyboard")
    }

    @Test
    fun pickingAHitShowsItAndClosesTheList() = runTest {
        val s = mount(adding())
        settle()
        s.type("lig")
        s.push(s.state().searched(listOf(bolt), listOf("Lightning Helix")))
        settle()
        s.hits().first().click()
        settle()
        assertEquals("Lightning Bolt", s.state().pick?.name, "the tap did not pick the card")
        assertEquals(0, s.hits().size, "the list stayed open over the choice")
        assertTrue(s.picked()!!.textContent!!.contains("Lightning Bolt"), "the sheet does not show the card chosen")
        assertEquals("Lightning Bolt", s.box().value, "the box does not hold the card that was picked")
    }

    @Test
    fun aCardNobodyOwnsIsPickableAndSaysItWouldBeBought() = runTest {
        val s = mount(adding())
        settle()
        s.type("lig")
        s.push(s.state().searched(emptyList(), listOf("Lightning Helix")))
        settle()
        s.hits().first().click()
        settle()
        assertEquals("Lightning Helix", s.state().pick?.name, "an unowned card could not be picked")
        assertTrue(
            s.picked()!!.textContent!!.contains("would be bought"),
            "the sheet did not warn that the card has to be bought",
        )
        assertTrue(s.state().ready, "an unowned card is not good enough to preview")
    }

    @Test
    fun pickingADifferentCardAfterwardsReplacesTheFirst() = runTest {
        val s = mount(adding())
        settle()
        s.type("lig")
        s.push(s.state().searched(listOf(bolt)))
        settle()
        s.hits().first().click()
        settle()

        s.type("sol")
        s.push(s.state().searched(listOf(found("Sol Ring", 1, "matt", "Artifact", 5))))
        settle()
        assertEquals(1, s.hits().size, "the second search had nowhere to show itself")
        s.hits().first().click()
        settle()
        assertEquals("Sol Ring", s.state().pick?.name, "the second pick did not take")
        assertEquals(1, s.all(".tweak-pick").size, "both picks are on screen at once")
        assertTrue(s.picked()!!.textContent!!.contains("Sol Ring"))
    }

    @Test
    fun clearingTheBoxAfterAPickClearsThePick() = runTest {
        val s = mount(adding())
        settle()
        s.type("lig")
        s.push(s.state().searched(listOf(bolt)))
        settle()
        s.hits().first().click()
        settle()
        assertTrue(s.state().pick != null)

        s.type("")
        settle()
        assertEquals(null, s.state().pick, "the pick survived the box being emptied")
        assertEquals(null, s.picked(), "the chosen card is still on screen")
        assertEquals(0, s.hits().size, "an empty box is listing hits")
        assertTrue(s.button("Preview →")!!.disabled, "an empty finder can still be previewed")
    }

    @Test
    fun backingOffBelowTheMinimumClearsTheStaleHits() = runTest {
        // Hits for "lig" are not answers to "l". Leaving them up means
        // tapping a card the box no longer names.
        val s = mount(adding())
        settle()
        s.type("lig")
        s.push(s.state().searched(listOf(bolt), listOf("Lightning Helix")))
        settle()
        assertEquals(2, s.hits().size)

        s.type("l")
        settle()
        assertEquals(0, s.hits().size, "hits from a longer term stayed up")
    }

    // ---------------------------------------------------------- on a phone

    @Test
    fun everyHitIsAThumbSizedTarget() = runTest {
        val s = mount(adding())
        settle()
        s.type("lig")
        s.push(s.state().searched(listOf(bolt), listOf("Lightning Helix", "Lightning Strike")))
        settle()
        if (!Stylesheet.applied()) return@runTest
        s.hits().forEach {
            assertTrue(
                it.getBoundingClientRect().height >= 44.0,
                "a hit is ${it.getBoundingClientRect().height}px tall, under the 44px a thumb needs",
            )
        }
    }

    @Test
    fun aHitDoesNotRunOffA400pxPhone() = runTest {
        val long = found(
            "Hanweir, the Writhing Township",
            qty = 1,
            owner = "matt",
            type = "Legendary Creature — Eldrazi Horror Mutant",
            id = 11,
        )
        val s = mount(adding())
        settle()
        s.type("han")
        s.push(s.state().searched(listOf(long), listOf("Hanweir Battlements")))
        settle()
        if (!Stylesheet.applied()) return@runTest
        s.phoneWide()
        settle()
        s.hits().forEach {
            assertTrue(
                it.scrollWidth <= it.clientWidth + 1,
                "a hit is ${it.scrollWidth}px wide inside ${it.clientWidth}px",
            )
        }
        val list = s.root.querySelector(".found") as HTMLElement
        assertTrue(list.scrollWidth <= list.clientWidth + 1, "the hit list scrolls sideways on a phone")
    }

    @Test
    fun aHitCarriesNoHoverOnATouchScreen() = runTest {
        // A tap leaves :hover stuck on the row that was tapped, so the
        // list reads as though something is still selected.
        val top = Stylesheet.topLevelRules()
        if (top.isEmpty()) return@runTest
        val stuck = top.filter { ":hover" in it.substringBefore("{") && "found-row" in it }
        assertEquals(emptyList(), stuck, "the hit row hovers where there is no pointer")
    }

    @Test
    fun theBoxSaysWhatItIsFor() = runTest {
        val add = mount(adding())
        settle()
        assertEquals("Card name", add.box().placeholder, "the add box does not say what to type")

        val card = DeckCard("Sol Ring", qty = 1, role = null, owned = 1, typeLine = "Artifact")
        val swap = mount(DeckTweak.on(deck, "x", card, Tweak.SWAP))
        settle()
        assertEquals("Swap in…", swap.box().placeholder, "the swap box does not say what to type")
        assertFalse(swap.state().ready, "a swap with no card named is ready to preview")
    }
}
