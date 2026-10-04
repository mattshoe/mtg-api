package org.mattshoe.mtg.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What a deck is made of, worked out from its cards. */
class DeckStatsTest {

    private fun card(
        name: String,
        type: String? = "Creature — Human",
        cost: String? = "{1}{W}",
        cmc: Double? = 2.0,
        qty: Int = 1,
        owned: Int = 1,
        produces: String? = null,
        text: String? = null,
        rarity: String? = "common",
        price: Double? = 1.0,
        role: String? = null,
        ci: String? = "W",
    ) = DeckCard(
        name, qty, role, owned,
        nameNorm = name.lowercase(), typeLine = type, manaCost = cost, cmc = cmc,
        producedMana = produces, oracleText = text, colorIdentity = ci, rarity = rarity, price = price,
    )

    // --------------------------------------------------- basic lands

    @Test
    fun aBasicLandIsALandEvenWithNoPrintingBehindIt() {
        // Nobody inventories their basics, so a deck list is full of
        // Plains that match no row in the collection. Counted as
        // unknowns, Feather Storm read as a 22-land deck when it has
        // 37 — every deck's manabase was understated.
        val plains = DeckCard("Plains", 9, null, 0, nameNorm = "plains")
        assertEquals(DeckGroup.LANDS, plains.group)
        assertEquals("W", plains.knownProducedMana)
        assertEquals(0.0, plains.knownManaValue)
        assertTrue(plains.isBasicLand)
    }

    @Test
    fun snowCoveredAndWastesCountToo() {
        assertEquals(DeckGroup.LANDS, DeckCard("Snow-Covered Plains", 1, null, 0, nameNorm = "snow-covered plains").group)
        assertEquals("W", Basic.of("snow-covered plains")?.produces)
        assertEquals("C", Basic.of("wastes")?.produces)
        assertEquals(11, Basic.names.size, "five colours, their snow versions, and Wastes")
    }

    @Test
    fun aRealPrintingStillWins() {
        // A Plains the collection does know about keeps its own row.
        val known = DeckCard(
            "Plains", 1, null, 1, nameNorm = "plains",
            typeLine = "Basic Land — Plains", producedMana = "W", cmc = 0.0,
        )
        assertEquals("Basic Land — Plains", known.knownTypeLine)
    }

    @Test
    fun aBasicIsNeverSomethingYouAreShortOf() {
        // "18 not owned" on Feather Storm was nine Plains, four
        // Snow-Covered Plains, three Forests and two Mountains, and
        // not one of them is something to go and buy.
        val plains = DeckCard("Plains", 9, null, 0, nameNorm = "plains")
        assertEquals(0, plains.short)
        val real = DeckCard("Mana Crypt", 1, null, 0, nameNorm = "mana crypt", typeLine = "Artifact")
        assertEquals(1, real.short)
        assertEquals(0, DeckAnalysis.of(listOf(plains)).missing)
        assertEquals(1, DeckAnalysis.of(listOf(plains, real)).missing)
    }

    @Test
    fun aBasicLandCountsAsASourceOfItsColour() {
        val s = DeckAnalysis.of(
            listOf(
                DeckCard("Plains", 9, null, 0, nameNorm = "plains"),
                DeckCard("Mountain", 2, null, 0, nameNorm = "mountain"),
            ),
        )
        assertEquals(9, s.sources.first { it.label == "White" }.value)
        assertEquals(2, s.sources.first { it.label == "Red" }.value)
        assertEquals(11, s.lands)
        assertEquals(0, s.unknown, "a basic is not an unknown card")
    }

    // ------------------------------------------------------ mana costs

    @Test
    fun aCostIsReadSymbolBySymbol() {
        assertEquals(listOf("2", "W", "U"), ManaCost.symbols("{2}{W}{U}"))
        assertEquals(emptyList(), ManaCost.symbols(null))
        assertEquals(emptyList(), ManaCost.symbols(""))
    }

