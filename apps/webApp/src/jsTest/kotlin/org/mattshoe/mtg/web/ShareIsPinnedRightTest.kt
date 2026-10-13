package org.mattshoe.mtg.web

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import org.jetbrains.compose.web.renderComposable
import org.mattshoe.mtg.core.Account
import org.mattshoe.mtg.core.Admin
import org.mattshoe.mtg.core.AppState
import org.mattshoe.mtg.core.CardDetail
import org.mattshoe.mtg.core.CardRef
import org.mattshoe.mtg.core.Deck
import org.mattshoe.mtg.core.DeckCard
import org.mattshoe.mtg.core.DecksState
import org.mattshoe.mtg.core.Route
import org.mattshoe.mtg.core.View
import org.w3c.dom.HTMLElement
import org.w3c.dom.get
import kotlin.js.Promise
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Matt: "the share button in all pages at the top must always be
 * pinned to the right side, and on the deck details it should be
 * overlayed on the banner image".
 *
 * Unlocked, the open deck's header was the share and then "Edit list",
 * "Rename" and "Disassemble", which pushed the share inward. Through
 * the real `AppShell`, at a phone's width and a laptop's, measured
 * against the real stylesheet. Sibling of Android's
 * `ShareIsPinnedRightTest`.
 */
class ShareIsPinnedRightTest {

    private val roots = mutableListOf<HTMLElement>()

    @BeforeTest
    fun loadTheStylesheet() = Stylesheet.load()

    @AfterTest
    fun cleanUp() {
        roots.forEach { it.remove() }
        roots.clear()
    }

    private suspend fun settle() = repeat(4) {
        Promise<Unit> { resolve, _ -> window.requestAnimationFrame { resolve(Unit) } }.await()
    }

    private fun shell(start: AppState, width: Int): HTMLElement {
        val root = document.createElement("div") as HTMLElement
        root.style.width = "${width}px"
        document.body!!.appendChild(root)
        roots += root
        renderComposable(root = root) {
            var s by remember { mutableStateOf(start) }
            AppShell(s, { s = it }, {}, {}, {}, {})
        }
        return root
    }

    private fun HTMLElement.all(css: String): List<HTMLElement> =
        querySelectorAll(css).let { n -> (0 until n.length).mapNotNull { n[it] as? HTMLElement } }

    private fun HTMLElement.share(label: String): HTMLElement =
        all("button").firstOrNull { it.getAttribute("aria-label") == label }
            ?: error("there is no '$label' button on the page")

    private val admin = Admin().signIn(Account(key = "e7de0cb1", role = "admin"), "t")

    private val withCommander = Deck("alela", "Alela", "matt", "Alela, Artful Provocateur (ELD) 324", "UW", 3, null)

    /** A 60-card list: no commander, so no banner to sit on. */
    private val sixty = Deck("alela", "Sixty", "matt", null, "UW", null, null)

    private fun onThe(deck: Deck, unlocked: Boolean) = AppState(
        route = Route(View.DECKS, "alela"),
        admin = if (unlocked) admin else Admin(),
        decks = DecksState().loaded(listOf(deck)).opened(
            "alela",
            listOf(DeckCard("Sol Ring", 1, null, 1, nameNorm = "sol ring", typeLine = "Artifact")),
        ),
    )

    private val widths = listOf(390, 1000)

    // --------------------------------------------------- the deck banner

    @Test
    fun anOpenDecksShareSitsOnTheBannerInItsTopRightCorner() = runTest {
        listOf(false, true).forEach { unlocked ->
            widths.forEach { width ->
                val root = shell(onThe(withCommander, unlocked), width)
                settle()
                assertTrue(Stylesheet.applied(), "app.css never loaded, so nothing here can be measured")
                val at = "at ${width}px, ${if (unlocked) "unlocked" else "locked"}"
                val hero = (root.all("div.deck-hero").firstOrNull() ?: error("$at the deck has no banner"))
                    .getBoundingClientRect()
                val s = root.share("Share this deck").getBoundingClientRect()
                assertTrue(
                    s.top >= hero.top && s.bottom <= hero.bottom && s.left >= hero.left && s.right <= hero.right,
                    "$at the share is not on the banner: the share is ${s.left},${s.top} to ${s.right},${s.bottom}, " +
                        "the banner ${hero.left},${hero.top} to ${hero.right},${hero.bottom}",
                )
                assertTrue(
                    hero.right - s.right <= 16,
                    "$at the share is not pinned to the banner's right: ${hero.right - s.right}px short of it",
                )
                assertTrue(
                    s.top - hero.top <= 16,
                    "$at the share is not at the top of the banner: ${s.top - hero.top}px down it",
                )
                roots.forEach { it.remove() }
                roots.clear()
            }
        }
    }

