package org.mattshoe.mtg.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The state behind the card finder.
 *
 * `DeckTweakTest` covers what a tweak does to a deck list. This
 * covers the half a finger touches: what is worth asking the server,
 * what stays on screen between keystrokes, and what a pick throws
 * away.
 */
class TweakFinderCoreTest {

    private val deck = Deck("alela", "Alela", "matt", "Alela, Artful Provocateur", "UW", 3, null)

    private fun found(name: String, qty: Int = 0, owner: String = "", id: Long = 1) =
        Found(id, name, "sf-$id", "Instant", qty, owner)

    private val bolt = found("Lightning Bolt", qty = 4, owner = "matt", id = 1)

    private fun adding() = DeckTweak.add(deck, "Alela, Artful Provocateur")

    private fun searched(vararg names: String) =
        adding().copy(term = "lig").searched(names.map { found(it, 1, "matt") })

    @Test
    fun twoLettersIsTheLeastWorthAsking() {
        assertEquals(2, DeckTweak.MIN_TERM, "the minimum term changed under the finder")
    }

    @Test
    fun aTermTooShortToSearchIsNotLeftLookingForever() {
        assertFalse(adding().typed("l").searching, "one letter put the finder into a search")
        assertTrue(adding().typed("li").searching, "the minimum term did not start a search")
        assertFalse(adding().typed("  ").searching, "whitespace started a search")
    }

    @Test
    fun theAnswerComingBackEndsTheSearch() {
        val t = adding().typed("lig").searched(listOf(bolt))
        assertFalse(t.searching, "the finder is still looking after the answer arrived")
        assertEquals(listOf("Lightning Bolt"), t.found.map { it.name })
    }

    @Test
    fun backingOffBelowTheMinimumThrowsTheHitsAway() {
        // They were answers to a longer word.
        val t = searched("Lightning Bolt", "Lightning Helix")
        assertEquals(2, t.found.size)
        assertEquals(emptyList(), t.typed("l").found, "hits for a longer term survived")
        assertEquals(emptyList(), t.typed("").found, "emptying the box left the hits up")
    }

    @Test
    fun butKeepsThemWhileTheTermIsStillLongEnough() {
        // Blanking the list on every keystroke flickers the whole
        // sheet; the next answer replaces them.
        val t = searched("Lightning Bolt")
        assertEquals(1, t.typed("ligh").found.size, "the list blanked mid-word")
    }

    @Test
    fun theSameCardFromTheSamePersonIsOneHit() {
        val t = adding().searched(listOf(bolt, bolt.copy(id = 2)))
        assertEquals(1, t.found.size, "one card was offered twice")
    }

    @Test
    fun twoPeopleOwningACardIsStillOneCard() {
        // The finder names a card, any card in the world. Where the
        // copy comes from is the plan's question, not the list's.
        val t = adding().searched(listOf(bolt, bolt.copy(id = 2, owner = "kayla", qty = 1)))
        assertEquals(listOf("Lightning Bolt"), t.found.map { it.name }, "one card was offered once per owner")
        assertEquals(listOf(""), t.found.map { it.owner }, "the finder is still naming an owner")
    }

    @Test
    fun aNameThatCameBackTwiceIsStillOneHit() {
        val t = adding().searched(emptyList(), listOf("Sol Ring", "sol ring", " Sol Ring "))
        assertEquals(1, t.found.size, "one name was offered more than once")
        assertEquals("Sol Ring", t.found.single().name)
    }

    @Test
    fun anEmptyNameIsNotACard() {
        val t = adding().searched(emptyList(), listOf("", "   ", "Sol Ring"))
        assertEquals(listOf("Sol Ring"), t.found.map { it.name }, "a blank name was offered as a card")
    }

    @Test
    fun pickingClosesTheListAndFillsTheBox() {
        val t = searched("Lightning Bolt", "Lightning Helix")
        val p = t.picked(t.found.first())
        assertEquals("Lightning Bolt", p.pick?.name)
        assertEquals("Lightning Bolt", p.term, "the box does not hold the card that was picked")
        assertEquals(emptyList(), p.found, "the list stayed open over the choice")
        assertFalse(p.searching, "the finder is still looking after a card was picked")
    }

    @Test
    fun pickingAgainReplacesTheFirstPick() {
        val t = searched("Lightning Bolt", "Lightning Helix")
        val p = t.picked(t.found[0]).let { it.copy(found = t.found) }.picked(t.found[1])
        assertEquals("Lightning Helix", p.pick?.name, "the second pick did not take")
    }

    @Test
    fun typingAgainLetsGoOfTheCardThatWasPicked() {
        val t = searched("Lightning Bolt").let { it.picked(it.found.first()) }
        assertEquals(null, t.typed("sol").pick, "the old card survived a new term")
        assertFalse(t.typed("sol").ready, "a stale pick is still ready to preview")
    }

    @Test
    fun aCardNobodyOwnsIsStillPickable() {
        val t = adding().searched(emptyList(), listOf("Black Lotus"))
        val p = t.picked(t.found.single())
        assertEquals(0, p.pick?.qty, "an unowned card came back owned")
        assertTrue(p.ready, "an unowned card cannot be previewed")
    }
}