    @Test
    fun onlyColouredPipsCount() {
        assertEquals(listOf(Pip.W, Pip.W), ManaCost.pips("{2}{W}{W}"))
        // Generic, variable and snow are not colour requirements.
        assertEquals(emptyList(), ManaCost.pips("{3}{X}{S}"))
        // Colourless is.
        assertEquals(listOf(Pip.C), ManaCost.pips("{C}"))
    }

    @Test
    fun aHybridCountsForBothHalves() {
        // It can be paid either way, and a deck that can only pay one
        // half is exactly as constrained as one asking for that half.
        assertEquals(listOf(Pip.W, Pip.U), ManaCost.pips("{W/U}"))
        // Phyrexian is the colour or two life, not a second colour.
        assertEquals(listOf(Pip.G), ManaCost.pips("{G/P}"))
        // And the generic half of a two-brid is not a colour either.
        assertEquals(listOf(Pip.R), ManaCost.pips("{2/R}"))
    }

    @Test
    fun manaValueAddsUpTheGenericAndTheRest() {
        assertEquals(4, ManaCost.manaValue("{2}{W}{U}"))
        assertEquals(0, ManaCost.manaValue("{X}"))
        // A two-brid costs two; the other hybrid costs one.
        assertEquals(3, ManaCost.manaValue("{W/U}{2/R}"))
    }

    /**
     * The symbol itself is what tells two costs apart here — "W" vs
     * "U" in the URL — never a colour value, so this stays readable
     * to someone who cannot tell the pips apart by hue.
     */
    @Test
    fun symbolArtIsTheBracesAndSlashesTakenOut() {
        assertEquals("${ManaCost.SYMBOL_BASE}/W.svg", ManaCost.symbolArt("W"))
        assertEquals("${ManaCost.SYMBOL_BASE}/WU.svg", ManaCost.symbolArt("W/U"))
        assertEquals("${ManaCost.SYMBOL_BASE}/2W.svg", ManaCost.symbolArt("2/W"))
    }

    @Test
    fun artPairsEverySymbolWithItsOwnPicture() {
        val art = ManaCost.art("{1}{G}")
        assertEquals(listOf("1", "G"), art.map { it.first })
        assertEquals("${ManaCost.SYMBOL_BASE}/G.svg", art.last().second)
    }

    // ---------------------------------------------------------- curve

    @Test
    fun theCurveLeavesTheLandsOut() {
        // Lands cost nothing. In the curve they are a third of the
        // deck in the nought column, which is the one thing a curve
        // must not say.
        val s = DeckAnalysis.of(
            listOf(
                card("Plains", type = "Basic Land — Plains", cost = null, cmc = 0.0, qty = 10),
                card("Bear", cmc = 2.0, qty = 4),
            ),
        )
        assertEquals(0, s.curve.first { it.label == "0" }.value)
        assertEquals(4, s.curve.first { it.label == "2" }.value)
        assertEquals(10, s.lands)
    }

    @Test
    fun everythingAboveSevenIsOneColumn() {
        val s = DeckAnalysis.of(listOf(card("Big", cmc = 9.0), card("Bigger", cmc = 12.0)))
        assertEquals(2, s.curve.last().value)
        assertEquals("7+", s.curve.last().label)
    }

    @Test
    fun aCardNobodyOwnsIsNotANoughtDrop() {
        // No printing in the collection means no type and no cost. As
        // a nought-drop it flattens the curve and drags the average
        // down — the chart would be lying rather than incomplete.
        val s = DeckAnalysis.of(
            listOf(
                card("Bear", cmc = 2.0),
                card("Mystery", type = null, cost = null, cmc = null, owned = 0),
            ),
        )
        assertEquals(0, s.curve.first { it.label == "0" }.value)
        assertEquals(1, s.unknown)
        assertEquals(2.0, s.averageManaValue, "the unknown card dragged the average down")
        assertEquals(2, s.totalCards, "and it still counts as a card in the deck")
    }

    @Test
    fun theAverageAndMedianAreOverSpellsOnly() {
        val s = DeckAnalysis.of(
            listOf(
                card("Island", type = "Basic Land — Island", cost = null, cmc = 0.0, qty = 20),
                card("One", cmc = 1.0),
                card("Three", cmc = 3.0),
                card("Eight", cmc = 8.0),
            ),
        )
        assertEquals(4.0, s.averageManaValue)
        assertEquals(3.0, s.medianManaValue)
    }

