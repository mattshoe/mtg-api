package org.mattshoe.mtg.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/*
 * Decks.kt and DeckStats.kt, exhaustively.
 *
 * The existing DecksStatsTest and DeckStatsTest cover the headline
 * behaviours. This file covers the edges around them: every spelling a
 * hand-written colour string arrives in, every type line a card can
 * carry, every way a decoded row can be malformed, and every path
 * through the analysis that divides by something that might be nought.
 */

// ------------------------------------------------------------ helpers

private fun deck(
    key: String = "d",
    name: String = "D",
    owner: String = "e7de0cb1",
    commander: String? = null,
    colors: String? = null,
    bracket: Int? = null,
    artId: String? = null,
) = Deck(key, name, owner, commander, colors, bracket, artId)

/** The identity a hand-written `decks.colors` is read as. */
private fun ident(colors: String?) = deck(colors = colors).identity

private fun c(
    name: String = "Card",
    qty: Int = 1,
    role: String? = null,
    owned: Int = 1,
    nameNorm: String = name.lowercase(),
    type: String? = "Creature — Human",
    id: String? = null,
    cost: String? = null,
    cmc: Double? = null,
    produces: String? = null,
    text: String? = null,
    ci: String? = null,
    rarity: String? = null,
    price: Double? = null,
) = DeckCard(
    name = name, qty = qty, role = role, owned = owned, nameNorm = nameNorm,
    typeLine = type, scryfallId = id, manaCost = cost, cmc = cmc,
    producedMana = produces, oracleText = text, colorIdentity = ci,
    rarity = rarity, price = price,
)

/** A spell of a known mana value, so a curve has something in it. */
private fun spell(mv: Double, qty: Int = 1, name: String = "Spell $mv") =
    c(name = name, qty = qty, type = "Creature — Human", cmc = mv, cost = "{${mv.toInt()}}")

/** A land that is not one of the eleven basics. */
private fun land(name: String = "Nonbasic Land", produces: String? = null, qty: Int = 1) =
    c(name = name, qty = qty, type = "Land", cmc = 0.0, produces = produces)

private fun rows(vararg j: String) = j.map { Json.parseToJsonElement(it) as JsonArray }

private val deckCols = listOf("key", "name", "owner", "commander", "colors", "bracket", "art_id")

// =========================================================== identity

/**
 * `decks.colors` is free text somebody typed. Read a character at a
 * time it turns a deck name into a row of nonsense pips, so the reader
 * tries the shapes it knows in order: bare letters, mana symbols,
 * colour words, then the five-colour spellings.
 */
class DeckIdentityExhaustiveTest {

    // ----------------------------------------------- bare letters

    @Test fun oneWhiteLetter() = assertEquals("W", ident("W"))
    @Test fun oneBlueLetter() = assertEquals("U", ident("U"))
    @Test fun oneBlackLetter() = assertEquals("B", ident("B"))
    @Test fun oneRedLetter() = assertEquals("R", ident("R"))
    @Test fun oneGreenLetter() = assertEquals("G", ident("G"))

    @Test fun aLowercaseLetterIsStillAColour() = assertEquals("W", ident("w"))

    @Test fun bareLettersComeBackAlphabetical() {
        // The database stores 'UW', never 'WU', and the pips are drawn
        // in that order wherever they came from.
        assertEquals("UW", ident("WU"))
        assertEquals("UW", ident("UW"))
    }

    @Test fun threeBareLettersSort() = assertEquals("GUW", ident("GWU"))
    @Test fun twoBareLettersSortTheOtherWay() = assertEquals("BR", ident("RB"))
    @Test fun fourBareLettersSort() = assertEquals("BGRU", ident("URGB"))
    @Test fun fiveBareLettersSort() = assertEquals("BGRUW", ident("WUBRG"))

    @Test fun bareLettersAreDeduped() = assertEquals("W", ident("WW"))
    @Test fun fiveOfTheSameLetterIsOneColour() = assertEquals("W", ident("WWWWW"))
    @Test fun aRepeatedPairIsOnePair() = assertEquals("UW", ident("UWUW"))

    @Test fun mixedCaseBareLetters() = assertEquals("UW", ident("uW"))
    @Test fun allLowercaseFiveLetters() = assertEquals("BGRUW", ident("wubrg"))

    @Test fun surroundingSpaceIsTrimmedOffBareLetters() = assertEquals("UW", ident("  UW  "))
    @Test fun aTabAroundBareLettersIsTrimmed() = assertEquals("UW", ident("\tUW\t"))
    @Test fun aNewlineAroundBareLettersIsTrimmed() = assertEquals("UW", ident("\nUW\n"))

    @Test fun moreThanFiveLettersIsNotAnIdentityString() {
        // Six is more colours than Magic has, so it is not the bare
        // form, and nothing else in the string says anything either.
        assertEquals("", ident("WWWWWW"))
    }

    @Test fun sixLettersThatContainAllFiveAreStillFiveColour() {
        // Past the bare-letters branch, but the five-colour catch-all
        // still finds `wubrg` in it.
        assertEquals("BGRUW", ident("WUBRGW"))
    }

    @Test fun aNicknameThatHappensToBeLettersIsReadAsLetters() {
        // "RUG" is three real letters and three real colours, and
        // reading it as such is right by accident.
        assertEquals("GRU", ident("RUG"))
        assertEquals("BGU", ident("BUG"))
    }

    @Test fun colourlessIsNotOneOfTheFiveLetters() = assertEquals("", ident("C"))

    // ------------------------------------------------ mana symbols

    @Test fun oneManaSymbol() = assertEquals("W", ident("{W}"))
    @Test fun twoManaSymbolsSort() = assertEquals("UW", ident("{W}{U}"))
    @Test fun lowercaseManaSymbols() = assertEquals("UW", ident("{w}{u}"))
    @Test fun fiveManaSymbols() = assertEquals("BGRUW", ident("{W}{U}{B}{R}{G}"))
    @Test fun repeatedManaSymbolsAreDeduped() = assertEquals("W", ident("{W}{W}"))

    @Test fun symbolsAreFoundInsideProse() {
        assertEquals("BRUW", ident("{W}{U}{B}{R} (Breya's identity)"))
    }

    @Test fun aGenericSymbolIsNotAColour() = assertEquals("W", ident("{2}{W}"))
    @Test fun aColourlessSymbolIsNotOneOfTheFive() = assertEquals("", ident("{C}"))
    @Test fun anUnclosedBraceFindsNothing() = assertEquals("", ident("{W"))
    @Test fun twoLettersInOneBraceIsNotASymbol() = assertEquals("", ident("{WU}"))

    @Test fun aHybridSymbolIsNotReadAsEitherHalf() {
        // `{W/U}` is not the shape the identity reader knows, and
        // nothing else in that string says anything.
        assertEquals("", ident("{W/U}"))
    }

    @Test fun symbolsWinOverColourWords() {
        // Both shapes are present and the symbols are the precise
        // one, so they decide.
        assertEquals("GU", ident("Simic {G}{U}"))
    }

    @Test fun symbolsWinEvenWhenTheWordsSayMore() = assertEquals("U", ident("White and {U}"))

    // ------------------------------------------------ colour words

    @Test fun theWordWhite() = assertEquals("W", ident("white"))
    @Test fun theWordBlue() = assertEquals("U", ident("Blue"))
    @Test fun theWordBlackShouting() = assertEquals("B", ident("BLACK"))
    @Test fun theWordRed() = assertEquals("R", ident("red"))
    @Test fun theWordGreen() = assertEquals("G", ident("Green"))

    @Test fun aGuildNameWithItsColoursSpelledOut() = assertEquals("GU", ident("Simic (Green/Blue)"))
    @Test fun aShardNameWithItsColoursSpelledOut() = assertEquals("BGR", ident("Jund (Black/Red/Green)"))
    @Test fun aWedgeNameWithCommas() = assertEquals("BRU", ident("Grixis (Blue, Black, Red)"))

    @Test fun monoSomethingIsOneColour() = assertEquals("W", ident("Mono-White"))
    @Test fun monoSomethingWithASpace() = assertEquals("B", ident("Mono Black"))

    @Test fun aRepeatedColourWordIsOneColour() = assertEquals("W", ident("white white"))
    @Test fun colourWordsSeparatedByASlash() = assertEquals("GR", ident("Red/Green"))
    @Test fun colourWordsSeparatedByADash() = assertEquals("BR", ident("Black-Red"))

    @Test fun aWordThatMerelyStartsWithAColourIsNotAColour() {
        // `\b` on both sides, or "Whitemane Lion" makes a deck white.
        assertEquals("", ident("whiteness"))
        assertEquals("", ident("Greenery"))
    }

    @Test fun aWordThatMerelyEndsWithAColourIsNotAColour() = assertEquals("", ident("nonwhite"))

    @Test fun allFiveWordsSpelledOut() {
        assertEquals("BGRUW", ident("White, Blue, Black, Red and Green"))
    }

    // ---------------------------------------------------- five colour

    @Test fun fiveColourHyphenated() = assertEquals("BGRUW", ident("Five-color (WUBRG)"))
    @Test fun fiveColourWithASpace() = assertEquals("BGRUW", ident("Five color"))
    @Test fun fiveColourSpelledTheOtherWay() = assertEquals("BGRUW", ident("Five-Colour"))
    @Test fun fiveColourRunTogether() = assertEquals("BGRUW", ident("fivecolor"))
    @Test fun theLettersWubrgInsideProse() = assertEquals("BGRUW", ident("the WUBRG deck"))

    @Test fun aDigitIsNotTheWordFive() = assertEquals("", ident("5-color"))
    @Test fun rainbowIsNotASpellingWeKnow() = assertEquals("", ident("Rainbow"))

    // ---------------------------------------------------------- nothing

    @Test fun noColoursAtAll() = assertEquals("", ident(null))
    @Test fun anEmptyColoursColumn() = assertEquals("", ident(""))
    @Test fun aWhitespaceOnlyColoursColumn() = assertEquals("", ident("   "))
    @Test fun punctuationOnly() = assertEquals("", ident("???"))
    @Test fun digitsOnly() = assertEquals("", ident("123"))
    @Test fun aGuildNameOnItsOwnSaysNothing() = assertEquals("", ident("Boros"))
    @Test fun anotherGuildNameOnItsOwnSaysNothing() = assertEquals("", ident("Gruul"))
    @Test fun aDeckNameIsNotAnIdentity() = assertEquals("", ident("Explorers of the Deep"))
    @Test fun colourlessSpelledOutSaysNothing() = assertEquals("", ident("colorless"))

    // ------------------------------------------------------ colour pips

    @Test fun pipsAreTheIdentityOneLetterAtATime() {
        assertEquals(listOf("U", "W"), deck(colors = "WU").colorPips)
    }

    @Test fun pipsOfNothingAreNoPips() = assertTrue(deck(colors = null).colorPips.isEmpty())
    @Test fun pipsOfEmptyAreNoPips() = assertTrue(deck(colors = "").colorPips.isEmpty())

    @Test fun fiveColoursAreFivePips() {
        assertEquals(listOf("B", "G", "R", "U", "W"), deck(colors = "Five-color").colorPips)
    }

    @Test fun thePipCountAlwaysMatchesTheIdentityLength() {
        listOf("W", "WU", "{W}{U}{B}", "Five-color", "", null, "nonsense").forEach {
            val d = deck(colors = it)
            assertEquals(d.identity.length, d.colorPips.size, "pips disagree with identity for $it")
        }
    }

    @Test fun anIdentityNeverRepeatsAColour() {
        listOf("WWUU", "{W}{W}{U}", "white white blue", "wubrg").forEach {
            val pips = deck(colors = it).colorPips
            assertEquals(pips.distinct(), pips, "a colour appears twice for $it")
        }
    }

    @Test fun anIdentityIsAlwaysSorted() {
        listOf("GWU", "{G}{W}{U}", "Green, White, Blue", "Five-color").forEach {
            val pips = deck(colors = it).colorPips
            assertEquals(pips.sorted(), pips, "pips are out of order for $it")
        }
    }

    @Test fun everyIdentityLetterIsOneOfTheFive() {
        listOf("WU", "{B}{R}", "Green", "Five-colour", "{C}", "C", "nonsense").forEach { raw ->
            deck(colors = raw).colorPips.forEach { p ->
                assertTrue(p in listOf("W", "U", "B", "R", "G"), "$p is not a colour, from $raw")
            }
        }
    }
}

// ============================================================== title

/** A tile-width name plus prose, and the dash that separates them. */
class DeckTitleExhaustiveTest {

    @Test fun anEmDashSeparatesTheNameFromTheProse() {
        assertEquals(
            "Explorers of the Deep",
            deck(name = "Explorers of the Deep — Modern Horizons 3 Precon").title,
        )
    }

    @Test fun onlyTheFirstSegmentIsTheTitle() {
        assertEquals("A", deck(name = "A — B — C").title)
    }

    @Test fun aPlainNameIsItsOwnTitle() {
        assertEquals("Feather Storm", deck(name = "Feather Storm").title)
    }

    @Test fun aHyphenIsNotAnEmDash() {
        assertEquals("Snow-Covered Control", deck(name = "Snow-Covered Control").title)
    }

    @Test fun anEnDashIsNotAnEmDash() {
        assertEquals("A – B", deck(name = "A – B").title)
    }

    @Test fun aMinusSignIsNotAnEmDash() {
        assertEquals("A - B", deck(name = "A - B").title)
    }

    @Test fun anEmDashWithNoSpacesAroundItIsPartOfTheName() {
        assertEquals("A—B", deck(name = "A—B").title)
    }

    @Test fun anEmDashNeedsSpaceOnBothSides() {
        assertEquals("A —B", deck(name = "A —B").title)
        assertEquals("A— B", deck(name = "A— B").title)
    }

    @Test fun severalSpacesAroundTheDashStillSplit() {
        assertEquals("A", deck(name = "A    —    B").title)
    }

    @Test fun aTabAroundTheDashStillSplits() {
        assertEquals("A", deck(name = "A\t—\tB").title)
    }

    @Test fun aNewlineAroundTheDashStillSplits() {
        assertEquals("A", deck(name = "A\n—\nB").title)
    }

    @Test fun aNameThatBeginsWithTheDashKeepsTheWholeThing() {
        // Splitting leaves an empty first segment, which is not a
        // title, so the name itself is the best answer available.
        assertEquals(" — Foo", deck(name = " — Foo").title)
    }

    @Test fun anEmptyNameHasAnEmptyTitle() = assertEquals("", deck(name = "").title)

    @Test fun aWhitespaceOnlyNameIsLeftAlone() = assertEquals("   ", deck(name = "   ").title)

