package org.mattshoe.mtg.web

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import org.jetbrains.compose.web.renderComposable
import org.mattshoe.mtg.core.Account
import org.mattshoe.mtg.core.Admin
import org.mattshoe.mtg.core.AppState
import org.mattshoe.mtg.core.Tools
import org.mattshoe.mtg.core.View
import org.w3c.dom.HTMLElement
import org.w3c.dom.get
import kotlin.js.Promise
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Tools tab in a real browser, through the real menu and shell.
 *
 * Matt: "let's collapse fetch and entry into a "Tools" tab. the tools
 * page should look very similar in presentation to the users tab, but
 * instead of profile avatars, use the current icons on the bottom nav
 * for them". Sibling of `ToolsScreenTest` on the phone.
 */
class ToolsPageTest {

    private val roots = mutableListOf<HTMLElement>()
    private var screen: Tablet.Screen? = null
    private lateinit var held: androidx.compose.runtime.MutableState<AppState>

    private fun signedIn() = AppState(admin = Admin().signIn(Account(key = "k2", name = "Matt"), "t"))

    @BeforeTest
    fun loadTheStylesheet() = Stylesheet.load()

    @AfterTest
    fun cleanUp() {
        roots.forEach { it.remove() }
        roots.clear()
        screen?.frame?.remove()
        screen = null
    }

    private fun shell(start: AppState): @androidx.compose.runtime.Composable () -> Unit = {
        held = remember { mutableStateOf(start) }
        AppNav(held.value, onState = { held.value = it })
        AppShell(
            state = held.value,
            onState = { held.value = it },
            onSearch = {}, onOpenDeck = {},
            onPreviewEntry = {}, onApplyEntry = {},
        )
    }

    /** A phone's width. */
    private fun mount(start: AppState, width: Int = 400): HTMLElement {
        val frame = document.createElement("div") as HTMLElement
        frame.style.width = "${width}px"
        frame.style.position = "absolute"
        frame.style.left = "0px"
        document.body!!.appendChild(frame)
        roots += frame
        renderComposable(root = frame) { shell(start)() }
        return frame
    }

    private suspend fun settle(w: org.w3c.dom.Window = window) = repeat(4) {
        Promise<Unit> { r, _ -> w.requestAnimationFrame { r(Unit) } }.await()
    }

    private fun org.w3c.dom.ParentNode.all(css: String): List<HTMLElement> =
        querySelectorAll(css).let { n -> (0 until n.length).mapNotNull { n[it] as? HTMLElement } }

    private fun HTMLElement.tile(slug: String) =
        all("[data-tool='$slug']").singleOrNull() ?: error("there is no tile for $slug: ${all(".tool-tile").map { it.textContent }}")

    private fun HTMLElement.menu() = all(".app-menu button.app-tab").map { it.textContent?.trim().orEmpty() }

    @Test
    fun theMenuOffersToolsAndNeitherFetchNorEntry() = runTest {
        val root = mount(signedIn())
        settle()
        root.all("button.nav-burger").single().click()
        settle()
        assertEquals(listOf("Library", "Decks", "Users", "Tools", "Guide"), root.menu(), "the menu's places")
        root.all(".app-menu button.app-tab").first { it.textContent?.trim() == "Tools" }.click()
        settle()
        assertEquals(View.TOOLS, held.value.view, "the Tools item went somewhere else")
        assertTrue(root.all(".tools").isNotEmpty(), "the Tools item has no page")
    }

    @Test
    fun eachToolIsATileWithItsIconNameAndWhatItIsFor() = runTest {
        val root = mount(signedIn().navigate(View.TOOLS))
        settle()
        listOf(View.FETCH, View.ENTRY).forEach { tool ->
            val tile = root.tile(tool.slug)
            val text = tile.textContent.orEmpty()
            assertTrue(tool.label in text, "${tool.label}'s tile does not name it: $text")
            assertTrue(Tools.blurb(tool) in text, "${tool.label}'s tile does not say what it is for: $text")
            val icon = tile.all(".icon-${tool.slug}").singleOrNull() ?: error("${tool.label}'s tile has no icon")
            val css = window.getComputedStyle(icon)
            val mask = css.getPropertyValue("mask-image").ifBlank { css.getPropertyValue("-webkit-mask-image") }
            assertTrue(mask.startsWith("url("), "${tool.label}'s icon is masked to nothing: \"$mask\"")
            val i = icon.getBoundingClientRect()
            val t = tile.getBoundingClientRect()
            assertTrue(i.width >= 20.0 && i.height >= 20.0, "${tool.label}'s icon is ${i.width} by ${i.height}")
            assertTrue(
                i.left >= t.left && i.right <= t.right && i.top >= t.top && i.bottom <= t.bottom,
                "${tool.label}'s icon is not on its tile",
            )
        }
    }

