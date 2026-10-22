package org.mattshoe.mtg.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Which cards a carousel and a deck list mark as a game changer.
 *
 * Matt: "i want a clear indicator in carousels and deck lists for any
 * game changer cards". Both shells read the answer from here.
 */
class GameChangerMarkTest {

    private fun row(name: String) = DeckCard(name = name, qty = 1, role = null, owned = 1, nameNorm = name.lowercase())

    private fun deckOf(vararg names: String): AppState = AppState()
        .navigate(Route(View.DECKS, "alela"))
        .let {
            it.copy(
                decks = it.decks
                    .loaded(listOf(Deck("alela", "Alela", "matt0001", null, "UB", 3, null)))
                    .opened("alela", names.map(::row)),
            )
        }

    @Test
    fun aGameChangerIsKnownByItsNameInAnyCapitals() {
        assertTrue(GameChangers.isOne("Rhystic Study"), "Rhystic Study is not marked")
        assertTrue(GameChangers.isOne("rhystic study"), "the mark depends on capitals")
        assertFalse(GameChangers.isOne("Sol Ring"), "Sol Ring is marked and is not a game changer")
        assertFalse(GameChangers.isOne(""), "a blank name is marked")
    }

    @Test
    fun aTwoFacedGameChangerIsKnownByItsFrontFaceOrBoth() {
        assertTrue(GameChangers.isOne("Tergrid, God of Fright"), "Tergrid by her front face is not marked")
        assertTrue(GameChangers.isOne("Tergrid, God of Fright // Tergrid's Lantern"), "Tergrid by both faces is not marked")
    }

    @Test
    fun aDeckRowSaysWhetherItIsAGameChanger() {
        assertTrue(row("Cyclonic Rift").gameChanger, "a Cyclonic Rift row is not marked")
        assertFalse(row("Cultivate").gameChanger, "a Cultivate row is marked")
    }

    @Test
    fun theDeckCarouselMarksAGameChangerAndNothingElse() {
        val state = deckOf("Cultivate", "Demonic Tutor")
        val run = state.peekAt(0).peekRun
        assertEquals(listOf(false, true), run.map { it.gameChanger }, "the carousel marks the wrong cards: ${run.map { it.title }}")
    }

    @Test
    fun theGameChangersOwnCarouselMarksEveryCard() {
        val state = AppState().peekGameChanger(GameChangers.pageOrder.first())
        assertNotNull(state.peeked, "the carousel did not open")
        assertTrue(state.peekRun.all { it.gameChanger }, "a game changer on its own page is not marked")
    }

    @Test
    fun theMarkReadsGameChanger() = assertEquals("Game Changer", GameChangers.MARK)
}
