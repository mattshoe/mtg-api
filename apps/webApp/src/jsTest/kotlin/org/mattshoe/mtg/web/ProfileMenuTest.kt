package org.mattshoe.mtg.web

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.browser.document
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import org.jetbrains.compose.web.renderComposable
import org.mattshoe.mtg.core.Account
import org.mattshoe.mtg.core.Admin
import org.mattshoe.mtg.core.AppState
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLImageElement
import org.w3c.dom.get
import kotlin.js.Promise
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Who you are lives behind the profile, not in the hamburger.
 *
 * Matt: "Dude you still haven't done the fucking profile avatar on
 * web?!?! Why the fuck is all that shit still in the hamburger
 * menu!!!!!"
 *
 * The phone has had this since the account work landed: a bottom bar
 * for the places you move between, and an avatar in the top right for
 * who you are and what follows from being them — `Admin.bar` against
 * `Admin.behindProfile`, which the core has modelled all along. The
 * website read `visible` and poured the lot into one menu, so the
 * account, the sign-out and the server log sat under the same
 * hamburger as Library and Decks.
 *
 * Matt: "Leverage the profile icon for the account information and
 * log in log out" and "I also want you to use the user's Google
 * profile image as the profile icon. If they don't have one then the
 * existing image is fine."
 */
class ProfileMenuTest {

    private val roots = mutableListOf<HTMLElement>()

    @AfterTest
    fun cleanUp() {
        roots.forEach { it.remove() }
        roots.clear()
    }

    private suspend fun settle() = repeat(3) {
        Promise<Unit> { r, _ -> kotlinx.browser.window.requestAnimationFrame { r(Unit) } }.await()
    }

    private val me = Account(
        slug = "matt",
        name = "Matt Shoemaker",
        avatar = "https://lh3.googleusercontent.com/a/portrait",
        key = "e7de0cb1",
    )

    private fun mount(initial: AppState): HTMLElement {
        val root = document.createElement("div") as HTMLElement
        document.body!!.appendChild(root)
        roots += root
        renderComposable(root = root) {
            val s = remember { mutableStateOf(initial) }
            AppNav(s.value, onState = { s.value = it })
        }
        return root
    }

    /**
     * Mounted in the bar it really lives in, at a known width.
     *
     * The slot is `<div id="nav" class="topbar-nav">` inside
     * `<header class="topbar">` — `index.html` is static only because
     * the header is — so anything about where a control sits in the
     * bar has to be measured with the bar around it.
     */
    private fun inTheBar(initial: AppState, width: Int = 420): Pair<HTMLElement, HTMLElement> {
        val bar = document.createElement("header") as HTMLElement
        bar.className = "topbar"
        bar.style.width = "${width}px"
        bar.style.position = "absolute"
        bar.style.left = "0px"
        val slot = document.createElement("div") as HTMLElement
        slot.id = "nav"
        slot.className = "topbar-nav"
        bar.appendChild(slot)
        document.body!!.appendChild(bar)
        roots += bar
        renderComposable(root = slot) {
            val s = remember { mutableStateOf(initial) }
            AppNav(s.value, onState = { s.value = it })
        }
        return bar to slot
    }

    private fun HTMLElement.all(css: String): List<HTMLElement> =
        querySelectorAll(css).let { n -> (0 until n.length).mapNotNull { n[it] as? HTMLElement } }

    private fun HTMLElement.buttons(css: String): List<String> =
        all("$css button").mapNotNull { it.textContent?.trim() }

    /**
     * By label or by text. The hamburger carries three bars and no
     * words, so "Menu" is its `aria-label` and nothing else.
     */
    private fun HTMLElement.press(label: String) {
        (all("button").firstOrNull {
            it.textContent?.trim() == label || it.getAttribute("aria-label") == label
        }
            ?: throw AssertionError("no button '$label'; saw ${all("button").map { it.textContent?.trim() }}"))
            .let { (it as HTMLButtonElement).click() }
    }

