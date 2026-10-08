package org.mattshoe.mtg.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/** One line of the card page's Details section. */
data class Fact(val label: String, val value: String)

/** A heading and the facts under it. */
data class FactGroup(val title: String, val facts: List<Fact>)

/**
 * Everything the database holds about the printing on the card page.
 *
 * Matt: "The full card details needs to show EVERYTHING". The page
 * selected twelve of the fifty columns of `cards` and none of the
 * tables hanging off it, so the artist, the frame, the keywords, the
 * finishes and the EDHREC rank were all in the database and nowhere
 * on screen.
 *
 * One printing, the same one the picture is of — see [query]. The
 * values are kept as the database sent them, keyed by column, and
 * [groups] is the single definition of what the Details section says.
 * Both shells draw [groups] and decide nothing about it.
 *
 * [SHOWN] and [NOT_SHOWN] together have to cover every column of
 * `cards` and every `card_*` table. `CardFactsCoverageTest` reads
 * `schema.sql` and fails, by name, for anything in neither.
 */
class CardFacts(private val values: Map<String, String?>) {

    /** One column's value, or null when it holds nothing. */
    fun value(column: String): String? = values[column]?.takeIf { it.isNotBlank() }

    val layout: String? get() = value("layout")
    val scryfallId: String? get() = value("scryfall_id")
    val edhrecRank: Long? get() = value("edhrec_rank")?.toLongOrNull()

    /**
     * The card, then this printing. A column holding nothing says
     * nothing rather than printing a dash, and a group with nothing
     * in it is not drawn.
     */
    val groups: List<FactGroup>
        get() = GROUPS.map { (title, specs) ->
            FactGroup(
                title,
                specs.mapNotNull { s -> s.say(this)?.takeIf { it.isNotBlank() }?.let { Fact(s.label, it) } },
            )
        }.filter { it.facts.isNotEmpty() }

    val rows: List<Fact> get() = groups.flatMap { it.facts }

    private class Spec(val label: String, val columns: List<String>, val say: (CardFacts) -> String?)

