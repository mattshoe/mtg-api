package org.mattshoe.mtg.web

import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import org.jetbrains.compose.web.renderComposable
import org.mattshoe.mtg.core.Account
import org.mattshoe.mtg.core.Admin
import org.mattshoe.mtg.core.AppState
import org.mattshoe.mtg.core.Deck
import org.mattshoe.mtg.core.DeckCard
import org.mattshoe.mtg.core.DeckList
import org.mattshoe.mtg.core.Direction
import org.mattshoe.mtg.core.EntryHistory
import org.mattshoe.mtg.core.Facet
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
import org.mattshoe.mtg.core.findingFetch
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLTextAreaElement
import org.w3c.dom.events.Event
import org.w3c.dom.get
import kotlin.js.Promise
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every action that used to be a word is drawn, on a phone-sized
 * screen and on a tablet's.
 *
 * Matt: "Instead of an X icon or trash icon, just using the text
 * 'remove', same with share and edit etc etc. This looks terrible and
 * dated." Through the real `AppShell`, in a frame with the real
 * stylesheet: each button is found by its `aria-label`, paints no
 * word, holds a span the browser has masked to a shape, is at least
 * 40px each way, sits on the screen, and still does what the word
 * did. The phone's `ActionIconsTest` is the same claim.
 */
class ActionIconsTest {

    private var screen: Tablet.Screen? = null
    private var pressed: String? = null
    private lateinit var held: androidx.compose.runtime.MutableState<AppState>

    @AfterTest
    fun cleanUp() {
        screen?.frame?.remove()
        screen = null
    }

    private suspend fun settle(w: org.w3c.dom.Window) = repeat(4) {
        Promise<Unit> { r, _ -> w.requestAnimationFrame { r(Unit) } }.await()
    }

    private val me = Admin().signIn(Account("matt", role = "admin"), "t")

    private fun card(name: String, role: String? = null) =
        DeckCard(name, 1, role, 3, name.lowercase(), typeLine = "Creature", scryfallId = "abcdef12-3456")

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

    private suspend fun shell(start: AppState, size: Pair<Int, Int>): Tablet.Screen {
        val s = Tablet.open(size).also { screen = it }
        renderComposable(root = s.body) {
            held = androidx.compose.runtime.remember { mutableStateOf(start) }
            AppShell(
                state = held.value,
                onState = { held.value = it },
                onSearch = {},
                onOpenDeck = {},
                onPreviewEntry = {},
                onApplyEntry = {},
                onFetch = {
                    val f = held.value.findingFetch()
                    held.value = f.copy(fetch = f.fetch.found(FetchList.build(DeckList.entries(f.fetch.list), owned, emptyMap())))
                },
                onClearHistory = { pressed = "clear history" },
                onEditDeck = { pressed = "edit $it" },
                onAskRename = { pressed = "rename $it" },
                onTweak = { c, t -> pressed = "${t?.name} ${c.name}" },
            )
        }
        settle(s.window)
        return s
    }

    private fun Tablet.Screen.all(css: String): List<HTMLElement> =
        frame.contentDocument!!.querySelectorAll(css).let { n -> (0 until n.length).map { n[it]!!.unsafeCast<HTMLElement>() } }

    /**
     * The action called [label] is a drawing masked to [glyph], big
     * enough to hit and on the screen, and pressing it does [did].
     */
    private suspend fun Tablet.Screen.drawn(label: String, glyph: String, size: Pair<Int, Int>, did: () -> Boolean) {
        val on = "on a ${size.first} by ${size.second} screen"
        val found = all("button").filter { it.getAttribute("aria-label") == label }
        if (found.size != 1) {
            val names = all("button").map { it.getAttribute("aria-label") ?: it.textContent?.trim() }
            error("$on there are ${found.size} buttons a screen reader calls \"$label\"; the buttons are $names")
        }
        val button = found.single()
        button.scrollIntoView()
        settle(window)

        val words = button.textContent?.trim().orEmpty()
        assertEquals("", words, "$on \"$label\" is still spelled out")
        val icon = button.querySelector(".icon.$glyph")?.unsafeCast<HTMLElement>()
            ?: error("$on \"$label\" has no $glyph in it")
        val style = window.getComputedStyle(icon)
        val mask = style.getPropertyValue("mask-image").ifEmpty { style.getPropertyValue("-webkit-mask-image") }
        assertTrue("svg" in mask, "$on \"$label\"'s $glyph is masked to nothing: \"$mask\"")
        val ink = icon.getBoundingClientRect()
        assertTrue(ink.width >= 14.0 && ink.height >= 14.0, "$on \"$label\"'s $glyph is ${ink.width} by ${ink.height}px, too small to see")

        val box = button.getBoundingClientRect()
        assertTrue(box.width >= 40.0, "$on \"$label\" is ${box.width}px wide, too narrow to hit")
        assertTrue(box.height >= 40.0, "$on \"$label\" is ${box.height}px tall, too short to hit")
        assertTrue(
            box.left >= 0.0 && box.right <= size.first.toDouble(),
            "$on \"$label\" runs off the screen: ${box.left}..${box.right}",
        )

        button.click()
        settle(window)
        assertTrue(did(), "$on pressing \"$label\" did not do what the word did")
    }

