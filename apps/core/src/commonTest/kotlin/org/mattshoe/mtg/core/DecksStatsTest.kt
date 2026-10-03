package org.mattshoe.mtg.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DecksTest {

    private fun rows(vararg j: String) = j.map { Json.parseToJsonElement(it) as JsonArray }
    private val cols = listOf("slug", "name", "owner", "commander", "colors", "bracket", "art_id")

    @Test
    fun aDeckDecodes() {
        val d = DeckQueries.decode(
            cols,
            rows("""["alela","Alela","matt","Alela, Artful Provocateur (ELD) 324","UW",3,"abc"]"""),
        ).single()
        assertEquals("alela", d.slug)
        assertEquals("matt", d.owner)
        assertEquals(3, d.bracket)
        assertEquals(listOf("U", "W"), d.colorPips)
    }

    /** Commanders are stored with the set annotation often enough that
     *  matching the whole string finds nothing. */
    @Test
    fun theCommanderNameDropsItsSetAnnotation() {
        val d = DeckQueries.decode(
            cols,
            rows("""["a","A","matt","Alela, Artful Provocateur (ELD) 324",null,null,null]"""),
        ).single()
        assertEquals("Alela, Artful Provocateur", d.commanderName)
    }

    @Test
    fun aDeckWithNoCommanderSaysSoRatherThanGuessing() {
        val d = DeckQueries.decode(cols, rows("""["a","A","matt",null,null,null,null]""")).single()
        assertNull(d.commanderName)
        assertTrue(d.colorPips.isEmpty())
    }

    /**
     * A commander with nine printings must not multiply its deck row by
     * nine. The subquery collapses to one row per name first.
     */
    @Test
    fun theArtJoinCollapsesPrintingsFirst() {
        val sql = DeckQueries.all().sql
        assertTrue(sql.contains("GROUP BY name_norm"), sql)
        assertTrue(sql.contains("MIN(id)"))
    }

    @Test
    fun oneDecksCardsAreBoundBySlug() {
        val q = DeckQueries.cards("alela")
        assertEquals(listOf<Any?>("alela"), q.params)
        assertTrue(q.sql.contains("d.slug = ?"))
    }

    @Test
    fun aDecksCardsDecodeByColumnName() {
        val cols = listOf(
            "name", "name_norm", "qty", "role", "owned", "type_line", "scryfall_id",
            "mana_cost", "cmc", "produced_mana", "oracle_text", "color_identity", "rarity", "price",
        )
        val c = DeckQueries.decodeCards(
            cols,
            rows("""["Sol Ring","sol ring",1,"ramp",3,"Artifact","abc","{1}",1.0,null,"Add CC.","","uncommon",2.5]"""),
        ).single()
        assertEquals("Sol Ring", c.name)
        assertEquals(1, c.qty)
        assertEquals("ramp", c.role)
        assertEquals(3, c.owned)
        assertEquals("sol ring", c.nameNorm)
        assertEquals("Artifact", c.typeLine)
        assertEquals("abc", c.scryfallId)
        assertEquals("{1}", c.manaCost)
        assertEquals(1.0, c.cmc)
        assertEquals("uncommon", c.rarity)
        assertEquals(2.5, c.price)
    }

    @Test
    fun aDeckCardWithNoOwnedCopiesAndNoPriceDecodesToZeroRatherThanNull() {
        // A card the deck wants but nobody owns still has to appear in
        // the list, with no printing to read a price or a count from.
        val cols = listOf("name", "name_norm", "qty", "role", "owned", "cmc", "price")
        val c = DeckQueries.decodeCards(cols, rows("""["Mana Crypt","mana crypt",1,null,null,null,null]""")).single()
        assertEquals(0, c.owned)
        assertEquals(1, c.qty)
        assertNull(c.role)
        assertNull(c.cmc)
        assertNull(c.price)
    }

    @Test
    fun gapsAreTheCardsTheOwnerIsShortOf() {
        val s = DecksState().opened(
            "alela",
            listOf(
                DeckCard("Sol Ring", qty = 1, role = null, owned = 1),
                DeckCard("Mana Crypt", qty = 1, role = null, owned = 0),
                DeckCard("Island", qty = 10, role = null, owned = 4, nameNorm = "island"),
            ),
        )
        // Not the Island. Nobody inventories basics, so every deck
        // reads as owning none of them — and a Plains is not a card
        // to go and get.
        assertEquals(listOf("Mana Crypt"), s.gaps.map { it.name })
        assertEquals(12, s.totalCards)
    }

    @Test
    fun decksGroupByOwnerInAStableOrder() {
        val s = DecksState().loaded(
            listOf(
                Deck("b", "B", "matt", null, null, null, null),
                Deck("a", "A", "kayla", null, null, null, null),
                Deck("c", "C", "matt", null, null, null, null),
            ),
        )
        assertEquals(listOf("kayla", "matt"), s.byOwner.map { it.first })
        assertEquals(2, s.byOwner.last().second.size)
    }

    // --------------------------------------- saying it is still loading

    @Test
    fun aScreenBeingFetchedSaysSoRatherThanSayingItIsEmpty() {
        // The bug this is here for: Decks rendered "No decks yet" over
        // a load that was still in flight, because nothing marked it
        // busy. Intermittent, so it looked like the decks vanishing at
        // random.
        val s = AppState().navigate(View.DECKS)
        assertTrue(s.decks.decks.isEmpty())
        assertTrue(!s.decks.busy, "nothing has asked for anything yet")

        val fetching = s.fetching()
        assertTrue(fetching.decks.busy, "the decks screen does not say it is loading")
        assertNull(fetching.decks.error)
    }

    @Test
    fun andAFailedFetchSaysWhyRatherThanSayingItIsEmpty() {
        val failed = AppState().navigate(View.DECKS).fetching().fetchFailed("network down")
        assertTrue(!failed.decks.busy)
        assertEquals("network down", failed.decks.error)
        // Not a toast that has gone by the time the page is read.
        assertNull(failed.toast)
    }

    @Test
    fun everyScreenThatFetchesCanSayBothThings() {
        listOf(View.LIBRARY, View.DECKS, View.STATS).forEach { v ->
            val busy = AppState().navigate(v).fetching(v)
            val sick = busy.fetchFailed("nope", v)
            val says = when (v) {
                View.LIBRARY -> busy.library.busy to sick.library.error
                View.DECKS -> busy.decks.busy to sick.decks.error
                else -> busy.stats.busy to sick.stats.error
            }
            assertTrue(says.first, "$v does not say it is loading")
            assertEquals("nope", says.second, "$v does not say what went wrong")
        }
    }

    @Test
    fun aScreenWithNothingToFetchIsLeftAlone() {
        val s = AppState().navigate(View.CONSOLE)
        assertEquals(s, s.fetching(View.CONSOLE))
    }

    // ------------------------------------------------ grouped by type

    private fun card(
        name: String,
        type: String?,
        role: String? = null,
        qty: Int = 1,
    ) = DeckCard(name, qty, role, owned = qty, nameNorm = name.lowercase(), typeLine = type)

    @Test
    fun aCardGoesUnderTheMostSpecificTypeItHas() {
        // Most specific wins, or an Artifact Creature files under
        // Artifacts and the creature count is a lie.
        assertEquals(DeckGroup.CREATURES, card("Solemn Simulacrum", "Artifact Creature — Golem").group)
        assertEquals(DeckGroup.LANDS, card("Ancient Tomb", "Land").group)
        assertEquals(DeckGroup.LANDS, card("Dryad Arbor", "Legendary Land").group)
        assertEquals(DeckGroup.ARTIFACTS, card("Sol Ring", "Artifact").group)
        assertEquals(DeckGroup.ENCHANTMENTS, card("Rhystic Study", "Enchantment").group)
        assertEquals(DeckGroup.INSTANTS, card("Swords to Plowshares", "Instant").group)
        assertEquals(DeckGroup.SORCERIES, card("Toxic Deluge", "Sorcery").group)
        assertEquals(DeckGroup.PLANESWALKERS, card("Teferi", "Legendary Planeswalker — Teferi").group)
        assertEquals(DeckGroup.BATTLES, card("Invasion of Ravnica", "Battle — Siege").group)
    }

    @Test
    fun onlyTheFrontFaceDecidesWhichSectionItIsIn() {
        // A creature whose back is a land is a creature in the list.
        assertEquals(
            DeckGroup.CREATURES,
            card("Jwari Disruption", "Creature — Merfolk // Land").group,
        )
    }

    @Test
    fun aCardWithNoTypeLineAtAllSaysSoRatherThanHiding() {
        // Nobody owns a printing, so the joins came back empty. Its
        // own section, not "Other": as one of those it was drawn as a
        // nought-drop in the mana curve, which is a lie about the
        // deck rather than a gap in the data.
        assertEquals(DeckGroup.UNKNOWN, card("Something Unowned", null).group)
        assertEquals(DeckGroup.UNKNOWN, card("Something Unowned", "").group)
        assertEquals("Not in the collection", DeckGroup.UNKNOWN.title)
        // And a type line that simply is not a type we bucket still
        // lands in Other.
        assertEquals(DeckGroup.OTHER, card("Odd", "Dungeon").group)
    }

    @Test
    fun theCommanderIsItsOwnSectionWhateverItIsMadeOf() {
        val c = card("Alela", "Legendary Creature — Faerie", role = "commander")
        assertEquals(DeckGroup.COMMANDER, c.group)
        assertTrue(c.isCommander)
    }

    @Test
    fun theSectionsComeOutInReadingOrderAndAlphabeticalInside() {
        val s = DecksState().opened(
            "alela",
            listOf(
                card("Sol Ring", "Artifact"),
                card("Zulaport Cutthroat", "Creature — Human"),
                card("Island", "Basic Land — Island", qty = 10),
                card("Alela", "Legendary Creature — Faerie", role = "commander"),
                card("Birds of Paradise", "Creature — Bird"),
            ),
        )
        assertEquals(
            listOf(DeckGroup.COMMANDER, DeckGroup.CREATURES, DeckGroup.ARTIFACTS, DeckGroup.LANDS),
            s.byType.map { it.first },
        )
        assertEquals(
            listOf("Birds of Paradise", "Zulaport Cutthroat"),
            s.byType.first { it.first == DeckGroup.CREATURES }.second.map { it.name },
        )
    }

    @Test
    fun anEmptySectionIsNotShownAtAll() {
        val s = DecksState().opened("alela", listOf(card("Sol Ring", "Artifact")))
        assertEquals(listOf(DeckGroup.ARTIFACTS), s.byType.map { it.first })
    }

    @Test
    fun theCommanderIsAlsoOfferedOnItsOwnForTheBanner() {
        val s = DecksState().opened(
            "alela",
            listOf(
                card("Sol Ring", "Artifact"),
                card("Alela", "Legendary Creature — Faerie", role = "commander"),
            ),
        )
        assertEquals("Alela", s.commander?.name)
        assertNull(DecksState().opened("x", listOf(card("Sol Ring", "Artifact"))).commander)
    }

    @Test
    fun aCardCarriesEnoughToOpenItsDrawerAndDrawItsThumbnail() {
        // `name.lowercase()` is not `name_norm`, and a thumbnail with
        // no printing to take art from must be absent rather than a
        // broken image.
        val owned = DeckCard(
            "Jötun Grunt", 1, null, 1,
            nameNorm = "jotun grunt", scryfallId = "abcdef12-3456",
        )
        assertEquals("jotun grunt", owned.nameNorm)
        assertTrue(owned.art.orEmpty().contains("art_crop"), owned.art.orEmpty())
        assertNull(DeckCard("Nobody Owns This", 1, null, 0).art)
    }

    @Test
    fun closingADeckForgetsItsCards() {
        val s = DecksState().opened("alela", listOf(DeckCard("Sol Ring", 1, null, 1))).close()
        assertNull(s.openSlug)
        assertTrue(s.cards.isEmpty())
    }
}

