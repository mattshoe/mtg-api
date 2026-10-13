package org.mattshoe.mtg.core

/**
 * One numbered rule of the Comprehensive Rules.
 *
 * `number` as the file prints it: `702.19.` for a rule, `702.19b` for a
 * subrule. `examples` are the "Example:" paragraphs printed under it,
 * without the word. `section` is the three-digit heading it sits under,
 * `702. Keyword Abilities`, so a card can say where a rule comes from.
 */
data class Rule(
    val number: String,
    val text: String,
    val examples: List<String> = emptyList(),
    val section: String = "",
) {
    /** `702.19`, not `702.19.`: the trailing dot is the file's, not the rule's name. */
    val shownNumber: String get() = number.removeSuffix(".")

    /** Everything a search looks in, lower case, apostrophes made straight. */
    internal val haystack: String by lazy { RuleBook.fold("$number $text ${examples.joinToString(" ")}") }
}

/**
 * The Comprehensive Rules, parsed.
 *
 * Matt: "Ideally, this would use the live rule set, then parse it out
 * on-device to save server processing power". Wizards publishes the
 * rules as one text file; the Worker hands it over untouched and this
 * reads it, on the phone and in the browser alike, so neither can
 * number a rule differently from the other.
 */
data class RuleBook(val effective: String = "", val rules: List<Rule> = emptyList()) {

    /**
     * Every rule that mentions every word of [query], in the book's
     * order. Case does not matter, a straight apostrophe finds the curly
     * one the file prints, and a rule number finds that rule and its
     * subrules. Blank finds nothing.
     */
    fun search(query: String): List<Rule> {
        val words = fold(query).split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        return rules.filter { r -> words.all { it in r.haystack } }
    }

    companion object {
        /** `100.1.`, `100.1a`, `702.19.`: a number, then the rule. */
        private val NUMBERED = Regex("""^(\d{3}\.\d+(?:[a-z]+|\.))\s+(.*)$""")

        /** `702. Keyword Abilities`: a section, which every rule after it sits in. */
        private val SECTION = Regex("""^\d{3}\.\s+\S.*$""")

        private const val EXAMPLE = "Example:"

        internal fun fold(s: String): String =
            s.lowercase().replace('’', '\'').replace('‘', '\'').replace(Regex("""\s+"""), " ").trim()

        /**
         * The rules out of the file.
         *
         * The contents at the top list only sections, never a numbered
         * rule, so everything before the first numbered rule is skipped
         * without needing to find where the contents end. The glossary
         * starts at the line `Glossary` after that and is not rules.
         * Anything that is not the rules file at all is no rules, not a
         * crash.
         */
        fun parse(text: String): RuleBook {
            val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n').map { it.trim() }
            val effective = lines.firstOrNull { it.startsWith("These rules are effective as of") }.orEmpty()
            val start = lines.indexOfFirst { NUMBERED.matches(it) }
            if (start < 0) return RuleBook(effective)

            var section = lines.subList(0, start).lastOrNull { SECTION.matches(it) }.orEmpty()
            val rules = mutableListOf<Rule>()
            for (line in lines.subList(start, lines.size)) {
                if (line == "Glossary") break
                if (line.isEmpty()) continue
                val numbered = NUMBERED.matchEntire(line)
                when {
                    numbered != null -> rules += Rule(numbered.groupValues[1], numbered.groupValues[2], section = section)
                    SECTION.matches(line) -> section = line
                    line.startsWith(EXAMPLE) && rules.isNotEmpty() -> {
                        val last = rules.removeAt(rules.size - 1)
                        rules += last.copy(examples = last.examples + line.removePrefix(EXAMPLE).trim())
                    }
                }
            }
            return RuleBook(effective, rules)
        }
    }
}

/**
 * The Guide's rules page: the book once it has loaded, what was typed,
 * and which cards were folded away.
 *
 * Folded rather than opened, because every card starts open — Matt:
 * "i want each rule to be collapsible, but expanded my default".
 */
data class Rules(
    val book: RuleBook? = null,
    val busy: Boolean = false,
    val error: String? = null,
    val query: String = "",
    val collapsed: Set<String> = emptySet(),
) {
    private val found: List<Rule> by lazy { book?.search(query).orEmpty() }

    /** How many rules match, all of them. */
    val total: Int get() = found.size

    /** The ones on screen: the first [SHOWN], so a search for "a" does not draw three thousand cards. */
    val hits: List<Rule> get() = found.take(SHOWN)

    /** The line above the cards. */
    val count: String
        get() = when {
            total > hits.size -> "Showing ${hits.size} of $total rules. Add a word to narrow it."
            total == 1 -> "1 rule"
            else -> "$total rules"
        }

    fun isOpen(rule: Rule): Boolean = rule.number !in collapsed

    fun toggled(rule: Rule): Rules =
        copy(collapsed = if (rule.number in collapsed) collapsed - rule.number else collapsed + rule.number)

    fun searched(query: String): Rules = copy(query = query)

    fun loading(): Rules = copy(busy = true, error = null)

    fun loaded(text: String): Rules = copy(book = RuleBook.parse(text), busy = false, error = null)

    /** No spinner left behind, and the reason on screen, so Try again is the obvious next thing. */
    fun failed(message: String): Rules = copy(busy = false, error = message)

    companion object {
        const val SHOWN = 150
    }
}

/** Is the rules page the one on screen. */
val AppState.onRules: Boolean get() = guideTile?.slug == Guide.RULES
