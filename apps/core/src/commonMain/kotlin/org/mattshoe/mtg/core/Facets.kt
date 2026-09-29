package org.mattshoe.mtg.core

import kotlinx.serialization.json.JsonArray

/**
 * The lists the filter panel offers: every card type in the collection,
 * every set type, every layout, every deck.
 *
 * Read once on the first search and kept. They come from the collection
 * rather than from a hard-coded list, so a panel never offers a type
 * nothing has and never misses one that arrived last week.
 */
data class DeckRef2(val slug: String, val name: String, val owner: String) {
    val label: String get() = "$name ($owner)"
}

data class Facets(
    val types: List<String> = emptyList(),
    val sets: List<String> = emptyList(),
    val keywords: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val formats: List<String> = emptyList(),
    val artists: List<String> = emptyList(),
    val watermarks: List<String> = emptyList(),
    val setTypes: List<String> = emptyList(),
    val layouts: List<String> = emptyList(),
    val frames: List<String> = emptyList(),
    val borders: List<String> = emptyList(),
    val decks: List<DeckRef2> = emptyList(),
) {
    val loaded: Boolean get() = types.isNotEmpty() || layouts.isNotEmpty()

    companion object {
        /** Somewhere to start before the queries come back. */
        val RARITIES = listOf("common", "uncommon", "rare", "mythic", "special", "bonus")
        val GAMES = listOf("paper", "arena", "mtgo")
        val FINISHES = listOf("" to "Any", "nonfoil" to "Nonfoil", "foil" to "Foil", "etched" to "Etched")
        val LEGALITIES = listOf("legal", "banned", "restricted", "not_legal")
    }
}

/**
 * One statement per list. They are small and cacheable, and running them
 * as twelve reads once beats a bespoke endpoint that would have to be
 * kept in step with the panel.
 */
object FacetQueries {

    val types = Sql(
        "SELECT DISTINCT type FROM card_types WHERE kind='type' AND type GLOB '[A-Za-z]*' ORDER BY type",
        emptyList(),
    )
    val sets = Sql("SELECT DISTINCT upper(setcode) FROM cards ORDER BY 1", emptyList())
    val keywords = Sql("SELECT DISTINCT keyword FROM card_keywords ORDER BY 1", emptyList())
    val tags = Sql("SELECT tag_slug FROM card_tags GROUP BY 1 ORDER BY COUNT(*) DESC LIMIT 600", emptyList())
    val formats = Sql("SELECT DISTINCT format FROM legalities ORDER BY 1", emptyList())
    val artists = Sql("SELECT DISTINCT artist FROM cards WHERE artist IS NOT NULL ORDER BY 1", emptyList())
    val watermarks =
        Sql("SELECT DISTINCT watermark FROM cards WHERE watermark IS NOT NULL ORDER BY 1", emptyList())
    val setTypes =
        Sql("SELECT set_type FROM cards WHERE set_type IS NOT NULL GROUP BY 1 ORDER BY COUNT(*) DESC", emptyList())
    val layouts = Sql("SELECT layout FROM cards GROUP BY 1 ORDER BY COUNT(*) DESC", emptyList())
    val frames = Sql("SELECT DISTINCT frame FROM cards WHERE frame IS NOT NULL ORDER BY 1", emptyList())
    val borders =
        Sql("SELECT DISTINCT border_color FROM cards WHERE border_color IS NOT NULL ORDER BY 1", emptyList())
    val decks = Sql("SELECT slug, name, owner FROM decks ORDER BY owner, name", emptyList())

    /** In the order `assemble` expects them back. */
    val all: List<Sql> = listOf(
        types, sets, keywords, tags, formats, artists,
        watermarks, setTypes, layouts, frames, borders, decks,
    )

    /**
     * The eleven lists, in as few reads as D1 allows.
     *
     * Eleven round trips on every cold load is eleven queries queued
     * behind each other at the one database the search is also using —
     * which is what "D1 is overloaded, requests queued for too long"
     * looks like from the outside. Stacked with `UNION ALL` they are
     * three, and the rows say which list they came from.
     *
     * Five and not eleven because D1 refuses a compound SELECT with
     * more than five terms — measured, not guessed: six answers "too
     * many terms in compound SELECT".
     */
    const val MAX_UNION = 5

