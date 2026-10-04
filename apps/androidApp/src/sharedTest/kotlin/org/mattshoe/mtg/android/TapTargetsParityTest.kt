package org.mattshoe.mtg.android

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mattshoe.mtg.android.Parity.shoot
import org.mattshoe.mtg.core.AppState
import org.mattshoe.mtg.core.Facet
import org.mattshoe.mtg.core.View
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Everything in the app you tap with a thumb, measured (4.8).
 *
 * The nav's Lock and Find were 25dp wide. Their own neighbours, the
 * tab pills, were about 32. The five comparison operators under Power
 * were 26dp each with a hairline between them. Android asks for 48,
 * and the number matters more here than on the website: the pointer
 * that drives the web page is one pixel wide.
 *
 * Compose is not much help on its own. It stretches a small
 * control's hit area out to 48dp during hit testing, so
 * `touchBoundsInRoot` reported a flattering 48x48 for a 25dp button
 * long before any of this was fixed — but the stretch is a fringe
 * around the control, and two small controls six dp apart have
 * fringes that lie on top of each other. Whichever was laid out
 * first takes the overlap. So this measures two things that cannot be
 * faked: how much room the control actually occupies, and whether
 * next door's touch area runs through it.
 *
 * Through the real `AppShell`, every one of them. `Theme.kt`'s pieces
 * look right mounted alone — it is the nav row packing six of them
 * into 320dp, and the filter panel putting five inside one `Seg`,
 * that made them unhittable.
 */
@RunWith(AndroidJUnit4::class)
class TapTargetsParityTest {

    @get:Rule
    val rule = createComposeRule()

    /** The real shell over mutable state, the way `MainActivity` runs it. */
    private fun shell(start: AppState = AppState()): () -> AppState {
        var state = start
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface {
                    val held = androidx.compose.runtime.remember {
                        androidx.compose.runtime.mutableStateOf(start)
                    }
                    state = held.value
                    AppShell(
                        state = held.value,
                        onState = { held.value = it; state = it },
                        onUnlock = {},
                        onSearch = {},
                        onOpenDeck = {},
                        onRunSql = {},
                        onPreviewEntry = {},
                        onApplyEntry = {},
                    )
                }
            }
        }
        rule.waitForIdle()
        return { state }
    }

    /** The one control in the tree with this label that can be pressed. */
    private fun control(label: String): SemanticsNodeInteraction =
        rule.onNode(hasText(label) and hasClickAction())

    private fun SemanticsNodeInteraction.box() = getUnclippedBoundsInRoot()

    /**
     * What the platform would hand this node's press to, in pixels.
     *
     * Unclipped and unmerged by position: two of these overlapping is
     * the bug, whatever each one measures on its own.
     */
    private fun SemanticsNodeInteraction.touch() = fetchSemanticsNode().touchBoundsInRoot

    private fun assertBigEnough(what: String, node: SemanticsNodeInteraction) {
        val b = node.box()
        assertTrue(
            b.width.value >= 48f && b.height.value >= 48f,
            "$what is ${b.width.value}x${b.height.value}dp of touch target, " +
                "and Android asks for 48x48",
        )
    }

    @Test
    fun theNavsLockAndFindAreBigEnoughToHit() {
        shell()
        listOf("Unlock", "Find").forEach { assertBigEnough("the nav's $it", control(it)) }
        rule.onRoot().shoot("tap-targets-nav")
    }

    @Test
    fun soIsEveryTabPillBesideThem() {
        shell()
        AppState().admin.visible.forEach { view: View ->
            assertBigEnough("the ${view.label} tab", control(view.label))
        }
    }

    /**
     * The half a per-control measurement cannot see.
     *
     * Six dp apart with a 48dp hit fringe each, two 25dp buttons have
     * 42dp of shared touch area, and only one of them gets it.
     */
    @Test
    fun noTwoNavControlsClaimTheSameTouchArea() {
        shell()
        val labels = AppState().admin.visible.map { it.label } + listOf("Unlock", "Find")
        val boxes = labels.map { it to control(it).touch() }
        boxes.forEachIndexed { i, (a, ra) ->
            boxes.drop(i + 1).forEach { (b, rb) ->
                assertTrue(
                    ra.overlaps(rb).not(),
                    "$a and $b both answer to a press in the same place: $ra and $rb",
                )
            }
        }
    }

    /**
     * The five operators under Power, which shared one row with the
     * value box and divided what was left of it five ways.
     */
    @Test
    fun theStatOperatorsEachGetAnEqualAndBigEnoughShare() {
        shell()
        rule.onNodeWithTag("header-${Facet.MANA.id}").performScrollTo().performClick()
        rule.waitForIdle()

        // The first of each glyph is Power's — Toughness and Loyalty
        // have the same five under them. Asserting they share a top
        // edge is what proves these five are one control and not one
        // apiece from three.
        val ops = listOf("≥", "≤", "=", ">", "<").map { glyph ->
            val node = rule.onAllNodesWithText(glyph)[0]
            node.performScrollTo()
            glyph to node
        }
        rule.waitForIdle()
        val boxes = ops.map { (glyph, node) -> glyph to node.box() }
        val top = boxes.first().second.top.value
        boxes.forEach { (glyph, b) ->
            assertEquals(
                top,
                b.top.value,
                0.5f,
                "the $glyph segment is not in the same row as the other four",
            )
            assertTrue(
                b.width.value >= 48f && b.height.value >= 48f,
                "the $glyph operator is ${b.width.value}x${b.height.value}dp, " +
                    "and Android asks for 48x48",
            )
        }
        val widths = boxes.map { it.second.width.value }
        assertTrue(
            abs(widths.max() - widths.min()) <= 1f,
            "the five operators do not share the row evenly: $widths",
        )
        rule.onNodeWithTag("body-${Facet.MANA.id}").shoot("tap-targets-operators")
    }

    /**
     * And the one on the end still does what it says, pressed
     * through the shell rather than on a `Seg` by itself.
     */
    @Test
    fun theLastOperatorStillPicksItself() {
        val state = shell()
        rule.onNodeWithTag("header-${Facet.MANA.id}").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onAllNodesWithText("<")[0].performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals("<", state().library.filters.powOp, "the end segment did not take the press")
    }
}
