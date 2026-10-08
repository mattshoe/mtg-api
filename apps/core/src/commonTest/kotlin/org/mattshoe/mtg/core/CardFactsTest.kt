package org.mattshoe.mtg.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Everything the database knows about a card, on its page.
 *
 * Matt: "It looks like the card details is missing a BUNCH of stuff
 * from the database. The full card details needs to show EVERYTHING".
 * The page selected twelve of the fifty columns of `cards` and none of
 * the child tables.
 *
 * The fixture is not hand-built. It is what `CardFacts.query` returned
 * for Aetherblade Agent run against `schema.sql` and
 * `test/fixtures/seed.sql` in sqlite3, pasted as it came out — a
 * transform card with two keywords and two finishes, which is the
 * shape that would fan out if the child tables were joined rather
 * than subqueried.
 */
class CardFactsTest {

    private fun answer(json: String): Pair<List<String>, List<JsonArray>> {
        val o = Json.parseToJsonElement(json).jsonObject
        return o.keys.toList() to listOf(JsonArray(o.values.toList()))
    }

    private val agent = answer(AETHERBLADE_AGENT)

    private fun facts(): CardFacts =
        assertNotNull(CardFacts.decode(agent.first, agent.second), "one row decoded to no facts at all")

    private fun CardFacts.at(label: String): String? = rows.firstOrNull { it.label == label }?.value

    @Test
    fun theDetailsNameTheArtistTheLayoutTheFrameAndTheBorder() {
        val f = facts()
        assertEquals("Alexander Mokhov", f.at("Artist"), "no artist row in ${f.rows}")
        assertEquals("transform", f.at("Layout"))
        assertEquals("2015", f.at("Frame"))
        assertEquals("black", f.at("Border"))
    }

    @Test
    fun theChildTablesArriveAsOneLineEach() {
        val f = facts()
        assertEquals("Deathtouch, Transform", f.at("Keywords"), "keywords: ${f.rows}")
        assertEquals("foil, nonfoil", f.at("Finishes"))
        assertEquals("arena, mtgo, paper", f.at("Games"))
        assertTrue(f.at("Tags").orEmpty().contains("draw engine"), "tags: ${f.at("Tags")}")
    }

    @Test
    fun theRankIsWordedTheWayTheCarouselWordsIt() {
        assertEquals("#20,135", facts().at("EDHREC rank"))
    }

    @Test
    fun everyPrintingFlagIsSaidEitherWay() {
        val f = facts()
        assertEquals("Yes", f.at("In boosters"))
        assertEquals("No", f.at("Reprint"))
        assertEquals("No", f.at("Reserved list"))
    }

    @Test
    fun nothingIsPrintedForAColumnThatHoldsNothing() {
        val f = facts()
        // Watermark and security stamp are null on this printing, and
        // promo types and frame effects have no rows.
        assertNull(f.at("Watermark"), "an empty watermark row")
        assertNull(f.at("Promo types"), "an empty promo types row")
        assertTrue(f.rows.none { it.value.isBlank() }, "blank rows: ${f.rows.filter { it.value.isBlank() }}")
    }

    @Test
    fun theCardAndThePrintingAreTwoGroups() {
        val g = facts().groups
        assertEquals(listOf("The card", "This printing"), g.map { it.title })
        assertTrue(g[0].facts.any { it.label == "Keywords" }, "keywords belong to the card")
        assertTrue(g[1].facts.any { it.label == "Artist" }, "the artist belongs to the printing")
    }

    @Test
    fun theFactsAreAboutThePrintingWhosePictureIsShown() {
        // The picture is `printings.first()`, so the facts have to be
        // taken off the same row or the artist line names somebody
        // who did not paint what is on screen.
        val order = Regex("""ORDER BY ([^\n]+)""")
        val printings = order.find(CardQueries.printings("x").sql)?.groupValues?.get(1)?.trim()
        val facts = order.find(CardFacts.query("x").sql)?.groupValues?.get(1)?.trim()
        assertNotNull(facts, "the facts query has no ORDER BY, so it takes any printing")
        assertEquals(printings, facts)
    }

    @Test
    fun theQueryReadsEveryChildTableItShows() {
        val sql = CardFacts.query("x").sql
        listOf("card_keywords", "card_finishes", "card_games", "card_promo_types", "card_frame_effects", "card_tags")
            .forEach { assertTrue(it in sql, "the facts query never reads $it") }
    }

    @Test
    fun noRowsIsNoFacts() {
        assertNull(CardFacts.decode(agent.first, emptyList()))
    }

    @Test
    fun theCardPageAsksForTheFactsAndKeepsThem() {
        val statements = Load.card("aetherblade agent // gitaxian mindstinger")
        assertTrue(statements.any { it.sql == CardFacts.query("aetherblade agent // gitaxian mindstinger").sql }, "Load.card never asks for the facts")
        val empty = emptyList<String>() to emptyList<JsonArray>()
        val answers = statements.map { if (it.sql == CardFacts.query("x").sql) agent else empty }
        val card = Load.cardDetail("Aetherblade Agent", "aetherblade agent // gitaxian mindstinger", answers)
        assertEquals("Alexander Mokhov", card.facts?.value("artist"), "the facts were fetched and dropped")
    }

    companion object {
        const val AETHERBLADE_AGENT = """{"id":3240,"owner":"matt","qty":1,"finish":"nonfoil","foil_flag":"","scryfall_id":"dad34ae5-56b4-4394-be02-e043dc1cc23d","oracle_id":"80ecb069-36e2-490d-9f6c-ef553c00e997","name":"Aetherblade Agent // Gitaxian Mindstinger","name_norm":"aetherblade agent // gitaxian mindstinger","face1":"Aetherblade Agent","face2":"Gitaxian Mindstinger","mana_cost":"{1}{B}","cmc":2.0,"oracle_text":"Deathtouch\n{4}{U/P}: Transform this creature. Activate only as a sorcery. ({U/P} can be paid with either {U} or 2 life.)\n//\nDeathtouch\nWhenever this creature deals combat damage to a player or battle, draw a card.","flavor_text":null,"power":"1","toughness":"1","loyalty":null,"defense":null,"type_line":"Creature — Human Rogue // Creature — Phyrexian Rogue","supertypes":null,"types":"Creature","subtypes":"Human Rogue // Creature — Phyrexian Rogue","colors":"B","color_identity":"BU","color_identity_count":2,"produced_mana":null,"rarity":"common","setcode":"mom","set_name":"March of the Machine","set_type":"expansion","released_at":"2023-04-21","collector_number":"88","artist":"Alexander Mokhov","layout":"transform","frame":"2015","border_color":"black","watermark":null,"security_stamp":null,"reserved":0,"game_changer":0,"full_art":0,"textless":0,"promo":0,"reprint":0,"variation":0,"oversized":0,"story_spotlight":0,"booster":1,"edhrec_rank":20135,"keywords":"Deathtouch, Transform","finishes":"foil, nonfoil","games":"arena, mtgo, paper","promo_types":null,"frame_effects":null,"tags":"activated ability, alliteration, curiosity, cycle-mom-c-dfc, draw engine, life for cards, namesake spell, repeatable pure draw, synergy-battle, transform-improvement, triggered ability"}"""
    }
}
