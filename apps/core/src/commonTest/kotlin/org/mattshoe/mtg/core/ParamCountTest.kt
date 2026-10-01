package org.mattshoe.mtg.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * How many values a search binds.
 *
 * D1 refuses a statement with more than a hundred bound parameters,
 * so a search that binds more does not come back slow — it comes
 * back as "too many SQL variables". The name box was spending three
 * on every word typed, one for the name and one for each face, so
 * thirty-four words was enough to break searching altogether.
 */
class ParamCountTest {

    /** What the server will take. Not a style preference. */
    private val LIMIT = 100

    private fun count(f: Filters) = buildQuery(f).params.size

    @Test
    fun aWordCostsOneParameterNotThree() {
        assertEquals(1, count(Filters(q = "bolt")))
        assertEquals(10, count(Filters(q = (1..10).joinToString(" ") { "w$it" })))
    }

    @Test
    fun aLongSearchStaysUnderWhatTheServerTakes() {
        // Thirty-four words used to be 102 parameters.
        val wordy = Filters(q = (1..34).joinToString(" ") { "word$it" })
        assertTrue(count(wordy) <= LIMIT, "a 34-word search binds ${count(wordy)}")
        assertTrue(buildQuery(wordy, countOnly = true).params.size <= LIMIT)
    }

    @Test
    fun soDoesAFilterPanelWithPlentyTickedOnIt() {
        val busy = Filters(
            q = "goblin token",
            text = "whenever this creature attacks",
            sets = (1..40).map { "set$it" },
            keywords = (1..10).map { "kw$it" },
            tags = (1..10).map { "tag$it" },
            colors = listOf("R", "G"),
            rarities = listOf("rare", "mythic"),
        )
        assertTrue(count(busy) <= LIMIT, "that panel binds ${count(busy)}")
        assertTrue(buildQuery(busy, countOnly = true).params.size <= LIMIT)
    }

    @Test
    fun andTheExportOfOne() {
        val f = Filters(q = (1..30).joinToString(" ") { "w$it" })
        assertTrue(Export.query(f).params.size <= LIMIT)
    }
}