    @Test
    fun theShareMenuOpensOverTheBannerAndStaysOnScreen() = runTest {
        // `.deck-hero` clips whatever is inside it, so a menu that
        // hangs off a button inside the banner would be cut off at its
        // bottom edge.
        widths.forEach { width ->
            val root = shell(onThe(withCommander, unlocked = true), width)
            settle()
            assertTrue(Stylesheet.applied(), "app.css never loaded, so nothing here can be measured")
            root.share("Share this deck").click()
            settle()
            val open = root.all("div.app-menu.open").firstOrNull() ?: error("at ${width}px the menu did not open")
            // Karma's window is narrower than 1000px, and
            // `elementFromPoint` answers null for a point outside it,
            // so the last option is brought into view before it is
            // probed. The window, not `scrollIntoView`, which would
            // scroll a clipping banner too and hide the very bug this
            // is for. Everything is measured after the scroll.
            open.all("button.app-tab").last().getBoundingClientRect().let { r ->
                window.scrollBy(maxOf(0.0, r.right - window.innerWidth + 8), maxOf(0.0, r.bottom - window.innerHeight + 8))
            }
            settle()
            val menu = open.getBoundingClientRect()
            val hero = root.all("div.deck-hero").first().getBoundingClientRect()
            val page = root.getBoundingClientRect()
            assertTrue(menu.height > 0, "at ${width}px the menu has no size, so this measures nothing")
            assertTrue(menu.right <= page.right + 1, "at ${width}px the menu runs off the right: ${menu.right} > ${page.right}")
            assertTrue(menu.left >= page.left - 1, "at ${width}px the menu runs off the left: ${menu.left} < ${page.left}")
            val last = open.all("button.app-tab").last().getBoundingClientRect()
            val top = document.elementFromPoint((last.left + last.right) / 2, (last.top + last.bottom) / 2)
            assertTrue(
                last.bottom <= hero.bottom || open.contains(top),
                "at ${width}px the menu's last option is hidden: the banner clips the menu at ${hero.bottom}, " +
                    "and what is on top of it is ${top?.tagName ?: "nothing on screen"}",
            )
            roots.forEach { it.remove() }
            roots.clear()
        }
    }

    // ------------------------------------------------ a deck with no banner

    @Test
    fun aDeckWithNoBannerKeepsItsShareAtTheRightOfTheTop() = runTest {
        listOf(false, true).forEach { unlocked ->
            widths.forEach { width ->
                val root = shell(onThe(sixty, unlocked), width)
                settle()
                assertTrue(Stylesheet.applied(), "app.css never loaded, so nothing here can be measured")
                val at = "at ${width}px, ${if (unlocked) "unlocked" else "locked"}"
                val head = (root.all("div.wrap div.page-head").firstOrNull() ?: error("$at there is no header"))
                    .getBoundingClientRect()
                val s = root.share("Share this deck").getBoundingClientRect()
                assertTrue(
                    head.right - s.right <= 1,
                    "$at the share is not pinned right: ${head.right - s.right}px short of the header's right edge",
                )
                assertTrue(s.top - head.top <= 1, "$at the share is not on the header's first line")
                roots.forEach { it.remove() }
                roots.clear()
            }
        }
    }

    // ---------------------------------------------------------- a card

    @Test
    fun aCardsShareIsPinnedToTheRight() = runTest {
        listOf("Sol Ring", "Asmoranomardicadaistinaculdacar, the Long-Named").forEach { name ->
            widths.forEach { width ->
                val root = shell(
                    onThe(withCommander, unlocked = false)
                        .openCard(CardRef(nameNorm = name.lowercase()), name)
                        .copy(card = CardDetail(name = name, nameNorm = name.lowercase())),
                    width,
                )
                settle()
                assertTrue(Stylesheet.applied(), "app.css never loaded, so nothing here can be measured")
                val head = (root.all("div.wrap div.page-head").firstOrNull() ?: error("the card page has no header"))
                    .getBoundingClientRect()
                val s = root.share("Share this card").getBoundingClientRect()
                assertTrue(
                    head.right - s.right <= 1,
                    "at ${width}px with '$name' the share is ${head.right - s.right}px short of the right edge",
                )
                roots.forEach { it.remove() }
                roots.clear()
            }
        }
    }
}
