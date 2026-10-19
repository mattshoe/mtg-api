package org.mattshoe.mtg.web

import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import org.jetbrains.compose.web.renderComposable
import org.mattshoe.mtg.core.Account
import org.mattshoe.mtg.core.Admin
import org.mattshoe.mtg.core.AppState
import org.mattshoe.mtg.core.People
import org.mattshoe.mtg.core.Person
import org.mattshoe.mtg.core.Role
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
 * Delete account, on a person's page in Admin Settings, the website's half.
 *
 * Matt: "i want to be able to delete accounts via admin settings, and all
 * associated database info." The page draws a bin, asks before anything
 * goes, hands the one person to whoever calls the Worker, never offers it
 * on your own page, and after a refusal stops the spinner and lets it be
 * tried again. Through the real `AppShell`, on a phone, a folding phone
 * opened out and a tablet held sideways. The phone's
 * `DeleteAccountScreenTest` is the same claims.
 */
class DeleteAccountPageTest {

    private var screen: Tablet.Screen? = null

    @AfterTest
    fun cleanUp() {
        screen?.frame?.remove()
        screen = null
    }

    private suspend fun settle(w: org.w3c.dom.Window) = repeat(4) {
        Promise<Unit> { r, _ -> w.requestAnimationFrame { r(Unit) } }.await()
    }

    private val kayla = Person(key = "k4yy0003", name = "Kayla M")
    private val matt = Person(key = "m4tt0001", name = "Matt", role = Role.ADMIN)
    private val admin = Admin().signIn(Account(key = "m4tt0001", name = "Matt", role = Role.ADMIN), "t")

    private fun onPage(key: String) = AppState(
        route = Route(View.ADMIN, key),
        admin = admin,
        people = People().loaded(listOf(matt, kayla)),
    )

    private val PHONE = 390 to 844

    private class Mounted(
        val held: androidx.compose.runtime.MutableState<AppState>,
        val asked: MutableList<Person>,
        val all: (String) -> List<HTMLElement>,
        val text: () -> String,
        val window: org.w3c.dom.Window,
    )

    private suspend fun mount(size: Pair<Int, Int>, start: AppState): Mounted {
        val s = Tablet.open(size).also { screen = it }
        val held = mutableStateOf(start)
        val asked = mutableListOf<Person>()
        renderComposable(root = s.body) {
            AppShell(
                state = held.value,
                onState = { held.value = it },
                onSearch = {},
                onOpenDeck = {},
                onPreviewEntry = {},
                onApplyEntry = {},
                onDeleteAccount = { asked += it },
            )
        }
        settle(s.window)
        val doc = s.frame.contentDocument!!
        val all = { css: String -> doc.querySelectorAll(css).let { n -> (0 until n.length).map { n[it]!!.unsafeCast<HTMLElement>() } } }
        return Mounted(held, asked, all, { doc.body!!.textContent.orEmpty() }, s.window)
    }

    private fun Mounted.bin(): HTMLElement? = all("button").firstOrNull { it.getAttribute("aria-label") == People.DELETE }
    private fun Mounted.button(word: String): HTMLElement? = all("button").firstOrNull { it.textContent?.trim() == word }

    private suspend fun theBinIsDrawnAndOnScreen(size: Pair<Int, Int>) {
        val m = mount(size, onPage(kayla.key))
        val on = "on a ${size.first} by ${size.second} screen"
        val bin = m.bin() ?: error("$on Kayla's page has no Delete account button")
        assertEquals("", bin.textContent?.trim(), "$on Delete account is written, not drawn")
        assertTrue(bin.querySelector(".icon-bin") != null, "$on the Delete account button is not a bin")
        val r = bin.getBoundingClientRect()
        assertTrue(r.width >= 24.0 && r.height >= 24.0, "$on the bin is ${r.width} by ${r.height}, too small to tap")
        assertTrue(r.left >= 0.0 && r.right <= size.first.toDouble(), "$on the bin runs off the side: ${r.left}..${r.right}")
        assertTrue(r.top >= 0.0, "$on the bin is above the top of the page")
    }