    private fun HTMLElement.profile(): HTMLButtonElement =
        assertNotNull(
            all("button.nav-profile").firstOrNull() as? HTMLButtonElement,
            "there is no profile button in the header",
        )

    @Test
    fun thereIsAProfileButtonInTheHeader() = runTest {
        val root = mount(AppState(admin = Admin().signIn(me, "t")))
        settle()
        val p = root.profile()
        assertEquals("Profile", p.getAttribute("aria-label"))
        assertEquals("false", p.getAttribute("aria-expanded"), "the menu starts open")
    }

    @Test
    fun itWearsTheGooglePictureWhenThereIsOne() = runTest {
        val root = mount(AppState(admin = Admin().signIn(me, "t")))
        settle()
        val img = assertNotNull(
            root.profile().querySelector("img") as? HTMLImageElement,
            "the profile button is not the account's own picture",
        )
        assertEquals(me.avatar, img.src)
        // Decoration beside a labelled button, so it is not announced twice.
        assertEquals("", img.alt)
    }

    @Test
    fun andTheGenericIconWhenThereIsNot() = runTest {
        val root = mount(AppState(admin = Admin().signIn(me.copy(avatar = null), "t")))
        settle()
        val p = root.profile()
        assertEquals(0, p.querySelectorAll("img").length, "an image with nothing to show")
        // A masked span, not an inline `<svg>`: Compose HTML builds
        // elements in the HTML namespace and an `<svg>` there renders
        // as nothing at all. `Icons.kt` says so, having been caught by
        // it, and this is the mechanism every other icon here uses.
        assertNotNull(
            p.querySelector("span.icon-profile"),
            "nothing is drawn where the avatar goes",
        )
    }

    @Test
    fun signedOutItIsStillThereBecauseItIsTheWayIn() = runTest {
        val root = mount(AppState())
        settle()
        root.profile()
    }

    @Test
    fun theHamburgerIsPlacesToGoAndNothingElse() = runTest {
        // The whole complaint. Not the account, not the sign-out, not
        // the server log, not the operator's password.
        val root = mount(AppState(admin = Admin().signIn(me, "t")))
        settle()
        root.press("Menu")
        settle()
        val inMenu = root.buttons(".app-menu")
        assertEquals(listOf("Library", "Decks", "Stats", "Entry"), inMenu)
        assertEquals(0, root.all(".app-menu .app-who").size, "the account is still in the hamburger")
    }

    @Test
    fun theProfileMenuIsWhoYouAreAndWhatFollowsFromIt() = runTest {
        // An operator, because the log is the one thing behind the
        // profile and a role is what reaches it.
        val root = mount(AppState(admin = Admin().signIn(me.copy(role = "admin"), "t")))
        settle()
        root.profile().click()
        settle()
        val menu = assertNotNull(
            root.all(".profile-menu.open").firstOrNull(),
            "the profile button opened nothing",
        )
        assertTrue(menu.textContent.orEmpty().contains("Matt Shoemaker"), menu.textContent.orEmpty())
        // `/c/<key>`, which is the address the collection actually
        // has. This asserted `/c/matt` — the slug — which is not a
        // page anybody can open, so the test was pinning the bug.
        assertTrue(
            menu.textContent.orEmpty().contains("/c/${me.key}"),
            "it does not say where the collection lives: ${menu.textContent}",
        )
        val tabs = root.buttons(".profile-menu")
        assertTrue("Server Logs" in tabs, "the log is not behind the profile: $tabs")
        assertTrue("Log out" in tabs, "no way out: $tabs")
        assertFalse("Library" in tabs, "a place to go is behind the profile: $tabs")
    }

    @Test
    fun signedOutTheProfileMenuOffersTheWayIn() = runTest {
        val root = mount(AppState())
        settle()
        root.profile().click()
        settle()
        val tabs = root.buttons(".profile-menu")
        assertEquals(listOf("Sign in with Google"), tabs, "there is more than one way in")
        assertTrue(
            root.all(".profile-menu").first().textContent.orEmpty().contains("Not signed in"),
            "it does not say that nobody is signed in",
        )
    }

