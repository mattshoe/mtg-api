package org.mattshoe.mtg.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * `CardDetail.named` — promoting the printed name over the key.
 *
 * A card opened from a link arrives holding only its `name_norm`,
 * which is lowercase and has had its punctuation flattened. The first
 * printing that comes back from the database knows what the card is
 * actually called, and that is what the drawer should put at the top.
 *
 * The rules this file holds the feature to:
 *   - a real name from a printing wins over the norm
 *   - blank printing names are skipped, never promoted
 *   - the name is taken as printed: case, accents, commas, dashes, //
 *   - a printing carrying nothing but the norm never unseats a real name
 *   - naming is idempotent and touches nothing but the name
 */
class CardNamingTest {

    private fun printing(
        cardName: String,
        qty: Int = 1,
        id: Long = 1,
        owner: String = "matt",
    ) = Printing(
        id = id,
        setCode = "m3c",
        setName = "Modern Horizons 3 Commander",
        collectorNumber = "409",
        finish = "nonfoil",
        qty = qty,
        scryfallId = "abcdef",
        owner = owner,
        cardName = cardName,
    )

    /** How a card actually arrives from a link: the key, nothing more. */
    private fun fromLink(norm: String) = CardDetail(name = norm, nameNorm = norm)

    @Test
    fun aPrintedNameReplacesTheLowercaseNorm() {
        val out = fromLink("lightning bolt").named(listOf(printing("Lightning Bolt")))
        assertEquals("Lightning Bolt", out.name)
        assertEquals("lightning bolt", out.nameNorm, "the key is not rewritten by naming")
    }

    @Test
    fun theFirstPrintingWithARealNameWins() {
        val out = fromLink("sol ring").named(
            listOf(
                printing("", id = 1),
                printing("   ", id = 2),
                printing("Sol Ring", id = 3),
                // Rows are newest first; a later one does not get to argue.
                printing("Sol Ring // Sol Ring", id = 4),
            ),
        )
        assertEquals("Sol Ring", out.name)
    }

    @Test
    fun printingsWithNoNameAtAllLeaveTheNameAlone() {
        val before = fromLink("sol ring")
        assertSame(before, before.named(listOf(printing(""), printing("  "))))
        assertSame(before, before.named(emptyList()))
    }

    @Test
    fun aNameThatDiffersOnlyInCaseIsStillPromoted() {
        assertEquals("Sol Ring", fromLink("sol ring").named(listOf(printing("Sol Ring"))).name)
        assertEquals(
            "BFM (Big Furry Monster)",
            fromLink("bfm (big furry monster)").named(listOf(printing("BFM (Big Furry Monster)"))).name,
        )
    }

    @Test
    fun punctuationTheNormCannotCarryComesFromThePrinting() {
        assertEquals(
            "Yawgmoth's Will",
            fromLink("yawgmoths will").named(listOf(printing("Yawgmoth's Will"))).name,
        )
        assertEquals(
            "Kongming, \"Sleeping Dragon\"",
            fromLink("kongming sleeping dragon").named(listOf(printing("Kongming, \"Sleeping Dragon\""))).name,
        )
        assertEquals(
            "Jötun Grunt",
            fromLink("jotun grunt").named(listOf(printing("Jötun Grunt"))).name,
            "an accent cannot be recovered by casing the norm, so the row has to be believed",
        )
        // An em dash, which no amount of title-casing the norm produces.
        assertEquals(
            "Ajani — Strength of the Pride",
            fromLink("ajani strength of the pride").named(listOf(printing("Ajani — Strength of the Pride"))).name,
        )
    }

    @Test
    fun aDoubleFacedNameKeepsBothFacesAndItsSlashes() {
        val out = fromLink("delver of secrets").named(
            listOf(printing("Delver of Secrets // Insectile Aberration")),
        )
        assertEquals("Delver of Secrets // Insectile Aberration", out.name)
        assertTrue("//" in out.name)
    }

