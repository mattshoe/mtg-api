package org.mattshoe.mtg.android

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mattshoe.mtg.core.AppState
import org.mattshoe.mtg.core.Guide
import org.mattshoe.mtg.core.Route
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every single-line box has an x on its right that empties it.
 *
 * Matt: "i want all single line text entry fields to have an x on the
 * right to clear the text". Driven through the real shell, on a phone
 * and on a tablet. The website's `ClearTheBoxTest` is the same claim.
 */
@RunWith(AndroidJUnit4::class)
class ClearTheBoxTest {

    @get:Rule
    val rule = createComposeRule()

    private lateinit var held: androidx.compose.runtime.MutableState<AppState>

    private fun open(route: String, size: DpSize?) {
        rule.setContent {
            val shell = @androidx.compose.runtime.Composable {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    Surface {
                        held = androidx.compose.runtime.remember {
                            androidx.compose.runtime.mutableStateOf(AppState().navigate(Route.parse(route)))
                        }
                        AppShell(
                            state = held.value,
                            onState = { held.value = it },
                            onSearch = {},
                            onOpenDeck = {},
                            onPreviewEntry = {},
                            onApplyEntry = {},
                        )
                    }
                }
            }
            if (size == null) shell() else Tablet.Sized(size) { shell() }
        }
        rule.waitForIdle()
    }

    private fun x(box: String): SemanticsNodeInteraction =
        rule.onNode(hasTestTag(CLEAR_TAG) and hasAnyAncestor(hasTestTag(box)), useUnmergedTree = true)

    private fun typed(box: String): String =
        rule.onNodeWithTag(box).fetchSemanticsNode().config
            .getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()

    /** Type, find the x inside the box at its right end, press it, and the box is empty. */
    private fun clears(route: String, box: String, words: String, results: String, size: DpSize? = null) {
        open(route, size)
        val on = if (size == null) "on a phone" else "on a ${size.width} by ${size.height} screen"
        rule.onNodeWithTag(box).assertExists("$on there is no $box")
        x(box).assertDoesNotExist()

        rule.onNodeWithTag(box).performTextInput(words)
        rule.waitForIdle()
        assertEquals(words, typed(box), "$on typing did not reach $box")
        rule.onNodeWithTag(results).assertExists("$on typing \"$words\" in $box showed no $results")

        x(box).assertExists("$on $box holds \"$words\" and has no x to clear it")
        val field = rule.onNodeWithTag(box).getUnclippedBoundsInRoot()
        val mark = x(box).getUnclippedBoundsInRoot()
        assertTrue(
            mark.left >= field.left && mark.right <= field.right && mark.top >= field.top && mark.bottom <= field.bottom,
            "$on the x is not inside $box: ${mark.left}..${mark.right} by ${mark.top}..${mark.bottom}, " +
                "the box is ${field.left}..${field.right} by ${field.top}..${field.bottom}",
        )
        assertTrue(field.right - mark.right <= 16.dp, "$on the x is not at the right of $box: ${field.right - mark.right} short of it")
        assertTrue(mark.left - field.left >= (field.right - field.left) / 2, "$on the x sits in the left half of $box")
        val middle = (field.top + field.bottom) / 2 - (mark.top + mark.bottom) / 2
        assertTrue(middle.value in -3f..3f, "$on the x is $middle off the middle of $box")
        assertTrue(mark.right - mark.left >= 40.dp, "$on the x is ${mark.right - mark.left} wide, too small to hit")

        // Pressed by its semantics action, not by screen coordinate.
        //
        // `Tablet.Sized` lays the content out at tablet coordinates
        // inside whatever window the device actually has. On the
        // emulator that is a Pixel 9: the root is 411dp wide while this
        // x sits at 936..977dp, so `performClick` aimed a tap at a
        // point physically off the screen and nothing received it. The
        // box still held "Sylex" and the test read it as the app
        // failing to clear.
        //
        // Robolectric gives a window that really is 1280x800, which is
        // why the same case passes there and failed only on a device.
        // The bounds asserted just above are what prove the x is where
        // a finger would find it; this step is about what pressing it
        // does.
        x(box).performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        assertEquals("", typed(box), "$on pressing the x left \"${typed(box)}\" in $box")
        rule.onNodeWithTag(results).assertDoesNotExist()
        x(box).assertDoesNotExist()
    }

    @Test
    fun theGuideSearchClearsOnAPhone() = clears("#/guide", "guide-search", "Sylex", "guide-hits")

    @Test
    @Config(qualifiers = Tablet.UNFOLDED_QUALIFIERS)
    fun theGuideSearchClearsOnAFoldingPhoneOpenedOut() =
        clears("#/guide", "guide-search", "Sylex", "guide-hits", Tablet.UNFOLDED)

    @Test
    @Config(qualifiers = Tablet.LANDSCAPE_QUALIFIERS)
    fun theGuideSearchClearsOnATabletHeldSideways() =
        clears("#/guide", "guide-search", "Sylex", "guide-hits", Tablet.LANDSCAPE)

    @Test
    fun theGameChangersSearchClearsOnAPhone() =
        clears("#/guide/${Guide.GAME_CHANGERS}", "gc-search", "Rhystic", "gc-search-count")

    @Test
    @Config(qualifiers = Tablet.UNFOLDED_QUALIFIERS)
    fun theGameChangersSearchClearsOnAFoldingPhoneOpenedOut() =
        clears("#/guide/${Guide.GAME_CHANGERS}", "gc-search", "Rhystic", "gc-search-count", Tablet.UNFOLDED)
}
