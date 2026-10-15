package org.mattshoe.mtg.web

import androidx.compose.runtime.MutableState
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
import org.w3c.dom.HTMLElement
import org.w3c.dom.get
import kotlin.js.Promise
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Matt: "change the 'remove' text on the fetch list carousel to just an
 * icon. and stop using text for buttons when an icon would suffice.
 * like globally".
 *
 * Every action that was a word, drawn instead: a bin for remove and
 * delete, a pencil for edit and rename, an x for clear and for taking a
 * file back off. Each one through the real `AppShell` with the real
 * stylesheet, on a phone and on both tablet shapes, asserting the
 * button has no words on it, says what it is through `aria-label`, and
 * holds an icon the stylesheet actually masks to a shape. The phone's
 * `ActionIconsTest` is the same claims.
 */
class ActionIconsTest {

    private var screen: Tablet.Screen? = null

    @AfterTest
    fun cleanUp() {
        screen?.frame?.remove()
        screen = null
    }

    private suspend fun settle(w: org.w3c.dom.Window) = repeat(4) {
        Promise<Unit> { r, _ -> w.requestAnimationFrame { r(Unit) } }.await()
    }

    private val phone = 390 to 844

    private class Page(val s: Tablet.Screen, val held: MutableState<AppState>, val size: Pair<Int, Int>) {
        val on = "on a ${size.first} by ${size.second} screen"
        val doc get() = s.frame.contentDocument!!
        fun all(css: String) = doc.querySelectorAll(css).let { n -> (0 until n.length).map { n[it]!!.unsafeCast<HTMLElement>() } }
    }

