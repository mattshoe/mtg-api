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

    private fun row(
        name: String,
        identity: String?,
        rarity: String?,
        typeLine: String? = "Legendary Creature — Elf Druid",
    ) = CardRow(
        id = 1,
        owner = "matt",
        name = name,
        nameNorm = name.lowercase(),
        face2 = null,
        layout = null,
        scryfallId = "abcdef12-3456",
        manaCost = "{1}",
        cmc = 1.0,
        typeLine = typeLine,
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
                PeekFact.Words("Creature"),
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
                PeekFact.Words("Creature"),
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

    private fun factsOf(r: CardRow) = assertNotNull(inTheLibrary(r).peekRow(r).peeked).facts

    @Test
    fun theTypeIsJustTheCardTypeWithNoSupertypesSubtypesOrSecondTypes() {
        // Matt: "i JUST want creature, artifact, enchantment, sorcery".
        // Not legendary, not the creature types, not "enchantment creature".
        val cases = mapOf(
            "Legendary Creature — Elf Druid" to "Creature",
            "Legendary Enchantment Creature — God" to "Creature",
            "Artifact Creature — Golem" to "Creature",
            "Legendary Artifact — Equipment" to "Artifact",
            "Enchantment — Aura" to "Enchantment",
            "Kindred Sorcery — Elf" to "Sorcery",
            "Instant" to "Instant",
            "Legendary Planeswalker — Jace" to "Planeswalker",
            "Artifact Land" to "Land",
            "Basic Snow Land — Forest" to "Land",
            "Battle — Siege" to "Battle",
            "Creature — Human Wizard // Sorcery — Adventure" to "Creature",
            "Legendary Enchantment — Saga // Legendary Creature — God" to "Enchantment",
        )
        val shown = cases.keys.associateWith { factsOf(row("X", "G", "rare", typeLine = it)).first() }
        assertEquals(cases.mapValues { PeekFact.Words(it.value) }, shown)
    }

    @Test
    fun aColourlessCardStoredWithNoIdentityStillShowsTheColourlessSymbol() {
        // The Worker writes a colourless identity as NULL, not "", so
        // this is what Sol Ring actually looks like when it arrives.
        val r = row("Sol Ring", null, "uncommon", typeLine = "Artifact")
        assertEquals(
            listOf(PeekFact.Words("Artifact"), PeekFact.Identity(listOf("C")), PeekFact.Words("Uncommon")),
            factsOf(r),
        )
    }

    @Test
    fun aColourlessLandShowsTheColourlessSymbol() {
        val r = row("Command Tower", null, "common", typeLine = "Land")
        assertEquals(PeekFact.Identity(listOf("C")), factsOf(r)[1])
    }

    @Test
    fun aBasicLandNobodyOwnsInADeckShowsItsColour() {
        val deck = Deck("alela", "Alela", "matt", null, "UG", 1, null)
        val c = DeckCard(name = "Forest", qty = 10, role = null, owned = 0, nameNorm = "forest")
        val s = AppState()
            .navigate(Route(View.DECKS, "alela"))
            .let { it.copy(decks = it.decks.loaded(listOf(deck)).opened("alela", listOf(c))) }
            .peekAt(0)
        assertEquals(
            listOf(PeekFact.Words("Land"), PeekFact.Identity(listOf("G"))),
            assertNotNull(s.peeked).facts,
        )
    }
}
