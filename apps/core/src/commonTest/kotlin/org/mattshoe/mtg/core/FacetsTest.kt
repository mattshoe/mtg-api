package org.mattshoe.mtg.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The filter panel's lists: what the panel shows before anything has
 * loaded, and the two ways the twelve-way-split reads come back into a
 * single `Facets`.
 *
 * `FilterCoverageTest` already proves `Facet.countIn` and `Facet.inUse`
 * against the live panel. This file is the half behind it: the data
 * that panel is built from, and the decoding that turns D1's rows back
 * into it. Nothing here has ever been driven by a test, which is how a
 * renamed column or a reordered `assemble` call would have shipped
 * silently — the panel would just open with an empty list and nobody
 * would know which one.
 */
class FacetsTest {

    // -------------------------------------------------------- DeckRef2

    @Test
    fun aDeckRefsLabelIsTheNameAndTheOwnerInParens() {
        val ref = DeckRef2(slug = "alela", name = "Alela, Artful Provocateur", owner = "matt")
        // What a picker actually shows: two decks named the same thing
        // by two different owners have to read differently, or picking
        // one is a coin flip.
        assertEquals("Alela, Artful Provocateur (matt)", ref.label)
    }

    @Test
    fun theLabelDoesNotTouchTheSlug() {
        // The slug is what the URL and the SQL key on; nothing about
        // the label should leak into it or vice versa.
        val ref = DeckRef2(slug = "some-other-slug", name = "Korvold", owner = "kayla")
        assertEquals("Korvold (kayla)", ref.label)
        assertFalse(ref.label.contains("some-other-slug"))
    }

    // ----------------------------------------------------- Facets.loaded

    @Test
    fun freshFacetsAreNotLoaded() {
        assertFalse(Facets().loaded)
    }

    /**
     * `loaded` only checks `types` and `layouts` — not any of the other
     * nine lists. That is deliberate: those two always come back
     * non-empty for a real collection, so they are the cheapest proof
     * the first read actually happened, and a panel that waited on
     * every list would stay in its loading state if just one of them
     * (say `watermarks`, which plenty of collections have none of)
     * ever came back empty.
     */
    @Test
    fun loadedIsTrueAssoonAsTypesArrive() {
        assertTrue(Facets(types = listOf("Creature")).loaded)
    }

    @Test
    fun loadedIsTrueAssoonAsLayoutsArriveEvenAlone() {
        assertTrue(Facets(layouts = listOf("normal")).loaded)
    }

    @Test
    fun aFacetsFullOfEverythingElseIsStillNotLoadedWithoutTypesOrLayouts() {
        // Every other list populated, both of the two that matter
        // still empty — this is the case a bug could hide in, because
        // the object clearly "has data" to the eye.
        val f = Facets(
            sets = listOf("MH3"), keywords = listOf("Flying"), tags = listOf("ramp"),
            formats = listOf("commander"), artists = listOf("guay"),
            watermarks = listOf("boros"), setTypes = listOf("core"),
            frames = listOf("2015"), borders = listOf("black"),
            decks = listOf(DeckRef2("a", "A", "matt")),
        )
        assertFalse(f.loaded)
    }

    // --------------------------------------------------- FacetQueries.all

    @Test
    fun allListsTheTwelveStatementsInAssembleOrder() {
        // `assemble` reads `columns` positionally against this same
        // order — a reorder here with no matching reorder there is
        // exactly the kind of mistake that silently swaps two lists.
        assertEquals(12, FacetQueries.all.size)
        assertEquals(
            listOf(
                FacetQueries.types, FacetQueries.sets, FacetQueries.keywords, FacetQueries.tags,
                FacetQueries.formats, FacetQueries.artists, FacetQueries.watermarks,
                FacetQueries.setTypes, FacetQueries.layouts, FacetQueries.frames,
                FacetQueries.borders, FacetQueries.decks,
            ),
            FacetQueries.all,
        )
    }

