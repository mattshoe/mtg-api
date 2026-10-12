package org.mattshoe.mtg.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A deck list in, where to find every card out.
 *
 * Matt: "my cards are all sorted in order of: 1. Type 2. Color 3.
 * Rarity", and the list comes back as "clearly labeled sections for
 * each combination of the 3 sort criteria so that i can quickly pick
 * through each section". A rarity the database might have wrong is an
 * "Also check", and a card he does not own goes in a section of its
 * own.
 */
class FetchListTest {

    private fun owned(
        name: String,
        types: String,
        colors: String?,
        rarity: String,
        qty: Int = 1,
        face: String? = null,
    ) = OwnedPrinting(
        nameNorm = name.lowercase(),
        name = name,
        face = face,
        types = types,
        colors = colors,
        rarity = rarity,
        qty = qty,
    )

    private val collection = listOf(
        owned("Brazen Borrower // Petty Theft", "Creature", "U", "mythic", face = "Brazen Borrower"),
        owned("Counterspell", "Instant", "U", "common", qty = 2),
        owned("Counterspell", "Instant", "U", "uncommon"),
        owned("Arcane Signet", "Artifact", null, "common", qty = 3),
        owned("Dryad Arbor", "Land Creature", "G", "uncommon"),
        owned("Fractured Identity", "Sorcery", "WU", "rare"),
        owned("Ornithopter", "Artifact Creature", null, "uncommon"),
    )

    private fun fetch(list: String, printed: Map<String, Set<String>> = emptyMap()) =
        FetchList.build(DeckList.entries(list), collection, printed)

    @Test
    fun aCardIsFiledUnderItsTypeItsColourAndItsRarity() {
        val r = fetch("1 Fractured Identity")
        val s = r.sections.single()
        assertEquals(FetchType.SORCERY, s.type)
        assertEquals(FetchColor.MULTICOLOR, s.color)
        assertEquals(FetchRarity.RARE, s.rarity)
        assertEquals("Sorceries · Multicolor · Rare", s.title)
        assertEquals(listOf("Fractured Identity"), s.cards.map { it.name })
    }

    @Test
    fun theSectionsComeInTheOrderTheBindersAreSortedIn() {
        val r = fetch(
            """
            1 Dryad Arbor
            1 Arcane Signet
            1 Fractured Identity
            1 Counterspell
            1 Brazen Borrower
            1 Ornithopter
            """.trimIndent(),
        )
        assertEquals(
            listOf(
                "Creatures · Blue · Mythic",
                "Creatures · Colorless · Uncommon",
                "Instants · Blue · Common",
                "Sorceries · Multicolor · Rare",
                "Artifacts · Colorless · Common",
                "Lands · Green · Uncommon",
            ),
            r.sections.map { it.title },
        )
    }

    @Test
    fun aDoubleFacedCardIsFoundByItsFrontFace() {
        val r = fetch("1 Brazen Borrower")
        assertTrue(r.missing.isEmpty(), "the front face was not matched: ${r.missing}")
        assertEquals("Brazen Borrower // Petty Theft", r.sections.single().cards.single().name)
    }

    @Test
    fun aCardOwnedAtTwoRaritiesIsFiledUnderTheOneWithMoreCopiesAndTheOtherIsAnAlsoCheck() {
        val card = fetch("1 Counterspell").sections.single().cards.single()
        assertEquals(FetchRarity.COMMON, fetch("1 Counterspell").sections.single().rarity)
        assertEquals(listOf(FetchRarity.UNCOMMON), card.alsoCheck)
        assertEquals(3, card.have, "both printings are copies he owns")
    }

    @Test
    fun aRarityTheCardWasPrintedAtIsAnAlsoCheckEvenWhenHeOwnsOnlyOne() {
        // The database can name the wrong printing, so the rarity it
        // holds is not proof of which binder the card is really in.
        val card = fetch(
            "1 Arcane Signet",
            printed = mapOf("arcane signet" to setOf("common", "uncommon", "rare")),
        ).sections.single().cards.single()
        assertEquals(listOf(FetchRarity.UNCOMMON, FetchRarity.RARE), card.alsoCheck)
    }

    @Test
    fun aCardPrintedAtOneRarityHasNothingElseToCheck() {
        val card = fetch(
            "1 Fractured Identity",
            printed = mapOf("fractured identity" to setOf("rare")),
        ).sections.single().cards.single()
        assertEquals(emptyList(), card.alsoCheck)
    }

    @Test
    fun aCardNotInTheCollectionIsInTheMissingSection() {
        val r = fetch("1 Counterspell\n1 Black Lotus")
        assertEquals(listOf("Black Lotus"), r.missing.map { it.name })
        assertEquals(1, r.missing.single().short)
        assertEquals(1, r.sections.size)
    }