    @Test
    fun aStrangerIsOfferedFetchButNotEntry() = runTest {
        val root = mount(AppState(admin = Admin().settle()).navigate(View.TOOLS))
        settle()
        root.tile("fetch")
        assertTrue(
            root.all("[data-tool='entry']").isEmpty(),
            "a stranger is offered Entry, which writes to a collection they do not have",
        )
    }

    private suspend fun aTileOpens(tool: View) {
        val root = mount(signedIn().navigate(View.TOOLS))
        settle()
        root.tile(tool.slug).click()
        settle()
        assertEquals(tool, held.value.view, "the ${tool.label} tile opened something else")
        val lit = root.all(".app-menu button.app-tab.on").map { it.textContent?.trim() }
        assertEquals(listOf("Tools"), lit, "on ${tool.label} the menu marks the wrong place")
    }

    @Test
    fun theFetchTileOpensFetchAndToolsStaysLit() = runTest { aTileOpens(View.FETCH) }

    @Test
    fun theEntryTileOpensEntryAndToolsStaysLit() = runTest { aTileOpens(View.ENTRY) }

    private suspend fun tilesSitSideBySide(size: Pair<Int, Int>) {
        val s = Tablet.open(size).also { screen = it }
        renderComposable(root = s.body) { shell(signedIn().navigate(View.TOOLS))() }
        settle(s.window)
        val doc = s.frame.contentDocument!!
        val on = "on a ${size.first} by ${size.second} screen"
        val a = (doc.all("[data-tool='fetch']").singleOrNull() ?: error("$on Fetch has no tile")).getBoundingClientRect()
        val b = (doc.all("[data-tool='entry']").singleOrNull() ?: error("$on Entry has no tile")).getBoundingClientRect()
        assertTrue(
            abs(a.top - b.top) < 2.0 && b.left > a.right,
            "$on the tiles are one long column: Fetch's at ${a.left},${a.top}, Entry's at ${b.left},${b.top}",
        )
        assertTrue(doc.documentElement!!.scrollWidth <= size.first, "$on the page is ${doc.documentElement!!.scrollWidth}px wide")
    }

    /**
     * Matt: "the tools tab should always be on the far right of bottom
     * nav". The website's menu runs top to bottom, so the phone's far
     * right is the last place in it, measured. The phone's half is
     * `ToolsTabOnTheRightTest`.
     */
    private suspend fun toolsIsLast(start: AppState, size: Pair<Int, Int>) {
        val s = Tablet.open(size).also { screen = it }
        renderComposable(root = s.body) { shell(start)() }
        settle(s.window)
        val doc = s.frame.contentDocument!!
        val on = "on a ${size.first} by ${size.second} screen"
        doc.all("button.nav-burger").single().click()
        settle(s.window)
        val places = doc.all(".app-menu button.app-tab")
        val tools = places.singleOrNull { it.textContent?.trim() == "Tools" } ?: error("$on the menu has no Tools")
        val top = tools.getBoundingClientRect().top
        places.filter { it !== tools }.forEach {
            assertTrue(
                it.getBoundingClientRect().bottom <= top + 1.0,
                "$on ${it.textContent?.trim()} sits below Tools, so Tools is not the last place",
            )
        }
    }

    @Test
    fun toolsIsLastOnAPhoneSignedOut() = runTest { toolsIsLast(AppState(admin = Admin().settle()), 390 to 844) }

    @Test
    fun toolsIsLastOnAPhoneSignedIn() = runTest { toolsIsLast(signedIn(), 390 to 844) }

    @Test
    fun toolsIsLastOnAFoldingPhoneOpenedOut() = runTest { toolsIsLast(signedIn(), Tablet.UNFOLDED) }

    @Test
    fun toolsIsLastOnATabletHeldSideways() = runTest { toolsIsLast(signedIn(), Tablet.LANDSCAPE) }

    @Test
    fun tilesSitSideBySideOnAFoldingPhoneOpenedOut()= runTest { tilesSitSideBySide(Tablet.UNFOLDED) }

    @Test
    fun tilesSitSideBySideOnATabletHeldSideways() = runTest { tilesSitSideBySide(Tablet.LANDSCAPE) }
}
