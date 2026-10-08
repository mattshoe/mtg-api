package org.mattshoe.mtg.android

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mattshoe.mtg.core.AppState
import org.mattshoe.mtg.core.Sort
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * "The edhrec sorting looks backwards."
 *
 * Picking EDHREC rank leaves the arrow pointing down, and down is the
 * good end on every column. For a rank the good end is rank 1, so the
 * arrow has to say "Most played first". It said "Least played first",
 * which was the backwards order described accurately.
 *
 * Through a real `AppShell`, picking the sort the way a person does.
 * Which cards come back first is the journey's job, against real SQL.
 */
@RunWith(AndroidJUnit4::class)
class EdhrecSortParityTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun pickingEdhrecWithTheArrowDownSaysMostPlayedFirst() {
        var state = AppState()
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface {
                    val held = remember { mutableStateOf(state) }
                    AppShell(
                        state = held.value,
                        onState = { held.value = it; state = it },
                        onSearch = {},
                        onOpenDeck = {},
                        onPreviewEntry = {},
                        onApplyEntry = {},
                        onExport = {},
                        onLookup = {},
                    )
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("select-sort").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithText(Sort.EDHREC.label).performClick()
        rule.waitForIdle()

        assertEquals(Sort.EDHREC, state.library.filters.sort, "picking EDHREC rank never reached the state")
        assertTrue(state.library.filters.descending, "picking a column reversed the arrow")
        val said = rule.onAllNodesWithContentDescription("first", substring = true)
            .fetchSemanticsNodes()
            .flatMap { it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }
        assertTrue(
            "Most played first" in said,
            "the arrow points down on EDHREC but says $said, so the rank 22,000 end is on top",
        )
    }
}
