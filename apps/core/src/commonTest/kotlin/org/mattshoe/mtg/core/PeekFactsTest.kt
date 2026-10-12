package org.mattshoe.mtg.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * The line under the carousel's name.
 *
 * Matt: "underneath the name text, i want to see 'Type • <<color
 * identity>> • Rarity' where color identity is just the mana symbols,
 * not the words".
 *
 * Worded here so both shells draw the same three parts in the same
 * order; they only decide what a symbol looks like.
 */
class PeekFactsTest {

    private fun row(name: String, identity: String?, rarity: String?) = CardRow(
        id = 1,
        owner = "matt",
        name = name,
        nameNorm = name.lowercase(),
        face2 = null,
        layout = null,
        scryfallId = "abcdef12-3456",
        manaCost = "{1}",
        cmc = 1.0,
        typeLine = "Legendary Creature — Elf Druid",
        colorIdentity = identity,
        rarity = rarity,
        setCode = "m3c",
        setName = "Modern Horizons 3 Commander",
        collectorNumber = "409",
        edhrecRank = null,
        releasedAt = null,
        finish = "nonfoil",
        power = null,
        toughness = null,
        artist = null,
        qty = 1,
        printings = 1,
        free = 1,
        price = 1.75,
        value = 1.75,
    )

    private fun inTheLibrary(vararg rows: CardRow) = AppState()
        .navigate(View.LIBRARY)
        .let { it.copy(library = it.library.loaded(rows.toList(), rows.size)) }

    @Test
    fun aLibraryCardReadsTypeThenIdentitySymbolsThenRarity() {
        val r = row("Tana", "RG", "mythic")
        val card = assertNotNull(inTheLibrary(r).peekRow(r).peeked)
        assertEquals(
            listOf(
                PeekFact.Words("Legendary Creature — Elf Druid"),
                PeekFact.Identity(listOf("R", "G")),
                PeekFact.Words("Mythic"),
            ),
            card.facts,
        )
    }

    @Test
    fun aColourlessCardShowsTheColourlessSymbolRatherThanNothing() {
        val r = row("Sol Ring", "", "uncommon")
        val card = assertNotNull(inTheLibrary(r).peekRow(r).peeked)
        assertEquals(PeekFact.Identity(listOf("C")), card.facts[1])
    }

    @Test
    fun aDeckCardReadsTheSameThreeParts() {
        val deck = Deck("alela", "Alela", "matt", null, "UW", 1, null)
        val c = DeckCard(
            name = "Alela, Cunning Conqueror",
            qty = 1,
            role = null,
            owned = 1,
            nameNorm = "alela, cunning conqueror",
            typeLine = "Legendary Creature — Faerie Warlock",
            scryfallId = "abcdef12-3456",
            colorIdentity = "UB",
            rarity = "rare",
        )
        val s = AppState()
            .navigate(Route(View.DECKS, "alela"))
            .let { it.copy(decks = it.decks.loaded(listOf(deck)).opened("alela", listOf(c))) }
            .peekAt(0)
        assertEquals(
            listOf(
                PeekFact.Words("Legendary Creature — Faerie Warlock"),
                PeekFact.Identity(listOf("U", "B")),
                PeekFact.Words("Rare"),
            ),
            assertNotNull(s.peeked).facts,
        )
    }

    @Test
    fun aCardNobodyOwnsSaysNothingRatherThanBlanks() {
        // A card the deck wants that no printing matches: no type, no
        // identity, no rarity. An empty line, not " •  • ".
        val deck = Deck("alela", "Alela", "matt", null, "UW", 1, null)
        val c = DeckCard(name = "Rhystic Study", qty = 1, role = null, owned = 0, nameNorm = "rhystic study")
        val s = AppState()
            .navigate(Route(View.DECKS, "alela"))
            .let { it.copy(decks = it.decks.loaded(listOf(deck)).opened("alela", listOf(c))) }
            .peekAt(0)
        assertEquals(emptyList(), assertNotNull(s.peeked).facts)
    }
}
