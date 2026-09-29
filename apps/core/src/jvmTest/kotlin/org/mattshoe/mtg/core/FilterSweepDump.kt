package org.mattshoe.mtg.core

import java.io.File
import kotlin.test.Test

/**
 * Writes one query per filter to `build/filter-sweep.json`.
 *
 * Not an assertion: `scripts/filter_sweep.py` posts these at the real
 * database and reports which ones error or return something daft. The
 * split is deliberate — the test suite still never touches the network,
 * and the thing that actually proves a filter works still exists.
 */
class FilterSweepDump {

    private val cases = buildList {
        fun case(name: String, f: Filters) = add(name to f)

        case("no filters", Filters())
        case("owner matt", Filters(owner = "matt"))
        case("owner kayla", Filters(owner = "kayla"))

        // colour, all four modes, both targets
        ColorMode.entries.forEach { m ->
            case("colour $m identity wu", Filters(colorMode = m, colors = listOf("W", "U")))
            case(
                "colour $m printed wu",
                Filters(colorMode = m, colors = listOf("W", "U"), colorTarget = ColorTarget.PRINTED),
            )
        }
        case("colourless exactly", Filters(colorMode = ColorMode.EXACTLY, colors = listOf("C")))
        case("anyof with colourless", Filters(colorMode = ColorMode.ANYOF, colors = listOf("R", "C")))
        case("colours in identity 2..3", Filters(ciMin = "2", ciMax = "3"))
        case("produces green", Filters(produces = listOf("G")))

        // words
        case("name contains bolt", Filters(q = "bolt"))
        // LIKE's own wildcards, which must be characters rather than
        // patterns — and which need an ESCAPE clause SQLite accepts.
        case("name contains a percent", Filters(q = "%"))
        case("name contains an underscore", Filters(q = "_"))
        case("name contains a backslash", Filters(q = "\\"))
        case("oracle text with punctuation", Filters(text = "+1/+1"))
        case("oracle text with an apostrophe", Filters(text = "opponent's"))
        case("oracle text with a hyphen", Filters(text = "non-creature"))
        case("oracle text draw", Filters(text = "draw"))
        case("literal text enters tapped", Filters())
        case("flavour goblin", Filters(flavor = "goblin"))
        case("artist guay", Filters(artist = "guay"))
        case("watermark prismari", Filters(watermark = "prismari"))
        case("type line artifact creature", Filters(typeLine = "artifact creature"))
        case("mana cost gg", Filters(manaCost = "{G}{G}"))
        case("collector number 117", Filters(collnum = "117"))

        // numbers
        case("mv 2..4", Filters(cmcMin = "2", cmcMax = "4"))
        case("power >= 5", Filters(powOp = ">=", pow = "5"))
        case("toughness <= 2", Filters(touOp = "<=", tou = "2"))
        case("loyalty >= 3", Filters(loyOp = ">=", loy = "3"))
        case("qty 2..4", Filters(qtyMin = "2", qtyMax = "4"))
        case("edhrec <= 500", Filters(edhrecMax = "500"))
        case("price 5..50", Filters(priceMin = "5", priceMax = "50"))
        case("year >= 2023", Filters(yearMin = "2023"))

        // types
        case("type Creature", Filters(types = listOf("Creature")))
        case("two types", Filters(types = listOf("Artifact", "Creature")))
        case("not type Land", Filters())
        case("supertype Legendary", Filters())

        // printing
        case("rarity mythic", Filters(rarities = listOf("mythic")))
        case("two rarities", Filters(rarities = listOf("rare", "mythic")))
        case("set mh3", Filters(sets = listOf("MH3")))
        case("set type commander", Filters(setTypes = listOf("commander")))
        case("layout saga", Filters(layouts = listOf("saga")))
        case("frame 2015", Filters(frames = listOf("2015")))
        case("border black", Filters(borders = listOf("black")))
        case("finish foil", Filters(finish = "foil"))
        case("game paper", Filters(games = listOf("paper")))

        // flags, each one both ways
        Flag.entries.forEach {
            case("flag ${it.slug} yes", Filters(flags = mapOf(it to Tri.YES)))
            case("flag ${it.slug} no", Filters(flags = mapOf(it to Tri.NO)))
        }

        // keywords, tags, legality
        case("keyword flying", Filters(keywords = listOf("Flying")))
        case("tag mana-rock", Filters(tags = listOf("mana-rock")))
        case("legal in commander", Filters(format = "commander", legality = "legal"))
        case("banned in commander", Filters(format = "commander", legality = "banned"))
        case("not legal in standard", Filters(format = "standard", legality = "not_legal"))
        case("has rulings", Filters(hasRulings = Tri.YES))
        case("no rulings", Filters(hasRulings = Tri.NO))

        // the collection itself
        case("pool free", Filters(pool = Pool.FREE))
        case("pool committed", Filters(pool = Pool.COMMITTED))
        case("free at least 2", Filters(freeMin = "2"))
        case("in any deck", Filters(deck = "_any"))
        case("in no deck", Filters(deck = "_none"))

        // sorting, which is part of the same statement
        Sort.entries.forEach { case("sort ${it.slug}", Filters(sort = it)) }

        // and a couple of combinations, because filters are ANDed and
        // that is where a stray join or alias shows up
        case(
            "commander staple",
            Filters(
                colorMode = ColorMode.ATMOST, colors = listOf("W", "U", "B"),
                types = listOf("Creature"), cmcMax = "3", pool = Pool.FREE,
                format = "commander", rarities = listOf("rare", "mythic"),
            ),
        )
        case(
            "everything at once",
            Filters(
                owner = "matt", q = "a", text = "draw", cmcMin = "1", cmcMax = "6",
                types = listOf("Creature"), rarities = listOf("rare"),
                colors = listOf("G"), colorMode = ColorMode.ATLEAST,
                flags = mapOf(Flag.REPRINT to Tri.NO), hasRulings = Tri.YES,
                priceMin = "1", yearMin = "2015", pool = Pool.ALL, freeMin = "1",
            ),
        )
    }

    @Test
    fun dumpEveryFilterAsAQuery() {
        val out = File("build/filter-sweep.json")
        out.parentFile.mkdirs()
        val json = buildString {
            append("[\n")
            cases.forEachIndexed { i, (name, f) ->
                val page = buildQuery(f)
                val count = buildQuery(f, countOnly = true)
                append("  {")
                append("\"name\":").append(quote(name)).append(",")
                append("\"sql\":").append(quote(page.sql)).append(",")
                append("\"count\":").append(quote(count.sql)).append(",")
                // Typed, not stringified. SQLite applies column
                // affinity to a text operand, so `c.cmc >= '2'` happens
                // to work — but a comparison against an expression
                // (`COALESCE(u.free, 0)`, the price CASE) has no
                // affinity to apply and text never equals a number.
                // Dumping every param as a string made eight working
                // filters look broken.
                append("\"params\":[").append(page.params.joinToString(",", transform = ::literal)).append("]")
                append("}")
                if (i < cases.size - 1) append(",")
                append("\n")
            }
            append("]\n")
        }
        out.writeText(json)
        println("wrote ${cases.size} filter cases to ${out.absolutePath}")
    }

    /** A JSON value of the right type, which is what the API binds. */
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
