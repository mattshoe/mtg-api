package org.mattshoe.mtg.android

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
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
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Matt: "get rid of the 'back' button on all the details pages and put
 * the share icon inline with title or whatever is at the top. it looks
 * much better that way".
 *
 * The card, an open deck, a task and a person, each through the real
 * `AppShell` over a `ComponentActivity`, so the back press is the
 * dispatcher `MainActivity` uses. With the buttons gone that gesture
 * is the only way off these pages, so each one is pressed as well as
 * looked at. Sibling of the web's `DetailPagesHaveNoBackButtonTest`.
 */
@RunWith(AndroidJUnit4::class)
class DetailPagesHaveNoBackButtonTest {

    @get:Rule(order = Int.MIN_VALUE)
    val retry = Retry()

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun shell(start: AppState): () -> AppState {
        var state = start
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface {
                    val held = androidx.compose.runtime.remember {
                        androidx.compose.runtime.mutableStateOf(start)
                    }
                    state = held.value
                    AppShell(
                        state = held.value,
                        onState = { held.value = it; state = it },
                        onSearch = {},
                        onOpenDeck = {},
                        onPreviewEntry = {},
                        onApplyEntry = {},
                    )
                }
            }
        }
        rule.waitForIdle()
        return { state }
    }

    private fun pressBack() {
        rule.activity.onBackPressedDispatcher.onBackPressed()
        rule.waitForIdle()
    }

    private fun shown(text: String) = rule.onAllNodes(hasText(text)).fetchSemanticsNodes().size

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
    fun aCardsPageHasNoBackButton() {
        shell(onTheCard())
        assertTrue(rule.onAllNodes(hasTestTag("card-sheet")).fetchSemanticsNodes().isNotEmpty(), "the card page is not showing")
        assertEquals(0, shown("← Back"), "the card page still has its ← Back button")
    }

    @Test
    fun aCardsShareSitsOnTheSameLineAsItsName() {
        shell(onTheCard())
        val name = rule.onNode(hasText("Sol Ring") and hasAnyAncestor(hasTestTag("card-sheet")))
            .getUnclippedBoundsInRoot()
        val share = rule.onNode(hasContentDescription("Share this card")).getUnclippedBoundsInRoot()
        val middle = (name.top + name.bottom) / 2
        assertTrue(
            middle >= share.top && middle <= share.bottom,
            "the share is not on the name's line: the name's middle is at $middle, the share runs ${share.top} to ${share.bottom}",
        )
        assertTrue(share.left > name.left, "the share is not to the right of the name")
    }

    @Test
    fun backOffACardIsTheDeckItCameFrom() {
        val state = shell(onTheCard())
        pressBack()
        assertEquals(View.DECKS, state().view, "back off the card went somewhere else")
        assertEquals("alela", state().route.rest, "back off the card left the deck")
    }

    // --------------------------------------------------------- an open deck

    @Test
    fun anOpenDeckHasNoBackButtonAndKeepsItsShare() {
        shell(onTheDeck())
        assertEquals(0, shown("← Decks"), "the open deck still has its ← Decks button")
        assertEquals(
            1,
            rule.onAllNodes(hasContentDescription("Share this deck")).fetchSemanticsNodes().size,
            "the open deck lost its share",
        )
    }

    @Test
    fun backOffAnOpenDeckIsTheList() {
        val state = shell(onTheDeck())
        pressBack()
        assertEquals(Route(View.DECKS), state().route)
    }

    // -------------------------------------------------------------- a task

    private val task = Task(
        "ab12cd34", "Bigger buttons", TaskStatus.MERGED, "2026-10-08T12:00:00.000Z",
        startedAt = "2026-10-08T10:05:00.000Z",
    )

    private fun onTheTask() = AppState(admin = admin, tasks = Tasks().loaded(listOf(task)))
        .navigate(View.ADMIN)
        .openingTask(task)
        .copy(
            taskDetail = TaskDetail.decode(
                "ab12cd34",
                """{"key":"ab12cd34","title":"Bigger buttons","status":"merged","details":"x","files":[]}""",
            ),
        )

    @Test
    fun aTasksPageHasNoBackButton() {
        shell(onTheTask())
        assertTrue(rule.onAllNodes(hasTestTag("task-detail")).fetchSemanticsNodes().isNotEmpty(), "the task page is not showing")
        assertEquals(0, shown("← Admin Settings"), "the task page still has its ← Admin Settings button")
    }

    @Test
    fun backOffATaskIsAdminSettings() {
        val state = shell(onTheTask())
        pressBack()
        assertEquals(Route(View.ADMIN), state().route)
    }

    // ------------------------------------------------------------ a person

    private fun onThePerson() = AppState(admin = admin)
        .let { it.copy(people = it.people.copy(rows = listOf(Person("kayla123", "Kayla")))) }
        .navigate(Route(View.ADMIN, "kayla123"))

    @Test
    fun aPersonsPageHasNoBackButton() {
        shell(onThePerson())
        assertTrue(shown("Kayla") > 0, "the person's page is not showing")
        assertEquals(0, shown("← Everybody"), "the person's page still has its ← Everybody button")
    }

    @Test
    fun backOffAPersonIsEverybody() {
        val state = shell(onThePerson())
        pressBack()
        assertEquals(Route(View.ADMIN), state().route, "back off a person went to the wrong place")
    }
}
