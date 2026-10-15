package org.mattshoe.mtg.android

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mattshoe.mtg.core.Account
import org.mattshoe.mtg.core.Admin
import org.mattshoe.mtg.core.AppState
import org.mattshoe.mtg.core.Deck
import org.mattshoe.mtg.core.DeckCard
import org.mattshoe.mtg.core.DeckList
import org.mattshoe.mtg.core.DecksState
import org.mattshoe.mtg.core.Direction
import org.mattshoe.mtg.core.EntryHistory
import org.mattshoe.mtg.core.FetchList
import org.mattshoe.mtg.core.FetchListState
import org.mattshoe.mtg.core.HistoryEntry
import org.mattshoe.mtg.core.MassEntry
import org.mattshoe.mtg.core.OwnedPrinting
import org.mattshoe.mtg.core.Route
import org.mattshoe.mtg.core.SavedFetch
import org.mattshoe.mtg.core.Step
import org.mattshoe.mtg.core.TaskFile
import org.mattshoe.mtg.core.View
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Matt: "change the 'remove' text on the fetch list carousel to just an
 * icon. and stop using text for buttons when an icon would suffice.
 * like globally".
 *
 * Every action that was a word, drawn instead: a bin for remove and
 * delete, a pencil for edit and rename, an x for clear and for taking a
 * file back off. Each one through the real `AppShell`, on a phone and
 * on both tablet shapes, asserting the button carries no words, says
 * what it is to a screen reader, and has a drawing in it that is there
 * to see. The web's `ActionIconsTest` is the same claims.
 */
@RunWith(AndroidJUnit4::class)
class ActionIconsTest {

    @get:Rule
    val rule = createComposeRule()

    private fun on(size: DpSize?) = if (size == null) "on a phone" else "on a ${size.width} by ${size.height} screen"

