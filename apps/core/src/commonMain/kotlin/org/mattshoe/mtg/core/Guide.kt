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
    const val WHEEL = "wheel"
    const val RULES = "rules"
    const val PHYREXIA = "phyrexia"
    const val LORE = "lore"
    const val GAME_CHANGERS = "game-changers"
    const val BANNED = "banned"

    /**
     * Every story tile, in the order the story happened, roughly.
     *
     * Matt: "collapse all the Guide sections on lore (not the color
     * stuff) into a single top-level one called Lore ... keep them
     * sorted in roughly chronological order". A section spans eras,
     * so each sits where most of its story is: the void the planes
     * float in first, then the Thran, then the Brothers' War and on
     * to the Omenpaths. Their addresses do not change, so an old
     * bookmark still opens its story.
     */
    val lore: List<GuideTile> by lazy {
        val phyrexia = GuideTile(PHYREXIA, "Phyrexia", "The whole story, from the fall of the Thran to the March of the Machine, one era at a time.")
        // Matt: "each of those sections should be their own article, with
        // its own tabs, just like the phyrexian one". The words are in `MagicStory.kt`.
        val story = MagicStory.sections.associate { it.slug to GuideTile(it.slug, it.title, it.hook) }
        listOf(
            "blind-eternities", "phyrexia-origins", PHYREXIA, "brothers-war", "weatherlight", "planeswalkers",
            "ravnica", "nicol-bolas", "eldrazi", "innistrad", "new-phyrexia", "omenpaths",
        ).map { if (it == PHYREXIA) phyrexia else story.getValue(it) }
    }

    // Matt: "reorder the Guide sections such that they stay in order of:
    // rules, game changers, color combinations, color wheel".
    val tiles: List<GuideTile> = listOf(
        // Matt: "i want a guide section on rules". The page is in `Rules.kt`.
        GuideTile(RULES, "Rules", "The Comprehensive Rules, searchable. Every rule that mentions trample, or 702.19, one card each."),
        // Matt: "a guide section for game changers ... a list of cards
        // just like a deck page". The list is in `GameChangers.kt`.
        GuideTile(GAME_CHANGERS, "Game Changers", "The cards Commander's brackets count. Any one of them makes a deck bracket 3 or higher."),
        // Matt: "add a guide section below game Changers for banned
        // cards". The lists are in `Banned.kt`.
        GuideTile(BANNED, "Banned cards", "Every format's ban list. Commander first, then Standard, Pioneer, Modern, Legacy, Vintage and Pauper."),
        GuideTile(COLOURS, "Colour combinations", "Every name, from Azorius to WUBRG. Guilds, shards, wedges and the rest."),
        GuideTile(WHEEL, "The colour wheel", "What each colour wants, what it is good at, and who its friends and enemies are."),
        GuideTile(LORE, "Lore", "The story of Magic, from the Thran to the Omenpaths, in the order it happened."),
    )

    fun tile(slug: String): GuideTile? = tiles.firstOrNull { it.slug == slug } ?: lore.firstOrNull { it.slug == slug }

    /** The page a tile's way back leads to: Lore for a story, the Guide's front page for the rest. */
    fun up(tile: GuideTile): GuideTile? = if (tile in lore) tile(LORE) else null

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

    /**
     * Every combination as one list, for finding a name at a glance.
     *
     * Matt: "just a list of color combos and names, in wubrg order, so
     * they can easily be scanned to find what i need". Fewest colours
     * first, then WUBRG within each size, and each one's own letters
     * in WUBRG order too, so Selesnya is WG here and not GW. No notes:
     * those stay with the groups underneath.
     */
    val list: List<Combo> = colours.flatMap { it.combos }
        .map { Combo(wubrg(it.pips), it.name) }
        .sortedWith(compareBy<Combo> { it.pips.length }.thenBy { c -> c.pips.map { "WUBRG".indexOf(it) }.joinToString("") })

    private fun wubrg(pips: String) = "WUBRG".filter { it in pips }

    private fun named(pips: String) = Combo(pips, Guild.of(pips) ?: error("no name for $pips"))
}

/**
 * The tile on screen, or null on the Guide's front page and everywhere else.
 *
 * Only the first part of the address names the tile: a page of tabs
 * keeps its open tab after it, `#/guide/phyrexia/rath`.
 */
val AppState.guideTile: GuideTile? get() = if (view == View.GUIDE) Guide.tile(route.rest.substringBefore('/')) else null

/** A tile, at its own address, so back and a bookmark both work. */
fun AppState.openGuide(tile: GuideTile): AppState = navigate(Route(View.GUIDE, tile.slug))

/**
 * Where the open tile's way back leads: Lore for a story, the front
 * page for the rest. A story opened from a search goes back to the
 * search, which is still typed in, rather than to a Lore page the
 * person never passed through.
 */
private val AppState.guideAbove: GuideTile? get() = if (guideSearching) null else guideTile?.let(Guide::up)

/** What the open tile's back button says. */
val AppState.guideUpTo: String get() = guideAbove?.title ?: View.GUIDE.label

/** Out of the open tile, one level up. */
fun AppState.guideUp(): AppState = guideAbove?.let(::openGuide) ?: navigate(View.GUIDE)