    @Test
    fun theManaValueTextHasNoTrailingPointZero() {
        // `Double.toString()` is not one function: Kotlin/JS drops a
        // whole value's ".0" and Kotlin/JVM never does. This test
        // compiles for both targets (`commonTest`), so it is the two
        // platforms agreeing with each other, not two assertions of
        // the same string in two separate suites.
        assertEquals("2", manaValueText(2.0))
        assertEquals("0", manaValueText(0.0))
        assertEquals("2.5", manaValueText(2.5))
        assertEquals("2.24", manaValueText(2.24))
        assertEquals("3", manaValueText(2.999), "rounds, rather than truncating, past two places")
    }

    @Test
    fun theDeckStatsTextAgreesWithTheNumber() {
        val s = DeckAnalysis.of(
            listOf(
                card("Island", type = "Basic Land — Island", cost = null, cmc = 0.0, qty = 20),
                card("One", cmc = 1.0),
                card("Three", cmc = 3.0),
            ),
        )
        // Both land at exactly 2.0 — the value that read "2" on the
        // website and "2.0" on the phone before `manaValueText`.
        assertEquals("2", s.averageManaValueText)
        assertEquals("2", s.medianManaValueText)
    }

    // --------------------------------------------------------- colour

    @Test
    fun pipsAreCountedPerCopy() {
        // Four copies of a two-white card is eight white pips, not two.
        val s = DeckAnalysis.of(listOf(card("Priest", cost = "{W}{W}", qty = 4)))
        assertEquals(8, s.pips.first { it.label == "White" }.value)
    }

    @Test
    fun sourcesComeFromWhatEachCardCanProduce() {
        val s = DeckAnalysis.of(
            listOf(
                card("Plains", type = "Basic Land", cost = null, cmc = 0.0, qty = 8, produces = "W"),
                card("Tower", type = "Land", cost = null, cmc = 0.0, produces = "WUBRG"),
            ),
        )
        assertEquals(9, s.sources.first { it.label == "White" }.value)
        assertEquals(1, s.sources.first { it.label == "Blue" }.value)
    }

    @Test
    fun aColourAskedForWithNoSourceIsCalledOut() {
        // A splash with nothing to cast it is invisible in either
        // chart on its own.
        val s = DeckAnalysis.of(
            listOf(
                card("Plains", type = "Land", cost = null, cmc = 0.0, produces = "W"),
                card("Counterspell", cost = "{U}{U}", ci = "U"),
            ),
        )
        assertEquals(listOf("Blue"), s.unsupported)
    }

    @Test
    fun aColourWithSourcesAndPipsIsNotCalledOut() {
        val s = DeckAnalysis.of(
            listOf(
                card("Island", type = "Land", cost = null, cmc = 0.0, produces = "U"),
                card("Counterspell", cost = "{U}{U}", ci = "U"),
            ),
        )
        assertEquals(emptyList(), s.unsupported)
    }

    // ---------------------------------------------------------- shape

    @Test
    fun typesAndRaritiesAreCountedByCopies() {
        val s = DeckAnalysis.of(
            listOf(
                card("Bear", qty = 3, rarity = "common"),
                card("Bolt", type = "Instant", qty = 2, rarity = "uncommon"),
                card("Wrath", type = "Sorcery", rarity = "rare"),
            ),
        )
        assertEquals(3, s.types.first { it.label == "Creatures" }.value)
        assertEquals(2, s.types.first { it.label == "Instants" }.value)
        assertEquals(3, s.rarities.first { it.label == "Common" }.value)
        assertEquals(6, s.totalCards)
    }

    @Test
    fun theLandShareIsAPercentageOfTheWholeDeck() {
        val s = DeckAnalysis.of(
            listOf(
                card("Plains", type = "Land", cost = null, cmc = 0.0, qty = 37),
                card("Bear", qty = 63),
            ),
        )
        assertEquals(37, s.landShare)
        assertEquals(63, s.spells)
    }

