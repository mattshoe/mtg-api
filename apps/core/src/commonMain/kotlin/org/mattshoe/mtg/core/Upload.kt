package org.mattshoe.mtg.core

/**
 * A file dropped into the list box.
 *
 * Reading one only fills the box. It never submits and never advances a
 * step — the wizard's gates are the same whether the list was typed or
 * came off disk.
 */
object Upload {

    /** A collection export, not a database. */
    const val MAX_BYTES = 2L * 1024 * 1024

    fun tooBig(bytes: Long) = bytes > MAX_BYTES

    /** "2.4 MB", for the message that says why a file was refused. */
    fun size(bytes: Long): String {
        val tenths = (bytes * 10 + 500_000) / 1_000_000
        return "${tenths / 10}.${tenths % 10} MB"
    }

    /**
     * Appended, never replacing.
     *
     * Dropping a file on a box with something already typed in it must
     * not eat what is there.
     */
    fun merge(existing: String, incoming: String): String =
        if (existing.isBlank()) incoming else existing.trimEnd() + "\n" + incoming

    /** "manabox.csv — CSV, 412 cards". */
    fun describe(names: List<String>, text: String): String {
        val n = DeckList.countCards(text)
        val kind = if (DeckList.looksLikeCsv(text)) "CSV" else "decklist"
        return "${names.joinToString(", ")} — $kind, $n card${if (n == 1) "" else "s"}"
    }
}