    val everything: List<Sql> by lazy {
        lists.chunked(MAX_UNION).map { group ->
            Sql(
                group.joinToString("\nUNION ALL\n") { (kind, inner) ->
                    "SELECT '$kind' AS kind, v AS value, rn FROM (" +
                        "SELECT value AS v, ROW_NUMBER() OVER () AS rn FROM ($inner))"
                } + "\nORDER BY kind, rn",
                emptyList(),
            )
        }
    }

    /**
     * Each list as `SELECT ... AS value`, so they can be stacked.
     *
     * Spelled out rather than derived from `all`: the statements above
     * name their own columns, and rewriting somebody else's SELECT list
     * with a regular expression is how a column quietly goes missing.
     */
    private val lists: List<Pair<String, String>> = listOf(
        "types" to "SELECT DISTINCT type AS value FROM card_types WHERE kind='type' AND type GLOB '[A-Za-z]*' ORDER BY 1",
        "sets" to "SELECT DISTINCT upper(setcode) AS value FROM cards ORDER BY 1",
        "keywords" to "SELECT DISTINCT keyword AS value FROM card_keywords ORDER BY 1",
        "tags" to "SELECT tag_slug AS value FROM card_tags GROUP BY 1 ORDER BY COUNT(*) DESC LIMIT 600",
        "formats" to "SELECT DISTINCT format AS value FROM legalities ORDER BY 1",
        "artists" to "SELECT DISTINCT artist AS value FROM cards WHERE artist IS NOT NULL ORDER BY 1",
        "watermarks" to "SELECT DISTINCT watermark AS value FROM cards WHERE watermark IS NOT NULL ORDER BY 1",
        "setTypes" to "SELECT set_type AS value FROM cards WHERE set_type IS NOT NULL GROUP BY 1 ORDER BY COUNT(*) DESC",
        "layouts" to "SELECT layout AS value FROM cards GROUP BY 1 ORDER BY COUNT(*) DESC",
        "frames" to "SELECT DISTINCT frame AS value FROM cards WHERE frame IS NOT NULL ORDER BY 1",
        "borders" to "SELECT DISTINCT border_color AS value FROM cards WHERE border_color IS NOT NULL ORDER BY 1",
    )

    /** Those reads' rows, back into the eleven lists. */
    fun decodeEverything(
        answers: List<Pair<List<String>, List<JsonArray>>>,
        decks: List<DeckRef2>,
    ): Facets {
        val by = mutableMapOf<String, MutableList<String>>()
        answers.forEach { (cols, rows) ->
            val at = cols.withIndex().associate { (i, n) -> n to i }
            fun JsonArray.str(n: String) = at[n]?.let { i ->
                (getOrNull(i) as? kotlinx.serialization.json.JsonPrimitive)
                    ?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.content
            }
            rows.forEach { row ->
                val kind = row.str("kind").orEmpty()
                val value = row.str("value").orEmpty()
                if (kind.isNotBlank() && value.isNotBlank()) {
                    by.getOrPut(kind) { mutableListOf() } += value
                }
            }
        }
        fun of(kind: String): List<String> = by[kind].orEmpty()
        return Facets(
            types = of("types"),
            sets = of("sets"),
            keywords = of("keywords"),
            tags = of("tags"),
            formats = of("formats"),
            artists = of("artists"),
            watermarks = of("watermarks"),
            setTypes = of("setTypes"),
            layouts = of("layouts"),
            frames = of("frames"),
            borders = of("borders"),
            decks = decks,
        )
    }

    fun decodeDecks(cols: List<String>, rows: List<JsonArray>): List<DeckRef2> {
        val at = cols.withIndex().associate { (i, n) -> n to i }
        fun JsonArray.str(n: String) = at[n]?.let { i ->
            (getOrNull(i) as? kotlinx.serialization.json.JsonPrimitive)?.content
        }.orEmpty()
        return rows.map { DeckRef2(it.str("slug"), it.str("name"), it.str("owner")) }
    }