    @Test fun nothingAfterTheDashIsStillJustTheName() {
        assertEquals("A", deck(name = "A — ").title)
    }

    @Test fun trailingSpaceBeforeTheDashIsTrimmed() {
        assertEquals("Alela", deck(name = "Alela   —   Faeries").title)
    }

    @Test fun aLongTitleIsNotTruncated() {
        val long = "x".repeat(300)
        assertEquals(long, deck(name = long).title)
        assertEquals(300, deck(name = "$long — prose").title.length)
    }

    @Test fun theTitleKeepsItsCase() {
        assertEquals("ALELA bird Tribal", deck(name = "ALELA bird Tribal — precon").title)
    }

    @Test fun theTitleKeepsPunctuationAndAccents() {
        assertEquals("Jötun, Grunt's Deck!", deck(name = "Jötun, Grunt's Deck! — notes").title)
    }

    @Test fun theTitleIsIndependentOfEverythingElseOnTheRow() {
        val d = deck(name = "A — B", commander = "Alela (ELD) 1", colors = "WU", bracket = 4)
        assertEquals("A", d.title)
    }

    // ------------------------------------------------- commander name

    @Test fun theSetAnnotationIsDropped() {
        assertEquals(
            "Alela, Artful Provocateur",
            deck(commander = "Alela, Artful Provocateur (ELD) 324").commanderName,
        )
    }

    @Test fun aCommanderWithNoAnnotationIsItself() {
        assertEquals("Sol Ring", deck(commander = "Sol Ring").commanderName)
    }

    @Test fun noCommanderIsNull() = assertNull(deck(commander = null).commanderName)
    @Test fun anEmptyCommanderIsNull() = assertNull(deck(commander = "").commanderName)
    @Test fun aWhitespaceCommanderIsNull() = assertNull(deck(commander = "   ").commanderName)

    @Test fun anAnnotationWithNothingBeforeItIsNull() {
        // " (" at the very start leaves nothing to match on.
        assertNull(deck(commander = " (ELD) 324").commanderName)
    }

    @Test fun aParenthesisWithNoSpaceBeforeItIsPartOfTheName() {
        assertEquals("(ELD) 324", deck(commander = "(ELD) 324").commanderName)
    }

    @Test fun onlyTheFirstAnnotationMatters() {
        assertEquals("Alela", deck(commander = "Alela (ELD) 324 (foil)").commanderName)
    }

    @Test fun trailingSpaceIsTrimmedOffACommander() {
        assertEquals("Alela", deck(commander = "Alela   ").commanderName)
    }

    @Test fun aCommanderKeepsItsComma() {
        assertEquals(
            "Tinybones, Trinket Thief",
            deck(commander = "Tinybones, Trinket Thief").commanderName,
        )
    }

    @Test fun aDoubleFacedCommanderWhoseHalvesMatchIsOneName() {
        assertEquals(
            "Jetmir, Nexus of Revels",
            deck(commander = "Jetmir, Nexus of Revels // Jetmir, Nexus of Revels").commanderName,
        )
    }

    @Test fun aDoubleFacedCommanderWithTwoRealHalvesKeepsBoth() {
        assertEquals(
            "Brutal Cathar // Moonrage Brute",
            deck(commander = "Brutal Cathar // Moonrage Brute").commanderName,
        )
    }

    @Test fun aDoubleFacedCommanderWithAnAnnotation() {
        assertEquals("Jetmir", deck(commander = "Jetmir // Jetmir (SNC) 234").commanderName)
    }

    // ---------------------------------------------------------- one name

    @Test fun oneNameCollapsesIdenticalHalves() = assertEquals("A", oneName("A // A"))
    @Test fun oneNameLeavesTwoRealHalvesAlone() = assertEquals("A // B", oneName("A // B"))
    @Test fun oneNameLeavesAPlainNameAlone() = assertEquals("Sol Ring", oneName("Sol Ring"))
    @Test fun oneNameLeavesThreeHalvesAlone() = assertEquals("A // A // A", oneName("A // A // A"))
    @Test fun oneNameIsCaseSensitive() = assertEquals("a // A", oneName("a // A"))
    @Test fun oneNameTrimsTheHalfItKeeps() = assertEquals("A", oneName(" A  //  A "))
    @Test fun oneNameLeavesAnEmptyStringAlone() = assertEquals("", oneName(""))
    @Test fun oneNameNeedsSpacesAroundTheSlashes() = assertEquals("A//A", oneName("A//A"))

    @Test fun aCardShowsTheCollapsedName() {
        assertEquals("Jetmir", c(name = "Jetmir // Jetmir").shown)
    }

    @Test fun aCardWithTwoRealFacesShowsBoth() {
        assertEquals(
            "Brutal Cathar // Moonrage Brute",
            c(name = "Brutal Cathar // Moonrage Brute").shown,
        )
    }

    @Test fun aPlainCardShowsItsName() = assertEquals("Sol Ring", c(name = "Sol Ring").shown)
}

// ========================================================== sections

/** Which section of the list a card is read under. */
class DeckGroupExhaustiveTest {

    private fun group(type: String?, role: String? = null, name: String = "X") =
        c(name = name, type = type, role = role).group

    // --------------------------------------------- one type at a time

    @Test fun aCreature() = assertEquals(DeckGroup.CREATURES, group("Creature"))
    @Test fun aCreatureWithASubtype() = assertEquals(DeckGroup.CREATURES, group("Creature — Faerie Rogue"))
    @Test fun aLegendaryCreature() = assertEquals(DeckGroup.CREATURES, group("Legendary Creature — Human"))
    @Test fun aPlaneswalker() = assertEquals(DeckGroup.PLANESWALKERS, group("Planeswalker"))
    @Test fun aLegendaryPlaneswalker() = assertEquals(DeckGroup.PLANESWALKERS, group("Legendary Planeswalker — Jace"))
    @Test fun anInstant() = assertEquals(DeckGroup.INSTANTS, group("Instant"))
    @Test fun anInstantWithASubtype() = assertEquals(DeckGroup.INSTANTS, group("Instant — Adventure"))
    @Test fun aSorcery() = assertEquals(DeckGroup.SORCERIES, group("Sorcery"))
    @Test fun anArtifact() = assertEquals(DeckGroup.ARTIFACTS, group("Artifact"))
    @Test fun aLegendaryArtifact() = assertEquals(DeckGroup.ARTIFACTS, group("Legendary Artifact"))
    @Test fun anEquipment() = assertEquals(DeckGroup.ARTIFACTS, group("Artifact — Equipment"))
    @Test fun aVehicle() = assertEquals(DeckGroup.ARTIFACTS, group("Artifact — Vehicle"))
    @Test fun anEnchantment() = assertEquals(DeckGroup.ENCHANTMENTS, group("Enchantment"))
    @Test fun anAura() = assertEquals(DeckGroup.ENCHANTMENTS, group("Enchantment — Aura"))
    @Test fun aSaga() = assertEquals(DeckGroup.ENCHANTMENTS, group("Enchantment — Saga"))
    @Test fun aLegendaryEnchantment() = assertEquals(DeckGroup.ENCHANTMENTS, group("Legendary Enchantment"))
    @Test fun aBattle() = assertEquals(DeckGroup.BATTLES, group("Battle"))
    @Test fun aSiege() = assertEquals(DeckGroup.BATTLES, group("Battle — Siege"))
    @Test fun aLand() = assertEquals(DeckGroup.LANDS, group("Land"))
    @Test fun aLandWithASubtype() = assertEquals(DeckGroup.LANDS, group("Land — Gate"))
    @Test fun aBasicLand() = assertEquals(DeckGroup.LANDS, group("Basic Land — Island"))
    @Test fun aSnowBasicLand() = assertEquals(DeckGroup.LANDS, group("Snow Basic Land — Forest"))
    @Test fun aLegendaryLand() = assertEquals(DeckGroup.LANDS, group("Legendary Land"))
    @Test fun aKindredCreature() = assertEquals(DeckGroup.CREATURES, group("Kindred Enchantment Creature — Goblin"))
    @Test fun aTribalInstant() = assertEquals(DeckGroup.INSTANTS, group("Tribal Instant — Goblin"))

    // ---------------------------------------------- nothing we bucket

    @Test fun aDungeon() = assertEquals(DeckGroup.OTHER, group("Dungeon"))
    @Test fun aNamedDungeon() = assertEquals(DeckGroup.OTHER, group("Dungeon — Undercity"))
    @Test fun aPlane() = assertEquals(DeckGroup.OTHER, group("Plane — Equilor"))
    @Test fun aPhenomenon() = assertEquals(DeckGroup.OTHER, group("Phenomenon"))
    @Test fun aScheme() = assertEquals(DeckGroup.OTHER, group("Scheme"))
    @Test fun aVanguard() = assertEquals(DeckGroup.OTHER, group("Vanguard"))
    @Test fun aConspiracy() = assertEquals(DeckGroup.OTHER, group("Conspiracy"))
    @Test fun anEmblem() = assertEquals(DeckGroup.OTHER, group("Emblem — Jace"))
    @Test fun somethingNobodyHasPrintedYet() = assertEquals(DeckGroup.OTHER, group("Spacecraft"))

    // ------------------------------------------- the most specific wins

    @Test fun anArtifactCreatureIsACreature() {
        // Or the creature count on every deck is a lie.
        assertEquals(DeckGroup.CREATURES, group("Artifact Creature — Golem"))
    }

    @Test fun anEnchantmentCreatureIsACreature() =
        assertEquals(DeckGroup.CREATURES, group("Enchantment Creature — Nymph"))

    @Test fun aLandCreatureIsACreature() {
        // Dryad Arbor is printed "Land Creature — Forest Dryad", and
        // it attacks.
        assertEquals(DeckGroup.CREATURES, group("Land Creature — Forest Dryad"))
    }

    @Test fun anArtifactLandIsALand() = assertEquals(DeckGroup.LANDS, group("Artifact Land"))

    @Test fun anEnchantmentLandIsALand() {
        // Urza's Saga is printed "Enchantment Land", and it taps.
        assertEquals(DeckGroup.LANDS, group("Enchantment Land — Urza's Saga"))
    }

    @Test fun aBattleBeatsALand() = assertEquals(DeckGroup.BATTLES, group("Battle Land"))
    @Test fun aCreatureBeatsAPlaneswalker() = assertEquals(DeckGroup.CREATURES, group("Planeswalker Creature"))
    @Test fun aPlaneswalkerBeatsAnInstant() = assertEquals(DeckGroup.PLANESWALKERS, group("Instant Planeswalker"))
    @Test fun anInstantBeatsASorcery() = assertEquals(DeckGroup.INSTANTS, group("Instant Sorcery"))
    @Test fun aSorceryBeatsABattle() = assertEquals(DeckGroup.SORCERIES, group("Battle Sorcery"))
    @Test fun anArtifactBeatsAnEnchantment() = assertEquals(DeckGroup.ARTIFACTS, group("Artifact Enchantment"))

    @Test fun theOrderIsNotTheOrderTheWordsAreWrittenIn() {
        // "Creature Artifact" is not how anything is printed, but the
        // rule is about the type, not about where it sits in the line.
        assertEquals(DeckGroup.CREATURES, group("Creature Artifact"))
        assertEquals(DeckGroup.CREATURES, group("Artifact Creature"))
    }

    // ------------------------------------------- only the front face

    @Test fun aCreatureWhoseBackIsALandIsACreature() =
        assertEquals(DeckGroup.CREATURES, group("Creature — Merfolk // Land"))

    @Test fun aLandWhoseBackIsACreatureIsALand() =
        assertEquals(DeckGroup.LANDS, group("Land // Creature — Elemental"))

    @Test fun aSorceryWhoseBackIsALandIsASorcery() =
        assertEquals(DeckGroup.SORCERIES, group("Sorcery // Land"))

    @Test fun anInstantWhoseBackIsASorceryIsAnInstant() =
        assertEquals(DeckGroup.INSTANTS, group("Instant // Sorcery"))

    @Test fun aSlashWithNoSpacesStillSeparatesTheFaces() =
        assertEquals(DeckGroup.ARTIFACTS, group("Artifact//Creature"))

    @Test fun aTypeLineThatIsOnlySlashesKnowsNothing() =
        assertEquals(DeckGroup.UNKNOWN, group("//"))

    @Test fun aTypeLineWithAnEmptyFrontFaceKnowsNothing() =
        assertEquals(DeckGroup.UNKNOWN, group("// Land"))

    // ---------------------------------------------------- case

    @Test fun aShoutingTypeLine() = assertEquals(DeckGroup.CREATURES, group("CREATURE — HUMAN"))
    @Test fun aMixedUpTypeLine() = assertEquals(DeckGroup.LANDS, group("bAsIc LaNd"))

    // --------------------------------------------- nothing known at all

    @Test fun noTypeLineIsItsOwnSection() = assertEquals(DeckGroup.UNKNOWN, group(null))
    @Test fun anEmptyTypeLineIsItsOwnSection() = assertEquals(DeckGroup.UNKNOWN, group(""))
    @Test fun aWhitespaceTypeLineIsItsOwnSection() = assertEquals(DeckGroup.UNKNOWN, group("   "))
    @Test fun aTabOnlyTypeLineIsItsOwnSection() = assertEquals(DeckGroup.UNKNOWN, group("\t\n"))

    @Test fun aBasicLandNobodyOwnsIsStillALand() {
        // The joins come back empty, but the name is enough.
        assertEquals(DeckGroup.LANDS, c(name = "Plains", type = null, owned = 0).group)
        assertEquals(DeckGroup.LANDS, c(name = "Wastes", type = null, owned = 0).group)
        assertEquals(DeckGroup.LANDS, c(name = "Snow-Covered Swamp", type = null, owned = 0).group)
    }

    // ------------------------------------------------- the commander

    @Test fun theCommanderIsTheCommanderWhateverItsType() {
        assertEquals(DeckGroup.COMMANDER, group("Legendary Creature — Faerie", role = "commander"))
        assertEquals(DeckGroup.COMMANDER, group("Legendary Land", role = "commander"))
        assertEquals(DeckGroup.COMMANDER, group("Legendary Planeswalker", role = "commander"))
    }

    @Test fun theCommanderIsTheCommanderEvenWithNoTypeLine() =
        assertEquals(DeckGroup.COMMANDER, group(null, role = "commander"))

    @Test fun anotherRoleIsNotACommander() {
        assertEquals(DeckGroup.CREATURES, group("Creature", role = "main"))
        assertEquals(DeckGroup.CREATURES, group("Creature", role = ""))
    }

    @Test fun onlyTheCommanderRoleIsTheCommander() {
        assertTrue(c(role = "commander").isCommander)
        assertFalse(c(role = "main").isCommander)
        assertFalse(c(role = null).isCommander)
    }

