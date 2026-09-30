package org.mattshoe.mtg.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A token link that lists the token.
 *
 * Measured against the real thing: TCGplayer's search for "Sculpture
 * Treasure" returns four products, one token and three printings of
 * Vraska, Soul of Stone. With the rarity facet it returns one.
 */
class TcgTest {

    private val product =
        "https://partner.tcgplayer.com/c/4931599/1830156/21018?subId1=api" +
            "&u=https%3A%2F%2Fwww.tcgplayer.com%2Fproduct%2F149468%3Fpage%3D1"

    private val search =
        "https://partner.tcgplayer.com/c/4931599/1830156/21018?subId1=api" +
            "&u=https%3A%2F%2Fwww.tcgplayer.com%2Fsearch%2Fmagic%2Fproduct%3FproductLineName" +
            "%3Dmagic%26q%3DSculpture%2BTreasure%26view%3Dgrid"

    @Test
    fun aProductPageIsAlreadyExact() {
        assertTrue(Tcg.isProduct(product))
        assertEquals(product, Tcg.tokensOnly(product))
    }

    @Test
    fun aSearchIsToldToListTokensOnly() {
        assertTrue(!Tcg.isProduct(search))
        val narrowed = Tcg.tokensOnly(search)
        assertTrue(narrowed.endsWith("%26Rarity%3DToken"), narrowed)
        // The affiliate wrapper survives, or Scryfall loses the credit.
        assertTrue(narrowed.startsWith("https://partner.tcgplayer.com/c/4931599/"), narrowed)
        assertTrue("subId1=api" in narrowed)
    }

    @Test
    fun theFilterGoesInsideTheWrappedAddressNotBesideIt() {
        // Hung off the wrapper it would be a parameter of the redirect
        // rather than of the search it redirects to, and TCGplayer
        // would never see it.
        val narrowed = Tcg.tokensOnly(search)
        assertTrue("&Rarity=Token" !in narrowed, narrowed)
        assertEquals(1, Regex("Rarity").findAll(narrowed).count())
    }

    @Test
    fun aLinkWithMoreAfterTheAddressKeepsIt() {
        val wrapped = "https://partner.tcgplayer.com/c/1?u=https%3A%2F%2Fwww.tcgplayer.com%2Fsearch%2Fx&z=9"
        assertEquals(
            "https://partner.tcgplayer.com/c/1?u=https%3A%2F%2Fwww.tcgplayer.com%2Fsearch%2Fx%26Rarity%3DToken&z=9",
            Tcg.tokensOnly(wrapped),
        )
    }

    @Test
    fun aBareSearchGetsThePlainFacet() {
        assertEquals(
            "https://www.tcgplayer.com/search/magic/product?q=Treasure&Rarity=Token",
            Tcg.tokensOnly("https://www.tcgplayer.com/search/magic/product?q=Treasure"),
        )
    }

    @Test
    fun narrowingTwiceChangesNothing() {
        val once = Tcg.tokensOnly(search)
        assertEquals(once, Tcg.tokensOnly(once))
    }

    @Test
    fun nothingIsStillNothing() {
        assertEquals("", Tcg.tokensOnly(""))
    }
}