class StatsTest {

    private fun rows(vararg j: String) = j.map { Json.parseToJsonElement(it) as JsonArray }

    @Test
    fun theDefaultScopeIsEveryone() {
        assertEquals("Both", StatsScope().label)
        assertTrue(StatsQueries.totals(StatsScope()).params.isEmpty())
    }

    /**
     * The scope appears in eight subqueries, so it has to be bound eight
     * times. One short and SQLite silently shifts every later parameter.
     */
    @Test
    fun aScopedQueryBindsTheOwnerOncePerSubquery() {
        val q = StatsQueries.totals(StatsScope(Owner.MATT))
        assertEquals(8, q.params.size)
        assertTrue(q.params.all { it == "matt" })
        assertEquals(8, Regex("owner = \\?").findAll(q.sql).count())
    }

    @Test
    fun anUnscopedQueryStillSlotsIntoAWhere() {
        assertTrue(StatsQueries.totals(StatsScope()).sql.contains("WHERE 1=1"))
    }

    @Test
    fun theSideBySideIsAlwaysBoth() {
        assertTrue(StatsQueries.perOwner().params.isEmpty())
        assertTrue(StatsQueries.perOwner().sql.contains("GROUP BY c.owner"))
    }

    @Test
    fun totalsDecode() {
        val cols = listOf(
            "printings", "uniques", "physical", "decks", "free", "sets", "foils", "value", "priced_at",
        )
        val t = StatsQueries.decode(cols, rows("""[6032,3481,3743,24,4144,190,311,5046,"2026-09-27"]"""))
        assertEquals(6032, t.printings)
        assertEquals(3743, t.physical)
        assertEquals(24, t.decks)
        assertEquals(5046.0, t.value)
        assertEquals("2026-09-27", t.pricedAt)
    }

    @Test
    fun missingNumbersAreZeroRatherThanACrash() {
        val t = StatsQueries.decode(listOf("printings"), rows("[null]"))
        assertEquals(0, t.printings)
        assertNull(t.value)
    }

    @Test
    fun anEmptyResultIsZeroesNotAnException() {
        assertEquals(Totals(), StatsQueries.decode(listOf("printings"), emptyList()))
    }

    @Test
    fun choosingAnOwnerReplacesTheScopeAndDropsAnyStaleError() {
        val s = StatsState(error = "network down").scopedTo(Owner.KAYLA)
        assertEquals(Owner.KAYLA, s.scope.owner)
        assertNull(s.error)
        assertEquals(StatsScope(), StatsState().scopedTo(null).scope, "null is both, not a third owner")
    }

    @Test
    fun loadedTotalsReplaceTheOldOnesAndStopTheSpinner() {
        val s = StatsState(busy = true).loaded(Totals(printings = 100))
        assertFalse(s.busy)
        assertEquals(100, s.totals.printings)
        assertNull(s.error)
    }
}