    // ---------------------------------------------------------- the carousel

    private suspend fun removeFromTheCarousel(size: Pair<Int, Int>) {
        val s = shell(onADeck(), size)
        // The commander: on any other row the stepper's minus is the
        // remove, and the sheet offers only Swap.
        s.all(".deck-line").first { it.getAttribute("title") == "Alela, Artful Provocateur" }.click()
        settle(s.window)
        s.drawn("Remove", "icon-bin", size) { pressed == "REMOVE Alela, Artful Provocateur" }
    }

    @Test fun theCarouselsRemoveIsABinOnAPhone() = runTest { removeFromTheCarousel(PHONE) }
    @Test fun theCarouselsRemoveIsABinOnAFoldingPhoneOpenedOut() = runTest { removeFromTheCarousel(Tablet.UNFOLDED) }
    @Test fun theCarouselsRemoveIsABinOnATabletHeldSideways() = runTest { removeFromTheCarousel(Tablet.LANDSCAPE) }

    // ---------------------------------------------------------- the deck

    private suspend fun editTheDeck(size: Pair<Int, Int>) =
        shell(onADeck(), size).drawn("Edit list", "icon-pencil", size) { pressed == "edit alela" }

    private suspend fun renameTheDeck(size: Pair<Int, Int>) =
        shell(onADeck(), size).drawn("Rename", "icon-rename", size) { pressed == "rename alela" }

    @Test fun editListIsAPencilOnAPhone() = runTest { editTheDeck(PHONE) }
    @Test fun editListIsAPencilOnAFoldingPhoneOpenedOut() = runTest { editTheDeck(Tablet.UNFOLDED) }
    @Test fun editListIsAPencilOnATabletHeldSideways() = runTest { editTheDeck(Tablet.LANDSCAPE) }

    @Test fun renameIsDrawnOnAPhone() = runTest { renameTheDeck(PHONE) }
    @Test fun renameIsDrawnOnAFoldingPhoneOpenedOut() = runTest { renameTheDeck(Tablet.UNFOLDED) }
    @Test fun renameIsDrawnOnATabletHeldSideways() = runTest { renameTheDeck(Tablet.LANDSCAPE) }

    // ---------------------------------------------------------- the fetch list

    private suspend fun deleteASavedList(size: Pair<Int, Int>) {
        val saved = SavedFetch("s1", "Alela", "2026-10-12T00:00:00Z", "1 Counterspell", null)
        shell(AppState(admin = me, fetch = FetchListState(saved = listOf(saved))).navigate(Route(View.FETCH)), size)
            .drawn("Delete Alela", "icon-bin", size) { held.value.fetch.saved.isEmpty() }
    }

    private suspend fun found(size: Pair<Int, Int>): Tablet.Screen {
        val s = shell(AppState(admin = me).navigate(Route(View.FETCH)), size)
        val box = s.all("#fetch-list").single().unsafeCast<HTMLTextAreaElement>()
        box.value = "1 Counterspell\n1 Black Lotus"
        box.dispatchEvent(Event("input", js("({bubbles: true})")))
        settle(s.window)
        s.all("[data-fetch-find]").single().click()
        settle(s.window)
        assertTrue(held.value.fetch.result != null, "Find cards did nothing, so there is no list to edit")
        return s
    }

    private suspend fun editTheFetchList(size: Pair<Int, Int>) =
        found(size).drawn("Edit", "icon-pencil", size) { held.value.fetch.editing != null }

    private suspend fun removeAMissingCard(size: Pair<Int, Int>) =
        found(size).drawn("Remove Black Lotus from the list", "icon-close", size) { "Black Lotus" !in held.value.fetch.list }