    @Test
    fun everyStatementInAllBindsNoParameters() {
        // Every one of these is a fixed SELECT with nothing typed by a
        // person — a stray `?` here would hang the panel's first load
        // waiting on a parameter nobody ever supplies.
        FacetQueries.all.forEach { assertTrue(it.params.isEmpty(), it.sql) }
    }

    @Test
    fun everythingChunksTheElevenListsAtFive() {
        // Measured, not guessed: D1 refuses a compound SELECT past
        // five terms, so eleven lists has to become three batches of
        // at most `MAX_UNION` apiece or the first load fails outright.
        assertEquals(5, FacetQueries.MAX_UNION)
        assertEquals(3, FacetQueries.everything.size)
        FacetQueries.everything.dropLast(1).forEach {
            assertEquals(5, Regex("UNION ALL").findAll(it.sql).count() + 1)
        }
    }

    @Test
    fun everyBatchOrdersByKindThenRowNumber() {
        // Without this the three stacked lists come back interleaved
        // and `decodeEverything` has nothing to group them back up by.
        FacetQueries.everything.forEach { assertTrue(it.sql.trimEnd().endsWith("ORDER BY kind, rn")) }
    }

    // ----------------------------------------------- decodeEverything

    private fun answerRow(kind: String, value: kotlinx.serialization.json.JsonElement, rn: Int = 0) =
        JsonArray(listOf(JsonPrimitive(kind), value, JsonPrimitive(rn)))

    private fun answer(vararg rows: JsonArray) = listOf("kind", "value", "rn") to rows.toList()

    @Test
    fun decodeEverythingSortsEachRowIntoItsOwnList() {
        val answers = listOf(
            answer(
                answerRow("types", JsonPrimitive("Creature")),
                answerRow("types", JsonPrimitive("Artifact")),
                answerRow("sets", JsonPrimitive("MH3")),
            ),
        )
        val decks = listOf(DeckRef2("alela", "Alela", "matt"))
        val facets = FacetQueries.decodeEverything(answers, decks)
        assertEquals(listOf("Creature", "Artifact"), facets.types)
        assertEquals(listOf("MH3"), facets.sets)
        assertEquals(emptyList(), facets.keywords)
        // Decks never come from this read — they are handed in whole,
        // which is why the signature takes them separately rather than
        // expecting a "decks" kind among the rows.
        assertEquals(decks, facets.decks)
    }

    @Test
    fun decodeEverythingMergesMultipleBatches() {
        // The real caller hands this three answers, one per chunk of
        // `everything` — a decode that only read the first would lose
        // every list past the fifth.
        val first = answer(answerRow("types", JsonPrimitive("Land")), answerRow("sets", JsonPrimitive("WOE")))
        val second = answer(answerRow("layouts", JsonPrimitive("saga")), answerRow("borders", JsonPrimitive("black")))
        val facets = FacetQueries.decodeEverything(listOf(first, second), emptyList())
        assertEquals(listOf("Land"), facets.types)
        assertEquals(listOf("WOE"), facets.sets)
        assertEquals(listOf("saga"), facets.layouts)
        assertEquals(listOf("black"), facets.borders)
    }

    @Test
    fun decodeEverythingSkipsARowWithABlankValue() {
        // A kind with no rows of its own still shows up as an empty
        // string in some engines' GROUP BY — letting that through
        // would put a literal blank entry in the Library's set list.
        val answers = listOf(
            answer(
                answerRow("sets", JsonPrimitive("")),
                answerRow("sets", JsonPrimitive("MH3")),
            ),
        )
        assertEquals(listOf("MH3"), FacetQueries.decodeEverything(answers, emptyList()).sets)
    }

    @Test
    fun decodeEverythingSkipsARowWithABlankKind() {
        val answers = listOf(
            answer(
                answerRow("", JsonPrimitive("orphaned")),
                answerRow("sets", JsonPrimitive("MH3")),
            ),
        )
        val facets = FacetQueries.decodeEverything(answers, emptyList())
        assertEquals(listOf("MH3"), facets.sets)
        // The orphaned value must not have landed anywhere else either.
        assertFalse(
            listOf(
                facets.types, facets.keywords, facets.tags, facets.formats, facets.artists,
                facets.watermarks, facets.setTypes, facets.layouts, facets.frames, facets.borders,
            ).any { it.contains("orphaned") },
        )
    }

