package org.mattshoe.mtg.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Fetch and Entry, behind one Tools tab.
 *
 * Matt: "let's collapse fetch and entry into a "Tools" tab. the tools
 * page should look very similar in presentation to the users tab, but
 * instead of profile avatars, use the current icons on the bottom nav
 * for them".
 */
class ToolsTest {

    private val me = Account(key = "mattkey1", name = "Matt")
    private val signedIn = Admin().signIn(me, "s")

    @Test
    fun theBarHasToolsWhereFetchAndEntryWere() {
        val bar = listOf(View.LIBRARY, View.DECKS, View.USERS, View.GUIDE, View.TOOLS)
        assertEquals(bar, Admin().bar, "a stranger's bar")
        assertEquals(bar, signedIn.bar, "signing in still adds a tab to the bar")
        assertEquals(View.TOOLS, Route.parse("#/tools").view)
        assertEquals("Tools", View.TOOLS.label)
    }

    /**
     * Matt: "the tools tab should always be on the far right of bottom
     * nav". Last in `bar` is the far right on the phone and the bottom
     * of the website's menu, whoever is signed in.
     */
    @Test
    fun toolsIsAlwaysTheLastTabInTheBar() {
        val operator = Admin().signIn(Account(key = "e7de0cb1", role = "admin"), "t")
        listOf("a stranger" to Admin(), "somebody signed in" to signedIn, "an operator" to operator)
            .forEach { (who, admin) ->
                assertEquals(View.TOOLS, admin.bar.last(), "for $who Tools is not the far right: ${admin.bar}")
            }
    }

    @Test
    fun toolsOffersFetchToAnybodyAndEntryOnceSignedIn() {
        assertEquals(listOf(View.FETCH), Admin().tools, "a stranger can enter cards, or cannot fetch")
        assertEquals(listOf(View.FETCH, View.ENTRY), signedIn.tools)
    }

    @Test
    fun fetchAndEntryAreNotBehindTheProfileEither() {
        assertFalse(View.FETCH in signedIn.behindProfile, "Fetch moved behind the profile: ${signedIn.behindProfile}")
        assertFalse(View.ENTRY in signedIn.behindProfile, "Entry moved behind the profile: ${signedIn.behindProfile}")
    }

    @Test
    fun aToolLightsTheToolsTab() {
        assertEquals(View.TOOLS, View.FETCH.tab)
        assertEquals(View.TOOLS, View.ENTRY.tab)
        assertEquals(View.TOOLS, View.TOOLS.tab)
        assertEquals(View.LIBRARY, View.LIBRARY.tab)
        assertEquals(View.USERS, View.USERS.tab)
    }

    @Test
    fun theToolsPageLoadsNothingAndIsNeverBusy() {
        val s = AppState(admin = signedIn).navigate(View.TOOLS)
        assertEquals(View.TOOLS, s.view)
        assertTrue(Load.needs(Route(View.TOOLS)).isEmpty())
        assertEquals(s, s.fetching(View.TOOLS))
        assertFalse(s.fetching(View.TOOLS).refreshing)
        assertEquals(Brand.NAME, s.titleBeside(signedIn.bar))
    }

    @Test
    fun everyToolSaysWhatItIsFor() {
        signedIn.tools.forEach { tool ->
            assertTrue(Tools.blurb(tool).isNotBlank(), "${tool.label} has no line under its name")
        }
        assertEquals("Paste a deck list and find where every card is filed.", Tools.blurb(View.FETCH))
        assertEquals("Add cards to your collection, or take them out, by the list.", Tools.blurb(View.ENTRY))
    }
}
