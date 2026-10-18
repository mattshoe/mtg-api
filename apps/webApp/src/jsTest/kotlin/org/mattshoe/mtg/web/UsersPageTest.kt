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
import org.mattshoe.mtg.core.Collector
import org.mattshoe.mtg.core.Route
import org.mattshoe.mtg.core.UsersState
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
 * The Users tab in a real browser, through the real menu and shell.
 *
 * Matt: "i should have a bottom nav option to browse users, then i can
 * select any user, and browse their cards ... the landing page should
 * be a collection of 'cards' or 'tiles' or whatever, with a profile Pic
 * on it". Sibling of `UsersScreenTest` on the phone.
 */
class UsersPageTest {

    private val roots = mutableListOf<HTMLElement>()
    private var screen: Tablet.Screen? = null
    private lateinit var held: androidx.compose.runtime.MutableState<AppState>
    private var refreshed = 0

    private val people = listOf(
        Collector("k1", "Kayla", "data:image/gif;base64,R0lGODlhAQABAAAAACw=", 1234, 5),
        Collector("k2", "Matt", null, 3, 1),
        Collector("k3", null, null, 0, 0),
    )

    private fun signedIn() = AppState(admin = Admin().signIn(Account(key = "k2", name = "Matt"), "t"))
    private fun onTiles() = signedIn().navigate(View.USERS).copy(users = UsersState().loaded(people))

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
            onRefresh = { refreshed++; held.value = held.value.refreshed() },
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
        renderComposable(root = frame, content = shell(start))
        return frame
    }

    private suspend fun settle(w: org.w3c.dom.Window = window) = repeat(4) {
        Promise<Unit> { r, _ -> w.requestAnimationFrame { r(Unit) } }.await()
    }

    private fun org.w3c.dom.ParentNode.all(css: String): List<HTMLElement> =
        querySelectorAll(css).let { n -> (0 until n.length).mapNotNull { n[it] as? HTMLElement } }

    private fun HTMLElement.tile(key: String) =
        all("[data-user='$key']").singleOrNull() ?: error("there is no tile for $key: ${all(".user-tile").map { it.textContent }}")

    @Test
    fun theMenuOffersUsers() = runTest {
        val root = mount(signedIn())
        settle()
        root.all("button.nav-burger").single().click()
        settle()
        val item = root.all(".app-menu button.app-tab").firstOrNull { it.textContent?.trim() == "Users" }
            ?: error("the menu offers no Users: ${root.all(".app-menu button.app-tab").map { it.textContent }}")
        item.click()
        settle()
        assertEquals(View.USERS, held.value.view, "the Users item went somewhere else")
    }

    @Test
    fun eachTileSaysWhoAndHowMuchWithTheirPicture() = runTest {
        val root = mount(onTiles())
        settle()
        val kayla = root.tile("k1")
        assertTrue(kayla.textContent.orEmpty().contains("Kayla"), "Kayla's tile does not name her: ${kayla.textContent}")
        assertTrue(kayla.textContent.orEmpty().contains("1,234 cards · 5 decks"), "Kayla's tile does not say how much: ${kayla.textContent}")
        val pic = kayla.all("img").singleOrNull() ?: error("Kayla's tile has no picture")
        val box = pic.getBoundingClientRect()
        assertTrue(box.width >= 40.0 && abs(box.width - box.height) < 1.0, "the picture is ${box.width} by ${box.height}, not a circle's square")
        assertEquals("50%", window.getComputedStyle(pic).borderTopLeftRadius, "the picture is not round")
        // No picture and no name: the key, and its first letter in the ring.
        val nameless = root.tile("k3")
        assertTrue(nameless.textContent.orEmpty().contains("k3"), "a nameless tile says nothing: ${nameless.textContent}")
        assertEquals("K", nameless.all(".user-initial").single().textContent?.trim())
    }

    @Test
    fun tappingATileOpensTheirLibrary() = runTest {
        val root = mount(onTiles())
        settle()
        root.tile("k1").click()
        settle()
        assertEquals(Route(View.USERS, "k1"), held.value.route, "the tile did not open Kayla's cards")
        assertEquals("k1", held.value.scopedLibrary().filters.owner, "the Library searches somebody else's cards")
        // `LibraryPage`'s own panel, which the tiles page does not draw.
        assertTrue(root.all(".panel .panel-body").isNotEmpty(), "Kayla's page is not a Library")
        assertTrue(root.all("[data-user]").isEmpty(), "the tiles are still on screen over Kayla's cards")
        assertTrue(root.textContent.orEmpty().contains("Kayla's cards"), "the page does not say whose cards these are")
        root.all("button.users-back").single().click()
        settle()
        assertEquals(Route(View.USERS), held.value.route, "back did not return to the tiles")
    }

    @Test
    fun aListThatFailedSaysWhyAndCanBeAskedAgain() = runTest {
        val root = mount(signedIn().navigate(View.USERS).fetching().fetchFailed("HTTP 500: the database is down"))
        settle()
        assertTrue(root.all(".users-busy").isEmpty(), "the spinner outlived the failure")
        assertTrue(
            root.textContent.orEmpty().contains("HTTP 500: the database is down"),
            "the failure does not say what happened: ${root.textContent}",
        )
        root.all("button.users-retry").singleOrNull()?.click() ?: error("there is no Try again")
        settle()
        assertEquals(1, refreshed, "Try again asked for nothing")
        assertTrue(held.value.users.busy, "Try again did not go back to loading")
    }

    @Test
    fun aListOnItsWaySaysSo() = runTest {
        val root = mount(signedIn().navigate(View.USERS).fetching())
        settle()
        assertTrue(root.all(".users-busy").isNotEmpty(), "the list is loading with nothing to say so")
    }

    private suspend fun tilesSitInAGrid(size: Pair<Int, Int>) {
        val s = Tablet.open(size).also { screen = it }
        renderComposable(root = s.body, content = shell(onTiles()))
        settle(s.window)
        val doc = s.frame.contentDocument!!
        val on = "on a ${size.first} by ${size.second} screen"
        val a = doc.all("[data-user='k1']").single().getBoundingClientRect()
        val b = doc.all("[data-user='k2']").single().getBoundingClientRect()
        assertTrue(
            abs(a.top - b.top) < 2.0 && b.left > a.right,
            "$on the tiles are one long column: Kayla's at ${a.left},${a.top}, Matt's at ${b.left},${b.top}",
        )
        assertTrue(doc.documentElement!!.scrollWidth <= size.first, "$on the page is ${doc.documentElement!!.scrollWidth}px wide")
    }

    @Test
    fun tilesSitSideBySideOnAFoldingPhoneOpenedOut() = runTest { tilesSitInAGrid(Tablet.UNFOLDED) }

    @Test
    fun tilesSitSideBySideOnATabletHeldSideways() = runTest { tilesSitInAGrid(Tablet.LANDSCAPE) }

    @Test
    fun theirLibraryFitsAFoldingPhoneOpenedOut() = runTest {
        val s = Tablet.open(Tablet.UNFOLDED).also { screen = it }
        renderComposable(root = s.body, content = shell(onTiles().browseUser("k1")))
        settle(s.window)
        val doc = s.frame.contentDocument!!
        assertTrue(doc.body!!.textContent.orEmpty().contains("Kayla's cards"), "the browsed Library does not say whose it is")
        assertTrue(doc.documentElement!!.scrollWidth <= Tablet.UNFOLDED.first, "Kayla's Library runs off the side")
    }
}
