package org.mattshoe.mtg.core

/**
 * EDHREC: how popular a card is in Commander, and where to read more.
 *
 * Matt: "I want the carousel to show edhrec rank on it" and "a link to
 * edhrec for that card". The rank has been in `cards.edhrec_rank`
 * since the first import.
 */
object Edhrec {

    /** `#20,135`, or null for a card EDHREC has not ranked. */
    fun number(rank: Long?): String? {
        if (rank == null) return null
        val digits = rank.toString()
        val grouped = digits.reversed().chunked(3).joinToString(",").reversed()
        return "#$grouped"
    }

    /** `EDHREC #20,135`, which is how the carousel says it. */
    fun rankText(rank: Long?): String? = number(rank)?.let { "EDHREC $it" }

    /**
     * Layouts whose EDHREC page is named after both halves. Checked
     * against the live site: `commit-memory`, `cut-ribbons`. Every
     * other two-named card is filed under its front face.
     */
    private val BOTH_HALVES = setOf("split", "aftermath")

    /** Letters with marks on them, folded the way EDHREC folds them. */
    private val FOLD = mapOf(
        'à' to "a", 'á' to "a", 'â' to "a", 'ã' to "a", 'ä' to "a", 'å' to "a", 'æ' to "ae",
        'ç' to "c", 'è' to "e", 'é' to "e", 'ê' to "e", 'ë' to "e",
        'ì' to "i", 'í' to "i", 'î' to "i", 'ï' to "i", 'ñ' to "n",
        'ò' to "o", 'ó' to "o", 'ô' to "o", 'õ' to "o", 'ö' to "o", 'ø' to "o",
        'ù' to "u", 'ú' to "u", 'û' to "u", 'ü' to "u", 'ý' to "y", 'ÿ' to "y",
    )

    fun slug(name: String, layout: String?): String {
        val halves = name.split("//").map { it.trim() }.filter { it.isNotEmpty() }
        val named = if (layout in BOTH_HALVES) halves.joinToString(" ") else halves.firstOrNull().orEmpty()
        val folded = buildString {
            named.lowercase().forEach { c -> append(FOLD[c] ?: c.toString()) }
        }
        return folded
            .replace(Regex("""['’"]"""), "")
            .replace(Regex("""[^a-z0-9]+"""), "-")
            .trim('-')
    }

    fun url(name: String, layout: String?): String = "https://edhrec.com/cards/" + slug(name, layout)
}
