package org.mattshoe.mtg.android

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onChildAt
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mattshoe.mtg.core.ConsoleState
import org.mattshoe.mtg.core.LogLine
import org.mattshoe.mtg.core.LogsState
import org.mattshoe.mtg.core.Owner
import org.mattshoe.mtg.core.StatsState
import org.mattshoe.mtg.core.Table
import org.mattshoe.mtg.core.Totals
import java.io.File
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The query console, the server log and the stats screen, on a device.
 *
 * Every check here has a sibling in `ConsolePageTest` or
 * `DecksStatsPageTest` under Karma, and the ones that have no sibling
 * are about shape rather than words: three gaps found elsewhere today
 * were a screen whose text matched the website exactly while its
 * layout did not — a tally drawn as loose columns instead of a ruled
 * grid, facts run together instead of tags. Reading the two sources
 * and concluding they agree is how that survives, so these look at the
 * laid-out bounds and at the pixels.
 *
 * The colour assertions measure lightness, not hue. The owner is
 * colourblind, and "the failed row is red" is not an answer for him.
 */
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class ConsoleStatsParityTest {

    @get:Rule
    val rule = createComposeRule()

    // ------------------------------------------------------- the fixtures

    private fun table() = Table(
        listOf("name", "qty"),
        listOf(listOf("Sol Ring", "3"), listOf("Opt", null)),
    )

    /** A short first cell over a long one: the shove that breaks a row. */
    private fun ragged() = Table(
        listOf("name", "qty", "set"),
        listOf(
            listOf("Opt", "1", "ELD"),
            listOf("Thassa's Oracle, Merfolk Wizard", "12", "THB"),
        ),
    )

    private fun lines() = listOf(
        LogLine("2026-09-28T01:02:03Z", "info", null, "POST", "/query", 200, 12, null),
        LogLine("2026-09-28T01:02:04Z", "error", null, "POST", "/admin", 401, 8, null),
    )

    private fun totals() = Totals(
        printings = 6032, uniques = 3743, physical = 7781, decks = 9,
        free = 4120, sets = 418, foils = 611, value = 5046.0,
        pricedAt = "2026-09-27 04:10",
    )

    // ------------------------------------------------------------ mounting

    private var console by mutableStateOf(ConsoleState())
    private var logs by mutableStateOf(LogsState())
    private var stats by mutableStateOf(StatsState())

    private var lastConsole: ConsoleState? = null
    private var lastLogs: LogsState? = null
    private var scoped: Owner? = null
    private var scopeCalls = 0
    private var runs = 0
    private var sheets = 0

    /**
     * A phone's width, and nothing else.
     *
     * No scroller of its own: all three screens scroll themselves, the
     * same as the pages they are siblings of, and a second one wrapped
     * round them measures the first against an infinite height.
     */
    @androidx.compose.runtime.Composable
    private fun Frame(content: @androidx.compose.runtime.Composable () -> Unit) {
        MtgTheme { Box(Modifier.width(390.dp).testTag("frame")) { content() } }
    }

    private fun showConsole(state: ConsoleState) {
        console = state
        rule.setContent {
            Frame {
                ConsoleScreen(
                    state = console,
                    onState = { lastConsole = it; console = it },
                    onRun = { runs++ },
                    onCheatsheet = { sheets++ },
                )
            }
        }
        settle()
    }

    private fun showLogs(state: LogsState) {
        logs = state
        rule.setContent { Frame { LogsScreen(logs) { lastLogs = it; logs = it } } }
        settle()
    }

    private fun showStats(state: StatsState) {
        stats = state
        rule.setContent {
            Frame { StatsScreen(stats) { o -> scoped = o; scopeCalls++; stats = stats.scopedTo(o) } }
        }
        settle()
    }

    private fun settle() {
        rule.waitForIdle()
        rule.waitUntil(timeoutMillis = 10_000) {
            rule.onAllNodes(hasTestTag("frame")).fetchSemanticsNodes().isNotEmpty()
        }
    }

    // ------------------------------------------------------------- reading

    private fun tag(t: String): SemanticsNodeInteraction =
        rule.onNodeWithTag(t, useUnmergedTree = true)

    private fun exists(t: String): Boolean =
        rule.onAllNodes(hasTestTag(t), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun textSomewhere(fragment: String): Boolean =
        rule.onAllNodes(hasText(fragment, substring = true), useUnmergedTree = true)
            .fetchSemanticsNodes().isNotEmpty()

    private fun cell(t: String): String = tag(t).textOrEmpty()

    private fun left(t: String) = tag(t).getUnclippedBoundsInRoot().left.value
    private fun right(t: String) = tag(t).getUnclippedBoundsInRoot().right.value
    private fun top(t: String) = tag(t).getUnclippedBoundsInRoot().top.value
    private fun bottom(t: String) = tag(t).getUnclippedBoundsInRoot().bottom.value

    private fun same(a: Float, b: Float, slack: Float = 0.75f) = abs(a - b) <= slack

    /**
     * How light a node's own surface is, read off the device.
     *
     * The band is inside the cell's top padding and away from both
     * edges, so it is the background and nothing else: no text, no
     * border, no left rule. Lightness is the one channel the owner can
     * always read, so it is the one these assertions use.
     */
    private fun lightness(t: String): Double = lightnessOf(tag(t).captureToImage().asAndroidBitmap())

    /**
     * The mean lightness of the middle of a node.
     *
     * Deliberately the middle rather than a band near the top:
     * anything that measures an edge is measuring whatever happens to
     * be laid out there, which is how the toggle test came to read the
     * same blank strip twice. 30%-70% in both directions is inside any
     * pill big enough to have a fill at all.
     */
    private fun middleLightnessOf(bmp: Bitmap): Double {
        val y0 = (bmp.height * 0.30).toInt()
        val y1 = maxOf(y0 + 1, (bmp.height * 0.70).toInt())
        val x0 = (bmp.width * 0.30).toInt()
        val x1 = maxOf(x0 + 1, (bmp.width * 0.70).toInt())
        return mean(bmp, x0, x1, y0, y1)
    }

    private fun lightnessOf(bmp: Bitmap): Double {
        val y0 = maxOf(1, (bmp.height * 0.08).toInt())
        val y1 = maxOf(y0 + 1, (bmp.height * 0.19).toInt())
        val x0 = maxOf(1, (bmp.width * 0.30).toInt())
        val x1 = maxOf(x0 + 1, (bmp.width * 0.70).toInt())
        return mean(bmp, x0, x1, y0, y1)
    }

    private fun mean(bmp: Bitmap, x0: Int, x1: Int, y0: Int, y1: Int): Double {
        var sum = 0.0
        var n = 0
        for (y in maxOf(0, y0) until minOf(y1, bmp.height)) {
            for (x in maxOf(0, x0) until minOf(x1, bmp.width)) {
                val p = bmp.getPixel(x, y)
                sum += 0.299 * ((p shr 16) and 0xFF) + 0.587 * ((p shr 8) and 0xFF) + 0.114 * (p and 0xFF)
                n++
            }
        }
        // Not 0.0. A window that fell off the bitmap — a node below
        // the fold, a band above the first row — used to come back as
        // zero, and a zero subtracted from a zero is a lift of
        // exactly nothing, which reads like a real finding and is a
        // measurement that never happened.
        if (n == 0) error("sampled $x0..$x1 x $y0..$y1 of a ${bmp.width}x${bmp.height} shot, which is nowhere")
        return sum / n
    }

    /**
     * How much lighter a node's own surface is than what it sits on.
     *
     * Read off one screenshot of the whole screen, sampling a band
     * inside the node's top padding and a band of the same width just
     * above it. A tag, an error block or a marked segment all have to
     * come out positive here: that is the thing an eye reads when it
     * cannot read the hue.
     */
    private fun ownSurface(t: String): Double {
        // Into view first. The console scrolls, the error block sits
        // under a 180dp SQL box and a results panel, and off a short
        // screen it is simply not painted — so both bands sampled the
        // page behind it, came out identical, and reported a lift of
        // zero for a surface that was never on the screenshot.
        tag(t).performScrollTo()
        rule.waitForIdle()
        val bmp = rule.onRoot().captureToImage().asAndroidBitmap()
        val d = InstrumentationRegistry.getInstrumentation()
            .targetContext.resources.displayMetrics.density
        val b = tag(t).getUnclippedBoundsInRoot()
        fun px(v: Float) = (v * d).toInt()
        val l = px(b.left.value) + 6
        val r = px(b.right.value) - 6
        val top = px(b.top.value)
        check(top - 8 >= 0 && top + 9 < bmp.height && r > l) {
            "$t sits at $b on a ${bmp.width}x${bmp.height} shot, so there is no band above it to compare against"
        }
        val inside = mean(bmp, l, r, top + 3, top + 9)
        val outside = mean(bmp, l, r, top - 8, top - 2)
        return inside - outside
    }

    // ======================================================= the SQL box

    @Test
    fun theSqlBoxShowsWhatIsInIt() {
        showConsole(ConsoleState(sql = "SELECT 1"))
        tag("sql").assertExists()
        assertTrue(textSomewhere("SELECT 1"))
    }

    @Test
    fun typingInTheSqlBoxIsReportedThroughTheCallback() {
        showConsole(ConsoleState())
        rule.onNodeWithTag("sql").performTextInput("SELECT 2")
        rule.waitForIdle()
        assertEquals("SELECT 2", lastConsole?.sql)
    }

    @Test
    fun runIsRefusedWithNothingToRun() {
        showConsole(ConsoleState())
        rule.onNodeWithText("Run").assertIsNotEnabled()
    }

    @Test
    fun runIsRefusedWhenTheBoxHoldsOnlyWhitespace() {
        showConsole(ConsoleState(sql = "   \n  "))
        rule.onNodeWithText("Run").assertIsNotEnabled()
    }

    @Test
    fun runIsOfferedOnceThereIsSomethingToRun() {
        showConsole(ConsoleState(sql = "SELECT 1"))
        rule.onNodeWithText("Run").assertIsEnabled()
    }

    @Test
    fun pressingRunAsksThroughTheCallback() {
        showConsole(ConsoleState(sql = "SELECT 1"))
        rule.onNodeWithText("Run").performClick()
        rule.waitForIdle()
        assertEquals(1, runs)
    }

    @Test
    fun runReadsRunningWhileItRuns() {
        showConsole(ConsoleState(sql = "SELECT 1").running())
        rule.onNodeWithText("Running…").assertExists()
        rule.onNodeWithText("Run").assertDoesNotExist()
    }

    @Test
    fun runIsRefusedWhileItIsAlreadyRunning() {
        showConsole(ConsoleState(sql = "SELECT 1").running())
        rule.onNodeWithText("Running…").assertIsNotEnabled()
    }

    @Test
    fun startingARunClearsTheOldResult() {
        showConsole(ConsoleState(sql = "SELECT 1").ran(table(), 7).running())
        assertFalse(exists("result-grid"), "a new run must not leave the last table up")
        assertFalse(exists("result-count"))
    }

    @Test
    fun theCheatsheetIsOfferedAndAsksThroughTheCallback() {
        showConsole(ConsoleState())
        rule.onNodeWithText("Cheatsheet").assertExists()
        rule.onNodeWithText("Cheatsheet").performClick()
        rule.waitForIdle()
        assertEquals(1, sheets)
    }

    // ================================================== the result table

    @Test
    fun aResultRendersAsARealTable() {
        showConsole(ConsoleState(sql = "SELECT 1").ran(table(), 7))
        tag("result-grid").assertExists()
        assertEquals("NAME", cell("result-head-0"))
        assertEquals("QTY", cell("result-head-1"))
        assertEquals("Sol Ring", cell("result-cell-0-0"))
        assertEquals("3", cell("result-cell-0-1"))
    }

    @Test
    fun thereIsAHeaderCellPerColumnAndNoMore() {
        showConsole(ConsoleState(sql = "SELECT 1").ran(table(), 7))
        assertTrue(exists("result-head-0") && exists("result-head-1"))
        assertFalse(exists("result-head-2"), "a third header for a two-column result")
    }

    @Test
    fun thereIsACellPerValueAndNoMore() {
        showConsole(ConsoleState(sql = "SELECT 1").ran(table(), 7))
        (0..1).forEach { r -> (0..1).forEach { c -> assertTrue(exists("result-cell-$r-$c")) } }
        assertFalse(exists("result-cell-2-0"), "a third row for a two-row result")
        assertFalse(exists("result-cell-0-2"))
    }

    /** The bug this screen had: every row as one `Text` of pipes. */
    @Test
    fun theRowsAreNotOneRunOfPipedText() {
        showConsole(ConsoleState(sql = "SELECT 1").ran(table(), 7))
        assertFalse(textSomewhere("  |  "), "the table is ruled, not joined with pipes")
        assertFalse(textSomewhere("Sol Ring  |  3"))
    }

    @Test
    fun aHeaderCellSitsOverItsOwnColumn() {
        showConsole(ConsoleState(sql = "SELECT 1").ran(table(), 7))
        assertTrue(same(left("result-head-0"), left("result-cell-0-0")), "column 0 head over column 0")
        assertTrue(same(left("result-head-1"), left("result-cell-0-1")), "column 1 head over column 1")
        assertTrue(bottom("result-head-0") <= top("result-cell-0-0") + 0.75f, "the head is above the body")
    }

    /** A long cell in one row must not shove the next column in another. */
    @Test
    fun aLongCellDoesNotShoveTheColumnsOutOfLine() {
        showConsole(ConsoleState(sql = "SELECT 1").ran(ragged(), 3))
        assertTrue(same(left("result-cell-0-1"), left("result-cell-1-1")), "qty column must not stagger")
        assertTrue(same(left("result-cell-0-2"), left("result-cell-1-2")), "set column must not stagger")
        assertTrue(same(left("result-head-2"), left("result-cell-0-2")))
    }

    @Test
    fun theColumnsRunInOrderWithoutOverlapping() {
        showConsole(ConsoleState(sql = "SELECT 1").ran(ragged(), 3))
        assertTrue(left("result-head-1") >= right("result-head-0") - 0.75f)
        assertTrue(left("result-head-2") >= right("result-head-1") - 0.75f)
    }

    @Test
    fun theCellsOfOneRowShareItsLine() {
        showConsole(ConsoleState(sql = "SELECT 1").ran(ragged(), 3))
        assertTrue(same(top("result-cell-1-0"), top("result-cell-1-1")))
        assertTrue(same(top("result-cell-1-0"), top("result-cell-1-2")))
        assertTrue(top("result-cell-1-0") >= bottom("result-cell-0-0") - 0.75f, "row 1 is under row 0")
    }

    @Test
    fun aNullCellSaysNullRatherThanNothing() {
        showConsole(ConsoleState(sql = "SELECT 1").ran(table(), 7))
        assertEquals("null", cell("result-cell-1-1"))
    }

    @Test
    fun theRowCountAndTimingAreStated() {
        showConsole(ConsoleState(sql = "SELECT 1").ran(table(), 7))
        assertEquals("2 rows in 7ms", cell("result-count"))
    }

    @Test
    fun theRowCountIsTheWebsExactWordingEvenForOneRow() {
        showConsole(ConsoleState(sql = "SELECT 1").ran(Table(listOf("x"), listOf(listOf("1"))), 3))
        assertEquals("1 rows in 3ms", cell("result-count"))
    }

    @Test
    fun anEmptyResultSaysSoRatherThanShowingAnEmptyTable() {
        showConsole(ConsoleState(sql = "SELECT 1").ran(Table(listOf("x")), 1))
        assertEquals("No rows.", cell("result-empty"))
        assertFalse(exists("result-grid"), "an empty table has nothing to read")
    }

    @Test
    fun anEmptyResultStillSaysHowLongItTook() {
        showConsole(ConsoleState(sql = "SELECT 1").ran(Table(listOf("x")), 1))
        assertEquals("0 rows in 1ms", cell("result-count"))
    }

    @Test
    fun noTableIsDrawnBeforeAnythingHasRun() {
        showConsole(ConsoleState(sql = "SELECT 1"))
        assertFalse(exists("result-grid"))
        assertFalse(exists("result-count"))
        assertFalse(exists("result-empty"))
    }

    @Test
    fun aTableWiderThanThePhoneScrollsSidewaysRatherThanVanishing() {
        Parity.needsRealRendering()
        val cols = (0..7).map { "column_$it" }
        showConsole(
            ConsoleState(sql = "SELECT 1")
                .ran(Table(cols, listOf(cols.map { "value_of_$it" })), 4),
        )
        assertTrue(right("result-head-7") > 390f, "eight columns are wider than the phone")
        tag("result-head-7").performScrollTo()
        rule.waitForIdle()
        tag("result-head-7").assertIsDisplayed()
    }

    // ========================================================= the error

    @Test
    fun anErrorReplacesTheStaleResultRatherThanSittingAboveIt() {
        showConsole(
            ConsoleState(sql = "SELEC 1").ran(table(), 2).failed("near \"SELEC\": syntax error"),
        )
        assertTrue(textSomewhere("syntax error"))
        assertFalse(exists("result-grid"), "the old table under a new error reads as success")
        assertFalse(textSomewhere("Sol Ring"))
        assertFalse(exists("result-count"))
    }

    @Test
    fun theErrorIsShownWhereTheResultWouldHaveBeen() {
        showConsole(ConsoleState(sql = "SELEC 1").failed("boom"))
        assertEquals("boom", cell("err"))
    }

    @Test
    fun theErrorIsNotToldByItsColourAlone() {
        Parity.needsRealRendering()
        showConsole(ConsoleState(sql = "SELEC 1").failed("boom"))
        val lift = ownSurface("err")
        assertTrue(
            lift > 4.0,
            "the error needs a surface of its own, not just a colour; lift $lift",
        )
    }

    @Test
    fun theErrorIsAlsoAnnouncedAsOne() {
        showConsole(ConsoleState(sql = "SELEC 1").failed("boom"))
        rule.onNodeWithContentDescription("error: boom", useUnmergedTree = true).assertExists()
    }

    @Test
    fun theFailedSqlIsKeptSoItCanBeFixed() {
        showConsole(ConsoleState(sql = "SELEC 1").failed("boom"))
        assertTrue(textSomewhere("SELEC 1"))
        rule.onNodeWithText("Run").assertIsEnabled()
    }

    @Test
    fun typingAfterAFailureClearsTheError() {
        showConsole(ConsoleState(sql = "SELEC").failed("boom"))
        rule.onNodeWithTag("sql").performTextInput("T")
        rule.waitForIdle()
        assertEquals(null, lastConsole?.error)
        assertFalse(exists("err"))
    }

    // ====================================================== the log rows

    @Test
    fun theLogShowsItsLinesAndCountsTheFailures() {
        showLogs(LogsState().loaded(lines()))
        assertTrue(textSomewhere("Errors only (1)"))
        assertTrue(textSomewhere("/query"))
        assertTrue(textSomewhere("01:02:03"))
    }

    @Test
    fun theLogHasTheWebsSixColumnsInTheWebsOrder() {
        showLogs(LogsState().loaded(lines()))
        listOf("WHEN", "LEVEL", "METHOD", "PATH", "STATUS", "MS")
            .forEachIndexed { i, head -> assertEquals(head, cell("log-head-$i")) }
        assertFalse(exists("log-head-6"), "a seventh column")
    }

    @Test
    fun everyFactOfALineGetsItsOwnCell() {
        showLogs(LogsState().loaded(lines()))
        assertEquals("01:02:03", cell("log-cell-0-0"))
        assertEquals("info", cell("log-cell-0-1"))
        assertEquals("POST", cell("log-cell-0-2"))
        assertEquals("/query", cell("log-cell-0-3"))
        assertEquals("200", cell("log-cell-0-4"))
        assertEquals("12", cell("log-cell-0-5"))
    }

    @Test
    fun theLineIsNotOneRunOfText() {
        showLogs(LogsState().loaded(lines()))
        assertFalse(textSomewhere("info  POST"), "the facts are columns, not a sentence")
        assertFalse(textSomewhere("/query  200"))
    }

    @Test
    fun theTimeIsTheClockWithoutTheDate() {
        showLogs(LogsState().loaded(lines()))
        assertEquals("01:02:03", cell("log-cell-0-0"))
        assertFalse(textSomewhere("2026-09-28"), "the date is the same on every line")
    }

    @Test
    fun theMillisecondsCellIsABareNumberUnderItsHeading() {
        showLogs(LogsState().loaded(lines()))
        assertEquals("12", cell("log-cell-0-5"))
        assertFalse(textSomewhere("12ms"), "the column is already headed ms")
    }

    @Test
    fun aMissingMillisecondsLeavesTheCellEmpty() {
        showLogs(
            LogsState().loaded(
                listOf(LogLine("2026-09-28T01:02:03Z", "info", null, "GET", "/a", 200, null, null)),
            ),
        )
        assertEquals("", cell("log-cell-0-5"))
        assertFalse(textSomewhere("ms"), "a bare ms is the old suffix with nothing in front of it")
    }

    @Test
    fun aMissingMethodOrPathLeavesItsCellEmptyRatherThanSayingNull() {
        showLogs(
            LogsState().loaded(
                listOf(LogLine("2026-09-28T01:02:03Z", "info", null, null, null, null, null, null)),
            ),
        )
        assertEquals("", cell("log-cell-0-2"))
        assertEquals("", cell("log-cell-0-3"))
        assertEquals("", cell("log-cell-0-4"))
        assertFalse(textSomewhere("null"), "a log line is not a SQL result")
    }

    @Test
    fun theLogColumnsLineUpDownThePage() {
        showLogs(LogsState().loaded(lines()))
        (0..5).forEach { c ->
            assertTrue(same(left("log-head-$c"), left("log-cell-0-$c")), "head $c over column $c")
            assertTrue(same(left("log-cell-0-$c"), left("log-cell-1-$c")), "column $c staggers")
        }
    }

    @Test
    fun theCellsOfOneLineShareItsLine() {
        showLogs(LogsState().loaded(lines()))
        (1..5).forEach { c -> assertTrue(same(top("log-cell-1-0"), top("log-cell-1-$c"))) }
    }

    // ============================================== the failed-row marking

    @Test
    fun aFailedLineIsMarkedAndAGoodOneIsNot() {
        showLogs(LogsState().loaded(lines()))
        rule.onNodeWithContentDescription("failed: 01:02:04", useUnmergedTree = true).assertExists()
        rule.onAllNodes(hasTestTag("log-cell-0-0"), useUnmergedTree = true)
            .fetchSemanticsNodes().first().let { n ->
                assertFalse(
                    n.config.any { it.key.name == "ContentDescription" },
                    "a 200 is not a failure",
                )
            }
    }

    @Test
    fun aFailedLineIsNotToldByItsColourAlone() {
        Parity.needsRealRendering()
        showLogs(LogsState().loaded(lines()))
        val bad = lightness("log-cell-1-0")
        val ok = lightness("log-cell-0-0")
        assertTrue(
            bad - ok > 4.0,
            "a failed row must read as failed without its hue; failed $bad vs ok $ok",
        )
    }

    @Test
    fun theFailedMarkIsDrawnDownTheRowsLeadingEdge() {
        Parity.needsRealRendering()
        showLogs(LogsState().loaded(lines()))
        val bmp = tag("log-cell-1-0").captureToImage().asAndroidBitmap()
        val okBmp = tag("log-cell-0-0").captureToImage().asAndroidBitmap()
        // Past the grid's own 1dp border first. Sampling from x=0
        // read that border on both rows — the same pixels, the same
        // number twice — and called a rule that was plainly there
        // missing.
        val d = InstrumentationRegistry.getInstrumentation()
            .targetContext.resources.displayMetrics.density
        val border = kotlin.math.ceil(d).toInt()
        fun edge(b: Bitmap): Double {
            val y = b.height / 2
            var sum = 0.0
            var n = 0
            for (x in border until minOf(border + 4, b.width)) {
                val p = b.getPixel(x, y)
                sum += 0.299 * ((p shr 16) and 0xFF) + 0.587 * ((p shr 8) and 0xFF) + 0.114 * (p and 0xFF)
                n++
            }
            check(n > 0) { "the cell is only ${b.width}px wide, so there is nothing to read" }
            return sum / n
        }
        assertTrue(edge(bmp) - edge(okBmp) > 10.0, "the rule down the left edge: ${edge(bmp)} vs ${edge(okBmp)}")
    }

    @Test
    fun aBadStatusIsAFailureEvenAtInfoLevel() {
        showLogs(
            LogsState().loaded(
                listOf(LogLine("2026-09-28T01:02:03Z", "info", null, "GET", "/a", 500, 4, null)),
            ),
        )
        assertTrue(textSomewhere("Errors only (1)"))
        rule.onNodeWithContentDescription("failed: 01:02:03", useUnmergedTree = true).assertExists()
    }

    @Test
    fun anErrorLevelIsAFailureEvenWithAGoodStatus() {
        showLogs(
            LogsState().loaded(
                listOf(LogLine("2026-09-28T01:02:03Z", "error", null, "GET", "/a", 200, 4, null)),
            ),
        )
        assertTrue(textSomewhere("Errors only (1)"))
        rule.onNodeWithContentDescription("failed: 01:02:03", useUnmergedTree = true).assertExists()
    }

    @Test
    fun aMerelySlowLineIsNotMarkedAsFailed() {
        showLogs(
            LogsState().loaded(
                listOf(LogLine("2026-09-28T01:02:03Z", "info", null, "GET", "/a", 200, 4000, null)),
            ),
        )
        assertTrue(textSomewhere("Errors only (0)"))
        rule.onNodeWithContentDescription("failed: 01:02:03", useUnmergedTree = true)
            .assertDoesNotExist()
    }

    // ======================================== the errors-only narrowing

    @Test
    fun theToggleCountsFailuresNotLines() {
        showLogs(
            LogsState().loaded(
                lines() + LogLine("2026-09-28T01:02:05Z", "info", null, "GET", "/c", 204, 1, null),
            ),
        )
        assertTrue(textSomewhere("Errors only (1)"))
    }

    @Test
    fun narrowingToErrorsDoesNotThrowTheRestAway() {
        showLogs(LogsState().loaded(lines()))
        rule.onNode(hasText("Errors only (1)")).performClick()
        rule.waitForIdle()
        assertEquals(1, lastLogs!!.shown.size)
        assertEquals(2, lastLogs!!.lines.size, "narrowing is a filter, not a delete")
        assertFalse(lastLogs!!.toggleErrors().onlyErrors, "and it undoes")
    }

    @Test
    fun narrowedToErrorsOnlyTheFailuresAreDrawn() {
        showLogs(LogsState().loaded(lines()).toggleErrors())
        assertEquals("01:02:04", cell("log-cell-0-0"))
        assertFalse(exists("log-cell-1-0"), "only the one failure is left on screen")
        assertTrue(textSomewhere("Errors only (1)"), "the count still counts every line")
    }

    @Test
    fun theNarrowingPutsEverythingBack() {
        showLogs(LogsState().loaded(lines()).toggleErrors())
        rule.onNode(hasText("Errors only (1)")).performClick()
        rule.waitForIdle()
        assertTrue(exists("log-cell-1-0"))
        assertTrue(textSomewhere("/query"))
    }

    @Test
    fun theToggleIsMarkedWhileItIsInForce() {
        Parity.needsRealRendering()
        showLogs(LogsState().loaded(lines()))

        // The pill, through the unmerged tree, not the control.
        //
        // This test measured nothing at all for as long as item 4.8
        // has been in. `Ghost` wraps its label in a 48dp touch target
        // and `pressable` merges the semantics, so
        // `onNode(hasText(...))` stopped resolving to the tinted pill
        // and started resolving to the box around it — and
        // `lightnessOf` samples 8% to 19% of the node's height, which
        // on a 48dp box is empty space above the pill. Both states
        // sampled the same blank strip, so the two readings came back
        // identical to thirteen decimal places and the assertion still
        // passed on one emulator and not another. CI caught it; the
        // only reason it survived here is that this is a
        // `needsRealRendering` test, which never runs on the JVM.
        //
        // The middle of the pill is the thing that has to differ, so
        // that is what gets read.
        fun pill() = rule.onNode(hasText("Errors only (1)"), useUnmergedTree = true)

        val off = middleLightnessOf(pill().captureToImage().asAndroidBitmap())
        pill().performClick()
        rule.waitForIdle()
        val on = middleLightnessOf(pill().captureToImage().asAndroidBitmap())

        assertTrue(on - off > 4.0, "the toggle must look engaged without its hue; on $on vs off $off")
    }

    // ===================================================== the log states

    @Test
    fun theLogSaysItIsLoading() {
        showLogs(LogsState().loading())
        assertEquals("Loading…", cell("logs-busy"))
        assertFalse(exists("log-grid"))
    }

    @Test
    fun anEmptyLogSaysNothingLogged() {
        showLogs(LogsState().loaded(emptyList()))
        assertEquals("Nothing logged.", cell("logs-empty"))
        assertFalse(exists("log-grid"))
    }

    @Test
    fun aLogThatWillNotLoadSaysWhyInsteadOfShowingATable() {
        showLogs(LogsState().loaded(lines()).failed("offline"))
        assertEquals("offline", cell("err"))
        assertFalse(exists("log-grid"))
    }

    // ==================================================== the stats rows

    @Test
    fun theEightLabelAndValueRowsAreDrawn() {
        showStats(StatsState().loaded(totals()))
        listOf(
            "Printings" to "6032",
            "Unique cards" to "3743",
            "Physical cards" to "7781",
            "Free copies" to "4120",
            "Decks" to "9",
            "Sets" to "418",
            "Foils" to "611",
        ).forEachIndexed { _, (label, _) -> assertTrue(textSomewhere(label), "no row for $label") }
        assertTrue(textSomewhere("Value"))
        assertTrue(exists("stat-row-7"))
        assertFalse(exists("stat-row-8"), "a ninth row")
    }

    @Test
    fun theRowsAreInTheWebsOrderWithTheWebsLabels() {
        showStats(StatsState().loaded(totals()))
        listOf(
            "Printings", "Unique cards", "Physical cards", "Free copies",
            "Decks", "Sets", "Foils", "Value",
        ).forEachIndexed { i, label -> assertEquals(label, cell("stat-label-$i")) }
    }

    @Test
    fun eachNumberSitsInTheSameRowAsItsLabel() {
        showStats(StatsState().loaded(totals()))
        listOf("6032", "3743", "7781", "4120", "9", "418", "611").forEachIndexed { i, value ->
            assertTrue(
                textSomewhere(value),
                "no $value anywhere",
            )
            assertTrue(
                top("stat-value-$i") >= top("stat-row-$i") - 0.75f &&
                    bottom("stat-value-$i") <= bottom("stat-row-$i") + 0.75f,
                "the value for ${cell("stat-label-$i")} is outside its row",
            )
            assertTrue(
                left("stat-value-$i") >= right("stat-label-$i"),
                "the value for ${cell("stat-label-$i")} overlaps its label",
            )
        }
    }

    /** The gap found elsewhere today: facts run together instead of tags. */
    @Test
    fun eachNumberIsATagRatherThanProseBesideItsLabel() {
        Parity.needsRealRendering()
        showStats(StatsState().loaded(totals()))
        val lift = ownSurface("stat-value-0")
        assertTrue(
            lift > 4.0,
            "the number needs its own surface, not just a space beside the label; lift $lift",
        )
    }

    @Test
    fun theNumbersAreFlushRightSoTheyReadAsAColumn() {
        showStats(StatsState().loaded(totals()))
        val rights = (0..7).map { right("stat-value-$it") }
        assertTrue(rights.all { same(it, rights[0], 1.5f) }, "the tags do not share a right edge: $rights")
    }

    @Test
    fun anUnpricedCollectionSaysSoRatherThanShowingZero() {
        showStats(StatsState().loaded(totals().copy(value = null)))
        assertEquals("unpriced", tagText("stat-value-7"))
        assertFalse(textSomewhere("\$0"), "no price is not a value of nothing")
    }

    @Test
    fun aPricedCollectionShowsTheMoney() {
        showStats(StatsState().loaded(totals()))
        val shown = tagText("stat-value-7")
        assertTrue(shown.startsWith("$"), "the value is money: $shown")
        assertEquals("5046", shown.filter { it.isDigit() }, "the value's digits: $shown")
    }

    @Test
    fun pricesFromIsStatedWhenTheServerSaysWhen() {
        showStats(StatsState().loaded(totals()))
        assertEquals("Prices from 2026-09-27 04:10", cell("priced-at"))
    }

    @Test
    fun nothingIsSaidAboutPricesWhenThereIsNoDate() {
        showStats(StatsState().loaded(totals().copy(pricedAt = null)))
        assertFalse(exists("priced-at"))
        assertFalse(textSomewhere("Prices from"))
    }

    // ================================================ the scope switcher

    @Test
    fun theScopeSwitcherOffersBothMattAndKayla() {
        showStats(StatsState().loaded(totals()))
        tag("scope").assertExists()
        rule.onNodeWithText("Both").assertExists()
        rule.onNodeWithText("Matt").assertExists()
        rule.onNodeWithText("Kayla").assertExists()
    }

    @Test
    fun bothIsTheScopeWhenNoOwnerIsChosen() {
        Parity.needsRealRendering()
        showStats(StatsState().loaded(totals()))
        val both = seg("Both")
        assertTrue(both - seg("Matt") > 4.0, "Both is the one in force; $both vs ${seg("Matt")}")
        assertTrue(both - seg("Kayla") > 4.0)
    }

    @Test
    fun theActiveScopeIsMarked() {
        Parity.needsRealRendering()
        showStats(StatsState().scopedTo(Owner.MATT).loaded(totals()))
        val matt = seg("Matt")
        assertTrue(matt - seg("Both") > 4.0, "Matt is the one in force; $matt vs ${seg("Both")}")
        assertTrue(matt - seg("Kayla") > 4.0)
    }

    @Test
    fun theActiveScopeIsNotMarkedByHueAlone() {
        Parity.needsRealRendering()
        showStats(StatsState().scopedTo(Owner.KAYLA).loaded(totals()))
        // Lightness is the whole assertion: these numbers come off the
        // screen through a grey filter, which is what the owner sees.
        assertTrue(seg("Kayla") - seg("Both") > 4.0)
        assertTrue(seg("Kayla") - seg("Matt") > 4.0)
    }

    @Test
    fun onlyOneScopeIsMarkedAtATime() {
        Parity.needsRealRendering()
        showStats(StatsState().scopedTo(Owner.MATT).loaded(totals()))
        val lit = listOf("Both", "Matt", "Kayla").associateWith { seg(it) }
        val brightest = lit.maxByOrNull { it.value }!!
        assertEquals("Matt", brightest.key, "the wrong segment is the lit one: $lit")
        val rest = lit.filterKeys { it != "Matt" }.values
        assertTrue(
            rest.all { brightest.value - it > 4.0 },
            "every other segment must read as off: $lit",
        )
        assertTrue(
            abs(rest.first() - rest.last()) < 2.0,
            "two segments look lit at once: $lit",
        )
    }

    @Test
    fun switchingScopeAsksForTheOther() {
        showStats(StatsState().loaded(totals()))
        rule.onNodeWithText("Kayla").performClick()
        rule.waitForIdle()
        assertEquals(Owner.KAYLA, scoped)
        assertEquals(1, scopeCalls)
    }

    @Test
    fun switchingBackToBothAsksForNobody() {
        showStats(StatsState().scopedTo(Owner.MATT).loaded(totals()))
        rule.onNodeWithText("Both").performClick()
        rule.waitForIdle()
        assertEquals(null, scoped)
        assertEquals(1, scopeCalls)
    }

    @Test
    fun theMarkMovesWithTheChoice() {
        Parity.needsRealRendering()
        showStats(StatsState().loaded(totals()))
        rule.onNodeWithText("Kayla").performClick()
        rule.waitForIdle()
        assertTrue(seg("Kayla") - seg("Both") > 4.0, "the mark followed the press")
    }

    // =================================================== the stats states

    @Test
    fun statsSayTheyAreLoading() {
        showStats(StatsState().loading())
        assertEquals("Loading…", cell("stats-busy"))
        assertFalse(exists("stat-row-0"), "no half-loaded zeroes")
    }

    @Test
    fun theScopeSwitcherStaysUpWhileTheNumbersLoad() {
        Parity.needsRealRendering()
        showStats(StatsState().scopedTo(Owner.MATT).loading())
        rule.onNodeWithText("Matt").assertExists()
        assertTrue(seg("Matt") - seg("Both") > 4.0)
    }

    @Test
    fun statsThatWillNotLoadSayWhatCouldNotLoad() {
        showStats(StatsState().loaded(totals()).failed("500 from /query"))
        assertEquals("Could not load stats: 500 from /query", cell("stats-err"))
        assertFalse(exists("stat-row-0"), "stale totals under an error read as current")
        assertFalse(exists("priced-at"))
    }

    // ====================================================== screenshots

    /**
     * A picture of each screen in each state worth looking at.
     *
     * Evidence rather than an assertion. Every gap found today was
     * found by someone looking at one of these after the words had
     * already been asserted to match.
     */
    @Test
    fun theConsoleIsPhotographed() {
        Parity.needsRealRendering()
        showConsole(ConsoleState())
        shootRoot("c01-empty")
        console = ConsoleState(sql = "SELECT name, qty FROM cards LIMIT 10").running()
        rule.waitForIdle()
        shootRoot("c02-running")
        console = ConsoleState(sql = "SELECT name, qty FROM cards LIMIT 10").ran(table(), 7)
        rule.waitForIdle()
        shootRoot("c03-result")
        console = ConsoleState(sql = "SELECT name, qty FROM cards LIMIT 10").ran(Table(listOf("x")), 1)
        rule.waitForIdle()
        shootRoot("c04-no-rows")
        console = ConsoleState(sql = "SELEC 1").ran(table(), 2).failed("near \"SELEC\": syntax error")
        rule.waitForIdle()
        shootRoot("c05-error")
        console = ConsoleState(sql = "SELECT * FROM cards").ran(ragged(), 11)
        rule.waitForIdle()
        shoot("c06-ragged-grid", "result-grid")
    }

    @Test
    fun theLogIsPhotographed() {
        Parity.needsRealRendering()
        showLogs(LogsState().loaded(lines()))
        shootRoot("l01-rows")
        shoot("l02-grid", "log-grid")
        logs = logs.toggleErrors()
        rule.waitForIdle()
        shootRoot("l03-errors-only")
        logs = LogsState().loaded(emptyList())
        rule.waitForIdle()
        shootRoot("l04-empty")
        logs = LogsState().loaded(lines()).failed("offline")
        rule.waitForIdle()
        shootRoot("l05-error")
    }

    @Test
    fun theStatsScreenIsPhotographed() {
        Parity.needsRealRendering()
        showStats(StatsState().loaded(totals()))
        shootRoot("s01-both")
        stats = StatsState().scopedTo(Owner.MATT).loaded(totals())
        rule.waitForIdle()
        shootRoot("s02-matt")
        shoot("s03-scope", "scope")
        stats = StatsState().loaded(totals().copy(value = null))
        rule.waitForIdle()
        shootRoot("s04-unpriced")
        stats = StatsState().loading()
        rule.waitForIdle()
        shootRoot("s05-loading")
        stats = StatsState().failed("500 from /query")
        rule.waitForIdle()
        shootRoot("s06-error")
    }

    // --------------------------------------------------------- the camera

    private fun seg(label: String): Double =
        lightnessOf(rule.onNodeWithText(label).captureToImage().asAndroidBitmap())

    /** What is written inside a tagged wrapper, such as a `.tag` pill. */
    private fun tagText(t: String): String =
        tag(t).onChildAt(0).textOrEmpty()

    private fun shots(): File = File(
        InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),
        "parity",
    ).apply { mkdirs() }

    private fun shoot(name: String, testTag: String) {
        tag(testTag).performScrollTo()
        rule.waitForIdle()
        save(name, tag(testTag).captureToImage().asAndroidBitmap())
    }

    private fun shootRoot(name: String) {
        rule.waitForIdle()
        save(name, rule.onRoot().captureToImage().asAndroidBitmap())
    }

    private fun save(name: String, bmp: Bitmap) =
        File(shots(), "$name.png").outputStream()
            .use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
}

/** The text a node shows, or "" — a blank cell is a real answer. */
private fun SemanticsNodeInteraction.textOrEmpty(): String {
    val entry = fetchSemanticsNode().config.firstOrNull { it.key.name == "Text" } ?: return ""
    @Suppress("UNCHECKED_CAST")
    val text = entry.value as? List<androidx.compose.ui.text.AnnotatedString> ?: return ""
    return text.joinToString("") { it.text }
}
