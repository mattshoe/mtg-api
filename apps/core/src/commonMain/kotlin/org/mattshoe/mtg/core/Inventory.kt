package org.mattshoe.mtg.core

/**
 * Everything the hand-written web app does, enumerated, with a flag for
 * whether the multiplatform build does it yet.
 *
 * This exists because "port the whole app" is the kind of job that ends
 * with three screens quietly missing and nobody noticing until someone
 * needs one. A list in a commit message does not stop that. A test that
 * fails while anything here is `done = false` does.
 *
 * The rule: a feature flips to `done` only when there is a test
 * exercising it on both platforms. Flipping it because the code exists is
 * how the list becomes a lie.
 *
 * Taken from the routes in `app.js` and the exported surface of every
 * module in `frontend/js`, at 5,682 lines across 19 modules.
 */
enum class Area(val label: String) {
    LIBRARY("Library"),
    DECKS("Decks"),
    STATS("Stats"),
    QUERY("Query console"),
    ENTRY("Mass entry"),
    LOGS("Server logs"),
    CARD("Card detail"),
    SHELL("Shell and navigation"),
    ADMIN("Admin"),
    SHARE("Android share"),
}

data class Feature(
    val area: Area,
    /** What it does, in the words a person would use. */
    val what: String,
    /** Where it lives today, so the port has something to read. */
    val source: String,
    /**
     * The rules are in the shared core with tests on both targets.
     * Necessary for `done` and nowhere near sufficient — a search that
     * builds the right SQL is not a screen anyone can use.
     */
    val logic: Boolean = false,
    /** Usable end to end on Android and on the web. The only flag that counts. */
    val done: Boolean = false,
) {
    init {
        require(!done || logic) { "$what claims to be done without its logic ported" }
    }
}

/**
 * The manifest. Adding a row is how a gap gets recorded; flipping `done`
 * is how it gets closed.
 */
object Inventory {