    @Test
    fun aNameThatLooksNothingLikeTheNormIsStillTaken() {
        // The printings query is `WHERE name_norm = ?`, so whatever the
        // row says is this card by construction. Second-guessing it here
        // would mean reimplementing the database's normalisation, which
        // is exactly what `nameNorm` exists to avoid.
        val out = fromLink("lightning bolt").named(listOf(printing("Chain Lightning")))
        assertEquals("Chain Lightning", out.name)
    }

    @Test
    fun aPrintingCarryingOnlyTheNormNeverUnseatsARealName() {
        val known = CardDetail(name = "Lightning Bolt", nameNorm = "lightning bolt")
        val out = known.named(listOf(printing("lightning bolt")))
        assertEquals("Lightning Bolt", out.name, "the norm is what we were trying to get away from")
        assertSame(known, out)
    }

    @Test
    fun aRealNameLaterInTheListBeatsAnEarlierBareNorm() {
        val known = CardDetail(name = "Lightning Bolt", nameNorm = "lightning bolt")
        assertEquals(
            "Lightning Bolt",
            known.named(listOf(printing("lightning bolt", id = 1), printing("Lightning Bolt", id = 2))).name,
        )
    }

    @Test
    fun surroundingWhitespaceIsTrimmedOffThePromotedName() {
        assertEquals("Sol Ring", fromLink("sol ring").named(listOf(printing("  Sol Ring  "))).name)
        val known = CardDetail(name = "Sol Ring", nameNorm = "sol ring")
        assertSame(
            known,
            known.named(listOf(printing(" Sol Ring "))),
            "padding is not a different name",
        )
    }

    @Test
    fun aDetailWithNoNameAtAllFallsBackToItsNorm() {
        val out = CardDetail(name = "", nameNorm = "sol ring").named(emptyList())
        assertEquals("sol ring", out.name, "the norm is ugly but it beats an empty title")
        val padded = CardDetail(name = "   ", nameNorm = "sol ring").named(listOf(printing("")))
        assertEquals("sol ring", padded.name)
    }

    @Test
    fun namingTwiceChangesNothingTheSecondTime() {
        val printings = listOf(printing("Lightning Bolt"))
        val once = fromLink("lightning bolt").named(printings)
        val twice = once.named(printings)
        assertSame(once, twice)
        assertEquals("Lightning Bolt", twice.name)
    }

    @Test
    fun namingDisturbsNothingElseOnTheDetail() {
        val before = CardDetail(
            name = "sol ring",
            nameNorm = "sol ring",
            printings = listOf(printing("Sol Ring", qty = 3, id = 7)),
            usedIn = listOf(DeckUse("alela", "Alela", "matt", 1, "ramp", false)),
            legalities = listOf(Legality("commander", "legal")),
            rulings = listOf(Ruling("2019-01-01", "It taps for one.")),
            busy = true,
            error = "boom",
        )
        val after = before.named(before.printings)

        assertEquals("Sol Ring", after.name)
        assertEquals(before.nameNorm, after.nameNorm)
        assertEquals(before.printings, after.printings)
        assertEquals(before.usedIn, after.usedIn)
        assertEquals(before.legalities, after.legalities)
        assertEquals(before.rulings, after.rulings)
        assertEquals(before.busy, after.busy)
        assertEquals(before.error, after.error)
        assertEquals(before.owned, after.owned)
        assertEquals(before.committed, after.committed)
        assertEquals(before.free, after.free)
        assertEquals(before.byOwner, after.byOwner)
        assertEquals(before.copy(name = "Sol Ring"), after)
    }

    @Test
    fun namingDoesNotAdoptThePrintingsItWasHanded() {
        // `named` is told about printings so it can read a name off one,
        // not so it can install them — the caller already did that.
        val out = fromLink("sol ring").named(listOf(printing("Sol Ring", qty = 4)))
        assertTrue(out.printings.isEmpty())
        assertEquals(0, out.owned)
    }
}
