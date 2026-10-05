package org.mattshoe.mtg.android.e2e

/**
 * Splitting a `.sql` file into statements.
 *
 * `SQLiteDatabase.execSQL` takes one statement, and the two files
 * this harness loads are the repository's real `schema.sql` and the
 * real `test/fixtures/seed.sql` — 66 cards of actual collection
 * rows, flavour text and all. Splitting those on `;` gets it wrong
 * immediately: a Magic card's flavour text is full of semicolons,
 * and so is its oracle text.
 *
 * So this walks the characters and only treats a `;` as a terminator
 * when it is not inside a string. SQLite escapes a quote by doubling
 * it, which needs no special case here: the closing quote of the
 * pair flips the state off and the opening quote of the next pair
 * flips it straight back on.
 */
internal fun splitStatements(sql: String): List<String> {
    val out = mutableListOf<String>()
    val current = StringBuilder()
    var quoted = false
    var lineComment = false
    var blockComment = false
    var i = 0
    while (i < sql.length) {
        val c = sql[i]
        val next = sql.getOrNull(i + 1)
        when {
            lineComment -> if (c == '\n') { lineComment = false; current.append(c) }
            blockComment -> if (c == '*' && next == '/') { blockComment = false; i++ }
            quoted -> {
                current.append(c)
                if (c == '\'') quoted = false
            }
            c == '-' && next == '-' -> { lineComment = true; i++ }
            c == '/' && next == '*' -> { blockComment = true; i++ }
            c == '\'' -> { quoted = true; current.append(c) }
            c == ';' -> {
                out.add(current.toString())
                current.setLength(0)
            }
            else -> current.append(c)
        }
        i++
    }
    out.add(current.toString())
    return out.map { it.trim() }.filter { it.isNotEmpty() }
}
