package org.mattshoe.mtg.web

import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import org.jetbrains.compose.web.renderComposable
import org.mattshoe.mtg.core.Account
import org.mattshoe.mtg.core.Admin
import org.mattshoe.mtg.core.AppState
import org.mattshoe.mtg.core.DeckList
import org.mattshoe.mtg.core.FetchList
import org.mattshoe.mtg.core.FetchListState
import org.mattshoe.mtg.core.OwnedPrinting
import org.mattshoe.mtg.core.Route
import org.mattshoe.mtg.core.View
import org.w3c.dom.Document
import org.w3c.dom.HTMLElement
import org.w3c.dom.get
import kotlin.js.Promise
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Matt: "i want the 'not in collection' section in the fetch list to
 * show the count in the header, and every section of it". The website
 * already drew a count at the end of every head; this holds it there,
 * on the same line, inside its panel.
 *
 * The real `AppShell` on the list page, on a phone and on both tablet
 * shapes. The phone's `FetchListCountsTest` is the same claims.
 */
class FetchListCountsTest {

    private var screen: Tablet.Screen? = null

    @AfterTest
    fun cleanUp() {
        screen?.frame?.remove()
        screen = null
    }

    private suspend fun settle(w: org.w3c.dom.Window) = repeat(4) {
        Promise<Unit> { r, _ -> w.requestAnimationFrame { r(Unit) } }.await()
    }

    private val owned = listOf(
        OwnedPrinting("counterspell", "Counterspell", null, "Instant", "U", "common", 2, identity = "U"),
        OwnedPrinting("sol ring", "Sol Ring", null, "Artifact", null, "uncommon", 1),
    )

    private val list = "2 Counterspell\n1 Sol Ring\n3 Black Lotus\n1 Mox Pearl"

    private suspend fun open(size: Pair<Int, Int>): Document {
        val start = AppState(
            admin = Admin().signIn(Account(key = "e7de0cb1"), "t"),
            route = Route(View.FETCH, FetchListState.PAGE),
            fetch = FetchListState(list = list).found(FetchList.build(DeckList.entries(list), owned)),
        )
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
            )
        }
        settle(s.window)
        return s.frame.contentDocument!!
    }

    private fun headCounts(doc: Document, on: String, section: String, count: String) {
        val panel = doc.querySelector("[data-fetch-section=\"$section\"]")?.unsafeCast<HTMLElement>()
            ?: error("$on there is no $section section")
        val head = panel.querySelector(".panel-head")?.unsafeCast<HTMLElement>() ?: error("$on the $section section has no head")
        val tag = head.querySelector(".tag")?.unsafeCast<HTMLElement>() ?: error("$on the $section head shows no count")
        assertEquals(count, tag.textContent?.trim(), "$on the $section head counts the wrong number")
        val words = head.querySelector("h2")!!.getBoundingClientRect()
        val r = tag.getBoundingClientRect()
        assertTrue(r.width > 0.0 && r.height > 0.0, "$on the $section count is not drawn: ${r.width} by ${r.height}")
        assertTrue(r.top < words.bottom && words.top < r.bottom, "$on the $section count is not on the head's line")
        assertTrue(r.right <= panel.getBoundingClientRect().right + 0.5, "$on the $section count runs off its panel")
    }

    private suspend fun everyHeadCounts(size: Pair<Int, Int>) {
        val doc = open(size)
        val on = "on a ${size.first} by ${size.second} screen"
        headCounts(doc, on, "Instants · U · Common", "2")
        headCounts(doc, on, "Artifacts · C · Uncommon", "1")
        headCounts(doc, on, "Missing", "4")
    }

    @Test fun everyHeadCountsItsCardsOnAPhone() = runTest { everyHeadCounts(PHONE) }
    @Test fun everyHeadCountsItsCardsOnAFoldingPhoneOpenedOut() = runTest { everyHeadCounts(Tablet.UNFOLDED) }
    @Test fun everyHeadCountsItsCardsOnATabletHeldSideways() = runTest { everyHeadCounts(Tablet.LANDSCAPE) }

    private companion object {
        val PHONE = 390 to 844
    }
}