    // ------------------------------------------------ the enum itself

    @Test fun thereAreElevenSections() = assertEquals(11, DeckGroup.entries.size)

    @Test fun theSectionsAreInReadingOrder() {
        assertEquals(
            listOf(
                "Commander", "Creatures", "Planeswalkers", "Instants", "Sorceries",
                "Artifacts", "Enchantments", "Battles", "Lands", "Other",
                "Not in the collection",
            ),
            DeckGroup.entries.map { it.title },
        )
    }

    @Test fun theCommanderIsFirstAndTheUnknownsAreLast() {
        assertEquals(DeckGroup.COMMANDER, DeckGroup.entries.first())
        assertEquals(DeckGroup.UNKNOWN, DeckGroup.entries.last())
    }

    @Test fun landsComeAfterEveryKindOfSpell() {
        assertTrue(DeckGroup.LANDS.ordinal > DeckGroup.ENCHANTMENTS.ordinal)
        assertTrue(DeckGroup.LANDS.ordinal < DeckGroup.OTHER.ordinal)
    }

    @Test fun everySectionTitleIsDistinct() {
        val titles = DeckGroup.entries.map { it.title }
        assertEquals(titles.distinct().size, titles.size)
    }
}

// ======================================================= a deck card

/** What a row of a deck list knows about itself. */
class DeckCardExhaustiveTest {

    // ----------------------------------------------------- the basics

    @Test fun plainsIsWhite() = assertEquals("W", Basic.of("plains")?.produces)
    @Test fun islandIsBlue() = assertEquals("U", Basic.of("island")?.produces)
    @Test fun swampIsBlack() = assertEquals("B", Basic.of("swamp")?.produces)
    @Test fun mountainIsRed() = assertEquals("R", Basic.of("mountain")?.produces)
    @Test fun forestIsGreen() = assertEquals("G", Basic.of("forest")?.produces)
    @Test fun wastesIsColourless() = assertEquals("C", Basic.of("wastes")?.produces)

    @Test fun snowCoveredPlainsIsWhite() = assertEquals("W", Basic.of("snow-covered plains")?.produces)
    @Test fun snowCoveredIslandIsBlue() = assertEquals("U", Basic.of("snow-covered island")?.produces)
    @Test fun snowCoveredSwampIsBlack() = assertEquals("B", Basic.of("snow-covered swamp")?.produces)
    @Test fun snowCoveredMountainIsRed() = assertEquals("R", Basic.of("snow-covered mountain")?.produces)
    @Test fun snowCoveredForestIsGreen() = assertEquals("G", Basic.of("snow-covered forest")?.produces)

    @Test fun eachBasicKnowsItsTypeLine() {
        assertEquals("Basic Land — Plains", Basic.of("plains")?.typeLine)
        assertEquals("Basic Land — Island", Basic.of("island")?.typeLine)
        assertEquals("Basic Land — Swamp", Basic.of("swamp")?.typeLine)
        assertEquals("Basic Land — Mountain", Basic.of("mountain")?.typeLine)
        assertEquals("Basic Land — Forest", Basic.of("forest")?.typeLine)
    }

    @Test fun aSnowBasicSaysSoInItsTypeLine() =
        assertEquals("Snow Basic Land — Plains", Basic.of("snow-covered plains")?.typeLine)

    @Test fun wastesHasNoSubtype() = assertEquals("Basic Land", Basic.of("wastes")?.typeLine)

    @Test fun thereIsNoSuchThingAsASnowCoveredWastes() = assertNull(Basic.of("snow-covered wastes"))

    @Test fun aRealCardIsNotABasic() {
        assertNull(Basic.of("sol ring"))
        assertNull(Basic.of("ancient tomb"))
        assertNull(Basic.of(""))
        assertNull(Basic.of("plain"))
        assertNull(Basic.of("plainss"))
    }

    @Test fun theBasicLookupTrimsAndLowercases() {
        assertNotNull(Basic.of("  Plains  "))
        assertNotNull(Basic.of("PLAINS"))
        assertNotNull(Basic.of("Snow-Covered Forest"))
    }

    @Test fun thereAreElevenBasics() = assertEquals(11, Basic.names.size)

    @Test fun everyBasicNameResolves() {
        Basic.names.forEach { assertNotNull(Basic.of(it), "$it does not resolve") }
    }

    // ------------------------------------------- what a card can know

    @Test fun aCardWithAPrintingKnowsItsTypeLine() =
        assertEquals("Artifact", c(type = "Artifact").knownTypeLine)

    @Test fun aBasicWithNoPrintingKnowsItsTypeLineAnyway() =
        assertEquals("Basic Land — Plains", c(name = "Plains", type = null, owned = 0).knownTypeLine)

    @Test fun aCardWithNeitherKnowsNothing() =
        assertNull(c(name = "Mystery Card", type = null, owned = 0).knownTypeLine)

    @Test fun aRealPrintingBeatsTheBasicFallback() {
        val known = c(name = "Plains", type = "Basic Land — Plains", produces = "W", cmc = 0.0)
        assertEquals("Basic Land — Plains", known.knownTypeLine)
        assertEquals("W", known.knownProducedMana)
        assertEquals(0.0, known.knownManaValue)
    }

    @Test fun aPrintingWithAnOddTypeLineStillBeatsTheFallback() {
        // The row in the collection is the truth, whatever it says.
        assertEquals("Land", c(name = "Plains", type = "Land").knownTypeLine)
    }

    @Test fun aPrintedManaValueBeatsTheBasicsNought() =
        assertEquals(1.0, c(name = "Plains", type = "Land", cmc = 1.0).knownManaValue)

    @Test fun aPrintedProducedManaBeatsTheBasicsColour() =
        assertEquals("C", c(name = "Plains", type = "Land", produces = "C").knownProducedMana)

    @Test fun aLandsManaValueIsNoughtWithOrWithoutAPrinting() {
        assertEquals(0.0, c(name = "Island", type = null, owned = 0).knownManaValue)
        assertEquals(0.0, c(name = "Wastes", type = null, owned = 0).knownManaValue)
    }

    @Test fun aNonBasicWithNoPrintingHasNoManaValue() =
        assertNull(c(name = "Mystery Card", type = null, owned = 0).knownManaValue)

    @Test fun aNonBasicWithNoPrintingProducesNothingKnown() =
        assertNull(c(name = "Mystery Card", type = null, owned = 0).knownProducedMana)

    @Test fun aCardWithAPrintingKnowsWhatItTapsFor() =
        assertEquals("WU", c(type = "Land", produces = "WU").knownProducedMana)

    @Test fun aManaValueOfNoughtIsNotTheSameAsNotKnowing() {
        assertEquals(0.0, c(type = "Artifact", cmc = 0.0).knownManaValue)
        assertNull(c(name = "Mystery Card", type = "Artifact").knownManaValue)
    }

    // -------------------------------------- which name it is looked up by

    @Test fun theNormalisedNameIsWhatTheBasicLookupUses() {
        // `name_norm` is the column, not `name.lowercase()` — Jötun
        // Grunt normalises to "jotun grunt".
        assertTrue(c(name = "Whatever", nameNorm = "plains").isBasicLand)
    }

    @Test fun theNameIsTheFallbackWhenThereIsNoNormalisedOne() =
        assertTrue(c(name = "PLAINS", nameNorm = "").isBasicLand)

    @Test fun theNormalisedNameWinsOverTheName() =
        assertFalse(c(name = "Plains", nameNorm = "not a basic").isBasicLand)

    @Test fun aRealCardIsNotABasicLand() {
        assertFalse(c(name = "Sol Ring", type = "Artifact").isBasicLand)
        assertFalse(c(name = "Ancient Tomb", type = "Land").isBasicLand)
    }

    @Test fun everyBasicIsRecognisedOffItsNameAlone() {
        Basic.names.forEach {
            assertTrue(c(name = it, nameNorm = it, type = null).isBasicLand, "$it is not a basic")
        }
    }

    // ------------------------------------------ copies short of a deck

    @Test fun shortIsWhatTheDeckWantsLessWhatIsOwned() =
        assertEquals(3, c(name = "Mana Crypt", qty = 4, owned = 1).short)

    @Test fun nothingIsShortWhenItIsAllOwned() = assertEquals(0, c(qty = 2, owned = 2).short)

    @Test fun owningMoreThanTheDeckWantsIsNotANegativeShortfall() =
        assertEquals(0, c(qty = 1, owned = 5).short)

    @Test fun aDeckWantingNoneIsShortOfNone() = assertEquals(0, c(qty = 0, owned = 0).short)

    @Test fun aBasicIsNeverShort() {
        assertEquals(0, c(name = "Plains", qty = 9, owned = 0, type = null).short)
        assertEquals(0, c(name = "Snow-Covered Plains", qty = 4, owned = 0, type = null).short)
        assertEquals(0, c(name = "Wastes", qty = 1, owned = 0, type = null).short)
    }

    @Test fun aBasicWithAPrintingIsStillNeverShort() =
        assertEquals(0, c(name = "Plains", qty = 9, owned = 1, type = "Basic Land — Plains").short)

    @Test fun aCardNobodyOwnsIsShortOfEveryCopy() =
        assertEquals(7, c(name = "Mana Crypt", qty = 7, owned = 0).short)

    // ------------------------------------------------------------- art

    @Test fun aPrintingHasCroppedArt() {
        val art = c(id = "abcdef12-3456").art
        assertEquals("https://cards.scryfall.io/art_crop/front/a/b/abcdef12-3456.jpg", art)
    }

    @Test fun theArtPathShardsOnTheFirstTwoCharacters() {
        assertTrue(c(id = "zq999").art!!.contains("/front/z/q/"))
    }

    @Test fun noPrintingIsNoArtRatherThanABrokenImage() = assertNull(c(id = null).art)
    @Test fun anEmptyIdIsNoArt() = assertNull(c(id = "").art)
    @Test fun aBlankIdIsNoArt() = assertNull(c(id = "   ").art)
    @Test fun aOneCharacterIdIsNoArt() = assertNull(c(id = "a").art)
    @Test fun aTwoCharacterIdIsJustEnough() {
        assertNotNull(c(id = "ab").art)
    }

    // ------------------------------------------------------- defaults

    @Test fun aCardBuiltFromNothingButANameAndACountKnowsNothingElse() {
        val bare = DeckCard("Mystery Card", 1, null, 0)
        assertEquals("", bare.nameNorm)
        assertNull(bare.typeLine)
        assertNull(bare.scryfallId)
        assertNull(bare.manaCost)
        assertNull(bare.cmc)
        assertNull(bare.producedMana)
        assertNull(bare.oracleText)
        assertNull(bare.colorIdentity)
        assertNull(bare.rarity)
        assertNull(bare.price)
        assertEquals(DeckGroup.UNKNOWN, bare.group)
        assertFalse(bare.isCommander)
        assertNull(bare.art)
        assertEquals(1, bare.short)
    }
}

// ===================================================== the screen

/** The decks screen: a list, or one deck opened. */
class DecksStateExhaustiveTest {

    private val three = listOf(
        deck(key = "b", name = "B", owner = "e7de0cb1"),
        deck(key = "a", name = "A", owner = "bprh3d2s"),
        deck(key = "c", name = "C", owner = "e7de0cb1"),
    )

    @Test fun aFreshScreenHasNothingOnIt() {
        val s = DecksState()
        assertTrue(s.decks.isEmpty())
        assertTrue(s.cards.isEmpty())
        assertTrue(s.tokens.isEmpty())
        assertNull(s.openKey)
        assertNull(s.error)
        assertFalse(s.busy)
        assertNull(s.open)
        assertNull(s.commander)
        assertEquals(0, s.totalCards)
    }

    // ----------------------------------------------------- transitions

    @Test fun loadingSaysItIsBusy() = assertTrue(DecksState().loading().busy)

    @Test fun loadingForgetsTheLastError() =
        assertNull(DecksState().failed("nope").loading().error)

    @Test fun loadingKeepsWhatIsAlreadyOnTheScreen() {
        val s = DecksState().loaded(three).opened("b", listOf(c())).loading()
        assertEquals(3, s.decks.size)
        assertEquals(1, s.cards.size)
        assertEquals("b", s.openKey)
    }

    @Test fun loadedStopsBeingBusy() = assertFalse(DecksState().loading().loaded(three).busy)

    @Test fun loadedForgetsTheLastError() =
        assertNull(DecksState().failed("nope").loaded(three).error)

    @Test fun loadedReplacesTheListRatherThanAddingToIt() {
        val s = DecksState().loaded(three).loaded(listOf(deck(key = "z")))
        assertEquals(listOf("z"), s.decks.map { it.key })
    }

    @Test fun loadedWithNothingIsAnEmptyList() =
        assertTrue(DecksState().loaded(three).loaded(emptyList()).decks.isEmpty())

    @Test fun failedSaysWhyAndStopsBeingBusy() {
        val s = DecksState().loading().failed("network down")
        assertEquals("network down", s.error)
        assertFalse(s.busy)
    }

    @Test fun failedKeepsWhatWasAlreadyLoaded() {
        val s = DecksState().loaded(three).failed("nope")
        assertEquals(3, s.decks.size)
    }

    @Test fun failedKeepsTheOpenDeck() {
        val s = DecksState().loaded(three).opened("b", listOf(c())).failed("nope")
        assertEquals("b", s.openKey)
        assertEquals(1, s.cards.size)
    }

    @Test fun anEmptyMessageIsStillAnError() = assertEquals("", DecksState().failed("").error)

    @Test fun openingADeckRecordsItsKeyAndItsCards() {
        val s = DecksState().loaded(three).opened("b", listOf(c(name = "Sol Ring")))
        assertEquals("b", s.openKey)
        assertEquals(listOf("Sol Ring"), s.cards.map { it.name })
        assertFalse(s.busy)
        assertNull(s.error)
    }

    @Test fun openingADeckReplacesTheLastOnesCards() {
        val s = DecksState().opened("a", listOf(c(name = "One"))).opened("b", listOf(c(name = "Two")))
        assertEquals(listOf("Two"), s.cards.map { it.name })
    }

    @Test fun openingADeckForgetsTheLastError() =
        assertNull(DecksState().failed("nope").opened("a", emptyList()).error)

    @Test fun openingADeckWithNoCardsIsAllowed() {
        val s = DecksState().opened("a", emptyList())
        assertEquals("a", s.openKey)
        assertTrue(s.cards.isEmpty())
    }

    @Test fun tokensArriveBehindTheList() {
        val t = TokenCard("1", "Soldier", "Token Creature — Soldier")
        val s = DecksState().opened("a", listOf(c())).withTokens(listOf(t))
        assertEquals(1, s.tokens.size)
        assertEquals(1, s.cards.size, "the tokens did not disturb the list")
    }

