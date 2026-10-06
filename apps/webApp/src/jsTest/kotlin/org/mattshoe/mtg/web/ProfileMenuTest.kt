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
        val root = mount(AppState(admin = Admin("0.abc").signIn(me, "t")))
        settle()
        root.press("Menu")
        settle()
        val inMenu = root.buttons(".app-menu")
        assertEquals(listOf("Library", "Decks", "Stats", "Entry"), inMenu)
        assertEquals(0, root.all(".app-menu .app-who").size, "the account is still in the hamburger")
    }

    @Test
    fun theProfileMenuIsWhoYouAreAndWhatFollowsFromIt() = runTest {
        val root = mount(AppState(admin = Admin("0.abc").signIn(me, "t")))
        settle()
        root.profile().click()
        settle()
        val menu = assertNotNull(
            root.all(".profile-menu.open").firstOrNull(),
            "the profile button opened nothing",
        )
        assertTrue(menu.textContent.orEmpty().contains("Matt Shoemaker"), menu.textContent.orEmpty())
        assertTrue(menu.textContent.orEmpty().contains("/c/matt"), "it does not say where the collection lives")
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
        assertTrue("Sign in with Google" in tabs, tabs.toString())
        assertFalse("Log out" in tabs, "it offered a way out of being nobody: $tabs")
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
    fun theProfileSitsAtTheRightHandEndOfTheHeader() = runTest {
        // Where a profile lives, and where the phone's is. It was on
        // the left once, which Matt had something to say about.
        val root = mount(AppState(admin = Admin().signIn(me, "t")))
        settle()
        if (!Stylesheet.applied()) return@runTest
        val burger = root.all("button.nav-burger").first().getBoundingClientRect()
        val profile = root.profile().getBoundingClientRect()
        assertTrue(
            profile.left > burger.right,
            "the profile is not past the hamburger (profile ${profile.left}, burger ${burger.right})",
        )
    }
}