    @Test
    fun decodeEverythingTreatsAJsonNullValueAsAbsentRatherThanTheWordNull() {
        // `str` returns null for a JSON null rather than its text —
        // without that check a SQL NULL (a set with no watermark, say)
        // would show up in the panel as a selectable option spelled
        // "null".
        val answers = listOf(
            answer(
                answerRow("watermarks", JsonNull),
                answerRow("watermarks", JsonPrimitive("boros")),
            ),
        )
        assertEquals(listOf("boros"), FacetQueries.decodeEverything(answers, emptyList()).watermarks)
    }

    @Test
    fun decodeEverythingIgnoresAKindNobodyAsksFor() {
        // A thirteenth statement added upstream with a typo'd kind
        // must not crash the decode — it is simply never read back.
        val answers = listOf(answer(answerRow("nonsense", JsonPrimitive("whatever"))))
        val facets = FacetQueries.decodeEverything(answers, emptyList())
        assertEquals(Facets(), facets.copy(decks = emptyList()))
    }

    @Test
    fun decodeEverythingWithNoAnswersAtAllIsJustTheDecks() {
        val decks = listOf(DeckRef2("x", "X", "matt"))
        val facets = FacetQueries.decodeEverything(emptyList(), decks)
        assertEquals(Facets(decks = decks), facets)
    }

    // ----------------------------------------------------- decodeDecks

    @Test
    fun decodeDecksReadsSlugNameAndOwnerByColumnName() {
        val cols = listOf("slug", "name", "owner")
        val rows = listOf(
            JsonArray(listOf(JsonPrimitive("alela"), JsonPrimitive("Alela, Artful Provocateur"), JsonPrimitive("matt"))),
            JsonArray(listOf(JsonPrimitive("korvold"), JsonPrimitive("Korvold"), JsonPrimitive("kayla"))),
        )
        val decks = FacetQueries.decodeDecks(cols, rows)
        assertEquals(
            listOf(
                DeckRef2("alela", "Alela, Artful Provocateur", "matt"),
                DeckRef2("korvold", "Korvold", "kayla"),
            ),
            decks,
        )
    }

    @Test
    fun decodeDecksReadsByColumnNameNotByPosition() {
        // The SQL says `SELECT slug, name, owner`, but nothing here
        // should rely on that order never changing — the lookup is by
        // name, and this proves a reordered result set still decodes
        // the same deck.
        val cols = listOf("owner", "slug", "name")
        val row = JsonArray(listOf(JsonPrimitive("matt"), JsonPrimitive("alela"), JsonPrimitive("Alela")))
        assertEquals(listOf(DeckRef2("alela", "Alela", "matt")), FacetQueries.decodeDecks(cols, listOf(row)))
    }

    @Test
    fun decodeDecksDefaultsAMissingColumnToEmptyRatherThanThrowing() {
        // A deck row missing its name entirely must still produce a
        // row the picker can show — blank is recoverable, a crash on
        // the whole facets read is not.
        val cols = listOf("slug", "owner")
        val row = JsonArray(listOf(JsonPrimitive("alela"), JsonPrimitive("matt")))
        assertEquals(listOf(DeckRef2("alela", "", "matt")), FacetQueries.decodeDecks(cols, listOf(row)))
    }

    @Test
    fun decodeDecksOfNoRowsIsAnEmptyList() {
        assertEquals(emptyList(), FacetQueries.decodeDecks(listOf("slug", "name", "owner"), emptyList()))
    }

    // ------------------------------------------------------- assemble

