package org.mattshoe.mtg.web

import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import org.jetbrains.compose.web.renderComposable
import org.mattshoe.mtg.core.AppState
import org.mattshoe.mtg.core.CardRow
import org.mattshoe.mtg.core.Completion
import org.mattshoe.mtg.core.View
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The suggestion list goes away when the carousel opens over it.
 *
 * Matt: "i always have to hit back twice to get out of the carousel".
 * On the phone the first back closed the Library's suggestion list,
 * still open under the carousel, and only the second closed the
 * carousel. The website's back goes to `dismissTop` rather than
 * `AppState.back`, so it never spent a press on the list — but the
 * list was left open under the carousel all the same, because the
 * rule that put it away lives in `:core` and was not there.
 *
 * The real `AppShell` in a frame with the real stylesheet, on a phone
 * and both tablet shapes. The tile is clicked with `click()`, which
 * sends no `pointerdown`, so the page's own outside-tap close does not
 * run and what is asserted is the shared rule alone.
 */
class CarouselOneBackTest {

    private var screen: Tablet.Screen? = null

    @AfterTest
    fun cleanUp() {
        screen?.frame?.remove()
        screen = null
    }

    private suspend fun settle(w: org.w3c.dom.Window) = repeat(4) {
        Promise<Unit> { r, _ -> w.requestAnimationFrame { r(Unit) } }.await()
    }

    private fun row(name: String) = CardRow(
        id = 1, owner = "matt", name = name, nameNorm = name.lowercase(), face2 = null,
        layout = null, scryfallId = "abcdef12-3456", manaCost = "{1}", cmc = 1.0,
        typeLine = "Artifact", colorIdentity = null, rarity = "uncommon", setCode = "m3c",
        setName = "Modern Horizons 3 Commander", collectorNumber = "409", edhrecRank = null,
        releasedAt = null, finish = "nonfoil", power = null, toughness = null, artist = null,
        qty = 1, printings = 1, free = 1, price = 1.75, value = 1.75,
    )

    private val rows = listOf(row("Sol Ring"), row("Solemn Simulacrum"))

    private fun suggestingInTheLibrary() = AppState()
        .navigate(View.LIBRARY)
        .let { it.copy(library = it.library.loaded(rows, rows.size)) }
        .typedCardName(Completion().typed("sol").suggested(listOf("Sol Ring", "Solemn Simulacrum")))

    private suspend fun theListGoes(size: Pair<Int, Int>) {
        val s = Tablet.open(size).also { screen = it }
        val held = mutableStateOf(suggestingInTheLibrary())
        renderComposable(root = s.body) {
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
        val doc = s.frame.contentDocument!!
        assertNotNull(doc.querySelector(".ac-list"), "the fixture never showed the suggestion list")

        val tile = assertNotNull(doc.querySelector(".grid .card"), "no card tile in the Library").unsafeCast<HTMLElement>()
        tile.click()
        settle(s.window)

        assertNotNull(doc.querySelector(".peek-sheet"), "tapping the tile did not open the carousel")
        assertTrue(held.value.complete.term == "sol", "opening the carousel emptied the name box")
        assertNull(
            doc.querySelector(".ac-list"),
            "on a ${size.first} by ${size.second} screen the suggestion list is still open under the carousel, " +
                "which is what the phone's first back was spent closing",
        )
    }

    @Test
    fun theListGoesWhenTheCarouselOpensOnAPhone() = runTest { theListGoes(390 to 844) }

    @Test
    fun theListGoesWhenTheCarouselOpensOnAFoldingPhoneOpenedOut() = runTest { theListGoes(Tablet.UNFOLDED) }

    @Test
    fun theListGoesWhenTheCarouselOpensOnATabletHeldSideways() = runTest { theListGoes(Tablet.LANDSCAPE) }
}