    private fun shell(start: AppState, size: DpSize?, onClearHistory: () -> Unit = {}): MutableState<AppState> {
        val held = mutableStateOf(start)
        val content: @Composable () -> Unit = {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface {
                    AppShell(
                        state = held.value,
                        onState = { held.value = it },
                        onSearch = {},
                        onOpenDeck = {},
                        onPreviewEntry = {},
                        onApplyEntry = {},
                        onClearHistory = onClearHistory,
                    )
                }
            }
        }
        rule.setContent { if (size == null) content() else Tablet.Sized(size) { content() } }
        rule.waitForIdle()
        return held
    }

    /**
     * The button that says [label] to a screen reader is a drawing:
     * no words on its face, an icon tagged [icon] inside it, drawn at
     * a size you can see and on the screen.
     */
    private fun drawn(label: String, icon: String, size: DpSize?) {
        val on = on(size)
        val buttons = rule.onAllNodes(hasContentDescription(label) and hasClickAction()).fetchSemanticsNodes()
        assertEquals(1, buttons.size, "$on there is no one button that says \"$label\" to a screen reader")
        rule.onNode(hasContentDescription(label) and hasClickAction()).performScrollTo()
        val words = rule.onAllNodes(hasText(label.substringBefore(' '), substring = true) and hasAnyAncestor(hasContentDescription(label)), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .flatMap { n -> n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }
        assertTrue(words.isEmpty(), "$on \"$label\" is still written on the button: $words")
        val drawings = rule.onAllNodes(hasTestTag(icon) and hasAnyAncestor(hasContentDescription(label)), useUnmergedTree = true)
            .fetchSemanticsNodes()
        assertEquals(1, drawings.size, "$on \"$label\" has no $icon drawn in it")
        val r = rule.onNode(hasTestTag(icon) and hasAnyAncestor(hasContentDescription(label)), useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        assertTrue(r.right - r.left >= 16.dp && r.bottom - r.top >= 16.dp, "$on the $icon in \"$label\" is too small to see: $r")
        if (size != null) assertTrue(r.right <= size.width && r.bottom <= size.height, "$on the $icon in \"$label\" is off screen: $r")
    }

    private fun press(label: String) {
        rule.onNode(hasContentDescription(label) and hasClickAction()).performScrollTo().performClick()
        rule.waitForIdle()
    }

    private val signedIn = Admin().signIn(Account(key = "e7de0cb1", role = "admin"), "t")

    // ------------------------------------------------------- fetch list

    private val owned = listOf(
        OwnedPrinting("counterspell", "Counterspell", null, "Instant", "U", "common", 2, identity = "U"),
        OwnedPrinting("sol ring", "Sol Ring", null, "Artifact", null, "uncommon", 1),
    )
    private val list = "1 Counterspell\n1 Sol Ring\n1 Black Lotus"

    private fun fetchPage() = AppState(
        admin = signedIn,
        route = Route(View.FETCH, FetchListState.PAGE),
        fetch = FetchListState(list = list).found(FetchList.build(DeckList.entries(list), owned)),
    )

    private fun theFetchListsRemoveIsABinAndEditIsAPencil(size: DpSize?) {
        val held = shell(fetchPage(), size)
        drawn("Remove Counterspell from the list", TRASH_ICON_TAG, size)
        drawn("Remove Black Lotus from the list", TRASH_ICON_TAG, size)
        drawn("Edit", PENCIL_ICON_TAG, size)
        press("Remove Counterspell from the list")
        assertTrue(held.value.fetch.list.lines().none { "Counterspell" in it }, "${on(size)} the bin did not take Counterspell off")
        press("Edit")
        assertEquals("1 Sol Ring\n1 Black Lotus", held.value.fetch.editing, "${on(size)} the pencil did not open the list text")
    }

    private fun aSavedListIsDeletedWithABin(size: DpSize?) {
        val saved = SavedFetch("s1", "Alela", "2026-10-12T00:00:00Z", list, null)
        val held = shell(AppState(admin = signedIn, route = Route(View.FETCH), fetch = FetchListState(saved = listOf(saved))), size)
        drawn("Delete Alela", TRASH_ICON_TAG, size)
        press("Delete Alela")
        assertTrue(held.value.fetch.saved.isEmpty(), "${on(size)} the bin did not delete the saved list")
    }

    // --------------------------------------------------------- new task

    private fun aFileComesOffANewTaskWithAnX(size: DpSize?) {
        val start = AppState(admin = signedIn).startingATask().let {
            it.copy(newTask = it.newTask.attach(listOf(TaskFile("shot.png", "image/png", 820_000, "aGVsbG8="))))
        }
        val held = shell(start, size)
        drawn("Remove shot.png", CROSS_ICON_TAG, size)
        press("Remove shot.png")
        assertTrue(held.value.newTask.files.isEmpty(), "${on(size)} the x did not take the file off")
    }

    // ------------------------------------------------------- mass entry

    private fun recentEntriesAreClearedWithAnX(size: DpSize?) {
        var cleared = 0
        val start = AppState(
            admin = signedIn,
            route = Route(View.ENTRY),
            entry = MassEntry().choose(Direction.ADD).goTo(Step.LIST),
            history = EntryHistory().remember(HistoryEntry("2026-09-28T10:00:00Z", "add", "matt", 12, "12 Sol Ring")),
        )
        shell(start, size, onClearHistory = { cleared++ })
        drawn("Clear history", CROSS_ICON_TAG, size)
        press("Clear history")
        assertEquals(1, cleared, "${on(size)} the x did not clear the history")
    }

    // ------------------------------------------------------------ decks

    private val commander = DeckCard(
        "Alela, Artful Provocateur", qty = 1, role = "commander", owned = 1,
        nameNorm = "alela, artful provocateur", typeLine = "Legendary Creature — Faerie", scryfallId = "abcdef12-3456",
    )

    private fun opened() = AppState(
        route = Route(View.DECKS, "alela"),
        admin = Admin().signIn(Account("matt"), session = "t"),
        decks = DecksState()
            .loaded(listOf(Deck("alela", "Alela", "matt", "Alela, Artful Provocateur", "UW", 3, null)))
            .opened("alela", listOf(commander, DeckCard("Sol Ring", 1, null, 1))),
    )

    private fun aDeckIsRenamedWithAPencil(size: DpSize?) {
        shell(opened(), size)
        drawn("Rename", PENCIL_ICON_TAG, size)
    }

    private fun theDeckCarouselRemovesWithABin(size: DpSize?) {
        // The commander is the one card the stepper does not count, so
        // its sheet is where Remove is still a button of its own.
        shell(opened().peekCard(commander), size)
        drawn("Remove", TRASH_ICON_TAG, size)
    }

    // ------------------------------------------------------------ tests

    @Test fun theFetchListsRemoveIsABinAndEditIsAPencil() = theFetchListsRemoveIsABinAndEditIsAPencil(null)

    @Test @Config(qualifiers = Tablet.UNFOLDED_QUALIFIERS)
    fun theFetchListsRemoveIsABinAndEditIsAPencilOnAFoldingPhoneOpenedOut() = theFetchListsRemoveIsABinAndEditIsAPencil(Tablet.UNFOLDED)

    @Test @Config(qualifiers = Tablet.LANDSCAPE_QUALIFIERS)
    fun theFetchListsRemoveIsABinAndEditIsAPencilOnATabletHeldSideways() = theFetchListsRemoveIsABinAndEditIsAPencil(Tablet.LANDSCAPE)

    @Test fun aSavedListIsDeletedWithABin() = aSavedListIsDeletedWithABin(null)

    @Test @Config(qualifiers = Tablet.UNFOLDED_QUALIFIERS)
    fun aSavedListIsDeletedWithABinOnAFoldingPhoneOpenedOut() = aSavedListIsDeletedWithABin(Tablet.UNFOLDED)

    @Test @Config(qualifiers = Tablet.LANDSCAPE_QUALIFIERS)
    fun aSavedListIsDeletedWithABinOnATabletHeldSideways() = aSavedListIsDeletedWithABin(Tablet.LANDSCAPE)

    @Test fun aFileComesOffANewTaskWithAnX() = aFileComesOffANewTaskWithAnX(null)

    @Test @Config(qualifiers = Tablet.UNFOLDED_QUALIFIERS)
    fun aFileComesOffANewTaskWithAnXOnAFoldingPhoneOpenedOut() = aFileComesOffANewTaskWithAnX(Tablet.UNFOLDED)

    @Test @Config(qualifiers = Tablet.LANDSCAPE_QUALIFIERS)
    fun aFileComesOffANewTaskWithAnXOnATabletHeldSideways() = aFileComesOffANewTaskWithAnX(Tablet.LANDSCAPE)

    @Test fun recentEntriesAreClearedWithAnX() = recentEntriesAreClearedWithAnX(null)

    @Test @Config(qualifiers = Tablet.UNFOLDED_QUALIFIERS)
    fun recentEntriesAreClearedWithAnXOnAFoldingPhoneOpenedOut() = recentEntriesAreClearedWithAnX(Tablet.UNFOLDED)

    @Test @Config(qualifiers = Tablet.LANDSCAPE_QUALIFIERS)
    fun recentEntriesAreClearedWithAnXOnATabletHeldSideways() = recentEntriesAreClearedWithAnX(Tablet.LANDSCAPE)

    @Test fun aDeckIsRenamedWithAPencil() = aDeckIsRenamedWithAPencil(null)

    @Test @Config(qualifiers = Tablet.UNFOLDED_QUALIFIERS)
    fun aDeckIsRenamedWithAPencilOnAFoldingPhoneOpenedOut() = aDeckIsRenamedWithAPencil(Tablet.UNFOLDED)

    @Test @Config(qualifiers = Tablet.LANDSCAPE_QUALIFIERS)
    fun aDeckIsRenamedWithAPencilOnATabletHeldSideways() = aDeckIsRenamedWithAPencil(Tablet.LANDSCAPE)

    @Test fun theDeckCarouselRemovesWithABin() = theDeckCarouselRemovesWithABin(null)

    @Test @Config(qualifiers = Tablet.UNFOLDED_QUALIFIERS)
    fun theDeckCarouselRemovesWithABinOnAFoldingPhoneOpenedOut() = theDeckCarouselRemovesWithABin(Tablet.UNFOLDED)

    @Test @Config(qualifiers = Tablet.LANDSCAPE_QUALIFIERS)
    fun theDeckCarouselRemovesWithABinOnATabletHeldSideways() = theDeckCarouselRemovesWithABin(Tablet.LANDSCAPE)
}
