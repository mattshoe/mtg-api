package org.mattshoe.mtg.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A search is a link, and a link has to come back as the same search.
 *
 * The round trip is the test that matters: anything the codec forgets
 * silently changes what a shared link shows.
 */
class FilterUrlTest {

    private fun roundTrip(f: Filters): Filters =
        FilterUrl.fromHash(FilterUrl.toHash(f).substringAfter("?", ""))

    @Test
    fun theDefaultSearchIsABareHash() {
        assertEquals("#/search", FilterUrl.toHash(Filters()))
    }

    @Test
    fun anEmptyQueryStringIsTheDefaults() {
        assertEquals(Filters(), FilterUrl.fromHash(null))
        assertEquals(Filters(), FilterUrl.fromHash(""))
        assertEquals(Filters(), FilterUrl.fromHash("?"))
    }

    @Test
    fun onlyWhatDiffersIsWritten() {
        val hash = FilterUrl.toHash(Filters(q = "bolt"))
        assertEquals("#/search?q=bolt", hash)
        assertFalse(hash.contains("owner"), "a default must not be spelled out")
        assertFalse(hash.contains("sort"))
    }

    @Test
    fun everyFieldSurvivesTheRoundTrip() {
        val f = Filters(
            owner = "matt", qtyMin = "2", qtyMax = "4", pool = Pool.FREE, freeMin = "1",
            deck = "alela", finish = "foil",
            q = "bolt", text = "proliferate", textLike = "draw a card", flavor = "hero",
            artist = "Bierek", watermark = "azorius", typeLine = "creature",
            colorTarget = ColorTarget.PRINTED, colorMode = ColorMode.EXACTLY,
            colors = listOf("W", "U"), ciMin = "1", ciMax = "3", produces = listOf("R"),
            cmcMin = "1", cmcMax = "5", manaCost = "{1}{U}",
            powOp = "<=", pow = "3", touOp = ">", tou = "2", loyOp = "=", loy = "4",
            types = listOf("Creature"), typesNot = listOf("Land"),
            supertypes = listOf("Legendary"), subtypes = listOf("Faerie"),
            rarities = listOf("rare", "mythic"), sets = listOf("M3C"),
            setTypes = listOf("expansion"), layouts = listOf("normal"),
            frames = listOf("2015"), borders = listOf("black"), games = listOf("paper"),
            yearMin = "2020", yearMax = "2024", collnum = "409",
            flags = mapOf(Flag.RESERVED to Tri.YES, Flag.PROMO to Tri.NO),
            priceMin = "1", priceMax = "50",
            keywords = listOf("Flying"), tags = listOf("ramp"),
            format = "commander", legality = "banned",
            edhrecMin = "1", edhrecMax = "500", hasRulings = Tri.YES,
            adv = "t:creature -is:reprint",
            sort = Sort.CMC, descending = false, page = 4,
        )
        assertEquals(f, roundTrip(f))
    }

    @Test
    fun spacesAndPunctuationSurvive() {
        val f = Filters(q = "Kardur, Doomscourge", textLike = "draw a card & scry 1")
        assertEquals(f, roundTrip(f))
    }

    @Test
    fun anApostropheSurvives() {
        val f = Filters(q = "Ambition's Cost")
        assertEquals(f, roundTrip(f))
    }

    @Test
    fun unicodeSurvives() {
        val f = Filters(q = "Æther Vial — 日本語")
        assertEquals(f, roundTrip(f))
    }

    @Test
    fun anEqualsSignInAValueDoesNotSplitThePair() {
        val f = Filters(adv = "mv<=3 pow>=4")
        assertEquals(f, roundTrip(f))
    }

    @Test
    fun anUnknownParameterIsIgnoredRatherThanCrashing() {
        val f = FilterUrl.fromHash("q=bolt&nonsense=1&alsobad")
        assertEquals("bolt", f.q)
    }

    @Test
    fun aNonsensePageFallsBackToOne() {
        assertEquals(1, FilterUrl.fromHash("page=banana").page)
        assertEquals(1, FilterUrl.fromHash("page=-4").page)
        assertEquals(9, FilterUrl.fromHash("page=9").page)
    }

    @Test
    fun anUnknownSortFallsBackRatherThanBreakingTheQuery() {
        assertEquals(Sort.NAME, FilterUrl.fromHash("sort=nonsense").sort)
    }

    @Test
    fun onlySetFlagsAppearInTheUrl() {
        val hash = FilterUrl.toHash(Filters(flags = mapOf(Flag.RESERVED to Tri.YES)))
        assertTrue(hash.contains("reserved=yes"))
        assertFalse(hash.contains("promo"))
    }

    @Test
    fun anEmptyListIsNotWritten() {
        assertFalse(FilterUrl.toHash(Filters(colors = emptyList())).contains("colors"))
    }
}
