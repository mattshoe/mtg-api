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
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
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
import org.mattshoe.mtg.core.Direction
import org.mattshoe.mtg.core.MassEntry
import org.mattshoe.mtg.core.Step
import org.mattshoe.mtg.core.DeckCard
import org.mattshoe.mtg.core.DeckList
import org.mattshoe.mtg.core.EntryHistory
import org.mattshoe.mtg.core.Facet
import org.mattshoe.mtg.core.FetchList
import org.mattshoe.mtg.core.FetchListState
import org.mattshoe.mtg.core.HistoryEntry
import org.mattshoe.mtg.core.OwnedPrinting
import org.mattshoe.mtg.core.Route
import org.mattshoe.mtg.core.SavedFetch
import org.mattshoe.mtg.core.TaskFile
import org.mattshoe.mtg.core.Tweak
import org.mattshoe.mtg.core.View
import org.mattshoe.mtg.core.findingFetch
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every action that used to be a word is drawn, on a phone and on a
 * tablet.
 *
 * Matt: "Instead of an X icon or trash icon, just using the text
 * 'remove', same with share and edit etc etc. This looks terrible and
 * dated." Through the real `AppShell`: each button is found by the
 * word it is still called to a screen reader, holds no painted word,
 * holds a drawing, is at least 40dp each way, sits on the screen, and
 * still does what the word did. The website's `ActionIconsTest` is the
 * same claim.
 */
@RunWith(AndroidJUnit4::class)
class ActionIconsTest {

    @get:Rule
    val rule = createComposeRule()

    private lateinit var held: androidx.compose.runtime.MutableState<AppState>
    private var pressed: String? = null

    private val me = Admin().signIn(Account("matt", role = "admin"), "t")

    private fun card(name: String, role: String? = null) = DeckCard(
        name = name,
        qty = 1,
        role = role,
        owned = 3,
        nameNorm = name.lowercase(),
        typeLine = "Creature",
        scryfallId = "abcdef12-3456",
    )

    private fun onADeck(): AppState = AppState(admin = me)
        .navigate(Route(View.DECKS, "alela"))
        .let {
            it.copy(
                decks = it.decks
                    .loaded(listOf(Deck("alela", "Alela", "matt", null, "UW", 3, null)))
                    .opened("alela", listOf(card("Alela, Artful Provocateur", role = "commander"), card("Cultivate"), card("Sol Ring"))),
            )
        }

    private val owned = listOf(
        OwnedPrinting("counterspell", "Counterspell", "Counterspell", "Instant", "U", "common", 2, identity = "U"),
    )