    @Test fun tokensCanBeReplacedWithNone() {
        val t = TokenCard("1", "Soldier", "Token Creature — Soldier")
        assertTrue(DecksState().withTokens(listOf(t)).withTokens(emptyList()).tokens.isEmpty())
    }

    @Test fun closingForgetsTheDeckAndItsCardsAndItsTokens() {
        val s = DecksState().loaded(three)
            .opened("b", listOf(c()))
            .withTokens(listOf(TokenCard("1", "Soldier", "Token Creature — Soldier")))
            .close()
        assertNull(s.openKey)
        assertTrue(s.cards.isEmpty())
        assertTrue(s.tokens.isEmpty())
    }

    @Test fun closingKeepsTheListBehindIt() =
        assertEquals(3, DecksState().loaded(three).opened("b", listOf(c())).close().decks.size)

    @Test fun closingTwiceIsNotAnError() {
        val s = DecksState().loaded(three).opened("b", listOf(c())).close().close()
        assertNull(s.openKey)
    }

    // -------------------------------------------------- which is open

    @Test fun theOpenDeckIsTheOneWhoseKeyMatches() {
        val s = DecksState().loaded(three).opened("c", emptyList())
        assertEquals("C", s.open?.name)
    }

    @Test fun aKeyNothingMatchesIsNoOpenDeck() =
        assertNull(DecksState().loaded(three).opened("zzz", emptyList()).open)

    @Test fun nothingOpenIsNoOpenDeck() = assertNull(DecksState().loaded(three).open)

    @Test fun theFirstMatchWinsWhenTwoDecksShareAKey() {
        val s = DecksState()
            .loaded(listOf(deck(key = "a", name = "First"), deck(key = "a", name = "Second")))
            .opened("a", emptyList())
        assertEquals("First", s.open?.name)
    }

    @Test fun theOpenDeckSurvivesAReload() {
        val s = DecksState().loaded(three).opened("b", listOf(c())).loaded(three)
        assertEquals("b", s.open?.key)
    }

    // There were ten tests here, over `DecksState.byOwner`: owners in
    // a stable order, one group each, an ownerless deck sorting
    // first. The shelf is not grouped by owner any more — the page is
    // one collection, so there was one group with its owner's name
    // over it — and the property they were about is gone with it.

    // ------------------------------------------------- the commander

    @Test fun theCommanderIsOfferedOnItsOwn() {
        val s = DecksState().opened("a", listOf(c(name = "Sol Ring"), c(name = "Alela", role = "commander")))
        assertEquals("Alela", s.commander?.name)
    }

    @Test fun aDeckWithNoCommanderOffersNone() =
        assertNull(DecksState().opened("a", listOf(c(name = "Sol Ring"))).commander)

    @Test fun thePartnerListedFirstIsTheOneOnTheBanner() {
        val s = DecksState().opened(
            "a",
            listOf(c(name = "One", role = "commander"), c(name = "Two", role = "commander")),
        )
        assertEquals("One", s.commander?.name)
    }

    @Test fun anEmptyDeckOffersNoCommander() = assertNull(DecksState().opened("a", emptyList()).commander)

    // --------------------------------------------------- total cards

    @Test fun theTotalIsCopiesNotNames() {
        val s = DecksState().opened("a", listOf(c(qty = 9, name = "A"), c(qty = 1, name = "B")))
        assertEquals(10, s.totalCards)
    }

    @Test fun anEmptyDeckHasNoCards() = assertEquals(0, DecksState().totalCards)

    @Test fun aRowWantingNoCopiesAddsNothing() =
        assertEquals(1, DecksState().opened("a", listOf(c(qty = 0), c(qty = 1, name = "B"))).totalCards)

    @Test fun theTotalCountsTheCommanderToo() {
        val s = DecksState().opened("a", listOf(c(name = "Alela", role = "commander"), c(name = "Sol Ring")))
        assertEquals(2, s.totalCards)
    }

    // ---------------------------------------------------------- gaps

    @Test fun aGapIsACardTheOwnerIsShortOf() {
        val s = DecksState().opened(
            "a",
            listOf(c(name = "Sol Ring", owned = 1), c(name = "Mana Crypt", qty = 1, owned = 0)),
        )
        assertEquals(listOf("Mana Crypt"), s.gaps.map { it.name })
    }

    @Test fun basicsAreNeverGaps() {
        val s = DecksState().opened(
            "a",
            listOf(c(name = "Island", qty = 10, owned = 0, type = null)),
        )
        assertTrue(s.gaps.isEmpty())
    }

    @Test fun gapsKeepTheOrderTheRowsArrivedIn() {
        val s = DecksState().opened(
            "a",
            listOf(
                c(name = "Zed", qty = 1, owned = 0),
                c(name = "Abe", qty = 1, owned = 0),
            ),
        )
        assertEquals(listOf("Zed", "Abe"), s.gaps.map { it.name })
    }

    @Test fun aDeckYouOwnEveryCardOfHasNoGaps() {
        val s = DecksState().opened("a", listOf(c(qty = 1, owned = 1), c(name = "B", qty = 2, owned = 4)))
        assertTrue(s.gaps.isEmpty())
    }

    @Test fun anEmptyDeckHasNoGaps() = assertTrue(DecksState().gaps.isEmpty())

    // -------------------------------------------------------- by type

    @Test fun theSectionsAreInReadingOrder() {
        val s = DecksState().opened(
            "a",
            listOf(
                land(name = "Ancient Tomb"),
                c(name = "Sol Ring", type = "Artifact"),
                c(name = "Bear"),
                c(name = "Alela", role = "commander"),
                c(name = "Mystery", type = null),
            ),
        )
        assertEquals(
            listOf(
                DeckGroup.COMMANDER, DeckGroup.CREATURES, DeckGroup.ARTIFACTS,
                DeckGroup.LANDS, DeckGroup.UNKNOWN,
            ),
            s.byType.map { it.first },
        )
    }

    @Test fun aSectionIsAlphabeticalInside() {
        val s = DecksState().opened(
            "a",
            listOf(c(name = "Zulaport Cutthroat"), c(name = "Birds of Paradise"), c(name = "Murder Hornet")),
        )
        assertEquals(
            listOf("Birds of Paradise", "Murder Hornet", "Zulaport Cutthroat"),
            s.byType.single().second.map { it.name },
        )
    }

    @Test fun alphabeticalIgnoresCase() {
        // Sorted on the raw name, every lowercase card would file
        // after every capitalised one.
        val s = DecksState().opened(
            "a",
            listOf(c(name = "cherry"), c(name = "Banana"), c(name = "apple")),
        )
        assertEquals(listOf("apple", "Banana", "cherry"), s.byType.single().second.map { it.name })
    }

    @Test fun anEmptySectionIsNotDrawnAtAll() {
        val s = DecksState().opened("a", listOf(c(name = "Sol Ring", type = "Artifact")))
        assertEquals(listOf(DeckGroup.ARTIFACTS), s.byType.map { it.first })
    }

    @Test fun aDeckOfOnlyACommanderIsOneSection() {
        val s = DecksState().opened("a", listOf(c(name = "Alela", role = "commander")))
        assertEquals(listOf(DeckGroup.COMMANDER), s.byType.map { it.first })
    }

    @Test fun anEmptyDeckHasNoSections() = assertTrue(DecksState().byType.isEmpty())

    @Test fun theUnknownsAreTheLastSection() {
        val s = DecksState().opened(
            "a",
            listOf(c(name = "Mystery", type = null), c(name = "Bear"), land(name = "Ancient Tomb")),
        )
        assertEquals(DeckGroup.UNKNOWN, s.byType.last().first)
    }

    @Test fun everyCardLandsInExactlyOneSection() {
        val cards = listOf(
            c(name = "Bear"), c(name = "Sol Ring", type = "Artifact"),
            land(name = "Ancient Tomb"), c(name = "Mystery", type = null),
            c(name = "Alela", role = "commander"),
        )
        val s = DecksState().opened("a", cards)
        assertEquals(cards.size, s.byType.sumOf { it.second.size })
    }

    @Test fun twoCardsOfTheSameTypeShareASection() {
        val s = DecksState().opened("a", listOf(c(name = "A"), c(name = "B")))
        assertEquals(1, s.byType.size)
        assertEquals(2, s.byType.single().second.size)
    }

    @Test fun theSectionIsTheGroupNotTheTypeLine() {
        // Two different type lines, one section.
        val s = DecksState().opened(
            "a",
            listOf(c(name = "A", type = "Creature — Bear"), c(name = "B", type = "Artifact Creature — Golem")),
        )
        assertEquals(listOf(DeckGroup.CREATURES), s.byType.map { it.first })
    }

    // ----------------------------------------------------- page order

    @Test fun thePageOrderIsTheSectionsFlattened() {
        val s = DecksState().opened(
            "a",
            listOf(
                c(name = "Zed"), c(name = "Sol Ring", type = "Artifact"),
                c(name = "Alela", role = "commander"), c(name = "Abe"),
            ),
        )
        assertEquals(listOf("Alela", "Abe", "Zed", "Sol Ring"), s.pageOrder.map { it.name })
        assertEquals(s.byType.flatMap { it.second }, s.pageOrder)
    }

    @Test fun thePageOrderHoldsEveryCardExactlyOnce() {
        val cards = listOf(c(name = "A"), c(name = "B", type = "Artifact"), c(name = "C", type = null))
        val s = DecksState().opened("a", cards)
        assertEquals(cards.size, s.pageOrder.size)
        assertEquals(cards.map { it.name }.sorted(), s.pageOrder.map { it.name }.sorted())
    }

    @Test fun anEmptyDeckHasNoPageOrder() = assertTrue(DecksState().pageOrder.isEmpty())

    // ---------------------------------------------------- scryfall ids

    @Test fun theIdsAreDistinct() {
        val s = DecksState().opened(
            "a",
            listOf(c(name = "a", id = "aaa"), c(name = "b", id = "bbb"), c(name = "c", id = "aaa")),
        )
        assertEquals(listOf("aaa", "bbb"), s.scryfallIds)
    }

    @Test fun aCardWithNoPrintingContributesNoId() {
        val s = DecksState().opened("a", listOf(c(name = "a", id = null), c(name = "b", id = "bbb")))
        assertEquals(listOf("bbb"), s.scryfallIds)
    }

    @Test fun theIdsKeepTheOrderTheyWereFirstSeenIn() {
        val s = DecksState().opened(
            "a",
            listOf(c(name = "a", id = "zzz"), c(name = "b", id = "aaa"), c(name = "c", id = "zzz")),
        )
        assertEquals(listOf("zzz", "aaa"), s.scryfallIds)
    }

    @Test fun anEmptyDeckAsksScryfallAboutNothing() = assertTrue(DecksState().scryfallIds.isEmpty())

    @Test fun aDeckNobodyOwnsAnythingFromAsksAboutNothing() {
        val s = DecksState().opened("a", listOf(c(name = "a", id = null), c(name = "b", id = null)))
        assertTrue(s.scryfallIds.isEmpty())
    }
}

// ======================================================= the queries

/** The SQL behind the tiles and the detail. */
class DeckQueriesSqlExhaustiveTest {

    private val all = DeckQueries.all().sql
    private val cards = DeckQueries.cards("alela").sql

    @Test fun theDeckListBindsNothing() = assertTrue(DeckQueries.all().params.isEmpty())

    @Test fun theDeckListHasNoPlaceholders() = assertEquals(0, all.count { it == '?' })

    @Test fun theDeckListSelectsEveryColumnTheTileDraws() {
        listOf("d.key", "d.name", "AS owner,", "AS owner_name", "d.commander", "d.colors", "d.bracket", "AS art_id")
            .forEach { assertTrue(it in all, "$it missing from the deck list query") }
    }

    @Test fun theDeckListReadsFromDecks() = assertTrue("FROM decks d" in all)

    @Test fun theArtJoinIsOuterSoADecklessCommanderStillShows() =
        assertTrue("LEFT JOIN" in all)

    @Test fun theArtJoinCollapsesPrintingsToOneRowPerName() {
        // Nine printings of a commander would otherwise be nine copies
        // of the deck, which is how a tile list once repeated itself.
        assertTrue("GROUP BY name_norm" in all)
        assertTrue("MIN(id)" in all)
    }

    @Test fun theArtJoinStripsTheCommandersSetAnnotation() {
        assertTrue("instr(d.commander, ' (')" in all)
        assertTrue("substr(d.commander, 1, instr(d.commander, ' (') - 1)" in all)
    }

    @Test fun theArtJoinMatchesOnTheNormalisedName() {
        assertTrue("c.name_norm = lower(trim(" in all)
    }

    @Test fun theDeckListIsOrderedByOwnerThenName() =
        assertTrue(all.trimEnd().endsWith("ORDER BY d.owner_id, d.name"))

    @Test fun theDeckListIsTheSameTextEveryTime() = assertEquals(all, DeckQueries.all().sql)

    // ----------------------------------------------------- one deck

    @Test fun oneDeckBindsItsKeyAndNothingElse() {
        assertEquals(listOf<Any?>("alela"), DeckQueries.cards("alela").params)
    }

    @Test fun oneDeckHasExactlyOnePlaceholder() = assertEquals(1, cards.count { it == '?' })

    @Test fun thePlaceholderIsTheKey() = assertTrue("WHERE d.key = ?" in cards)

    @Test fun theKeyIsBoundRatherThanInterpolated() {
        val q = DeckQueries.cards("'; DROP TABLE decks; --")
        assertEquals(listOf<Any?>("'; DROP TABLE decks; --"), q.params)
        assertFalse("DROP TABLE" in q.sql)
    }

    @Test fun anEmptyKeyIsStillBound() =
        assertEquals(listOf<Any?>(""), DeckQueries.cards("").params)

    @Test fun theTextOfTheQueryDoesNotDependOnTheKey() =
        assertEquals(DeckQueries.cards("a").sql, DeckQueries.cards("b").sql)

    @Test fun oneDeckReadsItsRowsFromDeckCards() {
        assertTrue("FROM deck_cards dc" in cards)
        assertTrue("JOIN decks d ON d.id = dc.deck_id" in cards)
    }

    @Test fun ownedCopiesComeFromTheTotalsView() {
        // `totals` is already one row per owner and name, and the
        // column is `total_qty`. `SUM(t.qty)` was neither, so opening
        // any deck answered "no such column".
        assertTrue("COALESCE((SELECT t.total_qty FROM totals t" in cards)
        assertFalse("SELECT SUM(t.qty)" in cards)
        assertTrue("AS owned" in cards)
    }

    @Test fun ownedCopiesAreScopedToTheDecksOwner() {
        assertTrue("t.owner_id = d.owner_id" in cards)
    }