    /** The twelve answers, in the order `all` asked for them. */
    fun assemble(columns: List<List<String>>, decks: List<DeckRef2>) = Facets(
        types = columns.getOrElse(0) { emptyList() },
        sets = columns.getOrElse(1) { emptyList() },
        keywords = columns.getOrElse(2) { emptyList() },
        tags = columns.getOrElse(3) { emptyList() },
        formats = columns.getOrElse(4) { emptyList() },
        artists = columns.getOrElse(5) { emptyList() },
        watermarks = columns.getOrElse(6) { emptyList() },
        setTypes = columns.getOrElse(7) { emptyList() },
        layouts = columns.getOrElse(8) { emptyList() },
        frames = columns.getOrElse(9) { emptyList() },
        borders = columns.getOrElse(10) { emptyList() },
        decks = decks,
    )
}

/**
 * The accordion: which groups the panel has, what they are called, and
 * what counts as "this one is in use".
 *
 * Shared so the phone and the browser fold the same things away, and so
 * the badge on a header cannot say two different numbers.
 */
enum class Facet(val id: String, val title: String) {
    COLLECTION("collection", "Collection"),
    COLOUR("colour", "Colour"),
    TYPE("type", "Card type"),
    MANA("mana", "Mana & stats"),
    TEXT("text", "Text"),
    TAGS("tags", "Keywords & tags"),
    PRINTING("printing", "Rarity & printing"),
    PHYSICAL("physical", "Physical & digital"),
    FLAGS("flags", "Flags"),
    LEGALITY("legality", "Legality"),
    ;

    /** How many of this group's filters are set, for the header badge. */
    fun countIn(f: Filters): Int = when (this) {
        COLLECTION -> listOf(
            f.owner != "both", f.pool != Pool.ALL, f.qtyMin.isNotBlank(), f.qtyMax.isNotBlank(),
            f.freeMin.isNotBlank(), f.deck.isNotBlank(),
            f.edhrecMin.isNotBlank(), f.edhrecMax.isNotBlank(),
            f.priceMin.isNotBlank(), f.priceMax.isNotBlank(),
        ).count { it }

        COLOUR -> listOf(
            f.colors.isNotEmpty(), f.produces.isNotEmpty(),
            f.ciMin.isNotBlank(), f.ciMax.isNotBlank(),
        ).count { it }

        TYPE -> listOf(f.types.isNotEmpty(), f.typeLine.isNotBlank()).count { it }

        MANA -> listOf(
            f.cmcMin.isNotBlank(), f.cmcMax.isNotBlank(), f.manaCost.isNotBlank(),
            f.pow.isNotBlank(), f.tou.isNotBlank(), f.loy.isNotBlank(),
        ).count { it }

        TEXT -> listOf(
            f.q.isNotBlank(), f.text.isNotBlank(),
            f.flavor.isNotBlank(), f.artist.isNotBlank(), f.watermark.isNotBlank(),
        ).count { it }

        TAGS -> listOf(f.keywords.isNotEmpty(), f.tags.isNotEmpty()).count { it }

        PRINTING -> listOf(
            f.rarities.isNotEmpty(), f.finish.isNotBlank(), f.sets.isNotEmpty(),
            f.setTypes.isNotEmpty(), f.yearMin.isNotBlank(), f.yearMax.isNotBlank(),
            f.collnum.isNotBlank(),
        ).count { it }

        PHYSICAL -> listOf(
            f.layouts.isNotEmpty(), f.frames.isNotEmpty(),
            f.borders.isNotEmpty(), f.games.isNotEmpty(),
        ).count { it }

        FLAGS -> f.flags.count { it.value != Tri.ANY }

        LEGALITY -> listOf(f.format.isNotBlank(), f.hasRulings != Tri.ANY).count { it }
    }

    companion object {
        /** Any group holding a filter opens, so an arriving link shows its work. */
        fun inUse(f: Filters): Set<Facet> = entries.filter { it.countIn(f) > 0 }.toSet()
    }
}
