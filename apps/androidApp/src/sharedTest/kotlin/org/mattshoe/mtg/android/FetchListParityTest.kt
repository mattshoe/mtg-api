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
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.state.ToggleableState
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mattshoe.mtg.core.Account
import org.mattshoe.mtg.core.Admin
import org.mattshoe.mtg.core.AppState
import org.mattshoe.mtg.core.DeckList
import org.mattshoe.mtg.core.FetchList
import org.mattshoe.mtg.core.FetchListState
import org.mattshoe.mtg.core.OwnedPrinting
import org.mattshoe.mtg.core.Route
import org.mattshoe.mtg.core.View
import org.mattshoe.mtg.core.savedFetch
import org.mattshoe.mtg.core.findingFetch
import org.mattshoe.mtg.core.onFetchList
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
        OwnedPrinting("counterspell", "Counterspell", "Counterspell", "Instant", "U", "common", 2, identity = "U"),
        OwnedPrinting("arcane signet", "Arcane Signet", "Arcane Signet", "Artifact", null, "common", 1),
        OwnedPrinting("brazen borrower // petty theft", "Brazen Borrower // Petty Theft", "Brazen Borrower", "Creature", "U", "mythic", 1, identity = "U"),
        // The database stores the letters in its own order; the card prints {U}{R}.
        OwnedPrinting("electrolyze", "Electrolyze", "Electrolyze", "Instant", "RU", "uncommon", 1, identity = "RU"),
    )

    private val list = "1 Counterspell\n1 Arcane Signet\n1 Brazen Borrower\n1 Electrolyze\n1 Black Lotus"

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
                            val s = held.value.findingFetch()
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

    private fun ticked(card: String): ToggleableState? =
        rule.onNodeWithTag("fetch-tick:$card").fetchSemanticsNode().config.getOrNull(SemanticsProperties.ToggleableState)

    @Test
    fun findGoesToANewPageWithoutTheEntryBox() {
        found()
        assertTrue(held.value.onFetchList, "Find stayed on the root")
        rule.onNodeWithTag("fetch-page").assertExists()
        assertTrue(
            rule.onAllNodesWithTag("fetch-list").fetchSemanticsNodes().isEmpty(),
            "the entry box is still on the list page",
        )
    }

    @Test
    fun theListPageShowsTheLoadingViewWhileTheCollectionIsAsked() {
        shell(AppState(admin = me, route = Route(View.FETCH)))
        rule.runOnIdle {
            val s = held.value
            held.value = s.copy(fetch = s.fetch.drafted(list)).findingFetch().fetching(View.FETCH)
        }
        rule.waitForIdle()
        val box = rule.onNodeWithTag("fetch-loading").getUnclippedBoundsInRoot()
        assertTrue(box.bottom.value - box.top.value > 80f, "the loading view is not drawn: $box")
        assertTrue(text("fetch-loading-line").contains("5 cards"), "the loading view does not say what it is looking for")
        assertTrue(
            rule.onAllNodesWithTag("fetch-section:Missing").fetchSemanticsNodes().isEmpty(),
            "sections are drawn under the loading view",
        )
        rule.runOnIdle {
            val s = held.value
            held.value = s.copy(fetch = s.fetch.found(FetchList.build(DeckList.entries(list), owned)))
        }
        rule.waitForIdle()
        assertTrue(
            rule.onAllNodesWithTag("fetch-loading").fetchSemanticsNodes().isEmpty(),
            "the loading view stayed over the list",
        )
    }

    @Test
    fun aCardIsTickedByTappingItsRow() {
        found()
        assertEquals(ToggleableState.Off, ticked("Counterspell"), "a card starts ticked")
        rule.onNodeWithTag("fetch-tick:Counterspell").performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(ToggleableState.On, ticked("Counterspell"), "the tap did not tick the card")
        assertTrue(held.value.fetch.isTicked("Counterspell"))
        assertEquals("1 of 4 found", held.value.fetch.progress)
    }

    @Test
    fun editingTheListKeepsTheTicks() {
        found()
        rule.onNodeWithTag("fetch-tick:Counterspell").performScrollTo().performClick()
        rule.onNodeWithTag("fetch-edit").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("fetch-edit-list").performTextReplacement(list + "\n1 Sol Ring")
        rule.onNodeWithTag("fetch-refind").performScrollTo().performClick()
        rule.waitForIdle()
        assertTrue(held.value.onFetchList, "an edit left the list page")
        assertTrue(held.value.fetch.list.endsWith("1 Sol Ring"), "the edit was not the list found")
        assertEquals(ToggleableState.On, ticked("Counterspell"), "editing removed a tick")
    }

    @Test
    fun aFailedFindSaysWhatHappenedAndOffersToTryAgain() {
        shell(AppState(admin = me, route = Route(View.FETCH)))
        rule.runOnIdle {
            val s = held.value
            held.value = s.copy(fetch = s.fetch.drafted(list)).findingFetch().fetching(View.FETCH)
                .fetchFailed("HTTP 500", View.FETCH)
        }
        rule.waitForIdle()
        assertEquals("HTTP 500", text("fetch-err"))
        assertTrue(
            rule.onAllNodesWithTag("fetch-loading").fetchSemanticsNodes().isEmpty(),
            "the loading view is still up after a failure",
        )
        rule.onNodeWithTag("fetch-retry").performClick()
        rule.waitForIdle()
        assertTrue(held.value.fetch.result != null, "Try again did not find the list")
    }

    @Test
    fun backFromTheListIsTheEntryBox() {
        found()
        rule.onNodeWithTag("fetch-back").performClick()
        rule.waitForIdle()
        assertTrue(!held.value.onFetchList, "back stayed on the list")
        rule.onNodeWithTag("fetch-list").assertExists()
        rule.onNodeWithTag("fetch-current").assertExists()
    }

    private fun top(tag: String) = rule.onNodeWithTag(tag).getUnclippedBoundsInRoot().top.value

    private fun text(tag: String): String =
        rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()
            .config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString("") { it.text }

    @Test
    fun theSectionsAreDrawnDownThePageInBinderOrder() {
        found()
        val tags = listOf(
            "fetch-section:Creatures · U · Mythic",
            "fetch-section:Instants · U · Common",
            "fetch-section:Instants · UR · Uncommon",
            "fetch-section:Artifacts · C · Common",
            "fetch-section:Missing",
        )
        val tops = tags.map { top(it) }
        assertEquals(tops.sorted(), tops, "the sections are not drawn top to bottom in binder order: $tops")
    }

    @Test
    fun aTwoColourSectionIsHeadedByItsManaSymbolsInPrintedOrderAndNotByAWord() {
        found()
        val head = "Instants · UR · Uncommon"
        rule.onNodeWithTag("fetch-head:$head").performScrollTo()
        assertTrue(
            rule.onAllNodes(hasText("Multicolor", substring = true)).fetchSemanticsNodes().isEmpty(),
            "a section still says Multicolor",
        )
        fun symbol(i: Int) = rule.onNodeWithTag("fetch-symbol:$head:$i", useUnmergedTree = true)
        val named = (0..1).map {
            symbol(it).fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
        }
        assertEquals(listOf("Blue", "Red"), named, "the head's symbols are not {U}{R}")
        val u = symbol(0).getUnclippedBoundsInRoot()
        val r = symbol(1).getUnclippedBoundsInRoot()
        assertTrue(u.left < r.left, "{U} is not drawn before {R}: ${u.left} vs ${r.left}")
        assertTrue(
            rule.onAllNodes(hasText("Instants · UR · Uncommon"), useUnmergedTree = true).fetchSemanticsNodes().isEmpty(),
            "the head is still the words, not the symbols",
        )
    }

    @Test
    fun aColourlessSectionIsHeadedByTheColourlessSymbol() {
        found()
        val head = "Artifacts · C · Common"
        rule.onNodeWithTag("fetch-head:$head").performScrollTo()
        val named = rule.onNodeWithTag("fetch-symbol:$head:0", useUnmergedTree = true).fetchSemanticsNode()
            .config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
        assertEquals("Colourless", named)
    }

    @Test
    fun theFilingIsOnScreenWhileTheAlsoCheckIsStillComing() {
        shell(AppState(admin = me, route = Route(View.FETCH, FetchListState.PAGE)))
        val s = held.value
        val filed = FetchList.build(DeckList.entries(list), owned)
        rule.runOnIdle { held.value = s.copy(fetch = s.fetch.typed(list).loading().filed(list, filed)) }
        rule.waitForIdle()
        rule.onNodeWithTag("fetch-card:Counterspell").performScrollTo()
        rule.onNodeWithTag("fetch-checking").performScrollTo()
        assertEquals(FetchListState.CHECKING, text("fetch-checking"))
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
        // The saved lists are on the root, with the entry box. On a
        // phone "Saved" floats over the top of the page, back button
        // included, so it is tapped away first, as a thumb would.
        rule.onNodeWithContentDescription("Dismiss").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("fetch-back").performScrollTo().performClick()
        rule.waitForIdle()
        assertTrue(!held.value.onFetchList, "back from a saved list stayed on the list page")
        assertEquals(listOf("Alela"), held.value.fetch.saved.map { it.title }, "leaving the page lost the save")
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
