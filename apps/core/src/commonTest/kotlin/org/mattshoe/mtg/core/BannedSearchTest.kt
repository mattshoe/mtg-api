package org.mattshoe.mtg.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Searching the banned cards by name, and the carousel saying where a
 * card is banned.
 *
 * Matt: "the banned cards should have a search field and the carousel
 * should show which formats for which s card is banned".
 */
class BannedSearchTest {

    private val onThePage = AppState().navigate(Route.parse("#/guide/${Guide.BANNED}"))

    private fun AppState.shown(): List<Pair<String, List<String>>> = banned.map { l -> l.format to l.cards.map { it.name } }

    @Test
    fun nothingTypedLeavesEveryList() {
        assertEquals(Banned.lists, onThePage.banned)
        assertEquals(Banned.lists, onThePage.searchBanned("   ").banned, "spaces alone filtered the lists")
        assertTrue(!onThePage.searchBanned("  ").bannedSearching, "spaces alone count as a search")
    }

    @Test
    fun aNameLeavesOnlyTheFormatsThatBanItWithOnlyThoseCards() {
        val s = onThePage.searchBanned("black lotus")
        assertTrue(s.bannedSearching)
        assertEquals(
            listOf("Commander" to listOf("Black Lotus"), "Legacy" to listOf("Black Lotus")),
            s.shown(),
            "searching black lotus did not leave Commander's and Legacy's Black Lotus",
        )
        assertEquals("2 of ${Banned.pageOrder.size} cards", Banned.count(s.banned))
    }

    @Test
    fun everyWordHasToBeThereInAnyCapitalsWithOrWithoutApostrophes() {
        val names = { q: String -> onThePage.searchBanned(q).banned.flatMap { it.cards.map { c -> c.name } }.toSet() }
        assertEquals(setOf("Mana Crypt"), names("CRYPT mana"))
        assertTrue("Lion's Eye Diamond" in names("lions eye"), "an apostrophe left out finds nothing: ${names("lions eye")}")
        assertTrue("Lion's Eye Diamond" in names("lion’s eye"), "a curly apostrophe finds nothing: ${names("lion’s eye")}")
    }

    @Test
    fun aNameNothingBansSaysSo() {
        val s = onThePage.searchBanned("sol ring")
        assertTrue(s.banned.isEmpty(), "Sol Ring found a list: ${s.shown()}")
        assertEquals("No banned card is called \"sol ring\".", Banned.none(s.bannedSearch))
    }

    @Test
    fun tappingAFoundCardPopsTheCarouselOverWhatWasFound() {
        val s = onThePage.searchBanned("black lotus")
        val legacy = s.banned.first { it.format == "Legacy" }
        val peeked = s.peekBanned(legacy, legacy.cards.single())
        assertTrue(Overlay.CARD_PEEK in peeked.overlays, "tapping Black Lotus opened no carousel")
        assertEquals("Black Lotus", peeked.peeked?.title)
        assertEquals("2 of 2", peeked.peekPlace, "the carousel is over every banned card, not the ones found, or on Commander's")
    }

    @Test
    fun theCarouselSaysEveryFormatTheCardIsBannedIn() {
        val commander = Banned.lists.first { it.format == "Commander" }
        val c = assertNotNull(onThePage.peekBanned(commander, commander.cards.first { it.name == "Black Lotus" }).peeked)
        val tag = c.tags.firstOrNull { it.text.startsWith("Banned in") } ?: error("the carousel does not say where Black Lotus is banned: ${c.tags}")
        assertEquals("Banned in Commander and Legacy", tag.text)
        assertTrue(tag.bad, "a ban is not drawn as a bad fact")

        val crypt = assertNotNull(onThePage.peekBanned(commander, commander.cards.first { it.name == "Mana Crypt" }).peeked)
        assertEquals(
            listOf("Banned in Commander"),
            crypt.tags.filter { it.text.startsWith("Banned") }.map { it.text },
        )
    }

    @Test
    fun aDeckOrGameChangerCarouselSaysWhereItsCardIsBannedTooAndNothingForALegalCard() {
        val deck = listOf(
            DeckCard("Mana Crypt", 1, null, 1, "mana crypt", "Artifact", "11111111-2222-3333-4444-555555555555"),
            DeckCard("Sol Ring", 1, null, 1, "sol ring", "Artifact", "11111111-2222-3333-4444-555555555556"),
        )
        val s = AppState().copy(decks = AppState().decks.opened("abcd1234", deck))
        val crypt = s.peekCard(deck[0]).peeked
        assertEquals("Mana Crypt", crypt?.title)
        assertTrue(crypt!!.tags.any { it.text == "Banned in Commander" }, "a deck's Mana Crypt does not say it is banned: ${crypt.tags}")
        val ring = s.peekCard(deck[1]).peeked
        assertEquals("Sol Ring", ring?.title)
        assertTrue(ring!!.tags.none { it.text.startsWith("Banned") }, "Sol Ring claims a ban: ${ring.tags}")

        val study = AppState().peekGameChanger(GameChangers.cards.first { it.name == "Rhystic Study" }).peeked
        assertTrue(study!!.tags.none { it.text.startsWith("Banned") }, "Rhystic Study is on no ban list and claims a ban: ${study.tags}")
        val vault = AppState().peekGameChanger(GameChangers.cards.first { it.name == "Mana Vault" }).peeked
        assertTrue(vault!!.tags.any { it.text.startsWith("Banned in") && "Legacy" in it.text }, "a game changer's Mana Vault does not say Legacy bans it: ${vault.tags}")
    }

    @Test
    fun aTwoFacedCardIsFoundByItsFrontFaceAlone() {
        val full = "Erayo, Soratami Ascendant // Erayo's Essence"
        val formats = Banned.lists.filter { l -> l.cards.any { it.name == full } }.map { it.format }
        assertTrue(formats.isNotEmpty(), "Erayo is on no list, so this proves nothing")
        assertEquals(formats, Banned.formatsOf(full))
        assertEquals(formats, Banned.formatsOf("Erayo, Soratami Ascendant"), "the front face alone is not found")
    }
}