    @Test fun theBinIsDrawnAndOnScreenOnAPhone() = runTest { theBinIsDrawnAndOnScreen(PHONE) }
    @Test fun theBinIsDrawnAndOnScreenOnAFoldingPhoneOpenedOut() = runTest { theBinIsDrawnAndOnScreen(Tablet.UNFOLDED) }
    @Test fun theBinIsDrawnAndOnScreenOnATabletHeldSideways() = runTest { theBinIsDrawnAndOnScreen(Tablet.LANDSCAPE) }

    private suspend fun itAsksFirstAndYesHandsOverThatPerson(size: Pair<Int, Int>) {
        val m = mount(size, onPage(kayla.key))
        val on = "on a ${size.first} by ${size.second} screen"
        m.bin()!!.click()
        settle(m.window)
        assertTrue(People.askDelete(kayla) in m.text(), "$on tapping the bin did not ask whether to delete Kayla's account")
        assertTrue(m.asked.isEmpty(), "$on tapping the bin deleted without asking")

        val yes = m.button(People.CONFIRM) ?: error("$on the question has no ${People.CONFIRM} button")
        val r = yes.getBoundingClientRect()
        assertTrue(r.right <= size.first.toDouble(), "$on ${People.CONFIRM} runs off the side")
        yes.click()
        settle(m.window)
        assertEquals(listOf(kayla), m.asked, "$on ${People.CONFIRM} did not hand over Kayla, and only Kayla")
        assertEquals(kayla.key, m.held.value.people.deleting, "$on the page is not marked busy while the delete is out")
        assertTrue(m.bin()?.hasAttribute("disabled") == true, "$on the bin is still live while the delete is out")
    }

    @Test fun itAsksFirstOnAPhone() = runTest { itAsksFirstAndYesHandsOverThatPerson(PHONE) }
    @Test fun itAsksFirstOnAFoldingPhoneOpenedOut() = runTest { itAsksFirstAndYesHandsOverThatPerson(Tablet.UNFOLDED) }
    @Test fun itAsksFirstOnATabletHeldSideways() = runTest { itAsksFirstAndYesHandsOverThatPerson(Tablet.LANDSCAPE) }

    @Test
    fun keepTakesTheQuestionAwayAndDeletesNothing() = runTest {
        val m = mount(PHONE, onPage(kayla.key))
        m.bin()!!.click()
        settle(m.window)
        (m.button(People.KEEP) ?: error("the question has no ${People.KEEP} button")).click()
        settle(m.window)
        assertTrue(People.askDelete(kayla) !in m.text(), "the question is still up after Keep")
        assertTrue(m.asked.isEmpty(), "Keep deleted the account")
        assertNull(m.held.value.people.deleting)
    }

    @Test
    fun yourOwnPageHasNoBin() = runTest {
        val m = mount(PHONE, onPage(matt.key))
        assertTrue("Matt" in m.text(), "your own page is not showing, so this proves nothing")
        assertNull(m.bin(), "your own page offers to delete the account you are signed in as")
    }

    @Test
    fun aRefusalStopsTheSpinnerSaysWhyAndCanBeTriedAgain() = runTest {
        val going = onPage(kayla.key).let { it.copy(people = it.people.deletingAccount(kayla.key)) }
        val m = mount(PHONE, going)
        assertTrue(m.bin()?.hasAttribute("disabled") == true, "the bin is live while a delete is out, so this proves nothing")
        m.held.value = m.held.value.deleteFailed("that needs the admin role")
        settle(m.window)
        assertTrue("that needs the admin role" in m.text(), "a refusal does not say why on the page")
        val bin = assertNotNull(m.bin(), "a refusal took the bin away")
        assertTrue(!bin.hasAttribute("disabled"), "after a refusal the bin is still dead, so it cannot be tried again")
        bin.click()
        settle(m.window)
        assertTrue(People.askDelete(kayla) in m.text(), "after a refusal the bin does not ask again")
    }

    @Test
    fun onceItIsGoneThePageIsTheListWithoutThem() = runTest {
        val m = mount(PHONE, onPage(kayla.key))
        m.held.value = m.held.value.accountDeleted(kayla.key)
        settle(m.window)
        assertEquals(Route(View.ADMIN), m.held.value.route)
        val rows = m.all("button.person-row").map { it.textContent.orEmpty() }.filter { "/c/" in it }
        assertTrue(rows.any { "Matt" in it }, "the list is not showing, so this proves nothing: $rows")
        assertTrue(rows.none { "Kayla" in it }, "Kayla is still on the list: $rows")
    }
}
