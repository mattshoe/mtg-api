package org.mattshoe.mtg.core

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The `?` toast only promises keys that do something.
 *
 * It was promising `c console` after the Query page had been dropped
 * — a key bound to nothing, advertising a page that no longer
 * existed. Found by an agent reading the diff rather than by the
 * suite, because the only test on this string pinned the string
 * itself, which is the kind of test that changes with the bug rather
 * than catching it.
 *
 * So this reads the keys back out of the text and asks each one
 * whether it actually does anything. A key that stops working, or a
 * view that is dropped, fails here without anybody remembering to
 * update a sentence.
 */
class HelpIsHonestTest {

    private val locked = Admin()
    private val open = Admin().signIn(Account(slug = "matt", role = "admin"), "t")

    /**
     * The single letters the toast offers, out of its own text.
     *
     * Each clause is "<key> <what it does>", so the key is the first
     * word of a clause — and only the one-character ones, because
     * "/ or ⌘K find" and "esc close" are not single-key bindings
     * that `Shortcuts.of` answers for.
     */
    private fun keysIn(help: String): List<String> =
        help.split("·")
            .map { it.trim().substringBefore(' ') }
            .filter { it.length == 1 && it[0].isLetter() }

    @Test
    fun everyKeyItOffersDoesSomething() {
        listOf(locked, open).forEach { admin ->
            val help = Shortcuts.help(admin)
            val keys = keysIn(help)
            assertTrue(keys.isNotEmpty(), "no keys found in: $help")
            keys.forEach { key ->
                assertTrue(
                    Shortcuts.of(key, false, admin, false) != null,
                    "the ? toast offers \"$key\" and nothing is bound to it " +
                        "(unlocked=${admin.unlocked}): $help",
                )
            }
        }
    }

    @Test
    fun itOffersEveryKeyThatDoesSomething() {
        // The other direction, so a new shortcut cannot be added
        // without the toast learning about it.
        listOf(locked, open).forEach { admin ->
            val offered = keysIn(Shortcuts.help(admin)).toSet()
            ('a'..'z').map { it.toString() }
                .filter { Shortcuts.of(it, false, admin, false) != null }
                .forEach { key ->
                    assertTrue(
                        key in offered,
                        "\"$key\" does something and the ? toast never mentions it " +
                            "(unlocked=${admin.unlocked})",
                    )
                }
        }
    }

    @Test
    fun itNeverNamesTheQueryPage() {
        listOf(locked, open).forEach { admin ->
            val help = Shortcuts.help(admin).lowercase()
            assertTrue(
                !help.contains("console") && !help.contains("query"),
                "the ? toast still advertises the Query page: $help",
            )
        }
    }

    @Test
    fun aLockedAppIsNotToldAboutAdminKeys() {
        val help = Shortcuts.help(locked).lowercase()
        assertTrue(!help.contains("entry"), "a locked app is offered mass entry: $help")
        assertTrue(!help.contains("logs"), "a locked app is offered the log: $help")
    }
}
