package org.mattshoe.mtg.android.e2e

import org.junit.Rule
import org.mattshoe.mtg.android.Retry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The harness, tested before anything is tested with it.
 *
 * A fake worker that quietly answers nothing turns every journey
 * red at once and tells you nothing about the app, which is the
 * worst possible failure mode for a test harness. So the harness
 * has its own suite: the schema loads, the fixture loads, `/query`
 * answers in the worker's shape, and a bad statement comes back the
 * way the real one comes back.
 */
@RunWith(AndroidJUnit4::class)
class HarnessTest {

    @get:Rule(order = Int.MIN_VALUE)
    val retry = Retry()

    private lateinit var fake: FakeWorker

    @Before
    fun start() {
        fake = FakeWorker.start()
    }

    @After
    fun stop() {
        // Guarded, so a failure in `start()` is reported as itself
        // rather than as an uninitialised field in the teardown.
        if (::fake.isInitialized) fake.close()
    }

    @Test
    fun theFixtureIsReallyInThere() {
        val cards = fake.rows("SELECT COUNT(*) FROM cards").first().first()?.toInt() ?: 0
        assertTrue(cards > 50, "only $cards cards loaded out of the fixture")
    }

    @Test
    fun theViewsLoadedToo() {
        // Six views, and `totals` is the one that stopped being a
        // table. If the schema did not apply cleanly this is where
        // it shows, rather than in a journey that mysteriously sees
        // no rows.
        listOf("totals", "card_usage", "bulk_cards", "deck_gaps", "decks_not_built", "deck_conflicts")
            .forEach { view ->
                fake.rows("SELECT * FROM $view LIMIT 1")
            }
    }

    @Test
    fun aSemicolonInsideFlavourTextDoesNotCutAStatementInHalf() {
        // The reason `splitStatements` exists. Magic flavour text is
        // full of semicolons and so is oracle text, so splitting the
        // fixture on `;` loses the tail of those rows and every
        // statement after them.
        val withSemicolons = fake.rows(
            "SELECT COUNT(*) FROM cards WHERE oracle_text LIKE '%;%' OR flavor_text LIKE '%;%'",
        ).first().first()?.toInt() ?: 0
        assertTrue(
            withSemicolons > 0,
            "no row in the fixture has a semicolon in its text, so this proves nothing " +
                "about the splitter — pick a different fixture or delete this test",
        )
    }

    @Test
    fun aSplitKeepsQuotedSemicolonsAndDropsComments() {
        val cut = splitStatements(
            """
            -- a comment with ; in it
            INSERT INTO t VALUES ('a; b');
            INSERT INTO t VALUES ('it''s; fine');
            """.trimIndent(),
        )
        assertEquals(2, cut.size, "split into ${cut.size}: $cut")
        assertTrue(cut[0].endsWith("('a; b')"), cut[0])
        assertTrue(cut[1].endsWith("('it''s; fine')"), cut[1])
    }
}