    @Test fun ownedCopiesFallBackToNoneRatherThanNull() {
        assertTrue("COALESCE((SELECT t.total_qty" in cards)
    }

    @Test fun thereAreTwoPrintingJoinsOwnThenAnybodys() {
        assertTrue("GROUP BY owner_id, name_norm) mine" in cards)
        assertTrue("GROUP BY name_norm) alt" in cards)
        assertTrue("mine.name_norm = dc.name_norm AND mine.owner_id = d.owner_id" in cards)
        assertTrue("alt.name_norm = dc.name_norm" in cards)
    }

    @Test fun bothPrintingJoinsCollapseToOneRowPerName() {
        // A card with nine printings would otherwise appear nine times.
        assertEquals(2, Regex("MIN\\(id\\) AS id").findAll(cards).count())
    }

    @Test fun everyAnalysedColumnPrefersTheOwnersOwnPrinting() {
        listOf(
            "type_line", "scryfall_id", "mana_cost", "cmc",
            "produced_mana", "oracle_text", "color_identity", "rarity",
        ).forEach {
            assertTrue("COALESCE(mine.$it, alt.$it)" in cards, "$it does not prefer the owner's printing")
        }
    }

    @Test fun thePriceAlsoPrefersTheOwnersOwnPrinting() {
        assertTrue("COALESCE(pm.usd, pa.usd)" in cards)
        assertTrue("LEFT JOIN prices pm ON pm.scryfall_id = mine.scryfall_id" in cards)
        assertTrue("LEFT JOIN prices pa ON pa.scryfall_id = alt.scryfall_id" in cards)
    }

    @Test fun everyPrintingJoinIsOuter() {
        // A card the deck wants that nobody owns still has to appear.
        assertEquals(4, Regex("LEFT JOIN").findAll(cards).count())
    }

    @Test fun oneDeckIsOrderedCommanderFirstThenByName() =
        assertTrue(cards.trimEnd().endsWith("ORDER BY dc.role IS NULL, dc.role, dc.name"))

    @Test fun oneDeckSelectsTheRowsOwnColumns() {
        listOf("dc.name", "dc.name_norm", "dc.qty", "dc.role").forEach {
            assertTrue(it in cards, "$it missing")
        }
    }

    @Test fun theTwoQueriesAreDifferentStatements() = assertTrue(all != cards)
}

// ======================================================== decoding

/** Rows off the wire, including the malformed ones. */
class DeckDecodeExhaustiveTest {

    @Test fun aWholeDeckRowDecodes() {
        val d = DeckQueries.decode(
            deckCols,
            rows("""["q8ytka9m","Alela — Faeries","e7de0cb1","Alela (ELD) 324","UW",3,"abc"]"""),
        ).single()
        assertEquals("q8ytka9m", d.key)
        assertEquals("e7de0cb1", d.owner)
        assertEquals(3, d.bracket)
        assertEquals("abc", d.artId)
        assertEquals("Alela", d.title)
        assertEquals("Alela", d.commanderName)
        assertEquals("UW", d.identity)
    }

    @Test fun noRowsIsAnEmptyList() =
        assertTrue(DeckQueries.decode(deckCols, emptyList()).isEmpty())

    @Test fun everyRowBecomesADeck() =
        assertEquals(
            2,
            DeckQueries.decode(
                deckCols,
                rows("""["a","A","m",null,null,null,null]""", """["b","B","m",null,null,null,null]"""),
            ).size,
        )

    @Test fun theRowOrderIsKept() {
        val ds = DeckQueries.decode(
            deckCols,
            rows("""["z","Z","m",null,null,null,null]""", """["a","A","m",null,null,null,null]"""),
        )
        assertEquals(listOf("z", "a"), ds.map { it.key })
    }

    @Test fun aColumnThatIsNotThereIsNotAFailure() {
        val d = DeckQueries.decode(listOf("key"), rows("""["alela"]""")).single()
        assertEquals("alela", d.key)
        assertEquals("", d.name)
        assertEquals("", d.owner)
        assertNull(d.commander)
        assertNull(d.colors)
        assertNull(d.bracket)
        assertNull(d.artId)
    }

    @Test fun noColumnsAtAllIsAllDefaults() {
        val d = DeckQueries.decode(emptyList(), rows("""["alela","Alela"]""")).single()
        assertEquals("", d.key)
        assertEquals("", d.name)
    }

    @Test fun aRowShorterThanItsHeaderIsNotAnIndexCrash() {
        val d = DeckQueries.decode(deckCols, rows("""["alela","Alela"]""")).single()
        assertEquals("alela", d.key)
        assertEquals("Alela", d.name)
        assertEquals("", d.owner)
        assertNull(d.bracket)
        assertNull(d.artId)
    }

    @Test fun anEmptyRowIsAllDefaults() {
        val d = DeckQueries.decode(deckCols, rows("[]")).single()
        assertEquals("", d.key)
        assertNull(d.commander)
    }

    @Test fun nullsBecomeNullsAndTheNonNullableOnesBecomeEmpty() {
        val d = DeckQueries.decode(deckCols, rows("[null,null,null,null,null,null,null]")).single()
        assertEquals("", d.key)
        assertEquals("", d.name)
        assertEquals("", d.owner)
        assertNull(d.commander)
        assertNull(d.colors)
        assertNull(d.bracket)
        assertNull(d.artId)
    }

    @Test fun aBracketThatIsNotANumberIsNoBracket() =
        assertNull(DeckQueries.decode(deckCols, rows("""["a","A","m",null,null,"high",null]""")).single().bracket)

    @Test fun aFractionalBracketIsNoBracket() =
        assertNull(DeckQueries.decode(deckCols, rows("""["a","A","m",null,null,3.5,null]""")).single().bracket)

    @Test fun aBracketArrivingAsTextStillReads() =
        assertEquals(4, DeckQueries.decode(deckCols, rows("""["a","A","m",null,null,"4",null]""")).single().bracket)

    @Test fun anEmptyBracketIsNoBracket() =
        assertNull(DeckQueries.decode(deckCols, rows("""["a","A","m",null,null,"",null]""")).single().bracket)

    @Test fun aNegativeBracketIsReadAsGiven() =
        assertEquals(-1, DeckQueries.decode(deckCols, rows("""["a","A","m",null,null,-1,null]""")).single().bracket)

    @Test fun aNestedObjectWhereAStringBelongsIsNotAString() {
        val d = DeckQueries.decode(deckCols, rows("""["a","A","m",{"x":1},null,null,null]""")).single()
        assertNull(d.commander)
    }

    @Test fun aNestedArrayWhereAStringBelongsIsNotAString() {
        val d = DeckQueries.decode(deckCols, rows("""["a","A","m",["x"],null,null,null]""")).single()
        assertNull(d.commander)
    }

    @Test fun aBooleanReadsAsItsText() =
        assertEquals("true", DeckQueries.decode(deckCols, rows("""["a","A","m",true,null,null,null]""")).single().commander)

    @Test fun columnsBeyondTheHeaderAreIgnored() {
        val d = DeckQueries.decode(listOf("key", "name"), rows("""["a","A","m","x","y",9,"z"]""")).single()
        assertEquals("a", d.key)
        assertEquals("A", d.name)
        assertNull(d.commander)
    }

    @Test fun aRepeatedColumnNameTakesTheLastOne() {
        val d = DeckQueries.decode(listOf("key", "key"), rows("""["first","second"]""")).single()
        assertEquals("second", d.key)
    }

    @Test fun theColumnOrderIsWhatTheHeaderSays() {
        val d = DeckQueries.decode(
            listOf("owner_name", "owner", "key", "name"),
            rows("""["Matt Shoemaker","e7de0cb1","q8ytka9m","Alela"]"""),
        ).single()
        assertEquals("q8ytka9m", d.key)
        assertEquals("e7de0cb1", d.owner)
        assertEquals("Matt Shoemaker", d.ownerName)
    }

    // ------------------------------------------------------- the cards

    private val cardCols = listOf(
        "name", "name_norm", "qty", "role", "owned", "type_line", "scryfall_id",
        "mana_cost", "cmc", "produced_mana", "oracle_text", "color_identity", "rarity", "price",
    )

    private fun oneCard(json: String) = DeckQueries.decodeCards(cardCols, rows(json)).single()

    @Test fun aWholeCardRowDecodes() {
        val card = oneCard(
            """["Sol Ring","sol ring",1,"main",2,"Artifact","abc123","{1}",1,"C","{T}: Add {C}{C}.","","uncommon",1.75]""",
        )
        assertEquals("Sol Ring", card.name)
        assertEquals("sol ring", card.nameNorm)
        assertEquals(1, card.qty)
        assertEquals("main", card.role)
        assertEquals(2, card.owned)
        assertEquals("Artifact", card.typeLine)
        assertEquals("abc123", card.scryfallId)
        assertEquals("{1}", card.manaCost)
        assertEquals(1.0, card.cmc)
        assertEquals("C", card.producedMana)
        assertEquals("", card.colorIdentity)
        assertEquals("uncommon", card.rarity)
        assertEquals(1.75, card.price)
        assertEquals(DeckGroup.ARTIFACTS, card.group)
        assertEquals(0, card.short)
    }

    @Test fun noCardRowsIsAnEmptyList() =
        assertTrue(DeckQueries.decodeCards(cardCols, emptyList()).isEmpty())

    @Test fun theCardRowOrderIsKept() {
        val cs = DeckQueries.decodeCards(
            listOf("name"),
            rows("""["Zed"]""", """["Abe"]""", """["Mid"]"""),
        )
        assertEquals(listOf("Zed", "Abe", "Mid"), cs.map { it.name })
    }

    @Test fun aMissingQuantityIsNoneRatherThanACrash() =
        assertEquals(0, DeckQueries.decodeCards(listOf("name"), rows("""["Sol Ring"]""")).single().qty)

    @Test fun aNullQuantityIsNone() = assertEquals(0, oneCard("""["A","a",null,null,0,null,null,null,null,null,null,null,null,null]""").qty)

    @Test fun aQuantityThatIsNotANumberIsNone() =
        assertEquals(0, oneCard("""["A","a","lots",null,0,null,null,null,null,null,null,null,null,null]""").qty)

    @Test fun aFractionalQuantityIsNone() =
        assertEquals(0, oneCard("""["A","a",1.5,null,0,null,null,null,null,null,null,null,null,null]""").qty)

    @Test fun aQuantityArrivingAsTextStillReads() =
        assertEquals(9, oneCard("""["A","a","9",null,0,null,null,null,null,null,null,null,null,null]""").qty)

    @Test fun aNullOwnedCountIsNone() =
        assertEquals(0, oneCard("""["A","a",1,null,null,null,null,null,null,null,null,null,null,null]""").owned)

    @Test fun anOwnedCountThatIsNotANumberIsNone() =
        assertEquals(0, oneCard("""["A","a",1,null,"some",null,null,null,null,null,null,null,null,null]""").owned)

    @Test fun aManaValueArrivingAsAWholeNumberIsStillADouble() =
        assertEquals(3.0, oneCard("""["A","a",1,null,1,"Creature",null,"{3}",3,null,null,null,null,null]""").cmc)

    @Test fun aFractionalManaValueIsKept() =
        assertEquals(2.5, oneCard("""["A","a",1,null,1,"Creature",null,null,2.5,null,null,null,null,null]""").cmc)

    @Test fun aManaValueThatIsNotANumberIsUnknown() =
        assertNull(oneCard("""["A","a",1,null,1,"Creature",null,null,"two",null,null,null,null,null]""").cmc)

    @Test fun aMissingManaValueIsUnknown() =
        assertNull(DeckQueries.decodeCards(listOf("name"), rows("""["A"]""")).single().cmc)

    @Test fun aPriceDecodes() =
        assertEquals(12.34, oneCard("""["A","a",1,null,1,null,null,null,null,null,null,null,null,12.34]""").price)

    @Test fun aPriceArrivingAsTextStillReads() =
        assertEquals(1.5, oneCard("""["A","a",1,null,1,null,null,null,null,null,null,null,null,"1.50"]""").price)

    @Test fun aPriceThatIsNotANumberIsUnpriced() =
        assertNull(oneCard("""["A","a",1,null,1,null,null,null,null,null,null,null,null,"n/a"]""").price)

    @Test fun aNullPriceIsUnpriced() =
        assertNull(oneCard("""["A","a",1,null,1,null,null,null,null,null,null,null,null,null]""").price)

    @Test fun aPriceOfNoughtIsAPrice() =
        assertEquals(0.0, oneCard("""["A","a",1,null,1,null,null,null,null,null,null,null,null,0]""").price)

    @Test fun aMissingNameIsEmptyRatherThanNull() =
        assertEquals("", DeckQueries.decodeCards(listOf("qty"), rows("[1]")).single().name)

    @Test fun aMissingNormalisedNameIsEmpty() =
        assertEquals("", DeckQueries.decodeCards(listOf("name"), rows("""["Sol Ring"]""")).single().nameNorm)

    @Test fun aNullRoleIsNoRole() {
        val card = oneCard("""["A","a",1,null,1,"Creature",null,null,null,null,null,null,null,null]""")
        assertNull(card.role)
        assertFalse(card.isCommander)
    }

    @Test fun theCommanderRoleDecodes() {
        val card = oneCard("""["A","a",1,"commander",1,"Creature",null,null,null,null,null,null,null,null]""")
        assertTrue(card.isCommander)
        assertEquals(DeckGroup.COMMANDER, card.group)
    }

    @Test fun aCardRowShorterThanItsHeaderIsNotAnIndexCrash() {
        val card = DeckQueries.decodeCards(cardCols, rows("""["Sol Ring","sol ring"]""")).single()
        assertEquals("Sol Ring", card.name)
        assertEquals("sol ring", card.nameNorm)
        assertEquals(0, card.qty)
        assertEquals(0, card.owned)
        assertNull(card.typeLine)
        assertNull(card.price)
        assertEquals(DeckGroup.UNKNOWN, card.group)
    }

    @Test fun anEmptyCardRowIsAllDefaults() {
        val card = DeckQueries.decodeCards(cardCols, rows("[]")).single()
        assertEquals("", card.name)
        assertEquals(0, card.qty)
        assertEquals(DeckGroup.UNKNOWN, card.group)
    }

    @Test fun everyColumnMissingIsEveryDefault() {
        val card = DeckQueries.decodeCards(emptyList(), rows("""["Sol Ring",1]""")).single()
        assertEquals("", card.name)
        assertEquals(0, card.qty)
        assertNull(card.cmc)
    }

    @Test fun aNestedValueWhereAStringBelongsIsNotAString() =
        assertNull(oneCard("""["A","a",1,null,1,{"x":1},null,null,null,null,null,null,null,null]""").typeLine)

