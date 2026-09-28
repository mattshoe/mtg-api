package org.mattshoe.mtg.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PricesTest {

    @Test
    fun centsMatterUnderTenAndDoNotAboveIt() {
        assertEquals("$0.37", Prices.money(0.37))
        assertEquals("$9.99", Prices.money(9.99))
        // Rounded on the value as written. `9.995 * 100` is
        // 999.4999999999999 in binary, which floored to $9.99 while
        // every other formatter says $10.00.
        assertEquals("$10.00", Prices.money(9.995))
        assertEquals("$2.68", Prices.money(2.675))
        assertEquals("$0.15", Prices.money(0.145))
        assertEquals("$10", Prices.money(10.0), "ten and up is whole")
        assertEquals("$412", Prices.money(411.62))
    }

    @Test
    fun negativesPutTheSignBeforeTheSymbol() {
        assertEquals("-$5.00", Prices.money(-5.0))
        assertEquals("-$4,000", Prices.money(-4000.0), "under ten has to mean small, not negative")
        assertEquals("-$2.50", Prices.exact(-2.5))
    }

    @Test
    fun aPartialTotalSaysSo() {
        assertEquals("$18.20", Prices.atLeast(18.2, 0))
        assertEquals("$18.20 + 2 unpriced", Prices.atLeast(18.2, 2))
    }

    @Test
    fun thousandsAreGrouped() {
        assertEquals("$5,046", Prices.money(5046.0))
        assertEquals("$1,234,567", Prices.money(1234567.0))
    }

    @Test
    fun exactIsAlwaysTwoDecimals() {
        assertEquals("$2.50", Prices.exact(2.5))
        assertEquals("$0.05", Prices.exact(0.05))
        assertEquals("$5,046.00", Prices.exact(5046.0))
    }

    @Test
    fun roundingIsHalfUpAndDoesNotDropACent() {
        assertEquals("$0.13", Prices.exact(0.125))
        assertEquals("$1.00", Prices.exact(0.999))
    }

    @Test
    fun noPriceIsADashNotAZero() {
        assertEquals("—", Prices.money(null))
        assertEquals("—", Prices.exact(null))
        assertEquals("unpriced", Prices.money(null, dash = "unpriced"))
    }

    /** A dash on its own reads as a failure, and usually it is not one. */
    @Test
    fun anUnreleasedPrintingSaysWhenItArrives() {
        assertEquals(
            "releases 2026-11-14",
            Prices.reason(layout = "normal", releasedAt = "2026-11-14", today = "2026-09-28"),
        )
    }

    @Test
    fun aTokenIsNotSoldSingly() {
        assertEquals("not sold singly", Prices.reason("token", "2020-01-01", "2026-09-28"))
        assertEquals("not sold singly", Prices.reason("emblem", null, "2026-09-28"))
    }

    @Test
    fun anythingElseHasNoMarketPrice() {
        assertEquals("no market price", Prices.reason("normal", "2020-01-01", "2026-09-28"))
    }

    @Test
    fun aPriceBeatsAReason() {
        assertEquals("$2.50", Prices.orReason(2.5, "token", null, "2026-09-28"))
        assertEquals("not sold singly", Prices.orReason(null, "token", null, "2026-09-28"))
    }
}

class ExportTest {

    private fun card(name: String, qty: Int, face2: String? = null) = CardRow(
        id = 1, owner = "matt", name = name, nameNorm = name.lowercase(), face2 = face2,
        layout = null, scryfallId = null, manaCost = null, cmc = null, typeLine = null,
        colorIdentity = null, rarity = null, setCode = null, setName = null,
        collectorNumber = null, edhrecRank = null, releasedAt = null, finish = null,
        power = null, toughness = null, artist = null, qty = qty, printings = 1,
        free = null, price = null, value = null,
    )

    /** The whole search, not the page you happen to be looking at. */
    @Test
    fun theExportQueryIsUnpagedAndCapped() {
        val q = Export.query(Filters(q = "bolt", page = 7))
        assertTrue(q.sql.contains("LIMIT ${Export.CAP} OFFSET 0"), q.sql)
    }

    @Test
    fun theExportKeepsTheSearchItWasMadeFrom() {
        assertEquals(listOf<Any?>("%bolt%"), Export.query(Filters(q = "bolt")).params)
    }

    /** No set code: a row is a card summed over every printing owned. */
    @Test
    fun aDecklistIsQuantityAndName() {
        assertEquals(
            "3 Sol Ring\n1 Opt",
            Export.decklist(listOf(card("Sol Ring", 3), card("Opt", 1))),
        )
    }

    @Test
    fun aTwoFacedCardExportsWithBothNames() {
        assertEquals("1 Front // Back", Export.decklist(listOf(card("Front", 1, face2 = "Back"))))
    }

    @Test
    fun anEmptySearchExportsNothingRatherThanABlankLine() {
        assertEquals("", Export.decklist(emptyList()))
    }

    @Test
    fun theFilenameCarriesTheDate() {
        assertEquals("mtg-decklist-2026-09-28.txt", Export.filename("2026-09-28"))
    }
}
