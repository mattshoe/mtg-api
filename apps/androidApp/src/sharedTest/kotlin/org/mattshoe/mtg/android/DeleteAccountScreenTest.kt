package org.mattshoe.mtg.android

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mattshoe.mtg.core.Account
import org.mattshoe.mtg.core.Admin
import org.mattshoe.mtg.core.AppState
import org.mattshoe.mtg.core.People
import org.mattshoe.mtg.core.Person
import org.mattshoe.mtg.core.Role
import org.mattshoe.mtg.core.Route
import org.mattshoe.mtg.core.View
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Delete account, on a person's page in Admin Settings, the phone's half.
 *
 * Matt: "i want to be able to delete accounts via admin settings, and all
 * associated database info." The page draws a bin, asks before anything
 * goes, hands the one person to whoever calls the Worker, never offers it
 * on your own page, and after a refusal stops the spinner and lets it be
 * tried again. Through the real `AppShell`, on a phone, a folding phone
 * opened out and a tablet held sideways. The website's
 * `DeleteAccountPageTest` is the same claims.
 */
@RunWith(AndroidJUnit4::class)
class DeleteAccountScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val kayla = Person(key = "k4yy0003", name = "Kayla M")
    private val matt = Person(key = "m4tt0001", name = "Matt", role = Role.ADMIN)
    private val admin = Admin().signIn(Account(key = "m4tt0001", name = "Matt", role = Role.ADMIN), "t")

    private fun onPage(key: String) = AppState(
        route = Route(View.ADMIN, key),
        admin = admin,
        people = People().loaded(listOf(matt, kayla)),
    )

    private val asked = mutableListOf<Person>()

    private fun mount(start: AppState, size: DpSize? = null): MutableState<AppState> {
        val held = mutableStateOf(start)
        rule.setContent {
            val shell = @androidx.compose.runtime.Composable {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    Surface {
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
                }
            }
            if (size != null) Tablet.Sized(size) { shell() } else shell()
        }
        rule.waitForIdle()
        return held
    }

    private fun bins() = rule.onAllNodes(hasContentDescription(People.DELETE)).fetchSemanticsNodes()
    private fun bin() = rule.onNode(hasContentDescription(People.DELETE))
    private fun shown(text: String) = rule.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().size
    private val disabled = SemanticsMatcher.keyIsDefined(SemanticsProperties.Disabled)

    private fun theBinIsDrawnAndOnScreen(size: DpSize?, width: Float) {
        mount(onPage(kayla.key), size)
        val on = "on a ${size ?: "phone"} screen"
        assertEquals(1, bins().size, "$on Kayla's page has no Delete account button")
        bin().performScrollTo()
        rule.waitForIdle()
        assertEquals(0, shown(People.DELETE), "$on Delete account is written, not drawn")
        val r = bin().getUnclippedBoundsInRoot()
        assertTrue(r.right - r.left >= 24.dp && r.bottom - r.top >= 24.dp, "$on the bin is too small to tap: $r")
        assertTrue(r.left >= 0.dp && r.right.value <= width, "$on the bin runs off the side: ${r.left}..${r.right}")
    }

    @Test
    fun theBinIsDrawnAndOnScreenOnAPhone() = theBinIsDrawnAndOnScreen(null, 320f)

    @Test
    @Config(qualifiers = Tablet.UNFOLDED_QUALIFIERS)
    fun theBinIsDrawnAndOnScreenOnAFoldingPhoneOpenedOut() =
        theBinIsDrawnAndOnScreen(Tablet.UNFOLDED, Tablet.UNFOLDED.width.value)

    @Test
    @Config(qualifiers = Tablet.LANDSCAPE_QUALIFIERS)
    fun theBinIsDrawnAndOnScreenOnATabletHeldSideways() =
        theBinIsDrawnAndOnScreen(Tablet.LANDSCAPE, Tablet.LANDSCAPE.width.value)

    private fun itAsksFirstAndYesHandsOverThatPerson(size: DpSize?) {
        val held = mount(onPage(kayla.key), size)
        val on = "on a ${size ?: "phone"} screen"
        bin().performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(1, shown(People.askDelete(kayla)), "$on tapping the bin did not ask whether to delete Kayla's account")
        assertTrue(asked.isEmpty(), "$on tapping the bin deleted without asking")

        rule.onNode(hasText(People.CONFIRM)).performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(listOf(kayla), asked, "$on ${People.CONFIRM} did not hand over Kayla, and only Kayla")
        assertEquals(kayla.key, held.value.people.deleting, "$on the page is not marked busy while the delete is out")
        assertEquals(1, rule.onAllNodes(hasContentDescription(People.DELETE) and disabled).fetchSemanticsNodes().size,
            "$on the bin is still live while the delete is out")
    }

    @Test
    fun itAsksFirstOnAPhone() = itAsksFirstAndYesHandsOverThatPerson(null)

    @Test
    @Config(qualifiers = Tablet.UNFOLDED_QUALIFIERS)
    fun itAsksFirstOnAFoldingPhoneOpenedOut() = itAsksFirstAndYesHandsOverThatPerson(Tablet.UNFOLDED)

    @Test
    @Config(qualifiers = Tablet.LANDSCAPE_QUALIFIERS)
    fun itAsksFirstOnATabletHeldSideways() = itAsksFirstAndYesHandsOverThatPerson(Tablet.LANDSCAPE)

    @Test
    fun keepTakesTheQuestionAwayAndDeletesNothing() {
        val held = mount(onPage(kayla.key))
        bin().performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNode(hasText(People.KEEP)).performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(0, shown(People.askDelete(kayla)), "the question is still up after Keep")
        assertTrue(asked.isEmpty(), "Keep deleted the account")
        assertNull(held.value.people.deleting)
    }

    @Test
    fun yourOwnPageHasNoBin() {
        mount(onPage(matt.key))
        assertTrue(shown("m4tt0001") > 0, "your own page is not showing, so this proves nothing")
        assertEquals(0, bins().size, "your own page offers to delete the account you are signed in as")
    }

    @Test
    fun aRefusalStopsTheSpinnerSaysWhyAndCanBeTriedAgain() {
        val going = onPage(kayla.key).let { it.copy(people = it.people.deletingAccount(kayla.key)) }
        val held = mount(going)
        assertEquals(1, rule.onAllNodes(hasContentDescription(People.DELETE) and disabled).fetchSemanticsNodes().size,
            "the bin is live while a delete is out, so this proves nothing")
        held.value = held.value.deleteFailed("that needs the admin role")
        rule.waitForIdle()
        assertTrue(shown("that needs the admin role") > 0, "a refusal does not say why on the page")
        assertEquals(0, rule.onAllNodes(hasContentDescription(People.DELETE) and disabled).fetchSemanticsNodes().size,
            "after a refusal the bin is still dead, so it cannot be tried again")
        bin().performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(1, shown(People.askDelete(kayla)), "after a refusal the bin does not ask again")
    }

    @Test
    fun onceItIsGoneThePageIsTheListWithoutThem() {
        val held = mount(onPage(kayla.key))
        held.value = held.value.accountDeleted(kayla.key)
        rule.waitForIdle()
        assertEquals(Route(View.ADMIN), held.value.route)
        assertTrue(shown("/c/m4tt0001") > 0, "the list is not showing, so this proves nothing")
        assertEquals(0, shown("/c/k4yy0003"), "Kayla is still on the list")
    }
}