    @Test
    fun whatIsMissingAndWhatItIsWorth() {
        val s = DeckAnalysis.of(
            listOf(
                card("Bear", qty = 4, owned = 1, price = 2.0),
                card("Ghost", qty = 1, owned = 0, price = null),
            ),
        )
        assertEquals(4, s.missing, "three bears and the ghost")
        assertEquals(8.0, s.value)
        assertEquals(1, s.unpriced)
    }

    @Test
    fun aBarKnowsHowWideToDraw() {
        assertEquals(100, Bar("x", 8).share(8))
        assertEquals(50, Bar("x", 4).share(8))
        assertEquals(0, Bar("x", 0).share(8))
        // Nothing to compare against is not a division by zero.
        assertEquals(0, Bar("x", 3).share(0))
    }

    // --------------------------------------------------------- tokens

    @Test
    fun aTokenIsARealCardWithRealArt() {
        // Read out of the rules text this was a description, and a
        // bad one — "Or more", "Twice that many of those", and never
        // any art. Scryfall names the token components of every card
        // in `all_parts`, which is the authoritative answer.
        val t = TokenCard("aae7bdfe-1234", "Soldier", "Token Creature — Soldier", madeBy = 3)
        assertEquals("Soldier", t.name)
        assertEquals(3, t.madeBy)
        // "Token Creature — Soldier" on every one of them is noise.
        assertEquals("Creature — Soldier", t.shortType)
        assertTrue(t.art.orEmpty().contains("art_crop"), t.art.orEmpty())
    }

    @Test
    fun statsAreBlankUnlessBothPowerAndToughnessAreKnown() {
        val soldier = TokenCard("1", "Soldier", "Token Creature — Soldier", power = "1", toughness = "1")
        assertEquals("1/1", soldier.stats)
        // A token with no stats at all — an Incubator, a Clue — has
        // nothing to show rather than a slash with half of it missing.
        val clue = TokenCard("2", "Clue", "Token Artifact")
        assertNull(clue.stats)
    }

    /**
     * `all_parts` never says how a 1/1 white Bird differs from a 2/2
     * blue one — only fetching the tokens themselves does — so what
     * makes two rows the same card has to be power, toughness and
     * colour together, not just the name.
     */
    @Test
    fun identityTellsApartTwoTokensWithTheSameNameAndDifferentStats() {
        val whiteBird = TokenCard("1", "Bird", "Token Creature — Bird", "1", "1", colors = "W")
        val blueBird = TokenCard("2", "Bird", "Token Creature — Bird", "2", "2", colors = "U")
        assertTrue(whiteBird.identity != blueBird.identity)
        val sameBirdReprinted = TokenCard("3", "Bird", "Token Creature — Bird", "1", "1", colors = "W")
        assertEquals(whiteBird.identity, sameBirdReprinted.identity, "a reprint is still the same token")
    }

    @Test
    fun aDeckCarriesTheIdsItsTokensAreLookedUpBy() {
        val s = DecksState().opened(
            "d",
            listOf(
                card("a").copy(scryfallId = "aaa"),
                card("b").copy(scryfallId = "bbb"),
                card("c").copy(scryfallId = "aaa"),
                card("d").copy(scryfallId = null),
            ),
        )
        assertEquals(listOf("aaa", "bbb"), s.scryfallIds)
    }

    @Test
    fun openingADeckClearsTheTokensOfTheLastOne() {
        val first = DecksState().opened("a", listOf(card("x")))
            .withTokens(listOf(TokenCard("1", "Soldier", "Token Creature — Soldier")))
        assertEquals(1, first.tokens.size)
        assertEquals(0, first.opened("b", listOf(card("y"))).tokens.size)
        assertEquals(0, first.close().tokens.size)
    }

    @Test
    fun aDeckWithNoCardsAnalysesToNothingRatherThanCrashing() {
        val s = DeckAnalysis.of(emptyList())
        assertEquals(0, s.totalCards)
        assertEquals(0.0, s.averageManaValue)
        assertEquals(0.0, s.medianManaValue)
        assertEquals(0, s.landShare)
        assertTrue(!s.hasCurve)
        assertEquals(null, s.value)
    }

