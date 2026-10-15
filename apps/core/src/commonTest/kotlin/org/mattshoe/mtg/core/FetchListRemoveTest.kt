package org.mattshoe.mtg.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Matt: "i also want the ability to remove a card from the fetch list.
 * make sure doing so actually edits the deck list text as well".
 *
 * So a removed card is gone from the filing, from the missing list,
 * from the ticks, and from the text Edit opens, which is what Find
 * reads the next time.
 */
class FetchListRemoveTest {

    private val owned = listOf(
        OwnedPrinting("counterspell", "Counterspell", null, "Instant", "U", "common", 2, identity = "U"),
        OwnedPrinting("arcane signet", "Arcane Signet", null, "Artifact", null, "common", 1),
        OwnedPrinting(
            "brazen borrower // petty theft", "Brazen Borrower // Petty Theft", "Brazen Borrower",
            "Creature", "U", "mythic", 1, identity = "U",
        ),
    )

    private fun found(list: String) = FetchListState(list = list).found(FetchList.build(DeckList.entries(list), owned))

    private fun card(f: FetchListState, name: String): FetchCard =
        f.result!!.sections.flatMap { it.cards }.first { it.name == name }

    private fun names(f: FetchListState) = f.result!!.sections.flatMap { it.cards }.map { it.name }

    @Test
    fun aRemovedCardIsGoneFromTheListTextAndTheFiling() {
        val f = found("1 Counterspell\n1 Arcane Signet")
        val gone = f.remove(card(f, "Counterspell"))
        assertEquals("1 Arcane Signet", gone.list, "the list text still names Counterspell")
        assertEquals(listOf("Arcane Signet"), names(gone), "Counterspell is still filed")
        assertEquals("0 of 1 found", gone.progress)
    }

    @Test
    fun everyLineNamingTheCardGoesAndNothingElseDoes() {
        val list = "Deck\n// counters\n2 Counterspell (MH2) 267\n1 Arcane Signet\n1 counterspell *F*"
        val f = found(list)
        val gone = f.remove(card(f, "Counterspell"))
        assertEquals("Deck\n// counters\n1 Arcane Signet", gone.list)
    }

    @Test
    fun aCardListedByItsFrontFaceIsRemovedByThatLine() {
        val f = found("1 Brazen Borrower\n1 Arcane Signet")
        val gone = f.remove(card(f, "Brazen Borrower // Petty Theft"))
        assertEquals("1 Arcane Signet", gone.list, "the front face's line was left in the list")
        assertEquals(listOf("Arcane Signet"), names(gone))
    }

    @Test
    fun aRemovedTickIsNotCountedAnyMore() {
        val f = found("1 Counterspell\n1 Arcane Signet").tick("Counterspell")
        val gone = f.remove(card(f, "Counterspell"))
        assertFalse(gone.isTicked("Counterspell"), "a card no longer on the list is still ticked")
        assertEquals("0 of 1 found", gone.progress)
    }

    @Test
    fun theLastCardInASectionTakesTheSectionWithIt() {
        val f = found("1 Counterspell\n1 Arcane Signet")
        val gone = f.remove(card(f, "Arcane Signet"))
        assertEquals(listOf("Instants · U · Common"), gone.result!!.sections.map { it.title })
    }

    @Test
    fun aCardNotOwnedCanBeRemovedToo() {
        val f = found("1 Counterspell\n1 Black Lotus")
        val lotus = f.result!!.missing.single()
        val gone = f.remove(lotus)
        assertEquals("1 Counterspell", gone.list)
        assertTrue(gone.result!!.missing.isEmpty(), "Black Lotus is still listed as missing")
    }

    @Test
    fun removingACardYouHaveTooFewOfTakesItOffTheMissingListToo() {
        val f = found("4 Counterspell\n1 Arcane Signet")
        val gone = f.remove(card(f, "Counterspell"))
        assertTrue(gone.result!!.missing.isEmpty(), "the two Counterspells short are still missing")
    }

    @Test
    fun aRemovalOnASavedListIsSaved() {
        val f = found("1 Counterspell\n1 Arcane Signet").save("Alela", "2026-10-14T00:00:00Z")
        val gone = f.remove(card(f, "Counterspell"))
        assertEquals("1 Arcane Signet", gone.saved.single().list, "the saved list still has Counterspell")
        assertEquals(listOf("Arcane Signet"), gone.saved.single().result!!.sections.flatMap { it.cards }.map { it.name })
    }

    @Test
    fun aCsvLosesTheCardsRows() {
        val list = "Count,Name\n2,Counterspell\n1,\"Arcane Signet\""
        val f = found(list)
        val gone = f.remove(card(f, "Counterspell"))
        assertEquals("Count,Name\n1,\"Arcane Signet\"", gone.list)
    }

    @Test
    fun aListSavedBeforeCardsKnewTheirLineIsStillRemovable() {
        // Saved lists from before this change have no key on their cards.
        val f = found("1 Counterspell\n1 Arcane Signet")
        val old = f.copy(result = f.result!!.copy(sections = f.result!!.sections.map { s -> s.copy(cards = s.cards.map { it.copy(key = "") }) }))
        val gone = old.remove(card(old, "Counterspell"))
        assertEquals("1 Arcane Signet", gone.list)
    }
}
