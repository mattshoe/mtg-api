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
import org.w3c.dom.HTMLElement
import org.w3c.dom.get
import kotlin.js.Promise
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Matt: "in the fetch list page, if there are no entries in the bulk
 * for a card, then show which deck it belongs to if it appears in a
 * deck".
 *
 * The real `AppShell` on the list page, on a phone and on both tablet
 * shapes. Sol Ring's only copy is in a deck, so the deck is named under
 * it. Counterspell has a spare in bulk, so nothing is. The phone's
 * `FetchListDecksTest` is the same claim.
 */
class FetchListDecksTest {

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
        OwnedPrinting(
            "sol ring", "Sol Ring", "Sol Ring", "Artifact", null, "uncommon", 1,
            inDecks = 1, decks = listOf("Chaos Incarnate"),
        ),
        OwnedPrinting(
            "counterspell", "Counterspell", "Counterspell", "Instant", "U", "common", 2, identity = "U",
            inDecks = 1, decks = listOf("Elven Council"),
        ),
    )

    private suspend fun deckNamedUnderTheCard(size: Pair<Int, Int>) {
        val list = "1 Sol Ring\n1 Counterspell"
        val start = AppState(
            admin = Admin().signIn(Account(key = "e7de0cb1"), "t"),
            route = Route(View.FETCH, FetchListState.PAGE),
            fetch = FetchListState(list = list).found(FetchList.build(DeckList.entries(list), owned)),
        )
        val s = Tablet.open(size).also { screen = it }
        renderComposable(root = s.body) {
            val held = androidx.compose.runtime.remember { mutableStateOf(start) }
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
        val doc = s.frame.contentDocument!!
        val all = { css: String -> doc.querySelectorAll(css).let { n -> (0 until n.length).map { n[it]!!.unsafeCast<HTMLElement>() } } }
        val on = "on a ${size.first} by ${size.second} screen"
        fun card(name: String) = all("[data-fetch-card]").singleOrNull { it.getAttribute("data-fetch-card") == name }
            ?: error("$on $name is not on the list")

        val line = card("Sol Ring").querySelector("[data-fetch-decks]")?.unsafeCast<HTMLElement>()
            ?: error("$on Sol Ring has no copy in bulk and no deck is named under it")
        assertEquals("In deck: Chaos Incarnate", line.textContent?.trim(), on)
        val r = line.getBoundingClientRect()
        val name = card("Sol Ring").querySelector(".fetch-name")!!.unsafeCast<HTMLElement>().getBoundingClientRect()
        assertTrue(r.height > 4.0, "$on the deck line is not drawn: ${r.height}px tall")
        assertTrue(r.top >= name.bottom - 1.0, "$on the deck is not under its card: ${r.top} against ${name.bottom}")
        assertTrue(r.right <= size.first, "$on the deck line runs off the side: ${r.right}")
        assertTrue(
            card("Counterspell").querySelector("[data-fetch-decks]") == null,
            "$on Counterspell has a spare in bulk and a deck is still named under it",
        )
    }

    @Test
    fun aCardWithNoCopyInBulkNamesItsDeckOnAPhone() = runTest { deckNamedUnderTheCard(390 to 844) }

    @Test
    fun aCardWithNoCopyInBulkNamesItsDeckOnAFoldingPhoneOpenedOut() = runTest { deckNamedUnderTheCard(Tablet.UNFOLDED) }

    @Test
    fun aCardWithNoCopyInBulkNamesItsDeckOnATabletHeldSideways() = runTest { deckNamedUnderTheCard(Tablet.LANDSCAPE) }
}
