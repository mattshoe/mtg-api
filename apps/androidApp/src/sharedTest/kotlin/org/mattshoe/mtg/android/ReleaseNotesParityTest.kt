package org.mattshoe.mtg.android

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mattshoe.mtg.core.Account
import org.mattshoe.mtg.core.Admin
import org.mattshoe.mtg.core.AppState
import org.mattshoe.mtg.core.Release
import org.mattshoe.mtg.core.Releases
import org.mattshoe.mtg.core.View
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Release notes on Admin Settings, through `AppShell`.
 *
 * Matt: "I JUST WANT TO FUCKING SEE THEM IN THE ADMIN SETTINGS!!!!!"
 * Sibling of `ReleaseNotesTest` on the web.
 */
@RunWith(AndroidJUnit4::class)
class ReleaseNotesParityTest {

    @get:Rule(order = Int.MIN_VALUE)
    val retry = Retry()

    @get:Rule
    val rule = createComposeRule()

    private fun shell(start: AppState) {
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface {
                    val held = androidx.compose.runtime.remember {
                        androidx.compose.runtime.mutableStateOf(start)
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
        rule.waitForIdle()
    }

    private fun adminSettings(releases: Releases) = AppState(
        admin = Admin().signIn(Account(key = "e7de0cb1", role = "admin"), "t"),
        releases = releases,
    ).navigate(View.ADMIN)

    private val shipped = Releases().loaded(
        listOf(
            Release("android-v2.1.0-297", "2026-10-09T10:00:00Z", "Release notes in Admin Settings."),
            Release("android-v2.1.0-296", "2026-10-08T15:00:00Z", null),
        ),
    )

    private fun entries(): List<SemanticsNode> =
        rule.onAllNodes(hasTestTag("release"), useUnmergedTree = false).fetchSemanticsNodes()

    private fun SemanticsNode.says(): String =
        config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString(" ") { it.text }

    @Test
    fun adminSettingsListsEveryBuildNewestFirstWithItsDateAndWhatChanged() {
        shell(adminSettings(shipped))
        val rows = entries()
        assertEquals(2, rows.size, "Admin Settings shows no release notes")

        val first = rows[0].says()
        assertTrue("2.1.0 (297)" in first, "the newest build is not first: $first")
        assertTrue("2026-10-09" in first, "no date on the entry: $first")
        assertTrue("Release notes in Admin Settings." in first, "the note is not there: $first")
        assertTrue("No note was written for this build." in rows[1].says(), rows[1].says())

        // Unclipped: the screen scrolls, and an entry below the fold is
        // still an entry.
        val all = rule.onAllNodes(hasTestTag("release"))
        val a = all[0].getUnclippedBoundsInRoot()
        val b = all[1].getUnclippedBoundsInRoot()
        assertTrue(a.bottom > a.top && b.bottom > b.top, "an entry has no height")
        assertTrue(b.top >= a.bottom, "the second entry is not below the first")
    }

    @Test
    fun aLoadThatFailedSaysSo() {
        shell(adminSettings(Releases().failed("API rate limit exceeded")))
        assertTrue(
            rule.onAllNodes(hasText("Could not load release notes: API rate limit exceeded"))
                .fetchSemanticsNodes().isNotEmpty(),
            "the failure is not on screen",
        )
    }
}
