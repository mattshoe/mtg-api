package org.mattshoe.mtg.core

/**
 * One word of a search box.
 *
 * `phrase` means it was written in quotes and has to match as written,
 * spaces and all. `negated` means it was written with a `!` and has to
 * not match.
 */
data class Term(val text: String, val phrase: Boolean = false, val negated: Boolean = false)

/**
 * What a person types into a search box, the way Scryfall reads it.
 *
 * Every box in the filter panel used to take whatever was typed and
 * look for that exact run of characters. So "draw card" found nothing
 * unless a card said precisely "draw card", and the oracle-text box was
 * worse: it handed the whole string to FTS5 as one quoted phrase, which
 * is the opposite of what a full-text index is for.
 *
 * Now:
 *
 *   `draw card`        both words, anywhere, in any order
 *   `"draw a card"`    that run of words, as written
 *   `!token`           must not contain it
 *   `!"enters tapped"` must not contain that phrase
 *
 * Everything is case-insensitive, always — the LIKE columns are
 * compared lowercased on both sides and FTS5's tokenizer folds case
 * itself.
 */
object TextQuery {

    /**
     * Split the way a shell splits a command line: whitespace
     * separates, quotes group, and a `!` in front of either negates.
     *
     * An unclosed quote takes the rest of the string rather than
     * being an error. Somebody halfway through typing `"enters tap`
     * should see results for what they have so far, not a red banner.
     */
    fun parse(raw: String): List<Term> {
        val out = mutableListOf<Term>()
        var i = 0
        val s = raw
        while (i < s.length) {
            while (i < s.length && s[i].isWhitespace()) i++
            if (i >= s.length) break

            var negated = false
            // `!` only negates when it leads a word. Inside one it is
            // a character like any other, which matters for a card
            // whose text really does contain "!".
            if (s[i] == '!' && i + 1 < s.length && !s[i + 1].isWhitespace()) {
                negated = true
                i++
            }

            if (i < s.length && s[i] == '"') {
                i++
                val start = i
                while (i < s.length && s[i] != '"') i++
                val text = s.substring(start, i)
                if (i < s.length) i++
                if (text.isNotBlank()) out += Term(text.trim(), phrase = true, negated = negated)
                continue
            }

            val start = i
            while (i < s.length && !s[i].isWhitespace()) i++
            val text = s.substring(start, i)
            // A `!` with nothing after it is somebody mid-keystroke.
            // Inside a word it is an ordinary character — there are
            // cards whose text really does contain one.
            if (text.isNotBlank() && text != "!") {
                out += Term(text, phrase = false, negated = negated)
            }
        }
        return out
    }

    /** True when the box has nothing to search for yet. */
    fun isEmpty(raw: String): Boolean = parse(raw).isEmpty()

    /**
     * The terms as an FTS5 expression.
     *
     * Every term is quoted, including single words: FTS5 parses its
     * argument as a query, so a bare `+1/+1` or `Landfall:` is a
     * syntax error rather than a search. Quoting makes it a phrase,
     * and a one-word phrase is the same as the bare token — stemming
     * included, because a phrase is matched through the tokenizer too.
     */
    fun fts(terms: List<Term>, joiner: String): String =
        terms.joinToString(" $joiner ") { "\"" + it.text.replace("\"", "\"\"") + "\"" }
}