    companion object {

        private fun text(label: String, column: String) = Spec(label, listOf(column)) { it.value(column) }

        /** SQLite has no booleans. Said both ways: "No" is a fact too. */
        private fun flag(label: String, column: String) = Spec(label, listOf(column)) {
            when (it.value(column)) {
                null -> null
                "0", "false" -> "No"
                else -> "Yes"
            }
        }

        private val GROUPS: List<Pair<String, List<Spec>>> = listOf(
            "The card" to listOf(
                text("Layout", "layout"),
                Spec("Mana value", listOf("cmc")) { f ->
                    f.value("cmc")?.let { v -> v.toDoubleOrNull()?.let { if (it % 1.0 == 0.0) it.toLong().toString() else v } ?: v }
                },
                text("Supertypes", "supertypes"),
                text("Types", "types"),
                text("Subtypes", "subtypes"),
                text("Colours", "colors"),
                text("Colour identity", "color_identity"),
                text("Produces", "produced_mana"),
                text("Keywords", "keywords"),
                Spec("EDHREC rank", listOf("edhrec_rank")) { Edhrec.number(it.edhrecRank) },
                flag("Reserved list", "reserved"),
                flag("Game changer", "game_changer"),
                text("Tags", "tags"),
                text("Oracle ID", "oracle_id"),
            ),
            "This printing" to listOf(
                Spec("Set", listOf("set_name", "setcode")) { f ->
                    val code = f.value("setcode")?.uppercase()
                    when (val name = f.value("set_name")) {
                        null -> code
                        else -> if (code == null) name else "$name ($code)"
                    }
                },
                text("Set type", "set_type"),
                text("Collector number", "collector_number"),
                text("Rarity", "rarity"),
                text("Released", "released_at"),
                text("Artist", "artist"),
                text("Frame", "frame"),
                text("Border", "border_color"),
                text("Watermark", "watermark"),
                text("Security stamp", "security_stamp"),
                text("Finishes", "finishes"),
                text("Games", "games"),
                text("Promo types", "promo_types"),
                text("Frame effects", "frame_effects"),
                flag("Full art", "full_art"),
                flag("Textless", "textless"),
                flag("Promo", "promo"),
                flag("Reprint", "reprint"),
                flag("Variation", "variation"),
                flag("Oversized", "oversized"),
                flag("Story spotlight", "story_spotlight"),
                flag("In boosters", "booster"),
                text("Scryfall ID", "scryfall_id"),
            ),
        )

        /** Every column, and every child table's alias, that [groups] reads. */
        val SHOWN: Set<String> = GROUPS.flatMap { (_, specs) -> specs.flatMap { it.columns } }.toSet()

        /**
         * What is deliberately left out of Details, and why. Each is
         * either on the page already or means nothing to a person.
         */
        val NOT_SHOWN: Map<String, String> = mapOf(
            "id" to "the row's key, which means nothing to a person",
            "owner" to "already on the page, under Who owns it and on every printing line",
            "qty" to "already on the page, as the owned count",
            "finish" to "already on every printing line; this printing's finishes are shown",
            "foil_flag" to "the import's own spelling of finish",
            "name" to "the page's title",
            "name_norm" to "the key the page is opened by",
            "face1" to "the front half of the title",
            "face2" to "the back half of the title",
            "mana_cost" to "printed in the face panels",
            "type_line" to "printed in the face panels",
            "oracle_text" to "printed in the face panels",
            "flavor_text" to "printed in the face panels",
            "power" to "printed in the face panels",
            "toughness" to "printed in the face panels",
            "loyalty" to "printed in the face panels",
            "defense" to "printed in the face panels",
            "color_identity_count" to "the number of letters in Colour identity, which is shown",
            "card_faces" to "drawn as the face panels",
            "card_colors" to "the same letters as colors, color_identity and produced_mana, which are shown",
            "card_types" to "the same words as supertypes, types and subtypes, which are shown",
        )

        /**
         * The printing the picture is of, every column of it, and one
         * line per child table.
         *
         * Ordered exactly as [CardQueries.printings] is, because the
         * picture is `printings.first()` and an artist line about a
         * different printing names somebody who did not paint it.
         *
         * Each child table is its own correlated subquery. Joined, a
         * card with two keywords and two finishes comes back as four
         * rows, and every list on the page doubles.
         */
        fun query(nameNorm: String) = Sql(
            """WITH pick AS (
                    SELECT c.* FROM cards c WHERE c.name_norm = ?
                     ORDER BY ${CardQueries.PRINTING_ORDER}
                     LIMIT 1)
               SELECT pick.*,
                      ${list("keyword", "card_keywords")} AS keywords,
                      ${list("finish", "card_finishes")} AS finishes,
                      ${list("game", "card_games")} AS games,
                      ${list("promo_type", "card_promo_types")} AS promo_types,
                      ${list("frame_effect", "card_frame_effects")} AS frame_effects,
                      (SELECT group_concat(v, ', ') FROM (
                          SELECT DISTINCT COALESCE(t.label, ct.tag_slug) AS v
                            FROM card_tags ct
                            LEFT JOIN tags t ON t.slug = ct.tag_slug AND t.kind = ct.kind
                           WHERE ct.card_id = pick.id ORDER BY v)) AS tags
                 FROM pick""",
            listOf(nameNorm),
        )

        private fun list(column: String, table: String) =
            "(SELECT group_concat(v, ', ') FROM (" +
                "SELECT DISTINCT $column AS v FROM $table WHERE card_id = pick.id ORDER BY v))"

        fun decode(cols: List<String>, rows: List<JsonArray>): CardFacts? {
            val row = rows.firstOrNull() ?: return null
            return CardFacts(
                cols.withIndex().associate { (i, name) ->
                    name to row.getOrNull(i)?.let { v -> if (v is JsonNull) null else (v as? JsonPrimitive)?.content }
                },
            )
        }
    }
}
