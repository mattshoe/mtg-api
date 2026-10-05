package org.mattshoe.mtg.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * The header does not say what the bar already says.
 *
 * Matt, looking at a screenshot of the Android app with the new
 * bottom bar: "Why THE FUCK does it say library twice?!?! Are all the
 * fucking pages like that?!?!"
 *
 * Yes, all of them. `AppState.title` was written for a chrome with no
 * visible list of places — the web's bar is a mark and a title, and
 * the places are behind a button — so "what you are looking at" was
 * worth a line. A bottom bar names the place already, in the same
 * words, four inches below. Every page read `Library / Library`,
 * `Decks / Decks`, `Stats / Stats`.
 *
 * So the rule is about the chrome, not the page: a title that only
 * repeats a tab gives way to the app's name, and a title that says
 * something the bar cannot — which deck, which card — still gets
 * said.
 */
class HeaderTitleTest {

    private val bar = Admin(token = "t").unlock("t").bar

    @Test
    fun noTabNameIsPrintedTwice() {
        bar.forEach { view ->
            val state = AppState().navigate(view)
            assertNotEquals(
                view.label,
                state.titleBeside(bar),
                "the header repeats \"${view.label}\", which the bar is already showing",
            )
        }
    }

    @Test
    fun whatItSaysInsteadIsTheAppsName() {
        assertEquals(Brand.NAME, AppState().navigate(View.LIBRARY).titleBeside(bar))
    }

    @Test
    fun anOpenDeckStillGetsItsName() {
        // The one thing the bar cannot tell you on the Decks tab.
        val deck = Deck("alela", "Alela", "matt", null, "UW", 3, null)
        val state = AppState()
            .navigate(Route(View.DECKS, "alela"))
            .let { it.copy(decks = it.decks.loaded(listOf(deck)).opened("alela", emptyList())) }
        assertEquals("Alela", state.titleBeside(bar))
    }

    @Test
    fun aCardStillGetsItsName() {
        val state = AppState()
            .navigate(Route(View.CARD, "matt:sol+ring"))
            .copy(card = CardDetail(name = "Sol Ring"))
        assertEquals("Sol Ring", state.titleBeside(bar))
    }

    @Test
    fun aScreenThatIsNotInTheBarStillGetsItsLabel() {
        // Server Logs is behind the profile, so nothing else on
        // screen says where you are.
        val unlocked = AppState(admin = Admin(token = "t").unlock("t")).navigate(View.LOGS)
        assertEquals(View.LOGS, unlocked.view, "the fixture never reached the log")
        assertEquals(View.LOGS.label, unlocked.titleBeside(bar))
    }

    @Test
    fun aChromeWithNoBarIsUnchanged() {
        // The web has no visible list of places, so there is nothing
        // to duplicate and the title is still worth printing.
        View.entries.filter { it.inNav }.forEach { view ->
            val state = AppState().navigate(view)
            assertEquals(state.title, state.titleBeside(emptyList()))
        }
    }
}
