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

/**
 * Searching the Game Changers by name.
 *
 * Matt: "add a name search to the game changers page". A card is found
 * when every word typed is in its name, in any capitals and with or
 * without its apostrophes. The page keeps a deck list's sections, with
 * only the ones that still have a card in them, and the carousel runs
 * over what is found, so its next is still the next row down.
 */
class GameChangerSearchTest {

    private val onThePage = AppState().navigate(Route.parse("#/guide/${Guide.GAME_CHANGERS}"))

    private fun names(s: AppState) = s.gameChangers.flatMap { (_, cards) -> cards.map { it.name } }

    @Test
    fun nothingTypedIsTheWholeList() {
        assertEquals(GameChangers.byType, onThePage.gameChangers, "with nothing typed the page is not every game changer")
        assertEquals(GameChangers.byType, onThePage.searchGameChangers("   ").gameChangers, "spaces alone filtered the list")
        assertTrue(!onThePage.searchGameChangers("  ").gameChangersSearching, "spaces alone count as a search")
    }

    @Test
    fun aWordFindsEveryCardWithItInItsName() {
        val s = onThePage.searchGameChangers("tutor")
        assertTrue(s.gameChangersSearching, "typing tutor is not a search")
        assertEquals(
            listOf("Enlightened Tutor", "Mystical Tutor", "Vampiric Tutor", "Worldly Tutor", "Demonic Tutor"),
            names(s),
            "the tutors are not what the search found, in the page's order",
        )
        assertEquals(listOf("Instants", "Sorceries"), s.gameChangers.map { it.first.title }, "a section with no tutor in it is still on the page")
    }

    @Test
    fun everyWordMustBeInTheNameInAnyCapitalsAndApostrophesDoNotMatter() {
        assertEquals(listOf("Vampiric Tutor"), names(onThePage.searchGameChangers("TUTOR vamp")))
        assertEquals(listOf("Lion's Eye Diamond"), names(onThePage.searchGameChangers("lions eye")), "an apostrophe left out finds nothing")
        assertEquals(listOf("Gaea's Cradle"), names(onThePage.searchGameChangers("gaea’s")), "a curly apostrophe finds nothing")
        assertEquals(listOf("Tergrid, God of Fright // Tergrid's Lantern"), names(onThePage.searchGameChangers("lantern")), "the back face is not searched")
    }

    @Test
    fun aNameNoGameChangerHasFindsNothingAndSaysSo() {
        val s = onThePage.searchGameChangers("sol ring")
        assertEquals(emptyList(), s.gameChangers, "Sol Ring found something")
        assertEquals("No game changer is called \"sol ring\".", GameChangers.none(s.gameChangerSearch))
    }

    @Test
    fun theWordsAboveTheListSayHowManyWereFound() {
        assertEquals("1 of 53 cards", GameChangers.count(1))
        assertEquals("5 of 53 cards", GameChangers.count(5))
    }

    @Test
    fun theCarouselRunsOverWhatWasFound() {
        val s = onThePage.searchGameChangers("tutor")
        val worldly = s.gameChangers.flatMap { it.second }.first { it.name == "Worldly Tutor" }
        val peeked = s.peekGameChanger(worldly)
        assertEquals("Worldly Tutor", peeked.peeked?.title, "the carousel is not on the card that was tapped")
        assertEquals("4 of 5", peeked.peekPlace, "the carousel is over every game changer, not the ones found")
        assertEquals("Demonic Tutor", peeked.peekTo(4).peeked?.title, "the next card is not the next row down")
    }
}
