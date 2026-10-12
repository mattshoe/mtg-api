package org.mattshoe.mtg.android

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mattshoe.mtg.core.Account
import org.mattshoe.mtg.core.Admin
import org.mattshoe.mtg.core.AppState
import org.mattshoe.mtg.core.DeckList
import org.mattshoe.mtg.core.FetchList
import org.mattshoe.mtg.core.OwnedPrinting
import org.mattshoe.mtg.core.Route
import org.mattshoe.mtg.core.View
import org.mattshoe.mtg.core.savedFetch
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Fetch screen, through `AppShell`: the Fetch tab where Stats was,
 * the sections drawn down the page in binder order, the Also check
 * under the card it is about, what is missing, and Save asking for a
 * title before it keeps anything.
 *
 * Sibling of the web's `FetchListJourneyTest`. The query and Scryfall
 * are `FetchListLoadTest`'s; this is what the screen does with the
 * answer.
 */
@RunWith(AndroidJUnit4::class)
class FetchListParityTest {

    @get:Rule(order = Int.MIN_VALUE)
    val retry = Retry()

    @get:Rule
    val rule = createComposeRule()

    private lateinit var held: androidx.compose.runtime.MutableState<AppState>

    private val me = Admin().signIn(Account(key = "e7de0cb1"), "t")

    private val owned = listOf(
        OwnedPrinting("counterspell", "Counterspell", "Counterspell", "Instant", "U", "common", 2),
        OwnedPrinting("arcane signet", "Arcane Signet", "Arcane Signet", "Artifact", null, "common", 1),
        OwnedPrinting("brazen borrower // petty theft", "Brazen Borrower // Petty Theft", "Brazen Borrower", "Creature", "U", "mythic", 1),
    )

    private val list = "1 Counterspell\n1 Arcane Signet\n1 Brazen Borrower\n1 Black Lotus"

    private fun shell(start: AppState) {
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface {
                    held = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(start) }
                    AppShell(
                        state = held.value,
                        onState = { held.value = it },
                        onSearch = {},
                        onOpenDeck = {},
                        onPreviewEntry = {},
                        onApplyEntry = {},
                        // What `MainActivity.fetchCards` lands, over the
                        // same rows the query would return.
                        onFetch = {
                            val s = held.value
                            held.value = s.copy(
                                fetch = s.fetch.found(
                                    FetchList.build(
                                        DeckList.entries(s.fetch.list),
                                        owned,
                                        mapOf("arcane signet" to setOf("common", "uncommon")),
                                    ),
                                ),
                            )
                        },
                        onSaveFetch = { held.value = held.value.savedFetch("2026-10-12T00:00:00Z") },
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    private fun found() {
        shell(AppState(admin = me))
        rule.onNodeWithContentDescription("Fetch").performClick()
        rule.waitForIdle()
        assertEquals(View.FETCH, held.value.view, "the Fetch tab did not open the Fetch screen")
        rule.onNodeWithTag("fetch-list").performTextInput(list)
        rule.waitForIdle()
        rule.onNodeWithTag("fetch-find").performClick()
        rule.waitForIdle()
        assertTrue(held.value.fetch.result != null, "Find cards did nothing")
    }

    private fun top(tag: String) = rule.onNodeWithTag(tag).getUnclippedBoundsInRoot().top.value

    private fun text(tag: String): String =
        rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()
            .config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString("") { it.text }

    @Test
    fun theSectionsAreDrawnDownThePageInBinderOrder() {
        found()
        val tags = listOf(
            "fetch-section:Creatures · Blue · Mythic",
            "fetch-section:Instants · Blue · Common",
            "fetch-section:Artifacts · Colorless · Common",
            "fetch-section:Missing",
        )
        val tops = tags.map { top(it) }
        assertEquals(tops.sorted(), tops, "the sections are not drawn top to bottom in binder order: $tops")
    }

    @Test
    fun theAlsoCheckIsUnderTheCardItIsAbout() {
        found()
        rule.onNodeWithTag("fetch-also:Arcane Signet").performScrollTo()
        assertEquals("Also check: Uncommon", text("fetch-also:Arcane Signet"))
        val card = rule.onNodeWithTag("fetch-card:Arcane Signet").getUnclippedBoundsInRoot()
        val also = rule.onNodeWithTag("fetch-also:Arcane Signet").getUnclippedBoundsInRoot()
        assertTrue(
            also.top.value >= card.top.value && also.bottom.value <= card.bottom.value + 1f,
            "the Also check is not inside the card it is about",
        )
    }

    @Test
    fun aCardHeDoesNotOwnIsInTheMissingSection() {
        found()
        rule.onNodeWithTag("fetch-missing:Black Lotus").performScrollTo()
        val missing = rule.onNodeWithTag("fetch-section:Missing").getUnclippedBoundsInRoot()
        val lotus = rule.onNodeWithTag("fetch-missing:Black Lotus").getUnclippedBoundsInRoot()
        assertTrue(lotus.top.value >= missing.top.value, "Black Lotus is not in the Missing section")
    }

    @Test
    fun saveAsksForATitleThenKeepsTheList() {
        found()
        rule.onNodeWithTag("fetch-save").performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals("Give it a title first", text("fetch-err"))
        assertTrue(held.value.fetch.saved.isEmpty(), "it saved without a title")

        rule.onNodeWithTag("fetch-title").performTextInput("Alela")
        rule.onNodeWithTag("fetch-save").performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(listOf("Alela"), held.value.fetch.saved.map { it.title })
        rule.onNodeWithTag("fetch-saved:Alela").performScrollTo()
    }

    @Test
    fun statsIsBehindTheProfileNow() {
        shell(AppState(admin = me, route = Route(View.LIBRARY)))
        assertTrue(
            rule.onAllNodesWithContentDescription("Stats").fetchSemanticsNodes().isEmpty(),
            "Stats is still in the bottom bar",
        )
        rule.onNodeWithContentDescription("Profile").performClick()
        rule.waitForIdle()
        rule.onNode(hasText("Stats")).performClick()
        rule.waitForIdle()
        assertEquals(View.STATS, held.value.view, "Stats in the profile menu did not open Stats")
    }
}
