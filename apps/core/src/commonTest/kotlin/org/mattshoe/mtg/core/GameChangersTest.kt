package org.mattshoe.mtg.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Guide's Game Changers: Commander's list of the cards that
 * lift a deck's bracket.
 *
 * Matt: "add a guide section for game changers. it should be a list
 * of cards just like a deck page. tapping any of them should pop a
 * carousel like any other cards".
 */
class GameChangersTest {

    private fun card(name: String): DeckCard =
        GameChangers.pageOrder.firstOrNull { it.name == name }
            ?: error("$name is not a game changer: ${GameChangers.pageOrder.map { it.name }}")

    private val onThePage = AppState().navigate(Route.parse("#/guide/${Guide.GAME_CHANGERS}"))

    @Test
    fun theGuideHasAGameChangersTile() {
        val tile = assertNotNull(Guide.tile(Guide.GAME_CHANGERS), "the Guide has no Game Changers tile: ${Guide.tiles.map { it.slug }}")
        assertEquals("Game Changers", tile.title)
        assertTrue(tile.blurb.isNotBlank(), "the Game Changers tile says nothing")
        assertEquals(tile, onThePage.guideTile, "#/guide/${Guide.GAME_CHANGERS} does not land on the tile")
        assertEquals("Game Changers", onThePage.title)
    }

    @Test
    fun theListIsEveryGameChangerOnceEachWithItsArt() {
        val names = GameChangers.pageOrder.map { it.name }
        assertEquals(53, names.size, "the list is not the 53 game changers: $names")
        assertEquals(names.size, names.toSet().size, "a card is listed twice: $names")
        listOf("Rhystic Study", "The One Ring", "Cyclonic Rift", "Demonic Tutor", "Gaea's Cradle").forEach {
            assertTrue(it in names, "$it is missing from the game changers")
        }
        val uuid = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        GameChangers.pageOrder.forEach { c ->
            assertTrue(c.scryfallId.orEmpty().matches(uuid), "${c.name} has no printing to draw: ${c.scryfallId}")
            assertNotNull(c.art, "${c.name} has no art for its row")
            assertTrue(!c.typeLine.isNullOrBlank(), "${c.name} has no type line")
            assertEquals(c.name.lowercase(), c.nameNorm, "${c.name} cannot be opened by its name")
        }
    }

    @Test
    fun itIsReadLikeADeckByTypeThenAlphabetically() {
        assertEquals(
            listOf("Creatures", "Planeswalkers", "Instants", "Sorceries", "Artifacts", "Enchantments", "Lands"),
            GameChangers.byType.map { it.first.title },
            "the sections are not a deck list's, in a deck list's order",
        )
        GameChangers.byType.forEach { (group, cards) ->
            assertEquals(cards.map { it.name }.sortedBy { it.lowercase() }, cards.map { it.name }, "${group.title} is not alphabetical")
            cards.forEach { assertEquals(group, it.group, "${it.name} is filed under ${group.title}") }
        }
        assertEquals("Braids, Cabal Minion", GameChangers.pageOrder.first().name)
        assertEquals(GameChangers.byType.flatMap { it.second }, GameChangers.pageOrder)
    }

    @Test
    fun tappingACardOpensTheCarouselOverTheGameChangersAtThatCard() {
        val study = card("Rhystic Study")
        val at = GameChangers.pageOrder.indexOf(study)
        val s = onThePage.peekGameChanger(study)
        assertTrue(Overlay.CARD_PEEK in s.overlays, "tapping Rhystic Study opened no carousel")
        assertEquals("Rhystic Study", s.peeked?.title, "the carousel is not on the card that was tapped")
        assertEquals("${at + 1} of 53", s.peekPlace)
        assertEquals(study.scryfallId, s.peeked?.scryfallId, "the carousel draws a different printing from the row")

        val next = s.peekTo(at + 1)
        assertEquals(GameChangers.pageOrder[at + 1].name, next.peeked?.title, "a swipe does not reach the next card down the page")
        assertEquals("53 of 53", s.peekTo(99).peekPlace, "the carousel runs past the end of the list")
    }

    @Test
    fun theCarouselSaysHowMuchItIsPlayedAndNothingAboutADeckOrACollection() {
        val s = onThePage.peekGameChanger(card("Rhystic Study"))
        val c = assertNotNull(s.peeked)
        val tags = c.tags.map { it.text }
        assertTrue("EDHREC #46 of all cards" in tags, "the carousel does not say how much it is played: $tags")
        assertTrue(tags.none { "in deck" in it || "owned" in it || "free" in it }, "a guide card claims a count: $tags")
        assertNull(c.inDeck, "a guide card has deck buttons")
        assertNull(s.peekStepper, "a guide card has a count stepper")
        assertEquals(
            listOf<PeekFact>(PeekFact.Words("Enchantment"), PeekFact.Identity(listOf("U")), PeekFact.Words("Rare")),
            c.facts,
        )
    }

    @Test
    fun aDeckLeftLoadedIsNotWhatTheGameChangersCarouselShows() {
        val deck = listOf(DeckCard("Sol Ring", 1, null, 1, "sol ring", "Artifact", "11111111-2222-3333-4444-555555555555"))
        val s = onThePage.copy(decks = onThePage.decks.opened("abcd1234", deck)).peekGameChanger(card("Mana Vault"))
        assertEquals("Mana Vault", s.peeked?.title, "the carousel is over the deck left loaded, not the game changers")
        assertEquals(53, s.peekRun.size)
    }
}
