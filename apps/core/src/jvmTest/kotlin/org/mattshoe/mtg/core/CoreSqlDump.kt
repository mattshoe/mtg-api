package org.mattshoe.mtg.core

import java.io.File
import kotlin.test.Test
import kotlin.test.fail

/**
 * Every statement the core can emit, written where the Worker's own
 * suite can run it.
 *
 * The gap this closes: the Kotlin tests assert on SQL *text*. They will
 * happily agree that a statement says what it should while the column
 * it names does not exist. `DeckQueries.cards` selected `SUM(t.qty)`
 * from `totals`, whose column is `total_qty` and which is already
 * grouped — so every test passed and opening any deck answered "no such
 * column: t.qty". Nothing in 500-odd tests ran it against a database.
 *
 * So this writes the statements out, and `test/core-sql.test.js`
 * executes each one against a real SQLite with the real schema. The
 * file is committed: regenerating it here and finding it changed is the
 * failure, which keeps the two halves in step without either build
 * having to run the other.
 */
class CoreSqlDump {

    private val out = File("../../test/fixtures/core-sql.json")

    /** Named so a failure in the JS half says which query broke. */
    private fun cases(): List<Triple<String, String, List<Any?>>> = buildList {
        fun case(name: String, s: Sql) = add(Triple(name, s.sql, s.params))

        // --- the Library, the one place filters reach SQL
        val library = Library()
        val (page, count) = Load.library(library)
        case("library page", page)
        case("library count", count)
        val loaded = Library(
            Filters(
                owner = "matt", q = "a", text = "draw", cmcMin = "1", cmcMax = "6",
                types = listOf("Creature"), typesNot = listOf("Land"),
                supertypes = listOf("Legendary"), rarities = listOf("rare"),
                colors = listOf("G"), colorMode = ColorMode.ATLEAST,
                flags = mapOf(Flag.REPRINT to Tri.NO), hasRulings = Tri.YES,
                priceMin = "1", yearMin = "2015", pool = Pool.FREE, freeMin = "1",
                keywords = listOf("Flying"), tags = listOf("ramp"), deck = "_any",
                format = "commander", sets = listOf("MH3"), games = listOf("paper"),
                qtyMin = "1", qtyMax = "9", collnum = "1", artist = "guay",
            ),
        )
        val (fullPage, fullCount) = Load.library(loaded)
        case("library page, every filter at once", fullPage)
        case("library count, every filter at once", fullCount)
        // Every sort is a different ORDER BY over the same aggregate.
        Sort.entries.forEach { s ->
            case("library sorted by ${s.slug}", Load.library(Library(Filters(sort = s))).first)
        }
        case("export", Load.export(Filters(owner = "matt")))

        // --- everything else, which no test has ever executed
        case("decks", Load.decks())
        case("one deck", Load.deck("a-deck"))
        Owner.entries.forEach { o -> case("stats for ${o.slug}", Load.stats(StatsScope(o))) }
        case("stats for both", Load.stats(StatsScope()))
        case("stats per owner", StatsQueries.perOwner())
        Load.card("sol ring", "matt").forEachIndexed { i, s -> case("card detail $i", s) }
        case("palette find", Load.find("sol"))
        FacetQueries.all.forEachIndexed { i, s -> case("facet $i", s) }
        // `parseQueryBox` answers with a WHERE fragment rather than a
        // statement, so it is run the only way it is ever run: folded
        // into the Library's own query.
        case(
            "library with a query-box expression",
            Load.library(Library(Filters(adv = "owner:matt cmc<=2 t:creature"))).first,
        )
    }

    @Test
    fun theCommittedListMatchesWhatTheCoreActuallyEmits() {
        val json = render(cases())
        val before = if (out.exists()) out.readText() else ""
        if (before != json) {
            out.parentFile.mkdirs()
            out.writeText(json)
            if (before.isEmpty()) {
                println("wrote ${cases().size} statements to ${out.canonicalPath}")
            } else {
                fail(
                    "The core emits different SQL than test/fixtures/core-sql.json holds.\n" +
                        "It has been rewritten — commit it, and `npm test` will run the new " +
                        "statements against a real database.",
                )
            }
        }
    }

    private fun render(cases: List<Triple<String, String, List<Any?>>>): String = buildString {
        append("[\n")
        cases.forEachIndexed { i, (name, sql, params) ->
            append("  {")
            append("\"name\": ").append(quote(name)).append(", ")
            append("\"sql\": ").append(quote(sql)).append(", ")
            // Typed, not stringified: SQLite applies column affinity to
            // a bare column but not to an expression, so a number sent
            // as text silently matches nothing.
            append("\"params\": [").append(params.joinToString(", ", transform = ::literal)).append("]")
            append("}")
            if (i < cases.size - 1) append(",")
            append("\n")
        }
        append("]\n")
    }

    private fun literal(v: Any?): String = when (v) {
        null -> "null"
        is Number -> v.toString()
        is Boolean -> v.toString()
        else -> quote(v.toString())
    }

    private fun quote(s: String): String = buildString {
        append('"')
        s.forEach {
            when (it) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (it.code < 0x20) append("\\u%04x".format(it.code)) else append(it)
            }
        }
        append('"')
    }
}
