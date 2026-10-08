package org.mattshoe.mtg.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * No column of `cards` is left off the card page without a reason.
 *
 * "Everything" is a promise about the schema, not about a list
 * somebody wrote once, so this reads `schema.sql` and holds
 * `CardFacts` to it. A column added tomorrow fails here, by name,
 * until it is either shown or put on the not-shown list with a line
 * saying why.
 *
 * JVM only: it needs the repository on disk.
 */
class CardFactsCoverageTest {

    private val schema: String by lazy {
        val file = File("../../schema.sql")
        assertTrue(file.exists(), "cannot find the schema at ${file.absolutePath}")
        file.readText()
    }

    private fun columnsOf(table: String): List<String> {
        val body = Regex("""CREATE TABLE $table \((.*?)\n\);""", RegexOption.DOT_MATCHES_ALL)
            .find(schema)?.groupValues?.get(1) ?: fail("schema.sql has no CREATE TABLE $table")
        return body.lines()
            .map { it.substringBefore("--") }
            .joinToString(" ")
            .split(',')
            .map { it.trim().substringBefore(' ') }
            .filter { it.isNotEmpty() }
    }

    @Test
    fun everyColumnOfCardsIsShownOrSaysWhyNot() {
        val columns = columnsOf("cards")
        assertTrue(columns.size > 40, "read only ${columns.size} columns out of cards: $columns")
        val missing = columns.filter { it !in CardFacts.SHOWN && it !in CardFacts.NOT_SHOWN }
        assertTrue(missing.isEmpty(), "cards columns neither shown nor listed as not shown: $missing")
    }

    @Test
    fun everyTableHangingOffACardIsShownOrSaysWhyNot() {
        val children = Regex("""CREATE TABLE (card_\w+)\s*\(""").findAll(schema).map { it.groupValues[1] }.toList()
        assertTrue("card_keywords" in children, "read no child tables out of schema.sql: $children")
        val sql = CardFacts.query("x").sql
        val missing = children.filter { it !in sql && it !in CardFacts.NOT_SHOWN }
        assertTrue(missing.isEmpty(), "tables about a card that the page never reads and never explains: $missing")
    }

    @Test
    fun theNotShownListOnlyNamesThingsThatExist() {
        val real = columnsOf("cards").toSet() +
            Regex("""CREATE TABLE (card_\w+)""").findAll(schema).map { it.groupValues[1] }
        val stale = CardFacts.NOT_SHOWN.keys - real
        assertTrue(stale.isEmpty(), "not-shown entries the schema no longer has: $stale")
    }
}