    val features: List<Feature> = listOf(
        // ---------------------------------------------------------- shell
        Feature(Area.SHELL, "Hash routing between the six views", "app.js"),
        Feature(Area.SHELL, "Nav tabs, with the admin group hidden until unlocked", "app.js, index.html"),
        Feature(Area.SHELL, "Keyboard shortcuts and the ? help toast", "app.js"),
        Feature(Area.SHELL, "Quick find palette on ⌘K and /", "app.js"),
        Feature(Area.SHELL, "Back button dismisses overlays instead of navigating", "overlay.js"),
        Feature(Area.SHELL, "Toasts", "util.js"),

        // -------------------------------------------------------- library
        Feature(Area.LIBRARY, "Card grid, 100 per page, with paging", "search.js", logic = true, done = true),
        Feature(Area.LIBRARY, "Filter panel: owner, pool, deck, finish, quantity", "filters.js", logic = true),
        Feature(Area.LIBRARY, "Filter panel: name, oracle text, flavour, artist, watermark, type line", "filters.js", logic = true),
        Feature(Area.LIBRARY, "Colour filter with exactly / at most / at least / any of", "filters.js", logic = true),
        Feature(Area.LIBRARY, "Filter panel: cmc, power, toughness, rarity, set, keyword, tag, format", "filters.js", logic = true),
        Feature(Area.LIBRARY, "Boolean flags — reserved, game changer, full art and the rest", "filters.js", logic = true),
        Feature(Area.LIBRARY, "Advanced query box with its own parser", "filters.js parseAdvanced"),
        Feature(Area.LIBRARY, "Sorting, price descending by default", "filters.js SORTS", logic = true, done = true),
        Feature(Area.LIBRARY, "Filter state in the URL, so a search is a link", "filters.js toHash/fromHash"),
        Feature(Area.LIBRARY, "Name autocomplete against all of Scryfall", "complete.js"),
        Feature(Area.LIBRARY, "Export the whole result as a decklist or to the clipboard", "search.js"),
        Feature(Area.LIBRARY, "Prices fetched and shown, with a reason when missing", "prices.js"),

        // ---------------------------------------------------------- decks
        Feature(Area.DECKS, "Deck tiles: name, colour pips, commander, bracket, art banner", "decks.js", logic = true, done = true),
        Feature(Area.DECKS, "Deck detail with its card list", "decks.js", logic = true, done = true),
        Feature(Area.DECKS, "Edit a deck's list, commander as its own field", "decks.js"),
        Feature(Area.DECKS, "Disassemble a deck back into bulk", "decks.js"),
        Feature(Area.DECKS, "New deck wizard: format, owner, name, commander, cards, sourcing", "newdeck.js"),
        Feature(Area.DECKS, "Card name validation against Scryfall in the wizard", "newdeck.js"),

        // ---------------------------------------------------------- stats
        Feature(Area.STATS, "Collection totals and breakdowns", "stats.js", logic = true, done = true),
        Feature(Area.STATS, "Per-owner scoping at #/stats/matt and /kayla", "stats.js", logic = true, done = true),

        // ---------------------------------------------------------- query
        Feature(Area.QUERY, "Free SQL against the collection, read-only", "console.js"),
        Feature(Area.QUERY, "Schema cheatsheet", "cheatsheet.js"),

        // ---------------------------------------------------------- entry
        Feature(Area.ENTRY, "Four step wizard: which, list, who, review", "manage.js", logic = true, done = true),
        Feature(Area.ENTRY, "Mandatory dry run before any write", "manage.js", logic = true, done = true),
        Feature(Area.ENTRY, "Owner never preselected", "manage.js", logic = true, done = true),
        Feature(Area.ENTRY, "Decklist and CSV parsing", "manage.js, parse.js", logic = true, done = true),
        Feature(Area.ENTRY, "File upload into the list box", "manage.js"),
        Feature(Area.ENTRY, "Recent history, with reuse", "manage.js"),

        // ----------------------------------------------------------- card
        Feature(Area.CARD, "Card detail drawer with art, prices, legalities, rulings", "card.js"),
        Feature(Area.CARD, "Which decks a card is in, and how many are free", "card.js"),

        // ----------------------------------------------------------- logs
        Feature(Area.LOGS, "Request log with filtering", "logs.js"),
        Feature(Area.LOGS, "Log summary counts", "logs.js"),

        // ---------------------------------------------------------- admin
        Feature(Area.ADMIN, "Password unlock, token kept until locked", "admin.js"),
        Feature(Area.ADMIN, "Gated views unreachable and invisible while locked", "app.js, admin.js"),

        // ---------------------------------------------------------- share
        Feature(Area.SHARE, "Receive a shared file from another Android app", "SharedFile.kt", logic = true, done = true),
        Feature(Area.SHARE, "Read it whatever its declared MIME type", "SharedFile.kt", logic = true, done = true),
        Feature(Area.SHARE, "Say what arrived when nothing usable did", "SharedFile.kt", logic = true, done = true),
    )

    val done: List<Feature> get() = features.filter { it.done }
    val logicOnly: List<Feature> get() = features.filter { it.logic && !it.done }
    val remaining: List<Feature> get() = features.filterNot { it.done }
    val untouched: List<Feature> get() = features.filterNot { it.logic || it.done }

    val percentDone: Int
        get() = if (features.isEmpty()) 100 else done.size * 100 / features.size

    /** What is left, grouped, for a build to print rather than a person to guess. */
    fun report(): String = buildString {
        appendLine(
            "Ported ${done.size} of ${features.size} features (${percentDone}%). " +
                "${logicOnly.size} more have their rules shared but no screen yet.",
        )
        Area.entries.forEach { area ->
            val inArea = features.filter { it.area == area }
            if (inArea.isEmpty()) return@forEach
            val left = inArea.count { !it.done }
            appendLine("  ${area.label}: ${inArea.size - left}/${inArea.size}")
            inArea.filterNot { it.done }.forEach {
                val state = if (it.logic) "rules only" else "todo     "
                appendLine("      $state  ${it.what}  [${it.source}]")
            }
        }
    }
}