    // ------------------------------------ mana the deck cannot spend

    private fun makes(cards: List<DeckCard>) =
        DeckAnalysis.of(cards).sources.associate { it.label to it.value }

    @Test
    fun manaOutsideTheCommandersIdentityIsCountedAsColourless() {
        // A land making red in a mono-black deck is not a red source
        // — nothing in the deck has a red cost. It is generic mana,
        // which is what colourless means here.
        val deck = listOf(
            card("Tinybones", type = "Legendary Creature", role = "commander", ci = "B"),
            card("Swamp", type = "Basic Land — Swamp", cost = null, cmc = 0.0, qty = 10, produces = "B", ci = "B"),
            card("Cascading Cataracts", type = "Land", cost = null, cmc = 0.0, produces = "WUBRG", ci = ""),
        )
        val m = makes(deck)
        assertEquals(11, m["Black"], "the land making black is still a black source")
        assertEquals(1, m["Colourless"], "the four colours it cannot use are one generic source")
        assertEquals(null, m["Red"])
        assertEquals(null, m["Green"])
    }

    @Test
    fun aCardMakingSeveralUnusableColoursIsOneColourlessSource() {
        // Not four. It is one card, and it makes one kind of mana
        // this deck can spend.
        val deck = listOf(
            card("Tinybones", type = "Legendary Creature", role = "commander", ci = "B"),
            card("Pool", type = "Land", cost = null, cmc = 0.0, produces = "WURG", ci = ""),
        )
        assertEquals(1, makes(deck)["Colourless"])
    }

    @Test
    fun realColourlessAndFoldedColourlessAddUp() {
        val deck = listOf(
            card("Tinybones", type = "Legendary Creature", role = "commander", ci = "B"),
            card("Wastes", type = "Basic Land — Wastes", cost = null, cmc = 0.0, produces = "C", ci = ""),
            card("Mountain", type = "Basic Land — Mountain", cost = null, cmc = 0.0, produces = "R", ci = "R"),
        )
        assertEquals(2, makes(deck)["Colourless"])
    }

    @Test
    fun aDeckWithNoCommanderKeepsEveryColourItMakes() {
        // Nothing says what is spendable, so nothing is folded.
        val deck = listOf(
            card("Mountain", type = "Basic Land — Mountain", cost = null, cmc = 0.0, produces = "R", ci = "R"),
            card("Island", type = "Basic Land — Island", cost = null, cmc = 0.0, produces = "U", ci = "U"),
        )
        val m = makes(deck)
        assertEquals(1, m["Red"])
        assertEquals(1, m["Blue"])
        assertEquals(null, m["Colourless"])
    }

    @Test
    fun twoCommandersSetTheIdentityBetweenThem() {
        val deck = listOf(
            card("Partner One", type = "Legendary Creature", role = "commander", ci = "W"),
            card("Partner Two", type = "Legendary Creature", role = "commander", ci = "U"),
            card("Plains", type = "Basic Land — Plains", cost = null, cmc = 0.0, produces = "W", ci = "W"),
            card("Island", type = "Basic Land — Island", cost = null, cmc = 0.0, produces = "U", ci = "U"),
            card("Mountain", type = "Basic Land — Mountain", cost = null, cmc = 0.0, produces = "R", ci = "R"),
        )
        val m = makes(deck)
        assertEquals(1, m["White"])
        assertEquals(1, m["Blue"])
        assertEquals(null, m["Red"], "red is outside the pair's identity")
        assertEquals(1, m["Colourless"])
    }

    @Test
    fun theNeedsSideIsUntouched() {
        // Only what the deck makes is folded. What it asks for is
        // what is printed on the cards.
        val deck = listOf(
            card("Tinybones", type = "Legendary Creature", role = "commander", ci = "B", cost = "{1}{B}"),
            card("Oddity", cost = "{R}", ci = "R"),
        )
        val needs = DeckAnalysis.of(deck).pips.associate { it.label to it.value }
        assertEquals(1, needs["Red"], "a red cost is still a red cost")
    }
}