    @Test
    fun owningFewerThanTheListWantsIsSaidOnTheCardAndInMissing() {
        val r = fetch("4 Counterspell")
        val card = r.sections.single().cards.single()
        assertEquals(4, card.want)
        assertEquals(3, card.have)
        val short = r.missing.single()
        assertEquals("Counterspell", short.name)
        assertEquals(1, short.short)
        assertEquals(3, short.have)
    }

    @Test
    fun theTypeIsTheOneASortedBinderUses() {
        assertEquals(FetchType.LAND, FetchType.of("Land Creature"), "a land goes with the lands")
        assertEquals(FetchType.CREATURE, FetchType.of("Artifact Creature"))
        assertEquals(FetchType.CREATURE, FetchType.of("Enchantment Creature"))
        assertEquals(FetchType.ARTIFACT, FetchType.of("Artifact"))
        assertEquals(FetchType.ENCHANTMENT, FetchType.of("Enchantment"))
        assertEquals(FetchType.PLANESWALKER, FetchType.of("Planeswalker"))
        assertEquals(FetchType.OTHER, FetchType.of(null))
    }

    @Test
    fun theColourIsOneColourMulticolourOrColourless() {
        assertEquals(FetchColor.WHITE, FetchColor.of("W"))
        assertEquals(FetchColor.GREEN, FetchColor.of("G"))
        assertEquals(FetchColor.MULTICOLOR, FetchColor.of("BG"))
        assertEquals(FetchColor.COLORLESS, FetchColor.of(null))
        assertEquals(FetchColor.COLORLESS, FetchColor.of(""))
    }

    @Test
    fun theQueryIsScopedToOneCollectionAndAsksForEveryNameAtOnce() {
        val q = assertNotNull(FetchList.query(listOf("sol ring", "brazen borrower"), "bprh3d2s"))
        assertTrue("owner_id = (SELECT id FROM users WHERE key = ?)" in q.sql, q.sql)
        assertEquals("bprh3d2s", q.params.first())
        // One parameter for the names, however many there are: D1
        // refuses more than a hundred, and a deck is a hundred cards.
        assertEquals(2, q.params.size)
        assertEquals("[\"sol ring\",\"brazen borrower\"]", q.params[1])
    }

    @Test
    fun noCollectionMeansNoQuery() {
        assertNull(FetchList.query(listOf("sol ring"), ""), "an empty owner reads every collection")
    }

    @Test
    fun theRowsComeBackAsPrintings() {
        val cols = listOf("name_norm", "name", "face1", "types", "colors", "rarity", "qty")
        val rows = listOf(
            JsonArray(
                listOf(
                    JsonPrimitive("arcane signet"), JsonPrimitive("Arcane Signet"), JsonPrimitive("Arcane Signet"),
                    JsonPrimitive("Artifact"), JsonNull, JsonPrimitive("common"), JsonPrimitive(6),
                ),
            ),
        )
        val p = FetchList.decode(cols, rows).single()
        assertEquals("arcane signet", p.nameNorm)
        assertNull(p.colors)
        assertEquals(6, p.qty)
    }

    @Test
    fun aListNeedsATitleToBeSaved() {
        val ready = FetchListState(list = "1 Counterspell", result = fetch("1 Counterspell"))
        assertEquals("Give it a title first", ready.save("  ", "2026-10-12T00:00:00Z").error)
        val saved = ready.titled("Alela").save("Alela", "2026-10-12T00:00:00Z")
        assertEquals(listOf("Alela"), saved.saved.map { it.title })
        assertEquals("1 Counterspell", saved.saved.single().list)
    }

    @Test
    fun aSavedListComesBackAfterARestart() {
        val store = Store.inMemory()
        val saved = FetchListState(list = "1 Counterspell", result = fetch("1 Counterspell"))
            .save("Alela", "2026-10-12T00:00:00Z")
        FetchListState.persist(store, saved.saved)
        val back = FetchListState.restore(store)
        assertEquals(listOf("Alela"), back.saved.map { it.title })
        val opened = back.open(back.saved.single().id)
        assertEquals("Counterspell", opened.result?.sections?.single()?.cards?.single()?.name)
        assertEquals("Alela", opened.title)
    }

    @Test
    fun aSavedListCanBeDeleted() {
        val saved = FetchListState(list = "1 Counterspell", result = fetch("1 Counterspell"))
            .save("Alela", "2026-10-12T00:00:00Z")
        assertEquals(emptyList(), saved.delete(saved.saved.single().id).saved)
    }

    @Test
    fun fetchTakesStatsPlaceInTheBarAndStatsMovesBehindTheProfile() {
        assertEquals("Fetch", View.FETCH.label)
        assertTrue(View.FETCH in Admin().bar)
        assertTrue(View.STATS !in Admin().bar, "Stats is still in the bar")
        assertTrue(View.STATS in Admin().behindProfile, "Stats is not behind the profile")
        assertEquals(View.FETCH, Route.parse("#/fetch").view)
    }
}
