package org.mattshoe.mtg.core

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The card page's printings, with nobody's name on them.
 *
 * Matt: "I DO NOT want ownership information on the cards details.
 * But i do need to keep deck membership."
 *
 * The printings query is every copy anybody owns, so with the owner
 * and the count gone, Matt's M3C and Kayla's M3C were two identical
 * rows. A printing is a set, a number and a finish; it is listed once.
 */
class CardPrintingsShownTest {

    @Test
    fun theSamePrintingOwnedByTwoPeopleIsOneLine() {
        val card = CardDetail(
            printings = listOf(
                Printing(1, "m3c", "Modern Horizons 3 Commander", "409", "nonfoil", 2, null, owner = "matt", price = 1.5),
                Printing(2, "m3c", "Modern Horizons 3 Commander", "409", "nonfoil", 3, null, owner = "kayla", price = 1.5),
                Printing(3, "m3c", "Modern Horizons 3 Commander", "409", "foil", 1, null, owner = "kayla", price = 4.0),
                Printing(4, "lcc", "The Lost Caverns of Ixalan Commander", "4", "nonfoil", 1, null, owner = "matt"),
            ),
        )
        assertEquals(
            listOf("m3c 409 nonfoil", "m3c 409 foil", "lcc 4 nonfoil"),
            card.printingsShown.map { "${it.setCode} ${it.collectorNumber} ${it.finish}" },
        )
    }
}
