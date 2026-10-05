package org.mattshoe.mtg.web

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.browser.document
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import org.jetbrains.compose.web.renderComposable
import org.mattshoe.mtg.core.Admin
import org.mattshoe.mtg.core.AppState
import org.mattshoe.mtg.core.Route
import org.mattshoe.mtg.core.View
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.get
import kotlin.js.Promise
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Query page is gone from the website.
 *
 * Matt: "Drop the Query page, none of the apps need that." `:core`
 * dropped `View.CONSOLE`, which is what makes the tab disappear — this
 * is the web half: nothing draws a SQL box any more, the address that
 * used to reach it lands somewhere useful, and taking a page out of the
 * middle of the menu did not quietly change who can reach the rest of
 * it or leave a rule with nothing on one side of it.
 *
 * Mounted as the real app mounts: `AppNav` and `AppShell` over one
 * state, because the menu lives in the header's own slot and a shell
 * mounted alone has nothing to navigate with.
 */
class QueryPageGoneTest {

    private val roots = mutableListOf<HTMLElement>()

    @AfterTest
    fun cleanUp() {
        roots.forEach { it.remove() }
        roots.clear()
    }

    private fun mount(initial: AppState): HTMLElement {
        val root = document.createElement("div") as HTMLElement
        document.body!!.appendChild(root)
        roots += root
        renderComposable(root = root) {
            val s = remember { mutableStateOf(initial) }
            AppNav(s.value) { s.value = it }
            AppShell(
                state = s.value,
                onState = { s.value = it },
                onUnlock = {},
                onSearch = {},
                onOpenDeck = {},
                onPreviewEntry = {},
                onApplyEntry = {},
            )
        }
        return root
    }

    private suspend fun settle() = repeat(3) {
        Promise<Unit> { r, _ -> kotlinx.browser.window.requestAnimationFrame { r(Unit) } }.await()
    }

    private fun HTMLElement.all(selector: String): List<HTMLElement> {
        val f = querySelectorAll(selector)
        return (0 until f.length).mapNotNull { f[it] as? HTMLElement }
    }

    /** Everything the hamburger offers, in the order it offers it. */
    private fun HTMLElement.menuLabels() =
        all(".app-menu button").map { it.textContent.orEmpty().trim() }

    /** Every button on the whole page, menu and screen alike. */
    private fun HTMLElement.everyLabel() =
        all("button").mapNotNull { (it as? HTMLButtonElement)?.textContent?.trim() }

    private val locked = AppState()
    private val unlocked = AppState(admin = Admin("0.abc"))

    // --------------------------------------------- nothing offers it

    @Test
    fun noScreenAndNoMenuStillOffersAQueryPage() = runTest {
        listOf(locked, unlocked).forEach { start ->
            View.entries.filter { start.admin.reachable(it) }.forEach { view ->
                val root = mount(start.navigate(view))
                settle()
                val labels = root.everyLabel()
                assertFalse(
                    labels.contains("Query"),
                    "the Query page is still offered on ${view.name}: $labels",
                )
                assertFalse(
                    labels.contains("Run"),
                    "a SQL box is still drawn on ${view.name}: $labels",
                )
                assertEquals(
                    0,
                    root.all("textarea.mono").size,
                    "a SQL box is still drawn on ${view.name}",
                )
            }
        }
    }

    /**
     * `#/console` is in somebody's history and in the old site's menu.
     * It has to draw a page, not an empty `<main>`.
     */
    @Test
    fun anOldQueryBookmarkDrawsTheLibraryRatherThanNothing() = runTest {
        val root = mount(AppState().navigate(Route.parse("#/console")))
        settle()
        assertEquals(
            "Library",
            root.all(".topbar-title").first().textContent?.trim(),
            "#/console did not land on the Library",
        )
        // The shell draws every screen inside `.wrap`; an unhandled
        // route would leave the main slot empty under a correct title.
        assertTrue(root.all("div.wrap").isNotEmpty(), "#/console drew no page at all")
        val here = root.all(".app-menu button").filter { it.className.contains("on") }
        assertEquals(
            listOf("Library"),
            here.map { it.textContent.orEmpty().trim() },
            "#/console did not light the Library up as the current tab",
        )
    }

    // -------------------------------------------- and nothing else moved

    /**
     * The Query page was the only ungated screen with an admin smell
     * to it. Taking it out must not have promoted or demoted anything.
     */
    @Test
    fun theLockStillHidesExactlyWhatItHidBefore() = runTest {
        val shut = mount(locked)
        settle()
        assertEquals(
            listOf("Library", "Decks", "Stats", "Unlock"),
            shut.menuLabels(),
            "the locked menu changed shape",
        )

        val open = mount(unlocked)
        settle()
        assertEquals(
            listOf("Library", "Decks", "Stats", "Entry", "Server Logs", "Lock"),
            open.menuLabels(),
            "the unlocked menu changed shape",
        )
    }

    /**
     * A rule with nothing under it, or an "Admin" heading with no
     * items beneath it, is what you get when a page is deleted out of
     * a menu that was drawn in halves.
     */
    @Test
    fun theMenuHasNoEmptyHalfAndNoStrayRule() = runTest {
        listOf("locked" to locked, "unlocked" to unlocked).forEach { (what, start) ->
            val root = mount(start)
            settle()
            val items = root.all(".app-menu > *")
            val rule = items.indexOfFirst { it.className.contains("app-menu-sep") }
            assertEquals(
                1,
                items.count { it.className.contains("app-menu-sep") },
                "$what: the menu has more than one rule in it",
            )
            assertTrue(
                items.take(rule).any { it.tagName.lowercase() == "button" },
                "$what: the rule has nothing above it",
            )
            assertTrue(
                items.drop(rule).any { it.tagName.lowercase() == "button" },
                "$what: the rule has nothing below it",
            )
            items.forEachIndexed { i, el ->
                if (!el.className.contains("app-menu-group")) return@forEachIndexed
                assertTrue(
                    items.drop(i + 1).any { it.tagName.lowercase() == "button" },
                    "$what: \"${el.textContent?.trim()}\" is a heading over nothing",
                )
            }
        }
    }
}