    @Test
    fun theTwoMenusAreNeverOpenAtOnce() = runTest {
        // One is where you are going and the other is who you are.
        // Two panels over each other is neither.
        val root = mount(AppState(admin = Admin().signIn(me, "t")))
        settle()
        root.press("Menu")
        settle()
        root.profile().click()
        settle()
        assertEquals(0, root.all(".app-menu.open").size, "the hamburger stayed open under the profile")
        assertEquals(1, root.all(".profile-menu.open").size)

        root.press("Menu")
        settle()
        assertEquals(0, root.all(".profile-menu.open").size, "the profile stayed open under the hamburger")
        assertEquals(1, root.all(".app-menu.open").size)
    }

    @Test
    fun theProfileSitsAtTheRightHandEndOfTheBarTheWayThePhonesDoes() = runTest {
        // Matt: "And put the fucking profile menu in the same fucking
        // place on web as android!!"
        //
        // The phone draws it as the last thing in the top bar, past a
        // title that takes `weight(1f)` — so it is at the right-hand
        // edge, not merely to the right of the hamburger. The first
        // cut of this sat it next to the burger at the left-hand end,
        // because that is where the nav slot starts, and a test that
        // only asked "is it past the burger" was happy with that.
        val (bar, root) = inTheBar(AppState(admin = Admin().signIn(me, "t")))
        settle()
        if (!Stylesheet.applied()) return@runTest
        val edge = bar.getBoundingClientRect()
        val burger = root.all("button.nav-burger").first().getBoundingClientRect()
        val profile = root.profile().getBoundingClientRect()
        assertTrue(
            profile.left > burger.right,
            "the profile is not past the hamburger (profile ${profile.left}, burger ${burger.right})",
        )
        // Within the bar's own padding of the right edge. 14px each
        // side, so anything inside 20 is "at the end".
        assertTrue(
            edge.right - profile.right < 20,
            "the profile is ${edge.right - profile.right}px short of the bar's right edge",
        )
    }

    @Test
    fun andItsMenuHangsOffThatEndRatherThanRunningOffTheSide() = runTest {
        val (bar, root) = inTheBar(AppState(admin = Admin().signIn(me, "t")))
        settle()
        root.profile().click()
        settle()
        if (!Stylesheet.applied()) return@runTest
        val edge = bar.getBoundingClientRect()
        val menu = root.all(".profile-menu.open").first().getBoundingClientRect()
        assertTrue(menu.right <= edge.right + 1, "the profile menu runs off the right of a ${edge.width}px bar")
        assertTrue(menu.left >= edge.left - 1, "the profile menu runs off the left of a ${edge.width}px bar")
    }

    @Test
    fun theAndroidAppIsInTheHamburgerWithTheOtherPlacesToGo() = runTest {
        // Matt: "WHY THE FUCK IS THE ANDROID APP LINK IN THE FUCKING
        // HEADER STILL!!!! [...] MOVE THE GOD DAMN LINK INTO THE
        // HAMBURGER MENU!"
        //
        // It was a loose `<a>` in `index.html`'s header, which no
        // Kotlin test mounts — see `test/static-header.test.js` for
        // the half of this that holds the static page to carrying no
        // controls of its own. It is a place you can go, so it goes
        // with the places you can go.
        val root = mount(AppState())
        settle()
        val away = assertNotNull(
            root.all(".app-menu a").firstOrNull(),
            "the Android app is not in the hamburger; it has ${root.all(".app-menu a").size} links",
        )
        assertEquals("Android app", away.textContent?.trim())
        assertTrue(away.getAttribute("href")!!.endsWith("app/"), away.getAttribute("href")!!)
        // It leaves the app, so it never lights up as the view you
        // are on — that mark belongs to the three that are views.
        assertFalse(away.className.contains("on"), "an outbound link marked as the current view")
    }
}
