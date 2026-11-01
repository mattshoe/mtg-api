package org.mattshoe.mtg.web

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.compose.web.renderComposable
import org.mattshoe.mtg.core.Account
import org.mattshoe.mtg.core.Admin
import org.mattshoe.mtg.core.AppState
import org.mattshoe.mtg.core.FacetQueries
import org.mattshoe.mtg.core.Facets
import org.mattshoe.mtg.core.Route
import org.mattshoe.mtg.core.View
import org.w3c.dom.Document
import org.w3c.dom.HTMLElement
import org.w3c.dom.get
import kotlin.js.Promise
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * The filter panel's "In a deck", through the real `AppShell`.
 *
 * Matt: "the collection filter shows ALL decks and NOT the deck the user
 * owns. this is unacceptable. especially when browsing other users
 * cards". The phone's `DeckPickerScopeTest` is the same claim.
 */
class DeckPickerScopeTest {

    private val roots = mutableListOf<HTMLElement>()
    private var screen: Tablet.Screen? = null

    @AfterTest
    fun cleanUp() {
        roots.forEach { it.remove() }
        roots.clear()
        screen?.frame?.remove()
        screen = null
    }

    private fun row(vararg v: String) = JsonArray(v.map { JsonPrimitive(it) })

    private val signedIn = AppState(
        admin = Admin().signIn(Account("m4tt0001"), "t"),
        facets = Facets(
            types = listOf("Creature"),
            decks = FacetQueries.decodeDecks(
                listOf("key", "name", "owner", "owner_key"),
                listOf(
                    row("d1", "Alela", "Matt", "m4tt0001"),
                    row("d2", "Korvold", "Kayla", "k4yy0003"),
                ),
            ),
        ),
    )

    private suspend fun settle() = repeat(4) {
        Promise<Unit> { r, _ -> window.requestAnimationFrame { r(Unit) } }.await()
    }

    private fun draw(root: HTMLElement, start: AppState) {
        renderComposable(root = root) {
            val held = remember { mutableStateOf(start) }
            AppShell(
                state = held.value,
                onState = { held.value = it },
                onSearch = {},
                onOpenDeck = {},
                onPreviewEntry = {},
                onApplyEntry = {},
            )
        }
    }

    /** What the deck dropdown lists, read off the options the browser holds. */
    private suspend fun options(doc: Document): List<String> {
        val summary = assertNotNull(
            doc.querySelector("details[data-facet=collection] summary"),
            "the Collection group is not on the page",
        ).unsafeCast<HTMLElement>()
        summary.click()
        settle()
        val selects = doc.querySelectorAll("details[data-facet=collection] select")
        for (i in 0 until selects.length) {
            val select = selects[i].unsafeCast<HTMLElement>()
            if (select.textContent.orEmpty().contains("in any deck")) {
                val opts = select.querySelectorAll("option")
                return (0 until opts.length).map { opts[it].unsafeCast<HTMLElement>().textContent.orEmpty() }
            }
        }
        error("the In a deck dropdown is not on the page")
    }

    private val fixed = listOf("any", "— in any deck —", "— in no deck —")

    private suspend fun onThePhone(start: AppState): List<String> {
        val root = document.createElement("div") as HTMLElement
        document.body!!.appendChild(root)
        roots += root
        draw(root, start)
        settle()
        return options(document)
    }

    private suspend fun onATablet(size: Pair<Int, Int>, start: AppState): List<String> {
        val s = Tablet.open(size).also { screen = it }
        draw(s.body, start)
        settle()
        return options(s.frame.contentDocument!!)
    }

    private val theirs get() = signedIn.navigate(Route(View.USERS, "k4yy0003"))
    private val yours get() = signedIn.navigate(View.LIBRARY)

    @Test
    fun somebodyElsesLibraryOffersOnlyTheirDecks() = runTest {
        assertEquals(fixed + "Korvold", onThePhone(theirs))
    }

    @Test
    fun yourLibraryOffersOnlyYourDecks() = runTest {
        assertEquals(fixed + "Alela", onThePhone(yours))
    }

    @Test
    fun somebodyElsesLibraryOffersOnlyTheirDecksOnAFoldingPhoneOpenedOut() = runTest {
        assertEquals(fixed + "Korvold", onATablet(Tablet.UNFOLDED, theirs))
    }

    @Test
    fun yourLibraryOffersOnlyYourDecksOnAFoldingPhoneOpenedOut() = runTest {
        assertEquals(fixed + "Alela", onATablet(Tablet.UNFOLDED, yours))
    }

    @Test
    fun somebodyElsesLibraryOffersOnlyTheirDecksOnATabletHeldSideways() = runTest {
        assertEquals(fixed + "Korvold", onATablet(Tablet.LANDSCAPE, theirs))
    }

    @Test
    fun yourLibraryOffersOnlyYourDecksOnATabletHeldSideways() = runTest {
        assertEquals(fixed + "Alela", onATablet(Tablet.LANDSCAPE, yours))
    }
}