    private fun shell(start: AppState, size: DpSize?) {
        rule.setContent {
            val shell = @androidx.compose.runtime.Composable {
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
                            onFetch = {
                                val s = held.value.findingFetch()
                                held.value = s.copy(
                                    fetch = s.fetch.found(FetchList.build(DeckList.entries(s.fetch.list), owned, emptyMap())),
                                )
                            },
                            onClearHistory = { pressed = "clear history" },
                            onEditDeck = { pressed = "edit $it" },
                            onAskRename = { pressed = "rename $it" },
                            onTweak = { c, t -> pressed = "${t?.name} ${c.name}" },
                        )
                    }
                }
            }
            if (size == null) shell() else Tablet.Sized(size) { shell() }
        }
        rule.waitForIdle()
    }

    private fun action(label: String): SemanticsNodeInteraction =
        rule.onNode(hasContentDescription(label) and hasClickAction())

    /**
     * The action called [label] is a drawing of [glyph], big enough to
     * hit and on the screen, and pressing it does [did].
     */
    private fun drawn(label: String, glyph: Glyph, size: DpSize?, did: () -> Boolean) {
        val on = if (size == null) "on a phone" else "on a ${size.width} by ${size.height} screen"
        val nodes = rule.onAllNodes(hasContentDescription(label) and hasClickAction()).fetchSemanticsNodes()
        if (nodes.size != 1) {
            val buttons = rule.onAllNodes(hasClickAction()).fetchSemanticsNodes().map {
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
                    ?: it.config.getOrNull(SemanticsProperties.Text)?.joinToString { t -> t.text }
            }
            error("$on there are ${nodes.size} buttons a screen reader calls \"$label\"; the buttons are $buttons")
        }
        runCatching { action(label).performScrollTo() }
        rule.waitForIdle()

        val words = action(label).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text).orEmpty()
        assertTrue(words.isEmpty(), "$on \"$label\" is still spelled out: ${words.map { it.text }}")
        rule.onNode(hasTestTag(glyph.tag) and hasAnyAncestor(hasContentDescription(label)), useUnmergedTree = true)
            .assertExists("$on \"$label\" has no ${glyph.name.lowercase()} drawn in it")

        val box = action(label).getUnclippedBoundsInRoot()
        assertTrue(box.right - box.left >= 40.dp, "$on \"$label\" is ${box.right - box.left} wide, too narrow to hit")
        assertTrue(box.bottom - box.top >= 40.dp, "$on \"$label\" is ${box.bottom - box.top} tall, too short to hit")
        // Against the size the screen was forced to, not the root: on a
        // device `Tablet.Sized` lays a tablet out inside a phone's
        // window, so the root is the phone. See ClearTheBoxTest.
        val right = size?.width ?: rule.onRoot().getUnclippedBoundsInRoot().right
        assertTrue(
            box.left >= 0.dp && box.right <= right,
            "$on \"$label\" runs off the screen: ${box.left}..${box.right} on 0..$right",
        )

        action(label).performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        assertTrue(did(), "$on pressing \"$label\" did not do what the word did")
    }

    // ---------------------------------------------------------- the carousel

    private fun removeFromTheCarousel(size: DpSize?) {
        // The commander: on any other row the stepper's minus is the
        // remove, and the sheet offers only Swap.
        shell(onADeck(), size)
        rule.onNode(hasText("Alela, Artful Provocateur") and hasClickAction()).performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        drawn("Remove", Glyph.BIN, size) { pressed == "REMOVE Alela, Artful Provocateur" }
    }

    @Test fun theCarouselsRemoveIsABinOnAPhone() = removeFromTheCarousel(null)

    @Test @Config(qualifiers = Tablet.UNFOLDED_QUALIFIERS)
    fun theCarouselsRemoveIsABinOnAFoldingPhoneOpenedOut() = removeFromTheCarousel(Tablet.UNFOLDED)

    @Test @Config(qualifiers = Tablet.LANDSCAPE_QUALIFIERS)
    fun theCarouselsRemoveIsABinOnATabletHeldSideways() = removeFromTheCarousel(Tablet.LANDSCAPE)

    // ---------------------------------------------------------- the deck

    private fun editTheDeck(size: DpSize?) {
        shell(onADeck(), size)
        drawn("Edit list", Glyph.PENCIL, size) { pressed == "edit alela" }
    }

    private fun renameTheDeck(size: DpSize?) {
        shell(onADeck(), size)
        drawn("Rename", Glyph.RENAME, size) { pressed == "rename alela" }
    }

    @Test fun editListIsAPencilOnAPhone() = editTheDeck(null)

    @Test @Config(qualifiers = Tablet.UNFOLDED_QUALIFIERS)
    fun editListIsAPencilOnAFoldingPhoneOpenedOut() = editTheDeck(Tablet.UNFOLDED)

    @Test @Config(qualifiers = Tablet.LANDSCAPE_QUALIFIERS)
    fun editListIsAPencilOnATabletHeldSideways() = editTheDeck(Tablet.LANDSCAPE)

    @Test fun renameIsDrawnOnAPhone() = renameTheDeck(null)

    @Test @Config(qualifiers = Tablet.UNFOLDED_QUALIFIERS)
    fun renameIsDrawnOnAFoldingPhoneOpenedOut() = renameTheDeck(Tablet.UNFOLDED)

    @Test @Config(qualifiers = Tablet.LANDSCAPE_QUALIFIERS)
    fun renameIsDrawnOnATabletHeldSideways() = renameTheDeck(Tablet.LANDSCAPE)

    // ---------------------------------------------------------- the fetch list

    private fun deleteASavedList(size: DpSize?) {
        val saved = SavedFetch("s1", "Alela", "2026-10-12T00:00:00Z", "1 Counterspell", null)
        shell(AppState(admin = me, fetch = FetchListState(saved = listOf(saved))).navigate(Route(View.FETCH)), size)
        drawn("Delete Alela", Glyph.BIN, size) { held.value.fetch.saved.isEmpty() }
    }

    private fun found(size: DpSize?) {
        shell(AppState(admin = me).navigate(Route(View.FETCH)), size)
        rule.onNodeWithTag("fetch-list").performTextInput("1 Counterspell\n1 Black Lotus")
        rule.waitForIdle()
        rule.onNode((hasTestTag("fetch-find") or hasAnyAncestor(hasTestTag("fetch-find"))) and hasClickAction())
            .performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        assertTrue(held.value.fetch.result != null, "Find cards did nothing, so there is no list to edit")
    }

    private fun editTheFetchList(size: DpSize?) {
        found(size)
        drawn("Edit", Glyph.PENCIL, size) { held.value.fetch.editing != null }
    }

    private fun removeAMissingCard(size: DpSize?) {
        found(size)
        drawn("Remove Black Lotus from the list", Glyph.CROSS, size) { "Black Lotus" !in held.value.fetch.list }
    }

    @Test fun aSavedListsDeleteIsABinOnAPhone() = deleteASavedList(null)

    @Test @Config(qualifiers = Tablet.UNFOLDED_QUALIFIERS)
    fun aSavedListsDeleteIsABinOnAFoldingPhoneOpenedOut() = deleteASavedList(Tablet.UNFOLDED)

    @Test @Config(qualifiers = Tablet.LANDSCAPE_QUALIFIERS)
    fun aSavedListsDeleteIsABinOnATabletHeldSideways() = deleteASavedList(Tablet.LANDSCAPE)

    @Test fun theFetchListsEditIsAPencilOnAPhone() = editTheFetchList(null)

    @Test @Config(qualifiers = Tablet.UNFOLDED_QUALIFIERS)
    fun theFetchListsEditIsAPencilOnAFoldingPhoneOpenedOut() = editTheFetchList(Tablet.UNFOLDED)

    @Test @Config(qualifiers = Tablet.LANDSCAPE_QUALIFIERS)
    fun theFetchListsEditIsAPencilOnATabletHeldSideways() = editTheFetchList(Tablet.LANDSCAPE)

    @Test fun aMissingCardsRemoveIsACrossOnAPhone() = removeAMissingCard(null)

    @Test @Config(qualifiers = Tablet.UNFOLDED_QUALIFIERS)
    fun aMissingCardsRemoveIsACrossOnAFoldingPhoneOpenedOut() = removeAMissingCard(Tablet.UNFOLDED)

    @Test @Config(qualifiers = Tablet.LANDSCAPE_QUALIFIERS)
    fun aMissingCardsRemoveIsACrossOnATabletHeldSideways() = removeAMissingCard(Tablet.LANDSCAPE)

    // ---------------------------------------------------------- the filters

    private fun clearTheColours(size: DpSize?) {
        val start = AppState(admin = me).navigate(Route(View.LIBRARY)).let {
            it.copy(library = it.library.copy(filters = it.library.filters.copy(colors = listOf("W", "U"))))
        }
        shell(start, size)
        // A group with a filter in force opens by itself.
        if (rule.onAllNodes(hasContentDescription("Clear the colours")).fetchSemanticsNodes().isEmpty()) rule.onNodeWithTag("header-${Facet.COLOUR.id}").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        drawn("Clear the colours", Glyph.CROSS, size) { held.value.library.filters.colors.isEmpty() }
    }

    @Test fun clearingTheColoursIsACrossOnAPhone() = clearTheColours(null)

    @Test @Config(qualifiers = Tablet.UNFOLDED_QUALIFIERS)
    fun clearingTheColoursIsACrossOnAFoldingPhoneOpenedOut() = clearTheColours(Tablet.UNFOLDED)

    @Test @Config(qualifiers = Tablet.LANDSCAPE_QUALIFIERS)
    fun clearingTheColoursIsACrossOnATabletHeldSideways() = clearTheColours(Tablet.LANDSCAPE)

    // ---------------------------------------------------------- mass entry

    private fun clearTheHistory(size: DpSize?) {
        val history = EntryHistory(listOf(HistoryEntry("2026-10-12T09:30:00Z", "add", "matt", 3, "3 Sol Ring")))
        // The history is on the step with the box, which it puts an old list back into.
        val entry = MassEntry(step = Step.LIST, direction = Direction.ADD)
        shell(AppState(admin = me, history = history, entry = entry).navigate(Route(View.ENTRY)), size)
        drawn("Clear history", Glyph.BIN, size) { pressed == "clear history" }
    }

    @Test fun clearingTheHistoryIsABinOnAPhone() = clearTheHistory(null)

    @Test @Config(qualifiers = Tablet.UNFOLDED_QUALIFIERS)
    fun clearingTheHistoryIsABinOnAFoldingPhoneOpenedOut() = clearTheHistory(Tablet.UNFOLDED)

    @Test @Config(qualifiers = Tablet.LANDSCAPE_QUALIFIERS)
    fun clearingTheHistoryIsABinOnATabletHeldSideways() = clearTheHistory(Tablet.LANDSCAPE)

    // ---------------------------------------------------------- new task

    private fun removeAFile(size: DpSize?) {
        val start = AppState(admin = me).startingATask().let {
            it.copy(newTask = it.newTask.attach(listOf(TaskFile("shot.png", "image/png", 820_000, "aGVsbG8="))))
        }
        shell(start, size)
        drawn("Remove shot.png", Glyph.CROSS, size) { held.value.newTask.files.isEmpty() }
    }

    @Test fun removingAFileIsACrossOnAPhone() = removeAFile(null)

    @Test @Config(qualifiers = Tablet.UNFOLDED_QUALIFIERS)
    fun removingAFileIsACrossOnAFoldingPhoneOpenedOut() = removeAFile(Tablet.UNFOLDED)

    @Test @Config(qualifiers = Tablet.LANDSCAPE_QUALIFIERS)
    fun removingAFileIsACrossOnATabletHeldSideways() = removeAFile(Tablet.LANDSCAPE)
}
