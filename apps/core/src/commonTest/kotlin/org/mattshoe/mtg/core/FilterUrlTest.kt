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
            supertypes = listOf("Legendary"),
            rarities = listOf("rare", "mythic"), sets = listOf("M3C"),
            setTypes = listOf("expansion"), layouts = listOf("normal"),
            frames = listOf("2015"), borders = listOf("black"), games = listOf("paper"),
            yearMin = "2020", yearMax = "2024", collnum = "409",
            flags = mapOf(Flag.RESERVED to Tri.YES, Flag.PROMO to Tri.NO),
            priceMin = "1", priceMax = "50",
            keywords = listOf("Flying"), tags = listOf("ramp"),
            format = "commander", legality = "banned",
            edhrecMin = "1", edhrecMax = "500", hasRulings = Tri.YES,
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
        val f = Filters(textLike = "power <= 3 and toughness >= 4")
        assertEquals(f, roundTrip(f))
    }

    /**
     * The query box was removed, so a link must not be able to apply
     * one — it would filter the results with no control on the page
     * showing it or able to clear it.
     */
    @Test
    fun theQueryBoxDoesNotTravelInALink() {
        assertEquals("", FilterUrl.fromHash("adv=is%3Areprint").adv)
        assertFalse(FilterUrl.toHash(Filters(adv = "is:reprint")).contains("adv"))
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
    fun anUnknownSortFallsBackToTheDefaultRatherThanAThirdThing() {
        // It used to fall back to Name while an *absent* sort fell
        // back to Price, so a typo in a link silently chose neither
        // the default nor what was asked for.
        assertEquals(Sort.PRICE, FilterUrl.fromHash("sort=nonsense").sort)
        assertEquals(Sort.PRICE, FilterUrl.fromHash("").sort)
    }

    @Test
    fun onlyTheTwoSpellingsOfADirectionMeanAnything() {
        assertEquals(false, FilterUrl.fromHash("dir=asc").descending)
        assertEquals(true, FilterUrl.fromHash("dir=desc").descending)
        // `dir=sideways` used to mean ascending, which reversed the
        // order of a hand-typed link for no stated reason.
        assertEquals(true, FilterUrl.fromHash("dir=sideways").descending)
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

    // ------------------------------------------- restoring, not just parsing

    @Test
    fun aRestoredSearchFillsTheBoxThatShowsIt() {
        // The bug: the name box is bound to `complete.term`, not to
        // `filters.q`. Restoring only the filters left the search
        // applied with an empty box above it — no way to see what was
        // filtering and no way to clear it by hand.
        val restored = AppState().restoredSearch(FilterUrl.fromHash("?q=bolt&colors=R"))
        assertEquals("bolt", restored.library.filters.q)
        assertEquals("bolt", restored.complete.term, "the name box came back empty")
        assertEquals(listOf("R"), restored.library.filters.colors)
    }

    @Test
    fun andItDoesNotArriveWithASuggestionListHangingOpen() {
        val restored = AppState().restoredSearch(FilterUrl.fromHash("?q=bolt"))
        assertTrue(!restored.complete.open, "the autocomplete opened itself on a cold start")
        assertTrue(restored.complete.items.isEmpty())
    }

    @Test
    fun anEmptySearchClearsTheBoxRatherThanLeavingTheLastWordInIt() {
        val typed = AppState().restoredSearch(FilterUrl.fromHash("?q=bolt"))
        assertEquals("", typed.restoredSearch(Filters()).complete.term)
    }

    @Test
    fun aRestoredSearchKeepsThePageItWasSentOn() {
        // `where` resets to page one, which is right for changing a
        // filter and wrong for a link that said page four.
        val restored = AppState().restoredSearch(FilterUrl.fromHash("?q=bolt&page=4"))
        assertEquals(4, restored.library.page)
    }

    @Test
    fun everyFieldThePanelShowsSurvivesTheRoundTrip() {
        // The whole point: what comes back has to be what went in, or
        // the page renders a filter it cannot show.
        val full = Filters(
            owner = "matt", q = "bolt", text = "draw", textLike = "enters", flavor = "goblin",
            artist = "guay", watermark = "boros", typeLine = "artifact creature",
            manaCost = "{G}{G}", collnum = "117", deck = "_any", finish = "foil",
            format = "commander", qtyMin = "2", qtyMax = "8", freeMin = "1",
            ciMin = "1", ciMax = "3", cmcMin = "2", cmcMax = "5",
            pow = "3", tou = "4", loy = "5", yearMin = "2015", yearMax = "2024",
            priceMin = "1", priceMax = "50", edhrecMin = "10", edhrecMax = "900",
            colors = listOf("G", "U"), produces = listOf("R"),
            types = listOf("Creature"), typesNot = listOf("Land"), supertypes = listOf("Legendary"),
            rarities = listOf("rare"), sets = listOf("MH3"), setTypes = listOf("expansion"),
            layouts = listOf("normal"), frames = listOf("2015"), borders = listOf("black"),
            games = listOf("paper"), keywords = listOf("Flying"), tags = listOf("ramp"),
            flags = mapOf(Flag.RESERVED to Tri.YES), hasRulings = Tri.NO,
            sort = Sort.EDHREC, descending = false, page = 3,
        )
        assertEquals(full, FilterUrl.fromHash(FilterUrl.toHash(full).substringAfter('?')))
    }
}
