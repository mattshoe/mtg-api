package org.mattshoe.mtg.core

/**
 * Reading a pasted or shared card list.
 *
 * This logic exists three times on main right now — `src/parse.js` on the
 * Worker, `countCards`/`looksLikeCsv` in `manage.js` on the web, and the
 * same two again in `SharedList.kt` on Android. Three implementations of
 * one set of rules about CSV headers, comment lines and section markers,
 * already free to disagree. This is the one copy.
 */
object DeckList {

    /**
     * The invisible character a Windows editor writes at the front.
     *
     * Notepad and several spreadsheet exports start a UTF-8 file with
     * a byte-order-mark, and `trim()` does not remove it because it is
     * not whitespace. Every pattern here is anchored with `^`, so the
     * mark sat in front of the anchor and the first line of such a
     * file lost its quantity in silence: "4 Sol Ring" came back as one
     * card literally named that. It is never part of a card's name, so
     * it comes off before anything reads the text.
     */
    private const val BOM = '\uFEFF'

    private fun String.noBom(): String =
        if (BOM in this) filterNot { it == BOM } else this

    /** Lines that are structure rather than cards. */
    private val COMMENT = Regex("^(#|//)")
    private val SECTION = Regex("^(deck|sideboard|maybeboard|commander|companion)\\s*:?\\s*$", RegexOption.IGNORE_CASE)

    /**
     * A CSV needs a header naming a card column. Commas alone are not
     * enough — "1 Kardur, Doomscourge" is a decklist line, and treating it
     * as CSV is how a comma once ate half a card name.
     */
    fun looksLikeCsv(text: String): Boolean {
        val first = text.noBom().lineSequence().firstOrNull { it.isNotBlank() } ?: return false
        if (!first.contains(',')) return false
        val cols = first.lowercase()
            .replace("\"", "")
            .split(",")
            .map { col -> col.filter { it.isLetter() } }
        return "name" in cols || "cardname" in cols
    }

    /** How many cards a list represents, whatever shape it is in. */
    fun countCards(raw: String): Int {
        val text = raw.noBom()
        if (text.isBlank()) return 0
        if (looksLikeCsv(text)) return text.lineSequence().count { it.isNotBlank() } - 1
        return text.lineSequence().count { isCardLine(it) }
    }

    /**
     * What a pasted list actually adds up to.
     *
     * `countCards` counts lines, which is what the request size is
     * limited by, and is not what somebody pasting a deck wants to
     * know. "4 Lightning Bolt" is four cards on one line, and the
     * same card written twice — once bare and once with a set code —
     * is one unique card, not two.
     */
    data class Tally(val cards: Int, val unique: Int, val lines: Int)

    /** A leading quantity: `4 Sol Ring`, `4x Sol Ring`, `4 x Sol Ring`. */
    private val QTY = Regex("""^(\d{1,4})\s*[xX]?\s+""")

    /** The trimmings that say which printing, not which card. */
    private val SET_AND_NUMBER = Regex("""\s*\((?:[A-Za-z0-9_]{2,6})\)(?:\s+\S+)?\s*""")
    private val FOIL = Regex("""\s*\*(?:F|foil|etched)\*\s*""", RegexOption.IGNORE_CASE)

    fun tally(raw: String): Tally {
        val text = raw.noBom()
        val lines = cardLines(text)
        if (lines.isEmpty()) return Tally(0, 0, 0)
        val counted = if (looksLikeCsv(text)) csvCounts(text, lines) else listCounts(lines)
        return Tally(
            cards = counted.values.sum(),
            unique = counted.size,
            lines = lines.size,
        )
    }

    /**
     * One card and how many of it, in the order the list names them.
     *
     * `name` is spelt the way the list spelt it, because that is what
     * goes on screen. `key` is the normalised form everything else
     * matches on. They were one field, and the screen got the
     * lowercase one.
     */
    data class Entry(val qty: Int, val name: String, val key: String = name.lowercase())

    /**
     * The list as cards rather than as text.
     *
     * Same rules as `tally` — comments, blank lines and section
     * headers are not cards, a quantity in front is a quantity, and a
     * CSV is read as a CSV. The difference is that this keeps the
     * cards, so a review can show one line each instead of handing
     * somebody back the thing they just pasted.
     */
    fun entries(raw: String): List<Entry> {
        val text = raw.noBom()
        val lines = cardLines(text)
        if (lines.isEmpty()) return emptyList()
        val counted = if (looksLikeCsv(text)) csvCounts(text, lines) else listCounts(lines)
        val spelt = spellings(text, lines)
        return counted.map { (key, qty) -> Entry(qty, spelt[key] ?: key, key) }
    }

