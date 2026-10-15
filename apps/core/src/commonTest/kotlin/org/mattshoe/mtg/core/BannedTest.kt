package org.mattshoe.mtg.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Guide's banned cards: every format's ban list, one after another.
 *
 * Matt: "add a guide section below game Changers for banned cards.
 * start with Commander at the top, then standard, then whatever order
 * makes sense".
 */
class BannedTest {

    private val onThePage = AppState().navigate(Route.parse("#/guide/${Guide.BANNED}"))

    private fun list(format: String): BanList =
        Banned.lists.firstOrNull { it.format == format } ?: error("there is no $format ban list: ${Banned.lists.map { it.format }}")

    @Test
    fun theBannedTileComesRightAfterGameChangers() {
        val slugs = Guide.tiles.map { it.slug }
        val at = slugs.indexOf(Guide.BANNED)
        assertTrue(at >= 0, "the Guide has no banned cards tile: $slugs")
        assertEquals(slugs.indexOf(Guide.GAME_CHANGERS) + 1, at, "the banned cards tile is not right below Game Changers: $slugs")
        val tile = Guide.tiles[at]
        assertEquals("Banned cards", tile.title)
        assertTrue(tile.blurb.isNotBlank(), "the banned cards tile says nothing")
        assertEquals(tile, onThePage.guideTile, "#/guide/${Guide.BANNED} does not land on the tile")
        assertEquals("Banned cards", onThePage.title)
    }

    @Test
    fun commanderComesFirstThenStandardThenTheRestFromNewestCardsToOldest() {
        assertEquals(
            listOf("Commander", "Standard", "Pioneer", "Modern", "Legacy", "Vintage", "Pauper"),
            Banned.lists.map { it.format },
            "the formats are not Commander, then Standard, then the rest",
        )
        Banned.lists.forEach { assertTrue(it.about.isNotBlank(), "${it.format} has no line saying what it is") }
    }

    @Test
    fun eachListIsTheRealBanListAlphabeticallyWithArtOnEveryRow() {
        val commander = list("Commander").cards.map { it.name }
        listOf("Black Lotus", "Mana Crypt", "Dockside Extortionist", "Jeweled Lotus", "Nadu, Winged Wisdom", "Primeval Titan").forEach {
            assertTrue(it in commander, "$it is missing from Commander's ban list: $commander")
        }
        assertTrue("Sol Ring" !in commander, "Sol Ring is legal in Commander and is on its ban list")
        assertTrue("Heartfire Hero" in list("Standard").cards.map { it.name }, "Heartfire Hero is missing from Standard's")
        assertTrue("Fury" in list("Modern").cards.map { it.name }, "Fury is missing from Modern's")
        val uuid = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        Banned.lists.forEach { l ->
            val names = l.cards.map { it.name }
            assertTrue(names.isNotEmpty(), "${l.format}'s ban list is empty")
            assertEquals(names.size, names.toSet().size, "a card is listed twice in ${l.format}: $names")
            assertEquals(names.sortedBy { it.lowercase() }, names, "${l.format} is not alphabetical")
            l.cards.forEach { c ->
                assertTrue(c.scryfallId.orEmpty().matches(uuid), "${c.name} has no printing to draw: ${c.scryfallId}")
                assertNotNull(c.art, "${c.name} has no art for its row")
                assertTrue(!c.typeLine.isNullOrBlank(), "${c.name} has no type line")
            }
        }
        assertEquals(Banned.lists.flatMap { it.cards }, Banned.pageOrder)
    }

    @Test
    fun tappingACardOpensTheCarouselAtThatRowEvenWhenAnotherFormatBansItToo() {
        // Black Lotus is on Commander's list and Legacy's both. Tapped in
        // Legacy, the carousel must be on Legacy's row, not Commander's.
        val legacy = list("Legacy")
        val lotus = legacy.cards.indexOfFirst { it.name == "Black Lotus" }
        assertTrue(lotus >= 0, "Black Lotus is not on Legacy's list")
        assertTrue(list("Commander").cards.any { it.name == "Black Lotus" }, "Black Lotus is not on Commander's list, so this proves nothing")
        val row = Banned.lists.takeWhile { it !== legacy }.sumOf { it.cards.size } + lotus

        val s = onThePage.peekBanned(legacy, legacy.cards[lotus])
        assertTrue(Overlay.CARD_PEEK in s.overlays, "tapping Black Lotus opened no carousel")
        assertEquals("Black Lotus", s.peeked?.title)
        assertEquals("${row + 1} of ${Banned.pageOrder.size}", s.peekPlace, "the carousel opened on another format's Black Lotus")
        assertEquals(Banned.pageOrder[row + 1].name, s.peekTo(row + 1).peeked?.title, "a swipe does not reach the next row down")
    }

    @Test
    fun theCarouselNamesTheCardAndClaimsNoCount() {
        val commander = list("Commander")
        val s = onThePage.peekBanned(commander, commander.cards.first { it.name == "Mana Crypt" })
        val c = assertNotNull(s.peeked)
        assertTrue(c.tags.none { "in deck" in it.text || "owned" in it.text }, "a banned card claims a count: ${c.tags}")
        assertNull(c.inDeck, "a banned card has deck buttons")
        assertNull(s.peekStepper, "a banned card has a count stepper")
        assertEquals(PeekFact.Words("Artifact"), c.facts.first())
    }

    @Test
    fun aDeckLeftLoadedIsNotWhatTheBannedCarouselShows() {
        val deck = listOf(DeckCard("Sol Ring", 1, null, 1, "sol ring", "Artifact", "11111111-2222-3333-4444-555555555555"))
        val commander = list("Commander")
        val s = onThePage.copy(decks = onThePage.decks.opened("abcd1234", deck))
            .peekBanned(commander, commander.cards.first { it.name == "Black Lotus" })
        assertEquals("Black Lotus", s.peeked?.title, "the carousel is over the deck left loaded, not the ban lists")
        assertEquals(Banned.pageOrder.size, s.peekRun.size)
    }
}