    @Test fun unknownColumnsAreIgnored() {
        val card = DeckQueries.decodeCards(
            listOf("name", "what", "qty"),
            rows("""["Sol Ring","nonsense",2]"""),
        ).single()
        assertEquals("Sol Ring", card.name)
        assertEquals(2, card.qty)
    }

    @Test fun aBasicNobodyOwnsDecodesIntoALand() {
        // The shape every deck list actually arrives in: a name, a
        // count, and nothing else.
        val card = DeckQueries.decodeCards(
            cardCols,
            rows("""["Plains","plains",9,null,0,null,null,null,null,null,null,null,null,null]"""),
        ).single()
        assertEquals(DeckGroup.LANDS, card.group)
        assertEquals("W", card.knownProducedMana)
        assertEquals(0.0, card.knownManaValue)
        assertEquals(0, card.short)
    }

    @Test fun aCardNobodyOwnsDecodesIntoAnUnknown() {
        val card = DeckQueries.decodeCards(
            cardCols,
            rows("""["Mana Crypt","mana crypt",1,null,0,null,null,null,null,null,null,null,null,null]"""),
        ).single()
        assertEquals(DeckGroup.UNKNOWN, card.group)
        assertEquals(1, card.short)
        assertNull(card.art)
    }

    @Test fun aDecodedDeckListAnalysesWithoutThrowing() {
        val cs = DeckQueries.decodeCards(
            cardCols,
            rows(
                """["Alela","alela",1,"commander",1,"Legendary Creature — Faerie","aa11","{1}{W}{U}{B}",3,null,null,"WUB","rare",2.5]""",
                """["Plains","plains",9,null,0,null,null,null,null,null,null,null,null,null]""",
                """["Mystery","mystery",1,null,0,null,null,null,null,null,null,null,null,null]""",
            ),
        )
        val s = DeckAnalysis.of(cs)
        assertEquals(11, s.totalCards)
        assertEquals(9, s.lands)
        assertEquals(1, s.unknown)
    }
}

// ========================================================== the curve

private fun bar(bars: List<Bar>, label: String) = bars.firstOrNull { it.label == label }?.value ?: 0

/** The mana curve, and the two averages beside it. */
class DeckCurveExhaustiveTest {

    private fun curveOf(vararg cards: DeckCard) = DeckAnalysis.of(cards.toList()).curve

    @Test fun sevenIsWhereTheCurveStops() = assertEquals(7, DeckAnalysis.CURVE_TOP)

    @Test fun thereIsAColumnForEveryManaValueUpToTheTop() {
        assertEquals(8, curveOf(spell(2.0)).size)
    }

    @Test fun theColumnsAreLabelledNoughtToSevenPlus() {
        assertEquals(
            listOf("0", "1", "2", "3", "4", "5", "6", "7+"),
            curveOf(spell(2.0)).map { it.label },
        )
    }

    @Test fun everyColumnSaysWhatItIs() {
        assertEquals("mana value 0", curveOf(spell(1.0)).first().note)
        assertEquals("mana value 7", curveOf(spell(1.0)).last().note)
    }

    @Test fun aOneDropIsInTheOneColumn() = assertEquals(1, bar(curveOf(spell(1.0)), "1"))
    @Test fun aNoughtDropIsInTheNoughtColumn() = assertEquals(1, bar(curveOf(spell(0.0)), "0"))
    @Test fun aSixDropIsInTheSixColumn() = assertEquals(1, bar(curveOf(spell(6.0)), "6"))

    @Test fun aSevenDropIsAtTheTop() = assertEquals(1, bar(curveOf(spell(7.0)), "7+"))

    @Test fun everythingAboveSevenPilesIntoTheTop() {
        // A seven-drop and a ten are the same problem.
        val cv = curveOf(spell(7.0), spell(8.0), spell(9.0), spell(20.0))
        assertEquals(4, bar(cv, "7+"))
    }

    @Test fun eachColumnHoldsOnlyItsOwn() {
        val cv = curveOf(spell(1.0), spell(2.0, qty = 2), spell(5.0))
        assertEquals(0, bar(cv, "0"))
        assertEquals(1, bar(cv, "1"))
        assertEquals(2, bar(cv, "2"))
        assertEquals(0, bar(cv, "3"))
        assertEquals(1, bar(cv, "5"))
    }

    @Test fun theCurveCountsCopiesNotNames() =
        assertEquals(4, bar(curveOf(spell(2.0, qty = 4)), "2"))

    @Test fun aFractionalManaValueFallsIntoTheColumnBelow() {
        // Truncated rather than rounded: a 2.5 is cast off two lands
        // and a half, which is to say three, but it is not a 3-drop.
        assertEquals(1, bar(curveOf(spell(2.0).copy(cmc = 2.9)), "2"))
    }

    @Test fun theLandsAreLeftOut() {
        val cv = curveOf(land(name = "Ancient Tomb", qty = 10), spell(2.0, qty = 4))
        assertEquals(0, bar(cv, "0"))
        assertEquals(4, bar(cv, "2"))
    }

    @Test fun theBasicsAreLeftOutToo() {
        val cv = curveOf(c(name = "Plains", qty = 9, owned = 0, type = null), spell(1.0))
        assertEquals(0, bar(cv, "0"))
        assertEquals(1, bar(cv, "1"))
    }

    @Test fun aCardNobodyOwnsIsNotInTheCurveAtAll() {
        val cv = curveOf(c(name = "Mystery", type = null, owned = 0), spell(3.0))
        assertEquals(0, bar(cv, "0"))
        assertEquals(1, bar(cv, "3"))
    }

    @Test fun theCommanderIsInTheCurve() {
        // It is a spell the deck casts, and the one it casts most.
        assertEquals(1, bar(curveOf(spell(4.0).copy(role = "commander")), "4"))
    }

    @Test fun aKnownCardWithNoPrintedManaValueFallsBackToItsCost() {
        // The row has a type line, so something is known about it; the
        // cost is right there and reading it beats calling it a
        // nought-drop.
        val odd = c(name = "Odd", type = "Creature — Human", cmc = null, cost = "{3}{W}")
        assertEquals(1, bar(curveOf(odd), "4"))
    }

    @Test fun aKnownCardWithNeitherIsANoughtDrop() =
        assertEquals(1, bar(curveOf(c(name = "Free", type = "Creature")), "0"))

    @Test fun anEmptyDeckHasAnEmptyCurve() {
        val cv = DeckAnalysis.of(emptyList()).curve
        assertEquals(8, cv.size)
        assertTrue(cv.all { it.value == 0 })
    }

    @Test fun anEmptyDeckHasNoCurveToDraw() = assertFalse(DeckAnalysis.of(emptyList()).hasCurve)

    @Test fun aDeckOfNothingButLandsHasNoCurveToDraw() =
        assertFalse(DeckAnalysis.of(listOf(land(qty = 40))).hasCurve)

    @Test fun oneSpellIsEnoughOfACurveToDraw() =
        assertTrue(DeckAnalysis.of(listOf(spell(1.0))).hasCurve)

    @Test fun aDeckOfOneCardHasACurveOfOne() {
        val s = DeckAnalysis.of(listOf(spell(3.0)))
        assertEquals(1, s.curve.sumOf { it.value })
        assertEquals(1, bar(s.curve, "3"))
    }

    @Test fun theCurveAddsUpToTheSpellsItCounted() {
        val s = DeckAnalysis.of(
            listOf(spell(1.0, qty = 3), spell(5.0, qty = 2), land(qty = 10), c(name = "Mystery", type = null)),
        )
        assertEquals(5, s.curve.sumOf { it.value })
    }

    // ------------------------------------------------------- average

    @Test fun theAverageIsOverTheSpells() =
        assertEquals(2.0, DeckAnalysis.of(listOf(spell(1.0), spell(3.0))).averageManaValue)

    @Test fun theAverageIgnoresTheLands() {
        val s = DeckAnalysis.of(listOf(land(qty = 37), spell(2.0)))
        assertEquals(2.0, s.averageManaValue)
    }

    @Test fun theAverageIgnoresTheUnknowns() {
        val s = DeckAnalysis.of(listOf(c(name = "Mystery", type = null, owned = 0), spell(4.0)))
        assertEquals(4.0, s.averageManaValue)
    }

    @Test fun theAverageWeightsByCopies() {
        // Four one-drops and one five-drop averages 1.8, not 3.
        val s = DeckAnalysis.of(listOf(spell(1.0, qty = 4), spell(5.0)))
        assertEquals(1.8, s.averageManaValue)
    }

    @Test fun theAverageIsRoundedToTwoPlaces() =
        assertEquals(1.33, DeckAnalysis.of(listOf(spell(1.0, qty = 2), spell(2.0))).averageManaValue)

    @Test fun theAverageOfOneCardIsThatCard() =
        assertEquals(3.0, DeckAnalysis.of(listOf(spell(3.0))).averageManaValue)

    @Test fun theAverageOfNothingIsNought() =
        assertEquals(0.0, DeckAnalysis.of(emptyList()).averageManaValue)

    @Test fun theAverageOfALandOnlyDeckIsNought() =
        assertEquals(0.0, DeckAnalysis.of(listOf(land(qty = 40))).averageManaValue)

    @Test fun theAverageOfADeckOfUnknownsIsNought() =
        assertEquals(0.0, DeckAnalysis.of(listOf(c(name = "Mystery", type = null))).averageManaValue)

    @Test fun theAverageReadsThePrintedCostWhenTheManaValueIsMissing() {
        // The curve already does. Counting it as nought here made the
        // two charts disagree about the same card.
        val odd = c(name = "Odd", type = "Creature — Human", cmc = null, cost = "{3}{W}")
        assertEquals(4.0, DeckAnalysis.of(listOf(odd)).averageManaValue)
    }

    @Test fun theAverageIncludesTheCommander() =
        assertEquals(3.0, DeckAnalysis.of(listOf(spell(3.0).copy(role = "commander"))).averageManaValue)

    @Test fun theAverageDoesNotFoldTheBigOnes() {
        // The curve puts a twelve-drop in the 7+ column. The average
        // still has to know it was a twelve.
        assertEquals(12.0, DeckAnalysis.of(listOf(spell(12.0))).averageManaValue)
    }

    // -------------------------------------------------------- median

    @Test fun theMedianOfAnOddNumberIsTheMiddleOne() =
        assertEquals(3.0, DeckAnalysis.of(listOf(spell(1.0), spell(3.0), spell(8.0))).medianManaValue)

    @Test fun theMedianOfAnEvenNumberIsBetweenTheMiddleTwo() =
        assertEquals(2.5, DeckAnalysis.of(listOf(spell(1.0), spell(4.0))).medianManaValue)

    @Test fun theMedianOfOneCardIsThatCard() =
        assertEquals(5.0, DeckAnalysis.of(listOf(spell(5.0))).medianManaValue)

    @Test fun theMedianOfNothingIsNought() =
        assertEquals(0.0, DeckAnalysis.of(emptyList()).medianManaValue)

    @Test fun theMedianOfALandOnlyDeckIsNought() =
        assertEquals(0.0, DeckAnalysis.of(listOf(land(qty = 40))).medianManaValue)

    @Test fun theMedianCountsCopies() {
        // Nine one-drops and one eight-drop has a median of one.
        assertEquals(1.0, DeckAnalysis.of(listOf(spell(1.0, qty = 9), spell(8.0))).medianManaValue)
    }

    @Test fun theMedianOfIdenticalCardsIsThatValue() =
        assertEquals(2.0, DeckAnalysis.of(listOf(spell(2.0, qty = 7))).medianManaValue)

    @Test fun theMedianIgnoresTheLandsAndTheUnknowns() {
        val s = DeckAnalysis.of(
            listOf(land(qty = 30), c(name = "Mystery", type = null), spell(2.0), spell(4.0)),
        )
        assertEquals(3.0, s.medianManaValue)
    }

    @Test fun theMedianReadsThePrintedCostWhenTheManaValueIsMissing() {
        val odd = c(name = "Odd", type = "Creature — Human", cmc = null, cost = "{2}{W}{W}")
        assertEquals(4.0, DeckAnalysis.of(listOf(odd)).medianManaValue)
    }

    @Test fun theMedianDoesNotFoldTheBigOnes() =
        assertEquals(12.0, DeckAnalysis.of(listOf(spell(12.0))).medianManaValue)

    @Test fun theMedianIsNotTheAverage() {
        // Nine one-drops and one twenty-drop: average 2.9, median 1.
        val s = DeckAnalysis.of(listOf(spell(1.0, qty = 9), spell(20.0)))
        assertEquals(2.9, s.averageManaValue)
        assertEquals(1.0, s.medianManaValue)
    }
}

// ========================================================== colour

/** What the deck asks for, what it makes, and the gap between them. */
class DeckColourExhaustiveTest {

    private fun pipsOf(vararg cards: DeckCard) =
        DeckAnalysis.of(cards.toList()).pips.associate { it.label to it.value }

    private fun sourcesOf(vararg cards: DeckCard) =
        DeckAnalysis.of(cards.toList()).sources.associate { it.label to it.value }

    // ---------------------------------------------- what it asks for

    @Test fun oneWhitePip() = assertEquals(1, pipsOf(c(cost = "{W}"))["White"])

    @Test fun eachColourIsCountedSeparately() {
        val p = pipsOf(c(cost = "{W}{U}{B}{R}{G}"))
        assertEquals(1, p["White"])
        assertEquals(1, p["Blue"])
        assertEquals(1, p["Black"])
        assertEquals(1, p["Red"])
        assertEquals(1, p["Green"])
    }

    @Test fun twoPipsOfOneColourAreTwo() = assertEquals(2, pipsOf(c(cost = "{W}{W}"))["White"])

    @Test fun pipsAreCountedOncePerCopy() =
        assertEquals(8, pipsOf(c(cost = "{W}{W}", qty = 4))["White"])

    @Test fun genericManaIsNotAColourRequirement() =
        assertNull(pipsOf(c(cost = "{3}"))["White"])

    @Test fun aVariableCostIsNotAColourRequirement() =
        assertTrue(DeckAnalysis.of(listOf(c(cost = "{X}{X}"))).pips.isEmpty())

    @Test fun snowManaIsNotAColourRequirement() =
        assertTrue(DeckAnalysis.of(listOf(c(cost = "{S}{S}"))).pips.isEmpty())

    @Test fun colourlessManaIsARequirement() = assertEquals(2, pipsOf(c(cost = "{C}{C}"))["Colourless"])

    @Test fun aHybridAsksForBothHalves() {
        val p = pipsOf(c(cost = "{W/U}"))
        assertEquals(1, p["White"])
        assertEquals(1, p["Blue"])
    }