    @Test fun aSavedListsDeleteIsABinOnAPhone() = runTest { deleteASavedList(PHONE) }
    @Test fun aSavedListsDeleteIsABinOnAFoldingPhoneOpenedOut() = runTest { deleteASavedList(Tablet.UNFOLDED) }
    @Test fun aSavedListsDeleteIsABinOnATabletHeldSideways() = runTest { deleteASavedList(Tablet.LANDSCAPE) }

    @Test fun theFetchListsEditIsAPencilOnAPhone() = runTest { editTheFetchList(PHONE) }
    @Test fun theFetchListsEditIsAPencilOnAFoldingPhoneOpenedOut() = runTest { editTheFetchList(Tablet.UNFOLDED) }
    @Test fun theFetchListsEditIsAPencilOnATabletHeldSideways() = runTest { editTheFetchList(Tablet.LANDSCAPE) }

    @Test fun aMissingCardsRemoveIsACrossOnAPhone() = runTest { removeAMissingCard(PHONE) }
    @Test fun aMissingCardsRemoveIsACrossOnAFoldingPhoneOpenedOut() = runTest { removeAMissingCard(Tablet.UNFOLDED) }
    @Test fun aMissingCardsRemoveIsACrossOnATabletHeldSideways() = runTest { removeAMissingCard(Tablet.LANDSCAPE) }

    // ---------------------------------------------------------- the filters

    private suspend fun clearTheColours(size: Pair<Int, Int>) {
        val start = AppState(admin = me).navigate(Route(View.LIBRARY)).let {
            it.copy(library = it.library.copy(filters = it.library.filters.copy(colors = listOf("W", "U"))))
        }
        val s = shell(start, size)
        // A group with a filter in force opens by itself.
        if (s.all("[data-facet=${Facet.COLOUR.id}][open]").isEmpty()) {
            s.all("[data-facet=${Facet.COLOUR.id}] summary").single().click()
            settle(s.window)
        }
        s.drawn("Clear the colours", "icon-close", size) { held.value.library.filters.colors.isEmpty() }
    }

    @Test fun clearingTheColoursIsACrossOnAPhone() = runTest { clearTheColours(PHONE) }
    @Test fun clearingTheColoursIsACrossOnAFoldingPhoneOpenedOut() = runTest { clearTheColours(Tablet.UNFOLDED) }
    @Test fun clearingTheColoursIsACrossOnATabletHeldSideways() = runTest { clearTheColours(Tablet.LANDSCAPE) }

    // ---------------------------------------------------------- mass entry

    private suspend fun clearTheHistory(size: Pair<Int, Int>) {
        val history = EntryHistory(listOf(HistoryEntry("2026-10-12T09:30:00Z", "add", "matt", 3, "3 Sol Ring")))
        // The history is on the step with the box, which it puts an old list back into.
        val entry = MassEntry(step = Step.LIST, direction = Direction.ADD)
        shell(AppState(admin = me, history = history, entry = entry).navigate(Route(View.ENTRY)), size)
            .drawn("Clear history", "icon-bin", size) { pressed == "clear history" }
    }

    @Test fun clearingTheHistoryIsABinOnAPhone() = runTest { clearTheHistory(PHONE) }
    @Test fun clearingTheHistoryIsABinOnAFoldingPhoneOpenedOut() = runTest { clearTheHistory(Tablet.UNFOLDED) }
    @Test fun clearingTheHistoryIsABinOnATabletHeldSideways() = runTest { clearTheHistory(Tablet.LANDSCAPE) }

    // ---------------------------------------------------------- new task

    private suspend fun removeAFile(size: Pair<Int, Int>) {
        val start = AppState(admin = me).startingATask().let {
            it.copy(newTask = it.newTask.attach(listOf(TaskFile("shot.png", "image/png", 820_000, "aGVsbG8="))))
        }
        shell(start, size).drawn("Remove shot.png", "icon-close", size) { held.value.newTask.files.isEmpty() }
    }

    @Test fun removingAFileIsACrossOnAPhone() = runTest { removeAFile(PHONE) }
    @Test fun removingAFileIsACrossOnAFoldingPhoneOpenedOut() = runTest { removeAFile(Tablet.UNFOLDED) }
    @Test fun removingAFileIsACrossOnATabletHeldSideways() = runTest { removeAFile(Tablet.LANDSCAPE) }

    private companion object {
        val PHONE = 400 to 800
    }
}
