package org.mattshoe.mtg.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The gate on "the port is finished".
 *
 * `theWholeAppIsPorted` fails, loudly and with a list, until every
 * feature in the inventory is done. It is meant to be red for most of
 * this job — that is the point. Nobody gets to call the migration
 * complete while this is failing, and nobody has to remember which three
 * screens were skipped, because it prints them.
 */
class InventoryTest {

    @Test
    fun everyFeatureNamesWhereItComesFrom() {
        Inventory.features.forEach {
            assertTrue(it.what.isNotBlank(), "a feature with no description")
            assertTrue(it.source.isNotBlank(), "${it.what} does not say where it lives today")
        }
    }

    @Test
    fun noDuplicateEntries() {
        val seen = Inventory.features.map { it.area to it.what }
        assertEquals(seen.size, seen.toSet().size, "the inventory lists something twice")
    }

    @Test
    fun everyAreaOfTheAppIsAccountedFor() {
        Area.entries.forEach { area ->
            assertTrue(
                Inventory.features.any { it.area == area },
                "${area.label} has no features listed, which means it was forgotten rather than finished",
            )
        }
    }

    /**
     * The one that used to stay red.
     *
     * It was `@Ignore`d while the port was in flight and it is not any
     * more, because it passes. Anything added to the inventory from
     * here turns it red again until there is a screen for it on both
     * platforms, which is the entire point of keeping it.
     */
    @Test
    fun theWholeAppIsPorted() {
        if (Inventory.remaining.isNotEmpty()) fail("\n" + Inventory.report())
    }

    /** Prints the remaining work on every run, without failing. */
    @Test
    fun reportWhatIsLeft() {
        println(Inventory.report())
    }

    /**
     * Progress cannot go backwards.
     *
     * If someone removes a feature from the shared code, this catches it
     * before the inventory quietly stops claiming it.
     */
    @Test
    fun portedFeaturesStayPorted() {
        // A ratchet, not a target. It is at the top now, so this is
        // the test that catches a feature being quietly dropped.
        val expected = 43
        assertTrue(
            Inventory.done.size >= expected,
            "the inventory went backwards: ${Inventory.done.size} done, was at least $expected",
        )
    }
}
