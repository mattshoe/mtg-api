package org.mattshoe.mtg.web

import androidx.compose.runtime.MutableState
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
import org.mattshoe.mtg.core.Overlay
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Matt: "on the fetch list carousel, i want to be able to remove cards
 * from the list. if i do so, the carousel should update in place with
 * the card removed, but i should keep my place in the carousel, just
 * moved forward one card to the next (unless i remove the last
 * obviously)".
 *
 * The real `AppShell` on the list page, on a phone and on both tablet
 * shapes. The phone's `FetchListCarouselRemoveTest` is the same claims.
 */
class FetchListCarouselRemoveTest {

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
        OwnedPrinting("arcane signet", "Arcane Signet", null, "Artifact", null, "common", 1),
        OwnedPrinting("sol ring", "Sol Ring", null, "Artifact", null, "uncommon", 1),
    )

    // Page order: Counterspell, Arcane Signet, Sol Ring.
    private val list = "1 Counterspell\n1 Arcane Signet\n1 Sol Ring"

    private class Page(val doc: Document, val held: MutableState<AppState>, val w: org.w3c.dom.Window, val size: Pair<Int, Int>) {
        val on = "on a ${size.first} by ${size.second} screen"
        fun all(css: String) = doc.querySelectorAll(css).let { n -> (0 until n.length).map { n[it]!!.unsafeCast<HTMLElement>() } }
        fun one(css: String, why: String) = all(css).singleOrNull() ?: error("$on $why")
        fun card(name: String) = all("[data-fetch-card]").singleOrNull { it.getAttribute("data-fetch-card") == name }
            ?: error("$on $name is not on the list")
        val shown get() = one(".peek-sheet .peek-name", "the carousel is not drawn").textContent
        val place get() = one(".peek-sheet .peek-place", "the carousel has no place").textContent?.trim()
        fun remove() = one("[data-peek-remove]", "the carousel's sheet has no Remove")
    }

    private suspend fun open(size: Pair<Int, Int>, at: String): Page {
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
        val p = Page(s.frame.contentDocument!!, held, s.window, size)
        p.card(at).querySelector(".fetch-name")!!.unsafeCast<HTMLElement>().click()
        settle(p.w)
        assertEquals(at, p.shown, "${p.on} tapping $at's row did not open the carousel on it")
        return p
    }

    private suspend fun removeKeepsYourPlace(size: Pair<Int, Int>) {
        val p = open(size, "Arcane Signet")

        val remove = p.remove()
        val r = remove.getBoundingClientRect()
        assertTrue(r.width >= 24.0 && r.height >= 24.0, "${p.on} the carousel's Remove is not drawn: ${r.width} by ${r.height}")
        assertTrue(r.bottom <= size.second + 0.5 && r.right <= size.first + 0.5, "${p.on} the carousel's Remove is off screen: $r")
        assertEquals("", remove.textContent?.trim(), "${p.on} Remove is a word, not an icon")
        val icon = remove.querySelector(".icon")?.unsafeCast<HTMLElement>() ?: error("${p.on} Remove has no icon")
        val mask = p.w.getComputedStyle(icon).getPropertyValue("mask-image").ifEmpty {
            p.w.getComputedStyle(icon).getPropertyValue("-webkit-mask-image")
        }
        assertTrue(mask.startsWith("url("), "${p.on} the Remove icon has no shape: '$mask'")
        assertEquals("Remove Arcane Signet from the list", remove.getAttribute("aria-label"))

        remove.click()
        settle(p.w)
        assertTrue(Overlay.CARD_PEEK in p.held.value.overlays, "${p.on} Remove shut the carousel")
        assertEquals("Sol Ring", p.shown, "${p.on} the carousel did not move on to the next card")
        assertEquals("2 of 2", p.place, "${p.on} the carousel's place is wrong")
        assertTrue(p.all("[data-fetch-card=\"Arcane Signet\"]").isEmpty(), "${p.on} Arcane Signet is still on the page")
        assertFalse("Arcane Signet" in p.held.value.fetch.list, "${p.on} the list text still names Arcane Signet")

        // The last card now: removing it steps back to the one before.
        p.remove().click()
        settle(p.w)
        assertEquals("Counterspell", p.shown, "${p.on} removing the last card did not step back")
        assertEquals("1 of 1", p.place)

        // The only card left: the carousel shuts, and the list is empty.
        p.remove().click()
        settle(p.w)
        assertFalse(Overlay.CARD_PEEK in p.held.value.overlays, "${p.on} the carousel is still open over an empty list")
        assertTrue(p.all(".peek-sheet").isEmpty(), "${p.on} the carousel is still drawn over an empty list")
        assertTrue(p.all("[data-fetch-card]").isEmpty(), "${p.on} the page still has cards")
    }

    @Test
    fun removeOnTheCarouselKeepsYourPlaceOnAPhone() = runTest { removeKeepsYourPlace(390 to 844) }

    @Test
    fun removeOnTheCarouselKeepsYourPlaceOnAFoldingPhoneOpenedOut() = runTest { removeKeepsYourPlace(Tablet.UNFOLDED) }

    @Test
    fun removeOnTheCarouselKeepsYourPlaceOnATabletHeldSideways() = runTest { removeKeepsYourPlace(Tablet.LANDSCAPE) }
}
