package org.mattshoe.mtg.android

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.DpSize
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mattshoe.mtg.core.Account
import org.mattshoe.mtg.core.Admin
import org.mattshoe.mtg.core.AppState
import org.robolectric.annotation.Config
import kotlin.test.assertTrue

/**
 * Matt: "the tools tab should always be on the far right of bottom
 * nav". Measured off the real bar, on a phone and on both tablets,
 * signed out and signed in. The website's half is in `ToolsPageTest`.
 */
@RunWith(AndroidJUnit4::class)
class ToolsTabOnTheRightTest {

    @get:Rule
    val rule = createComposeRule()

    private val places = listOf("Library", "Decks", "Users", "Guide")

    private fun toolsIsFarRight(start: AppState, size: DpSize?) {
        rule.setContent {
            val shell = @androidx.compose.runtime.Composable {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    Surface {
                        val held = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(start) }
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
        val on = if (size == null) "on a phone" else "on a ${size.width} by ${size.height} screen"
        val tools = rule.onNodeWithContentDescription("Tools").getUnclippedBoundsInRoot()
        places.forEach { label ->
            val other = rule.onNodeWithContentDescription(label).getUnclippedBoundsInRoot()
            assertTrue(
                tools.left.value >= other.right.value - 1f,
                "$on Tools starts at ${tools.left} but $label ends at ${other.right}, so Tools is not the far right",
            )
        }
    }

    private fun signedIn() = AppState(admin = Admin().signIn(Account(key = "matt", role = "admin"), "t"))

    @Test
    fun toolsIsFarRightOnAPhoneSignedOut() = toolsIsFarRight(AppState(), null)

    @Test
    fun toolsIsFarRightOnAPhoneSignedIn() = toolsIsFarRight(signedIn(), null)

    @Test
    @Config(qualifiers = Tablet.UNFOLDED_QUALIFIERS)
    fun toolsIsFarRightOnAFoldingPhoneOpenedOut() = toolsIsFarRight(signedIn(), Tablet.UNFOLDED)

    @Test
    @Config(qualifiers = Tablet.LANDSCAPE_QUALIFIERS)
    fun toolsIsFarRightOnATabletHeldSideways() = toolsIsFarRight(signedIn(), Tablet.LANDSCAPE)
}
