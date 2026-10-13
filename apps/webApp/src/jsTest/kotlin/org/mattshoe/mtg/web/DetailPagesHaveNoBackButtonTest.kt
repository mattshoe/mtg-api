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
import org.mattshoe.mtg.core.Person
import org.mattshoe.mtg.core.Route
import org.mattshoe.mtg.core.Task
import org.mattshoe.mtg.core.TaskDetail
import org.mattshoe.mtg.core.TaskStatus
import org.mattshoe.mtg.core.Tasks
import org.mattshoe.mtg.core.View
import org.mattshoe.mtg.core.openingTask
import org.w3c.dom.HTMLElement
import org.w3c.dom.get
import kotlin.js.Promise
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Matt: "get rid of the 'back' button on all the details pages and put
 * the share icon inline with title or whatever is at the top. it looks
 * much better that way".
 *
 * The card, an open deck, a task and a person, each through the real
 * `AppShell`. The browser's own back is what leaves them now;
 * `AppDriverTest` presses it. Sibling of Android's
 * `DetailPagesHaveNoBackButtonTest`.
 */
class DetailPagesHaveNoBackButtonTest {

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

    private fun shell(start: AppState): HTMLElement {
        val root = document.createElement("div") as HTMLElement
        root.style.width = "390px"
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

    private fun HTMLElement.buttons(): List<String> = all("button").map { it.textContent.orEmpty().trim() }

    private val admin = Admin().signIn(Account(key = "e7de0cb1", role = "admin"), "t")

    private val deck = Deck("alela", "Alela", "matt", "Alela, Artful Provocateur (ELD) 324", "UW", 3, null)

    private fun onTheDeck() = AppState(
        route = Route(View.DECKS, "alela"),
        decks = DecksState().loaded(listOf(deck)).opened(
            "alela",
            listOf(DeckCard("Sol Ring", 1, null, 1, nameNorm = "sol ring", typeLine = "Artifact")),
        ),
    )

    // ------------------------------------------------------------- a card

    private fun onTheCard() = onTheDeck()
        .openCard(CardRef(nameNorm = "sol ring"), "Sol Ring")
        .copy(card = CardDetail(name = "Sol Ring", nameNorm = "sol ring"))

    @Test
    fun aCardsPageHasNoBackButton() = runTest {
        val root = shell(onTheCard())
        settle()
        assertTrue(root.all("div.card-page").isNotEmpty(), "the card page is not showing")
        assertTrue("← Back" !in root.buttons(), "the card page still has its ← Back button: ${root.buttons()}")
    }

    @Test
    fun aCardsShareSitsOnTheSameLineAsItsName() = runTest {
        val root = shell(onTheCard())
        settle()
        assertTrue(Stylesheet.applied(), "app.css never loaded, so nothing here can be measured")
        val share = root.all("button").firstOrNull { it.getAttribute("aria-label") == "Share this card" }
            ?: error("the card page has no share")
        val name = root.all("div.page-head *").firstOrNull {
            it.tagName != "BUTTON" && it.children.length == 0 && it.textContent?.trim() == "Sol Ring"
        } ?: error("the card's name is not at the top of its page, beside the share")
        val n = name.getBoundingClientRect()
        val s = share.getBoundingClientRect()
        val middle = (n.top + n.bottom) / 2
        assertTrue(
            middle >= s.top && middle <= s.bottom,
            "the share is not on the name's line: the name's middle is at $middle, the share runs ${s.top} to ${s.bottom}",
        )
        assertTrue(s.left > n.left, "the share is not to the right of the name")
    }

    // --------------------------------------------------------- an open deck

    @Test
    fun anOpenDeckHasNoBackButtonAndKeepsItsShare() = runTest {
        val root = shell(onTheDeck())
        settle()
        assertTrue(root.textContent.orEmpty().contains("Sol Ring"), "the deck is not showing")
        assertTrue("← Decks" !in root.buttons(), "the open deck still has its ← Decks button: ${root.buttons()}")
        assertTrue(
            root.all("button").any { it.getAttribute("aria-label") == "Share this deck" },
            "the open deck lost its share",
        )
    }

    // -------------------------------------------------------------- a task

    private val task = Task(
        "ab12cd34", "Bigger buttons", TaskStatus.MERGED, "2026-10-08T12:00:00.000Z",
        startedAt = "2026-10-08T10:05:00.000Z",
    )

    @Test
    fun aTasksPageHasNoBackButton() = runTest {
        val root = shell(
            AppState(admin = admin, tasks = Tasks().loaded(listOf(task)))
                .navigate(View.ADMIN)
                .openingTask(task)
                .copy(
                    taskDetail = TaskDetail.decode(
                        "ab12cd34",
                        """{"key":"ab12cd34","title":"Bigger buttons","status":"merged","details":"x","files":[]}""",
                    ),
                ),
        )
        settle()
        assertEquals(1, root.all("[data-task-detail]").size, "the task page is not showing")
        assertTrue(
            "← Admin Settings" !in root.buttons(),
            "the task page still has its ← Admin Settings button: ${root.buttons()}",
        )
    }

    // ------------------------------------------------------------ a person

    @Test
    fun aPersonsPageHasNoBackButton() = runTest {
        val root = shell(
            AppState(admin = admin)
                .let { it.copy(people = it.people.copy(rows = listOf(Person("kayla123", "Kayla")))) }
                .navigate(Route(View.ADMIN, "kayla123")),
        )
        settle()
        assertTrue(root.all(".person-name").any { it.textContent == "Kayla" }, "the person's page is not showing")
        assertTrue("← Everybody" !in root.buttons(), "the person's page still has its ← Everybody button: ${root.buttons()}")
    }
}
