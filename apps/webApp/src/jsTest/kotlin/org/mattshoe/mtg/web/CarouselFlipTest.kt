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
import org.mattshoe.mtg.core.Route
import org.mattshoe.mtg.core.View
import org.w3c.dom.HTMLElement
import org.w3c.dom.get
import kotlin.js.Promise
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Turning a two-faced card over in the carousel, the website's half.
 *
 * Matt: "the carousel should have an icon overlaid on double sided
 * cards that allows you to flip the card so you can see the opposite
 * side back and forth".
 *
 * The real `AppShell` in a frame with the real stylesheet, at a
 * phone's size and both tablet sizes, over a deck holding Delver of
 * Secrets, which transforms, and Sol Ring, which does not. The
 * picture asserted is the `src` the browser holds, and the icon's
 * place is the box the browser laid out. The phone's
 * `CarouselFlipTest` is the same claim.
 */
class CarouselFlipTest {

    private var screen: Tablet.Screen? = null

    @AfterTest
    fun cleanUp() {
        screen?.frame?.remove()
        screen = null
    }

    private suspend fun settle(w: org.w3c.dom.Window) = repeat(4) {
        Promise<Unit> { r, _ -> w.requestAnimationFrame { r(Unit) } }.await()
    }

    private fun card(name: String, id: String, layout: String) =
        DeckCard(name, 1, null, 1, name.lowercase(), typeLine = "Creature", scryfallId = id, layout = layout)

    private fun onADeck(name: String): AppState {
        val s = AppState(admin = Admin().signIn(Account("matt"), "t"))
            .navigate(Route(View.DECKS, "alela"))
            .let {
                it.copy(
                    decks = it.decks
                        .loaded(listOf(Deck("alela", "Alela", "matt", null, "UW", 2, null)))
                        .opened("alela", listOf(card("Delver of Secrets", "d1e2-delver", "transform"), card("Sol Ring", "50a1-sol", "normal"))),
                )
            }
        return s.peekAt(s.decks.pageOrder.indexOfFirst { it.name == name })
    }

    private suspend fun open(size: Pair<Int, Int>, name: String): org.w3c.dom.Document {
        val s = Tablet.open(size).also { screen = it }
        renderComposable(root = s.body) {
            val held = androidx.compose.runtime.remember { mutableStateOf(onADeck(name)) }
            AppShell(
                state = held.value,
                onState = { held.value = it },
                onSearch = {},
                onOpenDeck = {},
                onPreviewEntry = {},
                onApplyEntry = {},
                onTweak = { _, _ -> },
            )
        }
        settle(s.window)
        return s.frame.contentDocument!!
    }

    /** The picture of the card named, wherever it is in the rail. */
    private fun shown(doc: org.w3c.dom.Document, name: String): String? =
        doc.querySelector(".peek-card img[alt='$name']")?.getAttribute("src")

    private suspend fun flipsBackAndForth(size: Pair<Int, Int>) {
        val doc = open(size, "Delver of Secrets")
        val front = "https://cards.scryfall.io/normal/front/d/1/d1e2-delver.jpg"
        val back = "https://cards.scryfall.io/normal/back/d/1/d1e2-delver.jpg"
        assertEquals(front, shown(doc, "Delver of Secrets"), "the carousel did not open on Delver's front")

        val flip = assertNotNull(doc.querySelector("[aria-label='Flip card']"), "Delver has no flip icon")
            .unsafeCast<HTMLElement>()
        val icon = flip.getBoundingClientRect()
        val face = assertNotNull(doc.querySelector(".peek-card img[alt='Delver of Secrets']")).getBoundingClientRect()
        assertTrue(
            icon.width > 0 && icon.left >= face.left - 0.5 && icon.right <= face.right + 0.5 &&
                icon.top >= face.top - 0.5 && icon.bottom <= face.bottom + 0.5,
            "on a ${size.first} by ${size.second} screen the flip icon at " +
                "${icon.left},${icon.top} ${icon.width}x${icon.height} is not over the card at " +
                "${face.left},${face.top} ${face.width}x${face.height}",
        )

        flip.click()
        settle(screen!!.window)
        assertEquals(back, shown(doc, "Delver of Secrets"), "flipped, the carousel still shows the front")
        doc.querySelector("[aria-label='Flip card']")!!.unsafeCast<HTMLElement>().click()
        settle(screen!!.window)
        assertEquals(front, shown(doc, "Delver of Secrets"), "flipped twice, the carousel did not come back to the front")
    }

    @Test
    fun aTwoFacedCardTurnsOverAndBackOnAPhone() = runTest { flipsBackAndForth(390 to 800) }

    @Test
    fun aTwoFacedCardTurnsOverAndBackOnAFoldingPhoneOpenedOut() = runTest { flipsBackAndForth(Tablet.UNFOLDED) }

    @Test
    fun aTwoFacedCardTurnsOverAndBackOnATabletHeldSideways() = runTest { flipsBackAndForth(Tablet.LANDSCAPE) }

    @Test
    fun aOneFacedCardHasNoFlipIcon() = runTest {
        val doc = open(390 to 800, "Sol Ring")
        assertEquals("https://cards.scryfall.io/normal/front/5/0/50a1-sol.jpg", shown(doc, "Sol Ring"), "the carousel did not open on Sol Ring")
        // Delver is in the rail beside it, so a flip icon on a card that
        // is not on show would be found here too.
        assertNull(doc.querySelector("[aria-label='Flip card']"), "Sol Ring, or a card beside it, has a flip icon")
    }
}
