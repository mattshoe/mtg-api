package org.mattshoe.mtg.core

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * "The edhrec sorting looks backwards."
 *
 * Every other column reads the arrow the same way: down is the good
 * end. A rank is the other way round, rank 1 is the card everybody
 * plays, so `descending` on EDHREC has to order the rank ascending.
 */
class EdhrecSortTest {

    private fun order(sort: Sort, descending: Boolean): String {
        val sql = buildQuery(Filters(sort = sort, descending = descending)).sql
        return sql.substring(sql.indexOf("ORDER BY"))
    }

    @Test
    fun arrowDownOnEdhrecPutsTheMostPlayedCardFirst() {
        val o = order(Sort.EDHREC, descending = true)
        assertTrue(
            o.contains("(MIN(c.edhrec_rank)) ASC"),
            "arrow down on EDHREC should put rank 1 first, but the rank is ordered: $o",
        )
    }

    @Test
    fun arrowUpOnEdhrecPutsTheLeastPlayedCardFirst() {
        val o = order(Sort.EDHREC, descending = false)
        assertTrue(o.contains("(MIN(c.edhrec_rank)) DESC"), "arrow up should put rank 22,000 first: $o")
    }

    @Test
    fun unrankedCardsStillGoLastBothWays() {
        listOf(true, false).forEach { d ->
            val o = order(Sort.EDHREC, descending = d)
            assertTrue(
                o.startsWith("ORDER BY (MIN(c.edhrec_rank)) IS NULL, "),
                "the IS NULL term is not first, so unranked cards would lead: $o",
            )
        }
    }

    @Test
    fun thePriceSortIsNotFlipped() {
        assertTrue(order(Sort.PRICE, descending = true).contains("(price) DESC"))
        assertTrue(order(Sort.PRICE, descending = false).contains("(price) ASC"))
    }
}