    @Test fun phyrexianAsksForItsColourOnly() {
        val p = pipsOf(c(cost = "{G/P}"))
        assertEquals(1, p["Green"])
        assertEquals(1, p.size)
    }

    @Test fun aTwobridAsksForItsColourOnly() {
        val p = pipsOf(c(cost = "{2/R}"))
        assertEquals(1, p["Red"])
        assertEquals(1, p.size)
    }

    @Test fun aCostOfNothingAsksForNothing() =
        assertTrue(DeckAnalysis.of(listOf(c(cost = null))).pips.isEmpty())

    @Test fun anEmptyCostAsksForNothing() =
        assertTrue(DeckAnalysis.of(listOf(c(cost = ""))).pips.isEmpty())

    @Test fun aLandAsksForNothing() =
        assertTrue(DeckAnalysis.of(listOf(land(qty = 40))).pips.isEmpty())

    @Test fun aCardNobodyOwnsAsksForNothing() =
        assertTrue(DeckAnalysis.of(listOf(c(name = "Mystery", type = null, cost = null))).pips.isEmpty())

    @Test fun theCommandersOwnCostCounts() =
        assertEquals(1, pipsOf(c(cost = "{1}{B}", role = "commander"))["Black"])

    @Test fun thePipsAreInWubrgOrder() {
        val labels = DeckAnalysis.of(listOf(c(cost = "{G}{R}{B}{U}{W}{C}"))).pips.map { it.label }
        assertEquals(listOf("White", "Blue", "Black", "Red", "Green", "Colourless"), labels)
    }

    @Test fun aColourNothingAsksForIsNotAPipBar() {
        val labels = DeckAnalysis.of(listOf(c(cost = "{W}"))).pips.map { it.label }
        assertEquals(listOf("White"), labels)
    }

    @Test fun eachPipBarCarriesItsLetter() {
        val white = DeckAnalysis.of(listOf(c(cost = "{W}"))).pips.single()
        assertEquals("W", white.note)
    }

    @Test fun anEmptyDeckAsksForNothing() = assertTrue(DeckAnalysis.of(emptyList()).pips.isEmpty())

    // ------------------------------------------------- what it makes

    @Test fun aLandIsASourceOfWhatItTapsFor() =
        assertEquals(1, sourcesOf(land(produces = "W"))["White"])

    @Test fun aBasicNobodyOwnsIsStillASource() =
        assertEquals(9, sourcesOf(c(name = "Forest", qty = 9, owned = 0, type = null))["Green"])

    @Test fun aDualIsASourceOfEachOfItsColours() {
        val m = sourcesOf(land(produces = "WU"))
        assertEquals(1, m["White"])
        assertEquals(1, m["Blue"])
    }

    @Test fun aLandMakingTheSameColourTwiceIsOneSourceOfIt() =
        assertEquals(1, sourcesOf(land(produces = "WW"))["White"])

    @Test fun sourcesAreCountedOncePerCopy() =
        assertEquals(9, sourcesOf(land(produces = "R", qty = 9))["Red"])

    @Test fun aRockIsASourceJustLikeALand() =
        assertEquals(1, sourcesOf(c(name = "Sol Ring", type = "Artifact", produces = "C"))["Colourless"])

    @Test fun aCreatureThatTapsForManaIsASource() =
        assertEquals(1, sourcesOf(c(name = "Birds", type = "Creature — Bird", produces = "WUBRG"))["Green"])

    @Test fun lowercaseProducedManaStillReads() =
        assertEquals(1, sourcesOf(land(produces = "wu"))["White"])

    @Test fun punctuationInProducedManaIsIgnored() {
        val m = sourcesOf(land(produces = "W, U"))
        assertEquals(1, m["White"])
        assertEquals(1, m["Blue"])
        assertEquals(2, m.size)
    }

    @Test fun aLandThatMakesNothingIsNoSource() =
        assertTrue(DeckAnalysis.of(listOf(land(produces = null))).sources.isEmpty())

    @Test fun anEmptyProducedManaIsNoSource() =
        assertTrue(DeckAnalysis.of(listOf(land(produces = ""))).sources.isEmpty())

    @Test fun theSourcesAreInWubrgOrder() {
        val labels = DeckAnalysis.of(listOf(land(produces = "GRBUWC"))).sources.map { it.label }
        assertEquals(listOf("White", "Blue", "Black", "Red", "Green", "Colourless"), labels)
    }

    @Test fun eachSourceBarCarriesItsLetter() =
        assertEquals("U", DeckAnalysis.of(listOf(land(produces = "U"))).sources.single().note)

    @Test fun anEmptyDeckMakesNothing() = assertTrue(DeckAnalysis.of(emptyList()).sources.isEmpty())

    @Test fun aColourNothingMakesIsNotASourceBar() {
        val labels = DeckAnalysis.of(listOf(land(produces = "W"))).sources.map { it.label }
        assertEquals(listOf("White"), labels)
    }

    // ------------------------------------- mana the deck cannot spend

    private val mono = c(name = "Tinybones", type = "Legendary Creature", role = "commander", ci = "B")

    @Test fun aColourTheCommanderAllowsIsItsOwnSource() =
        assertEquals(1, sourcesOf(mono, land(produces = "B"))["Black"])

    @Test fun aColourTheCommanderForbidsBecomesColourless() {
        val m = sourcesOf(mono, land(produces = "R"))
        assertNull(m["Red"])
        assertEquals(1, m["Colourless"])
    }

    @Test fun severalForbiddenColoursOnOneCardAreOneColourlessSource() =
        assertEquals(1, sourcesOf(mono, land(produces = "WURG"))["Colourless"])

    @Test fun aForbiddenAndAnAllowedColourOnOneCardAreOneOfEach() {
        val m = sourcesOf(mono, land(produces = "BR"))
        assertEquals(1, m["Black"])
        assertEquals(1, m["Colourless"])
    }

    @Test fun realColourlessIsNeverFolded() =
        assertEquals(1, sourcesOf(mono, land(produces = "C"))["Colourless"])

    @Test fun foldedAndRealColourlessAddUp() =
        assertEquals(2, sourcesOf(mono, land(produces = "C"), land(name = "Other Land", produces = "G"))["Colourless"])

    @Test fun theFoldingCountsCopies() =
        assertEquals(4, sourcesOf(mono, land(produces = "R", qty = 4))["Colourless"])

    @Test fun aColourlessCommanderFoldsEverything() {
        // Kozilek can spend nothing coloured, so nothing coloured in
        // the deck is a source of anything but generic mana.
        val eldrazi = c(name = "Kozilek", type = "Legendary Creature", role = "commander", ci = "")
        val m = sourcesOf(eldrazi, land(produces = "W"), land(name = "Other Land", produces = "U"))
        assertEquals(2, m["Colourless"])
        assertEquals(1, m.size)
    }

    @Test fun aCommanderNobodyOwnsAPrintingOfFoldsNothing() {
        // No printing means no colour identity, which says nothing
        // about what the deck can spend. Treated as "spends nothing",
        // every land in the deck became a colourless source and every
        // colour the deck plays was reported unsupported.
        val unknownLeader = c(name = "Mystery Commander", type = null, role = "commander", ci = null)
        val m = sourcesOf(unknownLeader, land(produces = "W"), land(name = "Other Land", produces = "U"))
        assertEquals(1, m["White"])
        assertEquals(1, m["Blue"])
        assertNull(m["Colourless"])
    }

    @Test fun onePartnerWithAPrintingIsEnoughToSayWhatIsSpendable() {
        val known = c(name = "Partner One", type = "Legendary Creature", role = "commander", ci = "W")
        val unknown = c(name = "Partner Two", type = null, role = "commander", ci = null)
        val m = sourcesOf(known, unknown, land(produces = "W"), land(name = "Other Land", produces = "U"))
        assertEquals(1, m["White"])
        assertNull(m["Blue"])
        assertEquals(1, m["Colourless"])
    }

    @Test fun twoCommandersAllowTheirColoursBetweenThem() {
        val m = sourcesOf(
            c(name = "Partner One", type = "Legendary Creature", role = "commander", ci = "W"),
            c(name = "Partner Two", type = "Legendary Creature", role = "commander", ci = "U"),
            land(produces = "WU"),
        )
        assertEquals(1, m["White"])
        assertEquals(1, m["Blue"])
    }

    @Test fun noCommanderFoldsNothing() {
        val m = sourcesOf(land(produces = "W"), land(name = "Other Land", produces = "R"))
        assertEquals(1, m["White"])
        assertEquals(1, m["Red"])
        assertNull(m["Colourless"])
    }

    @Test fun theFoldingOnlyTouchesWhatTheDeckMakes() {
        val needs = pipsOf(mono, c(name = "Oddity", cost = "{R}", ci = "R"))
        assertEquals(1, needs["Red"], "a red cost is still a red cost")
    }

    // -------------------------------------------- the colours it cannot cast

    @Test fun aColourAskedForWithNoSourceIsCalledOut() {
        val s = DeckAnalysis.of(listOf(land(produces = "W"), c(cost = "{U}{U}")))
        assertEquals(listOf("Blue"), s.unsupported)
    }

    @Test fun aColourWithBothIsNotCalledOut() {
        val s = DeckAnalysis.of(listOf(land(produces = "U"), c(cost = "{U}{U}")))
        assertTrue(s.unsupported.isEmpty())
    }

    @Test fun severalUnsupportedColoursComeOutInWubrgOrder() {
        val s = DeckAnalysis.of(listOf(c(cost = "{G}{W}{U}")))
        assertEquals(listOf("White", "Blue", "Green"), s.unsupported)
    }

    @Test fun aColourMadeButNotAskedForIsNotUnsupported() {
        val s = DeckAnalysis.of(listOf(land(produces = "WU"), c(cost = "{W}")))
        assertTrue(s.unsupported.isEmpty())
    }

    @Test fun colourlessIsNeverUnsupported() {
        // There is no such thing as a deck that cannot make generic
        // mana, and `{C}` is not a colour to splash.
        val s = DeckAnalysis.of(listOf(c(cost = "{C}{C}")))
        assertTrue(s.unsupported.isEmpty())
    }

    @Test fun aDeckThatAsksForNothingHasNothingUnsupported() =
        assertTrue(DeckAnalysis.of(listOf(land(produces = "W"))).unsupported.isEmpty())

    @Test fun anEmptyDeckHasNothingUnsupported() =
        assertTrue(DeckAnalysis.of(emptyList()).unsupported.isEmpty())

    @Test fun aSplashBehindACommandersIdentityIsCalledOut() {
        // The cost is red, the only red source is folded away, so the
        // card sits in hand — which is exactly the pairing worth
        // saying out loud.
        val s = DeckAnalysis.of(listOf(mono, land(produces = "R"), c(name = "Oddity", cost = "{R}")))
        assertEquals(listOf("Red"), s.unsupported)
    }

    // ------------------------------------------------- deck identity

    @Test fun theIdentityIsEveryColourOnEveryCard() =
        assertEquals("WU", DeckAnalysis.of(listOf(c(ci = "W"), c(name = "B", ci = "U"))).identity)

    @Test fun theIdentityIsInWubrgOrder() =
        assertEquals("WUBRG", DeckAnalysis.of(listOf(c(ci = "GRBUW"))).identity)

    @Test fun theIdentityIsDeduped() =
        assertEquals("W", DeckAnalysis.of(listOf(c(ci = "W"), c(name = "B", ci = "W"))).identity)

    @Test fun aCardWithNoIdentityAddsNothing() =
        assertEquals("U", DeckAnalysis.of(listOf(c(ci = null), c(name = "B", ci = "U"))).identity)

    @Test fun anEmptyIdentityAddsNothing() =
        assertEquals("U", DeckAnalysis.of(listOf(c(ci = ""), c(name = "B", ci = "U"))).identity)

    @Test fun aColourlessDeckHasNoIdentity() =
        assertEquals("", DeckAnalysis.of(listOf(c(ci = ""), c(name = "B", ci = null))).identity)

    @Test fun anEmptyDeckHasNoIdentity() = assertEquals("", DeckAnalysis.of(emptyList()).identity)

    @Test fun aLetterThatIsNotAColourIsNotAnIdentity() =
        assertEquals("", DeckAnalysis.of(listOf(c(ci = "C"))).identity)

    @Test fun aFiveColourDeckSaysSo() =
        assertEquals("WUBRG", DeckAnalysis.of(listOf(c(ci = "W"), c(name = "b", ci = "UB"), c(name = "c", ci = "RG"))).identity)
}

// =========================================================== shape

/** The counts along the top of the panel. */
class DeckShapeExhaustiveTest {

    private fun typesOf(vararg cards: DeckCard) = DeckAnalysis.of(cards.toList()).types
    private fun raritiesOf(vararg cards: DeckCard) = DeckAnalysis.of(cards.toList()).rarities

    // -------------------------------------------------------- types

    @Test fun theTypesAreCountedByCopies() =
        assertEquals(3, bar(typesOf(c(qty = 3)), "Creatures"))

    @Test fun theTypesAreInReadingOrder() {
        val labels = typesOf(
            land(name = "Ancient Tomb"),
            c(name = "Sol Ring", type = "Artifact"),
            c(name = "Bear"),
            c(name = "Alela", role = "commander"),
            c(name = "Mystery", type = null),
        ).map { it.label }
        assertEquals(
            listOf("Commander", "Creatures", "Artifacts", "Lands", "Not in the collection"),
            labels,
        )
    }

    @Test fun aTypeNothingFallsUnderIsNotABar() {
        assertEquals(listOf("Creatures"), typesOf(c()).map { it.label })
    }

    @Test fun theUnknownsAreCountedAsTheirOwnType() =
        assertEquals(4, bar(typesOf(c(name = "Mystery", type = null, qty = 4)), "Not in the collection"))

    @Test fun everyTypeBarAddsUpToTheDeck() {
        val s = DeckAnalysis.of(
            listOf(c(qty = 3), land(qty = 10), c(name = "Mystery", type = null, qty = 2)),
        )
        assertEquals(s.totalCards, s.types.sumOf { it.value })
    }

    @Test fun anEmptyDeckHasNoTypes() = assertTrue(DeckAnalysis.of(emptyList()).types.isEmpty())

    @Test fun aTypeBarCarriesNoNote() = assertEquals("", typesOf(c()).single().note)

    // ------------------------------------------------------ rarities

    @Test fun theRaritiesAreCountedByCopies() =
        assertEquals(3, bar(raritiesOf(c(qty = 3, rarity = "common")), "Common"))

