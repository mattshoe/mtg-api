package org.mattshoe.mtg.core

/** One tile on the Guide's front page. Its slug is its address, `#/guide/<slug>`. */
data class GuideTile(val slug: String, val title: String, val blurb: String)

/**
 * A named colour combination.
 *
 * `pips` in the order the cards themselves print them — Gruul is RG,
 * Naya is RGW — which is not always WUBRG order. `Guild` sorts, so
 * the two still agree on the name.
 */
data class Combo(val pips: String, val name: String, val note: String = "")

/** A run of combinations of one size, with a line saying what they have in common. */
data class ComboGroup(val title: String, val about: String, val combos: List<Combo>)

/**
 * The Guide: tips and references, as tiles.
 *
 * Matt: "a helpful little tips and info and references and whatnot
 * ... for the first 'item' we add, i want a reference to all the
 * names for all the color combinations like izzet, dimir, etc etc.
 * all of them all the way through wubrg."
 *
 * All of it lives here and both shells draw it, so the phone and the
 * site cannot list different names for the same colours. A new tip is
 * a new [GuideTile] and a page for its slug on each side.
 */
object Guide {
    const val COLOURS = "colours"
    const val RULES = "rules"

    val tiles: List<GuideTile> = listOf(
        GuideTile(COLOURS, "Colour combinations", "Every name, from Azorius to WUBRG. Guilds, shards, wedges and the rest."),
        // Matt: "i want a guide section on rules". The page is in `Rules.kt`.
        GuideTile(RULES, "Rules", "The Comprehensive Rules, searchable. Every rule that mentions trample, or 702.19, one card each."),
    )

    fun tile(slug: String): GuideTile? = tiles.firstOrNull { it.slug == slug }

    /**
     * Every colour combination there is, one to five, by name.
     *
     * Named through [Guild] wherever it names them, so a deck's hero
     * and this page cannot disagree. The four-colour sets are the
     * exception: `Guild` says "Four-colour, no white", which is right
     * for a deck, and here the Nephilim names are the point.
     */
    val colours: List<ComboGroup> = listOf(
        ComboGroup(
            "One colour",
            "Mono-coloured. Where every deck starts.",
            listOf("W", "U", "B", "R", "G").map(::named),
        ),
        ComboGroup(
            "Allied pairs",
            "Two colours side by side on the colour wheel. Guilds of Ravnica.",
            listOf("WU", "UB", "BR", "RG", "GW").map(::named),
        ),
        ComboGroup(
            "Enemy pairs",
            "Two colours across the wheel from each other. Also guilds of Ravnica.",
            listOf("WB", "UR", "BG", "RW", "GU").map(::named),
        ),
        ComboGroup(
            "Shards",
            "A colour and both its neighbours. From Alara.",
            listOf("GWU", "WUB", "UBR", "BRG", "RGW").map(::named),
        ),
        ComboGroup(
            "Wedges",
            "A colour and both its enemies. From Tarkir.",
            listOf("WBG", "URW", "BGU", "RWB", "GUR").map(::named),
        ),
        ComboGroup(
            "Four colours",
            "Everything but one. Named for the Nephilim of Guildpact.",
            listOf(
                Combo("UBRG", "Glint-Eye", "No white. Also called Chaos."),
                Combo("BRGW", "Dune-Brood", "No blue. Also called Aggression."),
                Combo("RGWU", "Ink-Treader", "No black. Also called Altruism."),
                Combo("GWUB", "Witch-Maw", "No red. Also called Growth."),
                Combo("WUBR", "Yore-Tiller", "No green. Also called Artifice."),
            ),
        ),
        ComboGroup(
            "All five",
            "Every colour at once.",
            listOf(named("WUBRG").copy(note = "Also called WUBRG, or rainbow.")),
        ),
    )

    private fun named(pips: String) = Combo(pips, Guild.of(pips) ?: error("no name for $pips"))
}

/** The tile on screen, or null on the Guide's front page and everywhere else. */
val AppState.guideTile: GuideTile? get() = if (view == View.GUIDE) Guide.tile(route.rest) else null

/** A tile, at its own address, so back and a bookmark both work. */
fun AppState.openGuide(tile: GuideTile): AppState = navigate(Route(View.GUIDE, tile.slug))
