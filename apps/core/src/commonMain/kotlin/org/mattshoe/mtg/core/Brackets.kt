package org.mattshoe.mtg.core

/**
 * How far a bracket lets a deck go on one question: not at all, a
 * little, or as far as it likes. Drawn as a mark AND a word on both
 * shells, never a hue alone, because Matt is colourblind.
 */
enum class Allowed(val mark: String) { NO("✕"), SOME("~"), YES("✓") }

/** One question a bracket answers: Game Changers, combos and so on. */
data class BracketRule(val topic: String, val allowed: Allowed, val short: String, val detail: String = "")

/** One of Commander's five brackets. */
data class Bracket(
    val number: Int,
    val name: String,
    val tagline: String,
    val about: String,
    val games: String,
    val rules: List<BracketRule>,
)

/**
 * Commander's brackets, lowest first.
 *
 * Matt: "create a guide section which shows the rules on brackets ...
 * stylish and very intuitive to quickly skim over and understand ...
 * each bracket section should have its own tile or section or card or
 * something, and each should be collapsed by default ... sort them in
 * ascending order".
 *
 * Every bracket answers the same five questions in the same order, so
 * two open cards can be read side by side. Both shells draw them from
 * here. When Wizards change the brackets, this changes.
 */
object Brackets {

    /** The line over the cards, the same on both shells. */
    const val ABOUT = "Wizards' five brackets say how hard a Commander deck hits, so a table can agree on a game. " +
        "A deck sits in the lowest bracket whose rules it keeps. Tap a bracket to open it."

    private fun gameChangers(allowed: Allowed, short: String) = BracketRule("Game Changers", allowed, short)
    private fun combos(allowed: Allowed, short: String, detail: String = "") = BracketRule("Two-card combos", allowed, short, detail)
    private fun landDenial(allowed: Allowed, short: String) = BracketRule("Mass land denial", allowed, short)
    private fun extraTurns(allowed: Allowed, short: String, detail: String = "") = BracketRule("Extra turns", allowed, short, detail)
    private fun tutors(allowed: Allowed, short: String) = BracketRule("Tutors", allowed, short)

    private val anything = listOf(
        gameChangers(Allowed.YES, "Any number"),
        combos(Allowed.YES, "Any"),
        landDenial(Allowed.YES, "Allowed"),
        extraTurns(Allowed.YES, "Allowed"),
        tutors(Allowed.YES, "Any number"),
    )

    val all: List<Bracket> = listOf(
        Bracket(
            1, "Exhibition", "Theme first, winning second.",
            "Ultra-casual. Decks built around a theme, a joke or a story, where showing it off matters more than winning. Talk about it before the game.",
            "Expect at least nine turns.",
            listOf(
                gameChangers(Allowed.NO, "None"),
                combos(Allowed.NO, "None"),
                landDenial(Allowed.NO, "None"),
                extraTurns(Allowed.NO, "None"),
                tutors(Allowed.SOME, "A few"),
            ),
        ),
        Bracket(
            2, "Core", "About as strong as a precon.",
            "The average preconstructed deck and the decks built like one. Focused, but games are slow and nobody wins out of nowhere.",
            "Expect at least eight turns.",
            listOf(
                gameChangers(Allowed.NO, "None"),
                combos(Allowed.NO, "None"),
                landDenial(Allowed.NO, "None"),
                extraTurns(Allowed.SOME, "A few", "Never chained one after another or looped."),
                tutors(Allowed.SOME, "A few"),
            ),
        ),
        Bracket(
            3, "Upgraded", "A precon, tuned up.",
            "Stronger than a precon, with better cards and a sharper plan, but still a casual table. A big swing can end it.",
            "Expect at least six turns.",
            listOf(
                gameChangers(Allowed.SOME, "Up to three"),
                combos(Allowed.SOME, "Late only", "Nothing that wins on the spot early in the game."),
                landDenial(Allowed.NO, "None"),
                extraTurns(Allowed.SOME, "A few", "Never chained one after another or looped."),
                tutors(Allowed.YES, "Any number"),
            ),
        ),
        Bracket(
            4, "Optimized", "The strongest deck you can build.",
            "High power with no rules but the ban list. Fast mana, tutors, combos and every Game Changer you like, without tuning for a tournament.",
            "Expect at least four turns.",
            anything,
        ),
        Bracket(
            5, "cEDH", "Tournament Commander.",
            "Competitive. The same freedom as bracket 4, built and played to win against the competitive metagame. Every choice is about winning.",
            "A game can end on any turn.",
            anything,
        ),
    )
}
