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
import org.mattshoe.mtg.core.PeekOf
import org.mattshoe.mtg.core.Route
import org.mattshoe.mtg.core.View
import org.w3c.dom.Document
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLImageElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.get
import kotlin.js.Promise
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Matt: "on the fetch list, i want to be able to swipe through the
 * missing cards just like the ones in my collection. however, it is a
 * different carousel of its own. i shouldn't be able to swipe into the
 * missing ones while swiping through the collection ones and vice
 * versa", and "i also want to be able to check off the missing ones
 * just like the collection ones".
 *
 * The real `AppShell` on the list page, on a phone and on both tablet
 * shapes. The phone's `FetchListMissingCarouselTest` is the same claims.
 */
class FetchListMissingCarouselTest {

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

    private val list = "1 Counterspell\n1 Sol Ring\n1 Black Lotus\n1 Mox Pearl"

    private class Page(val doc: Document, val held: MutableState<AppState>, val w: org.w3c.dom.Window, val size: Pair<Int, Int>) {
        val on = "on a ${size.first} by ${size.second} screen"
        fun all(css: String) = doc.querySelectorAll(css).let { n -> (0 until n.length).map { n[it]!!.unsafeCast<HTMLElement>() } }
        fun one(css: String, why: String) = all(css).singleOrNull() ?: error("$on $why")
        fun missing(name: String) = one("[data-fetch-missing=\"$name\"]", "$name is not listed as missing")
        fun box(name: String) =
            one("[data-fetch-missing-tick=\"$name\"]", "the missing $name has no box").unsafeCast<HTMLInputElement>()
        val shown get() = one(".peek-sheet .peek-name", "the carousel is not drawn").textContent
    }

    private suspend fun open(size: Pair<Int, Int>): Page {
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
        return Page(s.frame.contentDocument!!, held, s.window, size)
    }

    private suspend fun tappingAMissingCardOpensItsOwnCarousel(size: Pair<Int, Int>) {
        val p = open(size)
        p.missing("Black Lotus").querySelector(".fetch-name")!!.unsafeCast<HTMLElement>().click()
        settle(p.w)
        assertTrue(Overlay.CARD_PEEK in p.held.value.overlays, "${p.on} tapping Black Lotus opened no carousel")
        assertEquals(PeekOf.FETCH_MISSING, p.held.value.peek.of, "${p.on} the carousel is not over the missing cards")
        assertFalse(p.held.value.fetch.isMissingTicked("Black Lotus"), "${p.on} tapping the row checked it off as well")
        assertEquals("Black Lotus", p.shown)
        assertEquals("1 of 2", p.one(".peek-place", "the carousel has no place").textContent?.trim(), p.on)

        val art = p.all(".peek-face img").map { it.unsafeCast<HTMLImageElement>().src }
        assertTrue(art.any { "named?exact=Black%20Lotus" in it }, "${p.on} Black Lotus is drawn with no picture: $art")

        p.one("[aria-label=\"Next card\"]", "no next arrow").click()
        settle(p.w)
        assertEquals("Mox Pearl", p.shown, "${p.on} the next card is not the next missing one")
        p.all("[aria-label=\"Next card\"]").forEach { it.click() }
        settle(p.w)
        assertEquals("Mox Pearl", p.shown, "${p.on} swiping on from the last missing card reached a collection card")

        val box = p.one("[data-peek-tick]", "the missing carousel has no box").unsafeCast<HTMLInputElement>()
        val r = box.getBoundingClientRect()
        assertTrue(r.width >= 16.0 && r.bottom <= size.second + 0.5, "${p.on} the carousel's box is not on screen: $r")
        box.click()
        settle(p.w)
        assertTrue(p.held.value.fetch.isMissingTicked("Mox Pearl"), "${p.on} the carousel's box did not check off Mox Pearl")
        assertEquals("1 of 2 checked off", p.one("[data-peek-progress]", "the carousel has no count").textContent?.trim(), p.on)
        assertTrue(p.box("Mox Pearl").checked, "${p.on} the page's box did not follow the carousel")
        assertEquals("0 of 2 found", p.one("[data-fetch-progress]", "no count").textContent?.trim(), "${p.on} checking a missing card counted it found")
    }

    private suspend fun theCollectionCarouselNeverReachesAMissingCard(size: Pair<Int, Int>) {
        val p = open(size)
        p.one("[data-fetch-card=\"Sol Ring\"] .fetch-name", "Sol Ring is not on the list").click()
        settle(p.w)
        assertEquals(PeekOf.FETCH, p.held.value.peek.of)
        assertEquals("2 of 2", p.one(".peek-place", "the carousel has no place").textContent?.trim(), "${p.on} the collection carousel counts the missing cards")
        p.all("[aria-label=\"Next card\"]").forEach { it.click() }
        settle(p.w)
        assertEquals("Sol Ring", p.shown, "${p.on} swiping on from the last collection card reached a missing one")
    }

    private suspend fun theMissingBoxChecksOffWithoutOpeningTheCarousel(size: Pair<Int, Int>) {
        val p = open(size)
        p.box("Black Lotus").click()
        settle(p.w)
        assertTrue(p.held.value.fetch.isMissingTicked("Black Lotus"), "${p.on} the missing card's box does not check it off")
        assertFalse(Overlay.CARD_PEEK in p.held.value.overlays, "${p.on} checking the box opened the carousel")
        val name = p.missing("Black Lotus").querySelector(".fetch-name")!!.unsafeCast<HTMLElement>()
        val line = p.w.getComputedStyle(name).getPropertyValue("text-decoration-line")
        assertTrue("line-through" in line, "${p.on} a checked-off missing card is not struck through: $line")
    }

    @Test fun aMissingCardOpensItsOwnCarouselOnAPhone() = runTest { tappingAMissingCardOpensItsOwnCarousel(PHONE) }
    @Test fun aMissingCardOpensItsOwnCarouselOnAFoldingPhoneOpenedOut() = runTest { tappingAMissingCardOpensItsOwnCarousel(Tablet.UNFOLDED) }
    @Test fun aMissingCardOpensItsOwnCarouselOnATabletHeldSideways() = runTest { tappingAMissingCardOpensItsOwnCarousel(Tablet.LANDSCAPE) }

    @Test fun theCollectionCarouselNeverReachesAMissingCardOnAPhone() = runTest { theCollectionCarouselNeverReachesAMissingCard(PHONE) }
    @Test fun theCollectionCarouselNeverReachesAMissingCardOnATablet() = runTest { theCollectionCarouselNeverReachesAMissingCard(Tablet.UNFOLDED) }

    @Test fun theMissingBoxChecksOffOnAPhone() = runTest { theMissingBoxChecksOffWithoutOpeningTheCarousel(PHONE) }
    @Test fun theMissingBoxChecksOffOnAFoldingPhoneOpenedOut() = runTest { theMissingBoxChecksOffWithoutOpeningTheCarousel(Tablet.UNFOLDED) }
    @Test fun theMissingBoxChecksOffOnATabletHeldSideways() = runTest { theMissingBoxChecksOffWithoutOpeningTheCarousel(Tablet.LANDSCAPE) }

    private companion object {
        val PHONE = 390 to 844
    }
}