    /** key → the first spelling the list used for it. */
    private fun spellings(text: String, lines: List<String>): Map<String, String> {
        val out = mutableMapOf<String, String>()
        if (looksLikeCsv(text)) {
            val header = text.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
            val cols = splitCsv(header).map { c -> c.filter { it.isLetter() }.lowercase() }
            val nameAt = cols.indexOfFirst { it == "name" || it == "cardname" }
            lines.forEach { row ->
                val raw = splitCsv(row).getOrNull(nameAt).orEmpty()
                val shown = raw.replace(FOIL, " ").replace(SET_AND_NUMBER, " ").trim().trim(',').trim()
                if (shown.isNotEmpty() && shown.lowercase() !in out) out[shown.lowercase()] = shown
            }
            return out
        }
        lines.forEach { raw ->
            val line = raw.trim()
            val m = QTY.find(line)
            val rest = line.removePrefix(m?.value.orEmpty())
            val shown = rest.replace(FOIL, " ").replace(SET_AND_NUMBER, " ").trim().trim(',').trim()
            if (shown.isNotEmpty() && shown.lowercase() !in out) out[shown.lowercase()] = shown
        }
        return out
    }

    /** The first card named, which is where a commander is written. */
    fun firstCard(text: String): Entry? = entries(text).firstOrNull()

    /**
     * The same list with its first card taken out.
     *
     * Only the first line that is actually a card: a comment or a
     * `Commander:` header above it stays where it is.
     */
    fun withoutFirstCard(text: String): String {
        val lines = text.lines()
        val at = lines.indexOfFirst { isCardLine(it) }
        if (at < 0) return text.trim()
        return (lines.take(at) + lines.drop(at + 1)).joinToString("\n").trim()
    }

    /** name → how many, over a plain decklist. */
    private fun listCounts(lines: List<String>): Map<String, Int> {
        val out = mutableMapOf<String, Int>()
        lines.forEach { raw ->
            val line = raw.trim()
            val m = QTY.find(line)
            val qty = m?.groupValues?.get(1)?.toIntOrNull() ?: 1
            val name = cardKey(line.removePrefix(m?.value.orEmpty()))
            if (name.isNotEmpty()) out[name] = (out[name] ?: 0) + qty
        }
        return out
    }

    /**
     * The same, for a CSV.
     *
     * Only two columns matter: the one naming the card and the one
     * counting it. A file with no count column is one card a row,
     * which is what an export of singles looks like.
     */
    private fun csvCounts(text: String, rows: List<String>): Map<String, Int> {
        val header = text.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
        val cols = splitCsv(header).map { c -> c.filter { it.isLetter() }.lowercase() }
        val nameAt = cols.indexOfFirst { it == "name" || it == "cardname" }
        val qtyAt = cols.indexOfFirst { it in setOf("quantity", "qty", "count", "amount", "cardcount") }
        val out = mutableMapOf<String, Int>()
        rows.forEach { row ->
            val cells = splitCsv(row)
            val name = cardKey(cells.getOrNull(nameAt).orEmpty())
            val qty = cells.getOrNull(qtyAt)?.trim()?.toIntOrNull() ?: 1
            if (name.isNotEmpty()) out[name] = (out[name] ?: 0) + qty
        }
        return out
    }

    /**
     * Commas inside quotes are part of the name.
     *
     * "Kardur, Doomscourge" is one cell, and splitting it in two is
     * how a comma once ate half a card name.
     */
    private fun splitCsv(line: String): List<String> {
        val out = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        line.forEach { c ->
            when {
                c == '"' -> quoted = !quoted
                c == ',' && !quoted -> { out += cell.toString(); cell.clear() }
                else -> cell.append(c)
            }
        }
        out += cell.toString()
        return out
    }

    /**
     * What makes two lines the same card.
     *
     * The set code, the collector number and the foil marker say
     * which printing, not which card, so a list holding three
     * printings of Sol Ring is holding one unique card.
     */
    private fun cardKey(raw: String): String =
        raw.replace(FOIL, " ").replace(SET_AND_NUMBER, " ").trim().trim(',').trim().lowercase()

    /** The lines a human would point at and call cards. */
    fun cardLines(raw: String): List<String> = raw.noBom().let { text ->
        if (looksLikeCsv(text)) text.lines().filter { it.isNotBlank() }.drop(1)
        else text.lines().filter { isCardLine(it) }
    }

    private fun isCardLine(line: String): Boolean {
        val t = line.trim()
        return t.isNotEmpty() && !COMMENT.containsMatchIn(t) && !SECTION.matches(t)
    }

    /**
     * Did these bytes decode as text?
     *
     * A NUL settles it alone — no text file has one. Beyond that, bytes
     * that are not UTF-8 come back as replacement characters, so a few is
     * a file with an odd character in it and a great many is a JPEG.
     */
    fun looksTextual(s: String): Boolean {
        if (s.isEmpty()) return false
        if (s.contains('\u0000')) return false
        val head = s.take(4096)
        return head.count { it == '�' }.toDouble() / head.length < 0.02
    }
}
