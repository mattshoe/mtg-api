package org.mattshoe.mtg.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The "Legal in" row, before it is drawn.
 *
 * It had no test at all, which is how it ended up knowing exactly one
 * status word and painting everything else alarm red — banned,
 * restricted and simply-not-in-that-format all the same shade, and
 * the shade was the only thing saying it.
 */
class LegalityChipsTest {

    private fun card(vararg l: Pair<String, String>) =
        CardDetail(legalities = l.map { (f, s) -> Legality(f, s) })

    private fun formats(c: CardDetail) = c.legalityChips.map { it.format }

    // ------------------------------------------------------ one chip

    @Test
    fun aLegalChipKnowsItIsLegalAndNothingElseDoes() {
        assertTrue(Legality("commander", "legal").legal)
        assertFalse(Legality("legacy", "banned").legal)
        assertFalse(Legality("vintage", "restricted").legal)
        assertFalse(Legality("standard", "not_legal").legal)
    }

    @Test
    fun aBannedChipIsNotMerelyNotLegal() {
        // The page used to lump these together, which reads as "this
        // card was thrown out of Standard" when it was never in it.
        val banned = Legality("legacy", "banned")
        val absent = Legality("standard", "not_legal")
        assertTrue(banned.banned, "banned does not know it is banned")
        assertFalse(absent.banned, "a card absent from a format reads as banned from it")
        assertFalse(banned.restricted)
    }

    @Test
    fun aRestrictedChipIsItsOwnThing() {
        val r = Legality("vintage", "restricted")
        assertTrue(r.restricted)
        assertFalse(r.banned, "restricted is one copy allowed, not none")
        assertFalse(r.legal)
        assertEquals("restricted", r.label)
    }

    @Test
    fun aStatusReadsAsEnglishRatherThanAsAColumnValue() {
        assertEquals("not legal", Legality("standard", "not_legal").label)
        assertEquals("legal", Legality("commander", "legal").label)
    }

    @Test
    fun aFormatNameIsCapitalisedForDisplay() {
        assertEquals("Commander", Legality("commander", "legal").formatLabel)
        assertEquals("Paupercommander", Legality("paupercommander", "legal").formatLabel)
        assertEquals("Standard brawl", Legality("standard_brawl", "legal").formatLabel)
    }

    @Test
    fun howAStatusIsSpeltDoesNotChangeWhatItMeans() {
        // The same row has arrived as "Legal" and as " legal " from
        // two different importers.
        assertTrue(Legality("commander", "Legal").legal)
        assertTrue(Legality("commander", " legal ").legal)
        assertTrue(Legality("standard", "Not Legal").label == "not legal")
        assertTrue(Legality("legacy", "BANNED").banned)
    }

    // ------------------------------------- the signal, without colour

    @Test
    fun everyStatusSaysItselfInWordsAsWellAsInColour() {
        // Hue is the one channel the reader does not have, so the
        // word has to be on the chip.
        assertTrue("legal" in Legality("commander", "legal").chip)
        assertTrue("banned" in Legality("legacy", "banned").chip)
        assertTrue("restricted" in Legality("vintage", "restricted").chip)
        assertTrue("not legal" in Legality("standard", "not_legal").chip)
    }

    @Test
    fun everyStatusCarriesItsOwnShapeAndItsOwnTone() {
        val marks = listOf("legal", "banned", "restricted", "not_legal")
            .map { Legality("f", it).mark }
        assertEquals(marks.size, marks.toSet().size, "two statuses share a mark: $marks")
        val tones = listOf("legal", "banned", "restricted", "not_legal")
            .map { Legality("f", it).tone }
        assertEquals(tones.size, tones.toSet().size, "two statuses share a tone: $tones")
        assertEquals("ok", Legality("commander", "legal").tone)
        assertEquals("bad", Legality("legacy", "banned").tone)
    }

    @Test
    fun aBannedChipAndALegalChipDifferBeforeAnyColourIsApplied() {
        val legal = Legality("commander", "legal")
        val banned = Legality("commander", "banned")
        assertTrue(legal.mark != banned.mark, "same shape")
        assertTrue(legal.label != banned.label, "same word")
        assertTrue(legal.chip != banned.chip)
    }

    // ------------------------------------------------------- the row

    @Test
    fun chipsComeOutInTheOrderPeopleAskAboutFormats() {
        val c = card(
            "pauper" to "legal", "standard" to "not_legal", "vintage" to "legal",
            "legacy" to "legal", "modern" to "legal", "commander" to "legal",
        )
        assertEquals(
            listOf("commander", "modern", "legacy", "vintage", "standard", "pauper"),
            formats(c),
        )
    }

    @Test
    fun theOrderDoesNotDependOnTheOrderTheRowsArrivedIn() {
        val one = card("modern" to "legal", "commander" to "legal")
        val other = card("commander" to "legal", "modern" to "legal")
        assertEquals(formats(one), formats(other))
    }

    @Test
    fun aFormatNobodyHasHeardOfStillGetsAChipAtTheEnd() {
        // New formats appear; the row must not swallow them.
        val c = card("timeless" to "legal", "commander" to "legal", "alchemy" to "not_legal")
        assertEquals(listOf("commander", "alchemy", "timeless"), formats(c))
    }

    @Test
    fun unknownFormatsSortAmongThemselvesAlphabetically() {
        val c = card("zendikon" to "legal", "brawl" to "legal", "oathbreaker" to "legal")
        assertEquals(listOf("brawl", "oathbreaker", "zendikon"), formats(c))
    }

    @Test
    fun theSameFormatTwiceOnlyGetsOneChip() {
        val c = card("commander" to "legal", "commander" to "banned", "modern" to "legal")
        assertEquals(listOf("commander", "modern"), formats(c))
        assertEquals("legal", c.legalityChips.first().label, "the second row won")
    }

    @Test
    fun aRowWithNothingOnItIsNotAChip() {
        val c = card("" to "legal", "commander" to "", "modern" to "legal")
        assertEquals(listOf("modern"), formats(c))
    }

    @Test
    fun aCardWithNoLegalityDataHasNoChips() {
        assertTrue(CardDetail().legalityChips.isEmpty())
        assertFalse(CardDetail().legalAnywhere)
    }

    @Test
    fun aCardLegalNowhereKnowsItIsLegalNowhere() {
        val nowhere = card(
            "commander" to "banned", "legacy" to "banned", "standard" to "not_legal",
        )
        assertFalse(nowhere.legalAnywhere, "a card banned everywhere claims a format")
        assertEquals(3, nowhere.legalityChips.size, "the chips went away with the legality")
        assertTrue(nowhere.legalityChips.none { it.legal })
    }

    @Test
    fun aCardLegalAnywhereSaysSo() {
        assertTrue(card("commander" to "legal", "standard" to "not_legal").legalAnywhere)
        assertFalse(card("vintage" to "restricted").legalAnywhere, "restricted is not legal")
    }

    @Test
    fun theQueryAsksForTheSameOrderTheRowIsDrawnIn() {
        // Two places decide this order. They have to agree, or the
        // phone and the browser disagree about the same card.
        val sql = CardQueries.legalities("sol ring").sql
        val seen = Legality.ORDER.map { sql.indexOf("'$it'") }
        assertTrue(seen.none { it < 0 }, "the query does not mention every ordered format: $sql")
        assertEquals(seen.sorted(), seen, "the query orders the formats differently: $seen")
    }
}