    @Test
    fun assembleMapsEachColumnToItsOwnField() {
        val columns = listOf(
            listOf("Creature"), listOf("MH3"), listOf("Flying"), listOf("ramp"),
            listOf("commander"), listOf("guay"), listOf("boros"), listOf("core"),
            listOf("saga"), listOf("2015"), listOf("black"),
        )
        val decks = listOf(DeckRef2("alela", "Alela", "matt"))
        val facets = FacetQueries.assemble(columns, decks)
        assertEquals(listOf("Creature"), facets.types)
        assertEquals(listOf("MH3"), facets.sets)
        assertEquals(listOf("Flying"), facets.keywords)
        assertEquals(listOf("ramp"), facets.tags)
        assertEquals(listOf("commander"), facets.formats)
        assertEquals(listOf("guay"), facets.artists)
        assertEquals(listOf("boros"), facets.watermarks)
        assertEquals(listOf("core"), facets.setTypes)
        assertEquals(listOf("saga"), facets.layouts)
        assertEquals(listOf("2015"), facets.frames)
        assertEquals(listOf("black"), facets.borders)
        assertEquals(decks, facets.decks)
    }

    /**
     * `columns.getOrElse(n) { emptyList() }` rather than `columns[n]` —
     * a response short a column (one query in the batch came back
     * empty, say) has to degrade to an empty list for that field
     * instead of throwing `IndexOutOfBoundsException` and taking the
     * whole panel down with it.
     */
    @Test
    fun assembleToleratesFewerColumnsThanFieldsExist() {
        val facets = FacetQueries.assemble(listOf(listOf("Creature")), emptyList())
        assertEquals(listOf("Creature"), facets.types)
        assertEquals(emptyList(), facets.sets)
        assertEquals(emptyList(), facets.borders)
    }

    @Test
    fun assembleOfNothingAtAllIsJustAnEmptyFacets() {
        assertEquals(Facets(), FacetQueries.assemble(emptyList(), emptyList()))
    }

    @Test
    fun assembleAndDecodeEverythingAgreeOnTheSameData() {
        // Two different wire shapes for the same answer — the old
        // one-query-per-list form and the batched `UNION ALL` form —
        // have to decode to the exact same `Facets`, or switching
        // between them changes what the panel shows for no reason a
        // person typed.
        val decks = listOf(DeckRef2("alela", "Alela", "matt"))
        val viaAssemble = FacetQueries.assemble(
            listOf(listOf("Creature"), listOf("MH3")),
            decks,
        )
        val viaDecode = FacetQueries.decodeEverything(
            listOf(answer(answerRow("types", JsonPrimitive("Creature")), answerRow("sets", JsonPrimitive("MH3")))),
            decks,
        )
        assertEquals(viaAssemble, viaDecode)
    }

    // -------------------------------------------------- RARITIES etc.

    @Test
    fun theStartingListsAreFixedAndCoverTheWholeDomain() {
        // These six, three and four are what the panel shows before
        // the first read ever comes back — missing one here is a
        // checkbox nobody can ever tick until the network catches up,
        // and for rarity in particular these six are exhaustive, so a
        // seventh rarity type arriving in a new set would otherwise
        // have no checkbox at all until this list is updated by hand.
        assertEquals(listOf("common", "uncommon", "rare", "mythic", "special", "bonus"), Facets.RARITIES)
        assertEquals(listOf("paper", "arena", "mtgo"), Facets.GAMES)
        assertEquals(listOf("legal", "banned", "restricted", "not_legal"), Facets.LEGALITIES)
    }

    @Test
    fun finishesPairAMachineSlugWithAHumanLabelAndAnAnyOption() {
        assertEquals(4, Facets.FINISHES.size)
        // The blank slug is "Any" — not a fifth finish, the absence of one.
        assertEquals("" to "Any", Facets.FINISHES.first())
        assertTrue(Facets.FINISHES.map { it.first }.toSet().containsAll(listOf("nonfoil", "foil", "etched")))
    }

    // ----------------------------------------------------- JSON shape

    @Test
    fun decodeEverythingReadsARealJsonArrayJustLikeD1Would() {
        // The other tests build rows by hand; this one parses actual
        // JSON text, which is what the Worker's response really looks
        // like on the wire.
        val row = Json.parseToJsonElement("""["types","Planeswalker",0]""") as JsonArray
        val facets = FacetQueries.decodeEverything(listOf(listOf("kind", "value", "rn") to listOf(row)), emptyList())
        assertEquals(listOf("Planeswalker"), facets.types)
    }
}