    @Test fun theRaritiesRunCommonToBonus() {
        val labels = raritiesOf(
            c(name = "f", rarity = "bonus"),
            c(name = "e", rarity = "special"),
            c(name = "d", rarity = "mythic"),
            c(name = "c", rarity = "rare"),
            c(name = "b", rarity = "uncommon"),
            c(name = "a", rarity = "common"),
        ).map { it.label }
        assertEquals(listOf("Common", "Uncommon", "Rare", "Mythic", "Special", "Bonus"), labels)
    }

    @Test fun aRarityLabelIsCapitalised() =
        assertEquals("Mythic", raritiesOf(c(rarity = "mythic")).single().label)

    @Test fun aRarityNobodyChartsIsNotABar() =
        assertTrue(DeckAnalysis.of(listOf(c(rarity = "nonsense"))).rarities.isEmpty())

    @Test fun aCardWithNoRarityIsNotCharted() =
        assertTrue(DeckAnalysis.of(listOf(c(rarity = null))).rarities.isEmpty())

    @Test fun aRarityIsMatchedExactly() {
        // The column is lowercase, and a chart that silently drops a
        // third of the deck is worse than one that says nothing.
        assertTrue(DeckAnalysis.of(listOf(c(rarity = "Rare"))).rarities.isEmpty())
    }

    @Test fun anEmptyDeckHasNoRarities() = assertTrue(DeckAnalysis.of(emptyList()).rarities.isEmpty())

    @Test fun theRaritiesCountEveryPricedAndUnpricedCopyAlike() {
        val s = DeckAnalysis.of(
            listOf(c(qty = 2, rarity = "rare", price = 9.0), c(name = "b", qty = 1, rarity = "rare", price = null)),
        )
        assertEquals(3, bar(s.rarities, "Rare"))
    }

    // ------------------------------------------- lands, spells, share

    @Test fun theLandsAreCountedByCopies() =
        assertEquals(37, DeckAnalysis.of(listOf(land(qty = 37))).lands)

    @Test fun theBasicsCountTowardsTheLands() =
        assertEquals(9, DeckAnalysis.of(listOf(c(name = "Plains", qty = 9, owned = 0, type = null))).lands)

    @Test fun theSpellsAreEverythingElse() {
        val s = DeckAnalysis.of(listOf(land(qty = 37), c(qty = 62), c(name = "Alela", role = "commander")))
        assertEquals(37, s.lands)
        assertEquals(63, s.spells)
        assertEquals(100, s.totalCards)
    }

    @Test fun theUnknownsCountAsSpells() {
        // They are not lands, and the deck is still a hundred cards.
        val s = DeckAnalysis.of(listOf(land(qty = 37), c(name = "Mystery", type = null, qty = 63)))
        assertEquals(63, s.spells)
        assertEquals(63, s.unknown)
    }

    @Test fun aCommanderThatIsALandIsNotCountedAsALand() {
        val s = DeckAnalysis.of(listOf(c(name = "Dark Depths", type = "Legendary Land", role = "commander")))
        assertEquals(0, s.lands)
        assertEquals(1, s.spells)
    }

    @Test fun theLandShareIsAPercentage() =
        assertEquals(37, DeckAnalysis.of(listOf(land(qty = 37), c(qty = 63))).landShare)

    @Test fun theLandShareRoundsDown() =
        assertEquals(33, DeckAnalysis.of(listOf(land(qty = 1), c(qty = 2))).landShare)

    @Test fun theLandShareRoundsUp() =
        assertEquals(67, DeckAnalysis.of(listOf(land(qty = 2), c(qty = 1))).landShare)

    @Test fun anAllLandDeckIsAllLands() =
        assertEquals(100, DeckAnalysis.of(listOf(land(qty = 40))).landShare)

    @Test fun aDeckWithNoLandsSharesNone() =
        assertEquals(0, DeckAnalysis.of(listOf(c(qty = 60))).landShare)

    @Test fun anEmptyDeckIsNotADivisionByZero() =
        assertEquals(0, DeckAnalysis.of(emptyList()).landShare)

    @Test fun aDeckOfNothingButNoughtQuantityRowsIsNotADivisionByZero() =
        assertEquals(0, DeckAnalysis.of(listOf(land(qty = 0), c(qty = 0))).landShare)

    // ------------------------------------------------- what it is worth

    @Test fun theValueIsThePricesTimesTheCopies() =
        assertEquals(8.0, DeckAnalysis.of(listOf(c(qty = 4, price = 2.0))).value)

    @Test fun theValueAddsTheRowsUp() {
        val s = DeckAnalysis.of(listOf(c(qty = 2, price = 1.5), c(name = "b", qty = 1, price = 7.0)))
        assertEquals(10.0, s.value)
    }

    @Test fun anUnpricedRowIsLeftOutOfTheValue() =
        assertEquals(2.0, DeckAnalysis.of(listOf(c(price = 2.0), c(name = "b", price = null))).value)

    @Test fun aDeckWithNoPricesAtAllIsWorthNothingKnown() =
        assertNull(DeckAnalysis.of(listOf(c(price = null))).value)

    @Test fun anEmptyDeckIsWorthNothingKnown() = assertNull(DeckAnalysis.of(emptyList()).value)

    @Test fun aPriceOfNoughtIsAPriceRatherThanAnAbsence() {
        // Zero is a real answer from the price table, and it is not
        // "we do not know".
        assertEquals(0.0, DeckAnalysis.of(listOf(c(price = 0.0))).value)
    }

    @Test fun aNoughtQuantityRowIsWorthNothing() =
        assertEquals(0.0, DeckAnalysis.of(listOf(c(qty = 0, price = 5.0))).value)

    @Test fun theUnpricedAreCountedByCopies() =
        assertEquals(4, DeckAnalysis.of(listOf(c(qty = 4, price = null))).unpriced)

    @Test fun nothingIsUnpricedWhenEverythingHasAPrice() =
        assertEquals(0, DeckAnalysis.of(listOf(c(price = 1.0))).unpriced)

    @Test fun aPricedAndAnUnpricedDeckCountsBoth() {
        val s = DeckAnalysis.of(listOf(c(qty = 2, price = 1.0), c(name = "b", qty = 3, price = null)))
        assertEquals(2.0, s.value)
        assertEquals(3, s.unpriced)
    }

    @Test fun anEmptyDeckHasNothingUnpriced() = assertEquals(0, DeckAnalysis.of(emptyList()).unpriced)

    @Test fun aBasicNobodyOwnsIsUnpriced() =
        assertEquals(9, DeckAnalysis.of(listOf(c(name = "Plains", qty = 9, owned = 0, type = null))).unpriced)

    // ------------------------------------------------- what is missing

    @Test fun whatIsMissingIsTheCopiesShort() =
        assertEquals(3, DeckAnalysis.of(listOf(c(name = "Mana Crypt", qty = 4, owned = 1))).missing)

    @Test fun missingAddsUpAcrossTheDeck() {
        val s = DeckAnalysis.of(
            listOf(c(name = "a", qty = 4, owned = 1), c(name = "b", qty = 1, owned = 0)),
        )
        assertEquals(4, s.missing)
    }

    @Test fun nothingIsMissingFromADeckYouOwn() =
        assertEquals(0, DeckAnalysis.of(listOf(c(qty = 1, owned = 1))).missing)

    @Test fun theBasicsAreNeverMissing() {
        val s = DeckAnalysis.of(
            listOf(
                c(name = "Plains", qty = 9, owned = 0, type = null),
                c(name = "Snow-Covered Plains", qty = 4, owned = 0, type = null),
                c(name = "Forest", qty = 3, owned = 0, type = null),
                c(name = "Mountain", qty = 2, owned = 0, type = null),
            ),
        )
        assertEquals(0, s.missing)
    }

    @Test fun anEmptyDeckIsMissingNothing() = assertEquals(0, DeckAnalysis.of(emptyList()).missing)

    @Test fun owningSparesDoesNotMakeTheMissingNegative() =
        assertEquals(0, DeckAnalysis.of(listOf(c(qty = 1, owned = 9))).missing)

    @Test fun aCardNobodyOwnsIsMissingAndUnknownAtOnce() {
        val s = DeckAnalysis.of(listOf(c(name = "Mana Crypt", qty = 1, owned = 0, type = null)))
        assertEquals(1, s.missing)
        assertEquals(1, s.unknown)
    }

    // ------------------------------------------------------ unknowns

    @Test fun theUnknownsAreCountedByCopies() =
        assertEquals(18, DeckAnalysis.of(listOf(c(name = "Mystery", type = null, qty = 18))).unknown)

    @Test fun aBasicIsNotAnUnknown() =
        assertEquals(0, DeckAnalysis.of(listOf(c(name = "Plains", qty = 9, owned = 0, type = null))).unknown)

    @Test fun aKnownCardIsNotAnUnknown() = assertEquals(0, DeckAnalysis.of(listOf(c())).unknown)

    @Test fun anEmptyDeckHasNoUnknowns() = assertEquals(0, DeckAnalysis.of(emptyList()).unknown)

    @Test fun aCommanderIsNeverAnUnknownEvenWithNoPrinting() {
        // It is drawn in its own section with its name, which is all
        // the banner needs.
        val s = DeckAnalysis.of(listOf(c(name = "Mystery", type = null, role = "commander")))
        assertEquals(0, s.unknown)
    }
}

// ============================================================== bars

/** One bar of a chart, and how wide to draw it. */
class BarExhaustiveTest {

    @Test fun theTallestBarIsFullWidth() = assertEquals(100, Bar("x", 8).share(8))
    @Test fun halfTheTallestIsHalfWidth() = assertEquals(50, Bar("x", 4).share(8))
    @Test fun aQuarter() = assertEquals(25, Bar("x", 2).share(8))
    @Test fun nothingIsNoWidth() = assertEquals(0, Bar("x", 0).share(8))

    @Test fun aShareRoundsToTheNearestPercent() {
        assertEquals(33, Bar("x", 1).share(3))
        assertEquals(67, Bar("x", 2).share(3))
        assertEquals(13, Bar("x", 1).share(8))
    }

    @Test fun nothingToCompareAgainstIsNotADivisionByZero() = assertEquals(0, Bar("x", 3).share(0))
    @Test fun aNegativeTallestIsNotADivisionByZero() = assertEquals(0, Bar("x", 3).share(-5))
    @Test fun noBarsAtAllAreAllNoWidth() = assertEquals(0, Bar("x", 0).share(0))

    @Test fun aBarTallerThanTheTallestOverflows() {
        // Only possible if the caller passed the wrong `most`, and
        // clamping it would hide that.
        assertEquals(125, Bar("x", 10).share(8))
    }

    @Test fun aBarDefaultsToNoNote() = assertEquals("", Bar("x", 1).note)
    @Test fun aBarKeepsTheNoteItWasGiven() = assertEquals("W", Bar("White", 1, "W").note)

    @Test fun twoBarsOfTheSameThingAreEqual() =
        assertEquals(Bar("White", 3, "W"), Bar("White", 3, "W"))

    @Test fun aBarKnowsItsOwnLabelAndValue() {
        val b = Bar("7+", 12, "mana value 7")
        assertEquals("7+", b.label)
        assertEquals(12, b.value)
        assertEquals("mana value 7", b.note)
    }

    @Test fun everyCurveBarCanBeDrawn() {
        val curve = DeckAnalysis.of(listOf(spell(1.0, qty = 3), spell(2.0, qty = 9))).curve
        val most = curve.maxOf { it.value }
        curve.forEach { assertTrue(it.share(most) in 0..100, "${it.label} is ${it.share(most)}%") }
        assertEquals(100, curve.first { it.label == "2" }.share(most))
    }
}

// ====================================================== nothing at all

/**
 * A deck with no cards.
 *
 * Every number here is a division or a reduction over the list, and
 * every one of them has to answer nought rather than throw.
 */
class EmptyDeckExhaustiveTest {

    private val none = DeckAnalysis.of(emptyList())

    @Test fun noCards() = assertEquals(0, none.totalCards)
    @Test fun noLands() = assertEquals(0, none.lands)
    @Test fun noSpells() = assertEquals(0, none.spells)
    @Test fun noUnknowns() = assertEquals(0, none.unknown)
    @Test fun noAverage() = assertEquals(0.0, none.averageManaValue)
    @Test fun noMedian() = assertEquals(0.0, none.medianManaValue)
    @Test fun noLandShare() = assertEquals(0, none.landShare)
    @Test fun noValue() = assertNull(none.value)
    @Test fun nothingUnpriced() = assertEquals(0, none.unpriced)
    @Test fun nothingMissing() = assertEquals(0, none.missing)
    @Test fun noIdentity() = assertEquals("", none.identity)
    @Test fun noPips() = assertTrue(none.pips.isEmpty())
    @Test fun noSources() = assertTrue(none.sources.isEmpty())
    @Test fun noTypes() = assertTrue(none.types.isEmpty())
    @Test fun noRarities() = assertTrue(none.rarities.isEmpty())
    @Test fun nothingUnsupported() = assertTrue(none.unsupported.isEmpty())
    @Test fun noCurveToDraw() = assertFalse(none.hasCurve)

    @Test fun aCurveOfEightEmptyColumns() {
        assertEquals(8, none.curve.size)
        assertEquals(0, none.curve.sumOf { it.value })
    }

    @Test fun aDeckOfRowsWantingNoCopiesIsTheSameThing() {
        val zero = DeckAnalysis.of(listOf(c(qty = 0), land(qty = 0), c(name = "m", type = null, qty = 0)))
        assertEquals(0, zero.totalCards)
        assertEquals(0, zero.landShare)
        assertEquals(0.0, zero.averageManaValue)
        assertEquals(0.0, zero.medianManaValue)
        assertEquals(0, zero.spells)
        assertFalse(zero.hasCurve)
    }

    @Test fun aDeckOfOneCardIsNotADivisionByZeroEither() {
        val one = DeckAnalysis.of(listOf(spell(3.0)))
        assertEquals(1, one.totalCards)
        assertEquals(3.0, one.averageManaValue)
        assertEquals(3.0, one.medianManaValue)
        assertEquals(0, one.landShare)
        assertEquals(1, one.spells)
    }

    @Test fun aDeckOfOneLandIsNotADivisionByZeroEither() {
        val one = DeckAnalysis.of(listOf(land()))
        assertEquals(1, one.totalCards)
        assertEquals(100, one.landShare)
        assertEquals(0.0, one.averageManaValue)
        assertEquals(0, one.spells)
    }

    @Test fun aDeckOfOneUnknownCardIsNotADivisionByZeroEither() {
        val one = DeckAnalysis.of(listOf(c(name = "Mystery", type = null, owned = 0)))
        assertEquals(1, one.totalCards)
        assertEquals(1, one.unknown)
        assertEquals(0.0, one.averageManaValue)
        assertEquals(0.0, one.medianManaValue)
        assertFalse(one.hasCurve)
    }
}