    private suspend fun open(start: AppState, size: Pair<Int, Int>, onClearHistory: () -> Unit = {}): Page {
        val s = Tablet.open(size).also { screen = it }
        val held = mutableStateOf(start)
        renderComposable(root = s.body) {
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
        settle(s.window)
        return Page(s, held, size)
    }

    /**
     * The button that says [label] to a screen reader is a drawing: no
     * words on its face, and a `.icon-[icon]` in it that the stylesheet
     * masks to a shape, at a size you can see, on the screen.
     */
    private fun Page.drawn(label: String, icon: String): HTMLElement {
        val buttons = all("button[aria-label=\"$label\"]")
        assertEquals(1, buttons.size, "$on there is no one button that says \"$label\" to a screen reader")
        val button = buttons.single()
        button.scrollIntoView()
        val words = button.textContent.orEmpty().trim()
        assertEquals("", words, "$on \"$label\" is still written on the button")
        val drawing = button.querySelector(".icon-$icon")?.unsafeCast<HTMLElement>()
            ?: error("$on \"$label\" has no .icon-$icon in it")
        val style = s.window.getComputedStyle(drawing)
        val mask = style.getPropertyValue("mask-image").ifBlank { style.getPropertyValue("-webkit-mask-image") }
        assertTrue("svg" in mask, "$on the .icon-$icon in \"$label\" has no shape in the stylesheet: \"$mask\"")
        val r = drawing.getBoundingClientRect()
        assertTrue(r.width >= 12.0 && r.height >= 12.0, "$on the .icon-$icon in \"$label\" is too small to see: ${r.width} by ${r.height}")
        assertTrue(
            r.left >= -0.5 && r.right <= size.first + 0.5 && r.top >= -0.5 && r.bottom <= size.second + 0.5,
            "$on the .icon-$icon in \"$label\" is off screen: ${r.left},${r.top} to ${r.right},${r.bottom}",
        )
        return button
    }

    private val signedIn = Admin().signIn(Account(key = "e7de0cb1", role = "admin"), "t")

    // ------------------------------------------------------- fetch list

    private val owned = listOf(
        OwnedPrinting("counterspell", "Counterspell", null, "Instant", "U", "common", 2, identity = "U"),
        OwnedPrinting("sol ring", "Sol Ring", null, "Artifact", null, "uncommon", 1),
    )
    private val list = "1 Counterspell\n1 Sol Ring\n1 Black Lotus"

    private suspend fun theFetchListsRemoveIsABinAndEditIsAPencil(size: Pair<Int, Int>) {
        val p = open(
            AppState(
                admin = signedIn,
                route = Route(View.FETCH, FetchListState.PAGE),
                fetch = FetchListState(list = list).found(FetchList.build(DeckList.entries(list), owned)),
            ),
            size,
        )
        p.drawn("Remove Black Lotus from the list", "trash")
        p.drawn("Edit", "edit")
        p.drawn("Remove Counterspell from the list", "trash").click()
        settle(p.s.window)
        assertTrue(p.held.value.fetch.list.lines().none { "Counterspell" in it }, "${p.on} the bin did not take Counterspell off")
        p.drawn("Edit", "edit").click()
        settle(p.s.window)
        assertEquals("1 Sol Ring\n1 Black Lotus", p.held.value.fetch.editing, "${p.on} the pencil did not open the list text")
    }

    private suspend fun aSavedListIsDeletedWithABin(size: Pair<Int, Int>) {
        val saved = SavedFetch("s1", "Alela", "2026-10-12T00:00:00Z", list, null)
        val p = open(AppState(admin = signedIn, route = Route(View.FETCH), fetch = FetchListState(saved = listOf(saved))), size)
        p.drawn("Delete Alela", "trash").click()
        settle(p.s.window)
        assertTrue(p.held.value.fetch.saved.isEmpty(), "${p.on} the bin did not delete the saved list")
    }

    // --------------------------------------------------------- new task

    private suspend fun aFileComesOffANewTaskWithAnX(size: Pair<Int, Int>) {
        val start = AppState(admin = signedIn).startingATask().let {
            it.copy(newTask = it.newTask.attach(listOf(TaskFile("shot.png", "image/png", 820_000, "aGVsbG8="))))
        }
        val p = open(start, size)
        p.drawn("Remove shot.png", "close").click()
        settle(p.s.window)
        assertTrue(p.held.value.newTask.files.isEmpty(), "${p.on} the x did not take the file off")
    }

    // ------------------------------------------------------- mass entry

    private suspend fun recentEntriesAreClearedWithAnX(size: Pair<Int, Int>) {
        var cleared = 0
        val start = AppState(
            admin = signedIn,
            route = Route(View.ENTRY),
            entry = MassEntry().choose(Direction.ADD).goTo(Step.LIST),
            history = EntryHistory().remember(HistoryEntry("2026-09-28T10:00:00Z", "add", "matt", 12, "12 Sol Ring")),
        )
        val p = open(start, size, onClearHistory = { cleared++ })
        p.drawn("Clear history", "close").click()
        settle(p.s.window)
        assertEquals(1, cleared, "${p.on} the x did not clear the history")
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

    private suspend fun aDeckIsRenamedWithAPencil(size: Pair<Int, Int>) {
        open(opened(), size).drawn("Rename", "edit")
    }

    private suspend fun theDeckCarouselRemovesWithABin(size: Pair<Int, Int>) {
        // The commander is the one card the stepper does not count, so
        // its sheet is where Remove is still a button of its own.
        open(opened().peekCard(commander), size).drawn("Remove", "trash")
    }

    // ------------------------------------------------------------ tests

    @Test fun theFetchListsRemoveIsABinAndEditIsAPencilOnAPhone() = runTest { theFetchListsRemoveIsABinAndEditIsAPencil(phone) }
    @Test fun theFetchListsRemoveIsABinAndEditIsAPencilOnAFoldingPhoneOpenedOut() = runTest { theFetchListsRemoveIsABinAndEditIsAPencil(Tablet.UNFOLDED) }
    @Test fun theFetchListsRemoveIsABinAndEditIsAPencilOnATabletHeldSideways() = runTest { theFetchListsRemoveIsABinAndEditIsAPencil(Tablet.LANDSCAPE) }

    @Test fun aSavedListIsDeletedWithABinOnAPhone() = runTest { aSavedListIsDeletedWithABin(phone) }
    @Test fun aSavedListIsDeletedWithABinOnAFoldingPhoneOpenedOut() = runTest { aSavedListIsDeletedWithABin(Tablet.UNFOLDED) }
    @Test fun aSavedListIsDeletedWithABinOnATabletHeldSideways() = runTest { aSavedListIsDeletedWithABin(Tablet.LANDSCAPE) }

    @Test fun aFileComesOffANewTaskWithAnXOnAPhone() = runTest { aFileComesOffANewTaskWithAnX(phone) }
    @Test fun aFileComesOffANewTaskWithAnXOnAFoldingPhoneOpenedOut() = runTest { aFileComesOffANewTaskWithAnX(Tablet.UNFOLDED) }
    @Test fun aFileComesOffANewTaskWithAnXOnATabletHeldSideways() = runTest { aFileComesOffANewTaskWithAnX(Tablet.LANDSCAPE) }

    @Test fun recentEntriesAreClearedWithAnXOnAPhone() = runTest { recentEntriesAreClearedWithAnX(phone) }
    @Test fun recentEntriesAreClearedWithAnXOnAFoldingPhoneOpenedOut() = runTest { recentEntriesAreClearedWithAnX(Tablet.UNFOLDED) }
    @Test fun recentEntriesAreClearedWithAnXOnATabletHeldSideways() = runTest { recentEntriesAreClearedWithAnX(Tablet.LANDSCAPE) }

    @Test fun aDeckIsRenamedWithAPencilOnAPhone() = runTest { aDeckIsRenamedWithAPencil(phone) }
    @Test fun aDeckIsRenamedWithAPencilOnAFoldingPhoneOpenedOut() = runTest { aDeckIsRenamedWithAPencil(Tablet.UNFOLDED) }
    @Test fun aDeckIsRenamedWithAPencilOnATabletHeldSideways() = runTest { aDeckIsRenamedWithAPencil(Tablet.LANDSCAPE) }

    @Test fun theDeckCarouselRemovesWithABinOnAPhone() = runTest { theDeckCarouselRemovesWithABin(phone) }
    @Test fun theDeckCarouselRemovesWithABinOnAFoldingPhoneOpenedOut() = runTest { theDeckCarouselRemovesWithABin(Tablet.UNFOLDED) }
    @Test fun theDeckCarouselRemovesWithABinOnATabletHeldSideways() = runTest { theDeckCarouselRemovesWithABin(Tablet.LANDSCAPE) }
}
