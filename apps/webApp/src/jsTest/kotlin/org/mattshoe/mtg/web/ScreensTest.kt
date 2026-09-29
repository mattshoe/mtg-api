package org.mattshoe.mtg.web

import androidx.compose.runtime.Composable
import kotlinx.browser.document
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import org.jetbrains.compose.web.renderComposable
import org.mattshoe.mtg.core.Admin
import org.mattshoe.mtg.core.AppState
import org.mattshoe.mtg.core.CardDetail
import org.mattshoe.mtg.core.CardRow
import org.mattshoe.mtg.core.Completion
import org.mattshoe.mtg.core.Deck
import org.mattshoe.mtg.core.DeckCard
import org.mattshoe.mtg.core.DeckEditState
import org.mattshoe.mtg.core.DeckPlan
import org.mattshoe.mtg.core.DeckUse
import org.mattshoe.mtg.core.DecksState
import org.mattshoe.mtg.core.Direction
import org.mattshoe.mtg.core.DisassembleState
import org.mattshoe.mtg.core.Disassembly
import org.mattshoe.mtg.core.EntryHistory
import org.mattshoe.mtg.core.Filters
import org.mattshoe.mtg.core.Found
import org.mattshoe.mtg.core.HistoryEntry
import org.mattshoe.mtg.core.Library
import org.mattshoe.mtg.core.MassEntry
import org.mattshoe.mtg.core.NewDeck
import org.mattshoe.mtg.core.Overlay
import org.mattshoe.mtg.core.Owner
import org.mattshoe.mtg.core.PaletteState
import org.mattshoe.mtg.core.Printing
import org.mattshoe.mtg.core.Step
import org.mattshoe.mtg.core.Tally
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.get
import kotlin.js.Promise
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The web screens, in a real browser, clicked.
 *
 * Android has the same suite under instrumentation. Every one of these
 * has a sibling there, because "the port is done" is a claim about what
 * a person can do with the app and only something that presses the
 * buttons can check it.
 *
 * Headless Chrome via Karma, because Compose HTML recomposes on
 * requestAnimationFrame and a browser that is not painting never ticks
 * it.
 */
class ScreensTest {

    private val roots = mutableListOf<HTMLElement>()

    @AfterTest
    fun cleanUp() {
        roots.forEach { it.remove() }
        roots.clear()
    }

    private fun mount(body: @Composable () -> Unit): HTMLElement {
        val root = document.createElement("div") as HTMLElement
        document.body!!.appendChild(root)
        roots += root
        renderComposable(root = root) { body() }
        return root
    }

    private suspend fun settle() = repeat(3) {
        Promise<Unit> { resolve, _ -> kotlinx.browser.window.requestAnimationFrame { resolve(Unit) } }.await()
    }

    private fun HTMLElement.buttons(): List<HTMLButtonElement> {
        val f = querySelectorAll("button")
        return (0 until f.length).mapNotNull { f[it] as? HTMLButtonElement }
    }

    private fun HTMLElement.button(label: String) =
        buttons().first { it.textContent?.trim() == label }

    private fun HTMLElement.hasButton(label: String) =
        buttons().any { it.textContent?.trim() == label }

    private fun HTMLElement.text() = textContent.orEmpty()

    private fun card(name: String, qty: Int = 1) = CardRow(
        id = 1, owner = "matt", name = name, nameNorm = name.lowercase(), face2 = null,
        layout = "normal", scryfallId = null, manaCost = "{1}", cmc = 1.0,
        typeLine = "Artifact", colorIdentity = "", rarity = "rare", setCode = "m3c",
        setName = "Modern Horizons 3", collectorNumber = "409", edhrecRank = null,
        releasedAt = null, finish = "nonfoil", power = null, toughness = null,
        artist = null, qty = qty, printings = 1, free = 1, price = 1.5, value = 1.5,
    )

    private fun deck() = Deck("alela", "Alela", "matt", "Alela, Artful Provocateur", "UWB", 3, null)
    private fun deckCard() = DeckCard("Sol Ring", 1, null, 1)

    // Both, over one state — the arrangement the real app has. The
    // menu lives in the header's own slot, so the shell alone has
    // nothing to navigate with.
    private fun shell(state: AppState, onState: (AppState) -> Unit = {}) = mount {
        AppNav(state, onState)
        AppShell(state, onState, {}, {}, {}, {}, {}, {})
    }

    // ------------------------------------------------------------ shell

    @Test
    fun gatedTabsAreAbsentWhileLocked() = runTest {
        val root = shell(AppState())
        settle()
        assertTrue(root.hasButton("Library"))
        assertTrue(root.hasButton("Decks"))
        assertFalse(root.hasButton("Mass Entry"), "a gated tab while locked")
        assertFalse(root.hasButton("Server Logs"))
        assertTrue(root.hasButton("Unlock"))
    }

    @Test
    fun unlockingBringsTheGatedTabsBack() = runTest {
        val root = shell(AppState(admin = Admin(token = "t")))
        settle()
        assertTrue(root.hasButton("Mass Entry"))
        assertTrue(root.hasButton("Server Logs"))
        assertTrue(root.hasButton("Lock"))
    }

    @Test
    fun thereIsNoFindButton() = runTest {
        // The palette is still on ⌘K and `/`. A button for it in the
        // nav was one more thing in the bar that nobody asked for.
        val root = shell(AppState())
        settle()
        assertFalse(root.hasButton("Find"), "the Find button is back")
    }

    // ---------------------------------------------------------- library

    @Test
    fun theLibraryShowsTheRangeAndTheRows() = runTest {
        val lib = Library().loaded(listOf(card("Sol Ring"), card("Arcane Signet")), 250)
        val root = mount { LibraryPage(lib, {}, {}, {}) }
        settle()
        assertTrue(root.text().contains("Sol Ring"))
        assertTrue(root.text().contains("1–100 of 250"), root.text())
    }

    @Test
    fun theFilterButtonRevealsThePanel() = runTest {
        // The Filters button is gone: the groups are folded away
        // already, so all it bought was a way to lose track of a
        // filter that was still applied.
        val root = mount {
            LibraryPage(Library().loaded(listOf(card("Sol Ring")), 1), {}, {}, {})
        }
        settle()
        assertTrue(!root.hasButton("Filters"), "the Filters button is back")
        assertTrue(
            root.querySelectorAll("details[data-facet]").length > 0,
            "the filter groups are not on the page",
        )
    }

    @Test
    fun thePanelHasEveryGroupTheOldPageHad() = runTest {
        val root = mount { FilterPanel(Filters()) {} }
        settle()
        org.mattshoe.mtg.core.Facet.entries.forEach { facet ->
            assertTrue(root.text().contains(facet.title), "${facet.title} is missing from the panel")
        }
        // The four colour modes are the whole point of it for
        // Commander, and they live inside the group once it is open.
        (root.querySelector("details[data-facet=colour] summary") as HTMLElement).click()
        settle()
        listOf("Exactly", "At most", "At least", "Any of").forEach {
            assertTrue(root.buttons().any { b -> b.textContent?.contains(it) == true }, it)
        }
    }

    @Test
    fun thereIsNoQueryBoxOnTheLibrary() = runTest {
        val root = mount { LibraryPage(Library().loaded(listOf(card("Sol Ring")), 1), {}, {}, {}) }
        settle()
        val inputs = root.querySelectorAll("input")
        val placeholders = (0 until inputs.length)
            .mapNotNull { (inputs[it] as? HTMLElement)?.getAttribute("placeholder") }
        // Asked for and taken out. The name field is the only input the
        // Library itself has.
        assertFalse(placeholders.any { it.startsWith("Query box") }, placeholders.toString())
        assertEquals(listOf("Card name"), placeholders)
    }

    @Test
    fun bothWaysOutOfALibraryAreOffered() = runTest {
        val asked = mutableListOf<org.mattshoe.mtg.core.ExportTo>()
        val root = mount {
            LibraryPage(
                Library().loaded(listOf(card("Sol Ring")), 1),
                {}, {}, {},
                onExport = { asked += it },
            )
        }
        settle()
        root.button("Copy").click()
        root.button("Download").click()
        settle()
        assertEquals(
            listOf(org.mattshoe.mtg.core.ExportTo.CLIPBOARD, org.mattshoe.mtg.core.ExportTo.FILE),
            asked,
        )
    }

    @Test
    fun autocompleteOffersWhatCameBack() = runTest {
        var picked: String? = null
        val c = Completion().typed("sol").suggested(listOf("Sol Ring", "Solemn Simulacrum"))
        val root = mount { AutocompleteField("Card name", c, {}, { picked = it }) }
        settle()
        val items = root.querySelectorAll("li")
        assertEquals(2, items.length)
        (items[1] as HTMLElement).let { li ->
            li.dispatchEvent(org.w3c.dom.events.MouseEvent("mousedown", org.w3c.dom.events.MouseEventInit(bubbles = true)))
        }
        settle()
        assertEquals("Solemn Simulacrum", picked)
    }

    // ------------------------------------------------------------- card

    @Test
    fun theCardSheetSaysWhatIsOwnedAndWhatIsFree() = runTest {
        val detail = CardDetail(
            name = "Sol Ring",
            owner = "matt",
            printings = listOf(Printing(1, "m3c", "Modern Horizons 3", "409", "nonfoil", 3, null)),
            usedIn = listOf(DeckUse("alela", "Alela", "matt", 1, "ramp", false)),
        )
        val root = mount { CardSheet(detail) {} }
        settle()
        assertTrue(root.text().contains("Sol Ring"))
        assertTrue(root.text().contains("3 owned"), root.text())
        assertTrue(root.text().contains("2 free"))
        assertTrue(root.text().contains("Alela"))
    }

    @Test
    fun aProxyDoesNotEatACopy() = runTest {
        val detail = CardDetail(
            name = "Sol Ring",
            owner = "matt",
            printings = listOf(Printing(1, "m3c", null, "409", "nonfoil", 1, null)),
            usedIn = listOf(DeckUse("p", "Proxy deck", "matt", 1, null, true)),
        )
        val root = mount { CardSheet(detail) {} }
        settle()
        assertTrue(root.text().contains("1 free"), root.text())
    }

    // --------------------------------------------------------- overlays

    @Test
    fun theFinderListsWhatWasFound() = runTest {
        var opened: Found? = null
        val p = PaletteState().opened().typed("bo")
            .found(listOf(Found(1, "Lightning Bolt", null, "Instant", 4, "matt")))
        val root = mount { PaletteDialog(p, {}, { opened = it }, {}) }
        settle()
        (root.querySelectorAll("li")[0] as HTMLElement).click()
        settle()
        assertEquals("Lightning Bolt", opened?.name)
    }

    @Test
    fun theCheatsheetCoversEveryGroup() = runTest {
        val root = mount { CheatsheetDialog {} }
        settle()
        listOf("Words", "Colour", "Numbers", "Printing", "Oracle-level", "Collection").forEach {
            assertTrue(root.text().contains(it), "$it is missing from the cheatsheet")
        }
        assertTrue(root.text().contains("is: values"))
    }

    // ------------------------------------------------------------ decks

    @Test
    fun theAdminActionsAreHiddenWhileLocked() = runTest {
        val decks = DecksState(decks = listOf(deck()), openSlug = "alela", cards = listOf(deckCard()))
        val root = mount { DecksPage(decks, {}, {}, admin = false) }
        settle()
        assertFalse(root.hasButton("Edit list"))
        assertFalse(root.hasButton("Disassemble"))
    }

    @Test
    fun theAdminActionsAppearWhenUnlocked() = runTest {
        var edited: Deck? = null
        val decks = DecksState(decks = listOf(deck()), openSlug = "alela", cards = listOf(deckCard()))
        val root = mount { DecksPage(decks, {}, {}, admin = true, onEdit = { edited = it }) }
        settle()
        root.button("Edit list").click()
        settle()
        assertEquals("alela", edited?.slug)
    }

    @Test
    fun theEditDialogWillNotSaveBeforeItHasReviewed() = runTest {
        val edit = DeckEditState.of(deck(), listOf(deckCard()))
        val root = mount { DeckEditDialog(edit, {}, {}, {}, {}) }
        settle()
        assertTrue(root.hasButton("Review changes"))
        assertFalse(root.hasButton("Save list"))
    }

    @Test
    fun theEditDialogOffersSaveOnceThePlanIsIn() = runTest {
        var saved = false
        val edit = DeckEditState.of(deck(), listOf(deckCard()))
            .planned(DeckPlan(cardCount = 99, added = listOf(Tally("Sol Ring", 1))))
        val root = mount { DeckEditDialog(edit, {}, {}, { saved = true }, {}) }
        settle()
        assertTrue(root.text().contains("preview — nothing saved yet"))
        root.button("Save list").click()
        settle()
        assertTrue(saved)
    }

    @Test
    fun disassembleWillNotFireBeforeTheDryRunIsBack() = runTest {
        val root = mount { DisassembleDialog(DisassembleState("alela", "Alela", "matt"), {}, {}) }
        settle()
        assertTrue(root.button("Disassemble · free 0").disabled)
    }

    @Test
    fun disassembleArmsOnceTheDryRunIsBack() = runTest {
        var went = false
        val d = DisassembleState("alela", "Alela", "matt").planned(Disassembly(freed = 42))
        val root = mount { DisassembleDialog(d, { went = true }, {}) }
        settle()
        assertTrue(root.text().contains("cannot be undone"))
        root.button("Disassemble · free 42").click()
        settle()
        assertTrue(went)
    }

    // --------------------------------------------------------- new deck

    @Test
    fun theNewDeckWizardWillNotLeaveTheFirstStepUnanswered() = runTest {
        var s = NewDeck()
        val root = mount { NewDeckDialog(s, { s = it }, {}, {}, {}) }
        settle()
        assertTrue(root.button("Continue →").disabled)
        root.button("Commander").click()
        settle()
        assertNotNull(s.format)
    }

    // ------------------------------------------------------------ entry

    @Test
    fun nothingIsPreselectedInTheWizard() = runTest {
        val root = mount { MassEntryPage(MassEntry(), {}, {}, {}) }
        settle()
        assertTrue(root.button("Pick one to continue").disabled)
        assertTrue(root.text().contains("Nothing is preselected on purpose."))
    }

    @Test
    fun theFileDropIsOnTheListStep() = runTest {
        val s = MassEntry().choose(Direction.ADD).goTo(Step.LIST)
        val root = mount { MassEntryPage(s, {}, {}, {}) }
        settle()
        assertTrue(root.text().contains("Upload a file"))
        // Rendered, not display:none — Android Chrome will not open a
        // picker for an input that is not laid out.
        assertEquals(1, root.querySelectorAll("input[type=file]").length)
    }

    @Test
    fun recentEntriesCanBePutBackInTheBox() = runTest {
        var reused: HistoryEntry? = null
        val history = EntryHistory().remember(
            HistoryEntry("2026-09-28T10:00:00Z", "add", "matt", 12, "12 Sol Ring"),
        )
        val s = MassEntry().choose(Direction.ADD).goTo(Step.LIST)
        val root = mount {
            MassEntryPage(s, {}, {}, {}, history = history, onReuse = { reused = it })
        }
        settle()
        assertTrue(root.text().contains("Recent"))
        root.button("Reuse").click()
        settle()
        assertEquals(12, reused?.count)
    }

    @Test
    fun applyIsNotOfferedWithoutADryRun() = runTest {
        val s = MassEntry().choose(Direction.ADD).type("1 Sol Ring").assign(Owner.MATT).goTo(Step.REVIEW)
        val root = mount { MassEntryPage(s, {}, {}, {}) }
        settle()
        assertTrue(root.button("Nothing to apply").disabled)
    }
}

/**
 * The keyboard, with real browser events.
 *
 * `Shortcuts` itself is tested in the shared core on both targets;
 * what these check is the wiring only the web has — that a keystroke
 * aimed at a text field does not navigate the page out from under
 * whoever is typing.
 */
class BrowserKeysTest {

    private fun key(
        name: String,
        target: org.w3c.dom.HTMLElement? = null,
        meta: Boolean = false,
        ctrl: Boolean = false,
    ): org.w3c.dom.events.KeyboardEvent {
        val e = org.w3c.dom.events.KeyboardEvent(
            "keydown",
            org.w3c.dom.events.KeyboardEventInit(key = name, metaKey = meta, ctrlKey = ctrl),
        )
        val host = target ?: (document.createElement("div") as org.w3c.dom.HTMLElement)
        document.body!!.appendChild(host)
        host.dispatchEvent(e)
        host.remove()
        return e
    }

    @Test
    fun aBareLetterNavigates() {
        val next = AppState().onBrowserKey(key("d"))
        assertNotNull(next)
        assertEquals(org.mattshoe.mtg.core.View.DECKS, next.view)
    }

    @Test
    fun theSameLetterInATextFieldDoesNot() {
        val input = document.createElement("input") as org.w3c.dom.HTMLElement
        assertEquals(null, AppState().onBrowserKey(key("d", input)))
    }

    @Test
    fun theChordStillWorksInATextField() {
        val input = document.createElement("input") as org.w3c.dom.HTMLElement
        val next = AppState().onBrowserKey(key("k", input, meta = true))
        assertNotNull(next)
        assertTrue(next.palette.open)
    }

    @Test
    fun escapeClosesWhateverIsOnTop() {
        val open = AppState().opening(org.mattshoe.mtg.core.Overlay.CHEATSHEET)
        val next = open.onBrowserKey(key("Escape"))
        assertNotNull(next)
        assertFalse(next.overlays.any)
    }

    @Test
    fun escapeWithNothingOpenIsLeftAlone() {
        assertEquals(null, AppState().onBrowserKey(key("Escape")))
    }
}

/**
 * The card grid, which is most of what the site is.
 *
 * Its Android sibling is `theLibraryShowsTheRangeAndTheRows`, which
 * checks the same facts against `LibraryScreen`.
 */
class CardGridTest {

    private val roots = mutableListOf<org.w3c.dom.HTMLElement>()

    @AfterTest
    fun cleanUp() {
        roots.forEach { it.remove() }
        roots.clear()
    }

    private fun mount(body: @Composable () -> Unit): org.w3c.dom.HTMLElement {
        val root = document.createElement("div") as org.w3c.dom.HTMLElement
        document.body!!.appendChild(root)
        roots += root
        renderComposable(root = root) { body() }
        return root
    }

    private suspend fun settle() = repeat(3) {
        Promise<Unit> { resolve, _ -> kotlinx.browser.window.requestAnimationFrame { resolve(Unit) } }.await()
    }

    private fun card(id: String?, free: Int?, price: Double?) = CardRow(
        id = 1, owner = "matt", name = "Sol Ring", nameNorm = "sol ring", face2 = null,
        layout = "normal", scryfallId = id, manaCost = "{1}", cmc = 1.0,
        typeLine = "Artifact", colorIdentity = "", rarity = "uncommon", setCode = "m3c",
        setName = "Modern Horizons 3", collectorNumber = "409", edhrecRank = null,
        releasedAt = null, finish = "nonfoil", power = null, toughness = null,
        artist = null, qty = 3, printings = 1, free = free, price = price, value = price,
    )

    @Test
    fun theArtComesOffScryfallByTheIdOnTheRow() = runTest {
        val root = mount {
            LibraryPage(Library().loaded(listOf(card("abcdef12-3456", 1, 2.5)), 1), {}, {}, {})
        }
        settle()
        val img = root.querySelector(".card-img") as org.w3c.dom.HTMLImageElement
        assertTrue(img.src.endsWith("/normal/front/a/b/abcdef12-3456.jpg"), img.src)
        assertEquals("lazy", img.getAttribute("loading"))
    }

    @Test
    fun theBadgesSayWhatIsSpareAndWhatItIsWorth() = runTest {
        val root = mount {
            LibraryPage(Library().loaded(listOf(card("abcdef12-3456", 2, 2.5)), 1), {}, {}, {})
        }
        settle()
        assertEquals("2 free", (root.querySelector(".free-badge") as org.w3c.dom.HTMLElement).textContent)
        assertEquals("$2.50", (root.querySelector(".price-badge") as org.w3c.dom.HTMLElement).textContent)
    }

    @Test
    fun aCardWithNoSpareCopySaysWhereTheyWent() = runTest {
        val root = mount {
            LibraryPage(Library().loaded(listOf(card("abcdef12-3456", 0, null)), 1), {}, {}, {})
        }
        settle()
        val badge = root.querySelector(".free-badge") as org.w3c.dom.HTMLElement
        assertEquals("in decks", badge.textContent)
        assertTrue(badge.className.contains("none"), badge.className)
        // No price is a blank badge, not a dash: the dash reads as an
        // error and most of the time it is not one.
        assertEquals("", (root.querySelector(".price-badge") as org.w3c.dom.HTMLElement).textContent)
    }

    @Test
    fun theDeckTileWearsItsCommandersArt() = runTest {
        val deck = Deck("alela", "Alela — Custom Dimir Faerie Tribal", "matt",
            "Alela, Artful Provocateur", "Five-color (WUBRG)", 3, "abcdef12-3456")
        val root = mount { DecksPage(DecksState().loaded(listOf(deck)), {}, {}) }
        settle()
        val img = root.querySelector(".deck-banner img") as org.w3c.dom.HTMLImageElement
        assertTrue(img.src.endsWith("/art_crop/front/a/b/abcdef12-3456.jpg"), img.src)
        // The name is the tile-width half, and the pips are the five
        // colours rather than one per letter of the sentence.
        assertEquals("Alela", (root.querySelector(".deck-name") as org.w3c.dom.HTMLElement).textContent)
        assertEquals(5, root.querySelectorAll(".mana .ms").length)
    }
}

/**
 * The nav, which went missing on a phone.
 *
 * It borrowed `.tabs` from the hand-written page, and that class is
 * `display: none` below 720px — the header's own JavaScript opens it as
 * a drawer, and there is no header JavaScript any more. So the site
 * shipped with no navigation on the device most used to read it.
 */
class NavTest {

    private val roots = mutableListOf<org.w3c.dom.HTMLElement>()

    @AfterTest
    fun cleanUp() {
        roots.forEach { it.remove() }
        roots.clear()
    }

    private suspend fun settle() = repeat(3) {
        Promise<Unit> { resolve, _ -> kotlinx.browser.window.requestAnimationFrame { resolve(Unit) } }.await()
    }

    private fun mount(state: AppState): org.w3c.dom.HTMLElement {
        val root = document.createElement("div") as org.w3c.dom.HTMLElement
        document.body!!.appendChild(root)
        roots += root
        renderComposable(root = root) { AppNav(state) {} }
        return root
    }

    @Test
    fun theNavDoesNotBorrowTheClassTheHeadersOwnScriptOpens() = runTest {
        // `.tabs` is the hand-written header's, and it is opened by
        // the header's own JavaScript, which the shared build does not
        // run. Borrowing it left the phone with no navigation at all.
        val root = mount(AppState())
        settle()
        val nav = root.querySelector("nav") as org.w3c.dom.HTMLElement
        assertFalse(nav.className.split(" ").contains("tabs"), nav.className)
        assertTrue(nav.className.contains("app-nav"), nav.className)
    }

    @Test
    fun itIsOneHamburgerAndNothingElse() = runTest {
        val root = mount(AppState())
        settle()
        val nav = root.querySelector("nav") as org.w3c.dom.HTMLElement
        // A display:none element has no box at all, so a zero height
        // and width is the shape of the bug this is here for.
        val box = nav.getBoundingClientRect()
        assertTrue(box.width > 0 && box.height > 0, "the nav is not rendered: $box")
        assertEquals(1, root.querySelectorAll("button.nav-burger").length, "no hamburger")
        // Four views while locked, plus Unlock. Nothing loose beside
        // the burger.
        assertEquals(5, root.querySelectorAll(".app-menu button").length)
    }

    @Test
    fun theHamburgerOpensAndClosesTheMenu() = runTest {
        val root = mount(AppState())
        settle()
        val burger = root.querySelector("button.nav-burger") as org.w3c.dom.HTMLElement
        fun menu() = root.querySelector("div.app-menu") as org.w3c.dom.HTMLElement
        assertFalse(menu().className.contains("open"))
        assertEquals("false", burger.getAttribute("aria-expanded"))

        burger.click()
        settle()
        assertTrue(menu().className.contains("open"), "the menu did not open")
        assertEquals("true", burger.getAttribute("aria-expanded"))

        burger.click()
        settle()
        assertFalse(menu().className.contains("open"), "the menu did not close again")
    }

    @Test
    fun aPressAnywhereElseClosesIt() = runTest {
        val root = mount(AppState())
        settle()
        (root.querySelector("button.nav-burger") as org.w3c.dom.HTMLElement).click()
        settle()
        val backdrop = root.querySelector("div.nav-backdrop") as? org.w3c.dom.HTMLElement
            ?: error("nothing catches a press outside the menu")
        backdrop.click()
        settle()
        assertFalse(
            (root.querySelector("div.app-menu") as org.w3c.dom.HTMLElement).className.contains("open"),
        )
    }

    @Test
    fun pickingTheTabYouAreAlreadyOnStillClosesTheMenu() = runTest {
        // The old header closed the menu on a route change, so tapping
        // the tab you were already on left it sitting over the page.
        val root = mount(AppState())
        settle()
        (root.querySelector("button.nav-burger") as org.w3c.dom.HTMLElement).click()
        settle()
        val items = root.querySelectorAll(".app-menu button")
        val here = (0 until items.length)
            .map { items[it] as org.w3c.dom.HTMLElement }
            .first { it.className.contains("on") }
        here.click()
        settle()
        assertFalse(
            (root.querySelector("div.app-menu") as org.w3c.dom.HTMLElement).className.contains("open"),
        )
    }

    @Test
    fun theHeaderSaysWhatYouAreLookingAt() = runTest {
        // It used to be a heading floating at the top of every page,
        // saying the same thing the address bar already said.
        assertEquals("Library", title(mount(AppState())))
        assertEquals("Decks", title(mount(AppState().navigate(org.mattshoe.mtg.core.View.DECKS))))
        assertEquals("Stats", title(mount(AppState().navigate(org.mattshoe.mtg.core.View.STATS))))
    }

    @Test
    fun andAnOpenDeckPutsItsOwnNameThere() = runTest {
        val decks = org.mattshoe.mtg.core.DecksState()
            .loaded(listOf(org.mattshoe.mtg.core.Deck("a", "Feather Storm", "matt", null, "RW", 3, null)))
            .opened("a", emptyList())
        val s = AppState().navigate(org.mattshoe.mtg.core.Route(org.mattshoe.mtg.core.View.DECKS, "a")).copy(decks = decks)
        assertEquals("Feather Storm", title(mount(s)))
    }

    @Test
    fun theMarkIsTheSameSizeAsTheHamburgerBesideIt() = runTest {
        Stylesheet.load()
        val root = mount(AppState())
        settle()
        if (!Stylesheet.applied()) return@runTest
        val mark = (root.querySelector("img.brand-mark") as org.w3c.dom.HTMLElement).getBoundingClientRect()
        val burger = (root.querySelector("button.nav-burger") as org.w3c.dom.HTMLElement).getBoundingClientRect()
        assertTrue(
            kotlin.math.abs(mark.height - burger.height) <= 1,
            "the mark is ${mark.height}px against a ${burger.height}px button",
        )
    }

    private fun title(root: org.w3c.dom.HTMLElement) =
        (root.querySelector(".topbar-title") as? org.w3c.dom.HTMLElement)?.textContent?.trim()

    @Test
    fun theAdminHalfIsItsOwnSectionWithTheLockInIt() = runTest {
        val root = mount(AppState())
        settle()
        assertEquals(1, root.querySelectorAll(".app-menu-sep").length, "no rule between the halves")
        assertEquals(
            "Admin",
            (root.querySelector(".app-menu-group") as org.w3c.dom.HTMLElement).textContent?.trim(),
        )
        assertEquals(1, root.querySelectorAll("button.app-lock").length, "the lock is not in the menu")
    }

    @Test
    fun theGatedViewsSitUnderTheRuleAndTheOthersAbove() = runTest {
        val root = mount(AppState(admin = org.mattshoe.mtg.core.Admin("t")))
        settle()
        val items = root.querySelectorAll(".app-menu > *")
        val labels = (0 until items.length).map { (items[it] as org.w3c.dom.HTMLElement) }
        val rule = labels.indexOfFirst { it.className.contains("app-menu-sep") }
        assertTrue(rule > 0, "no rule in the menu")
        val above = labels.take(rule).mapNotNull { it.textContent?.trim() }
        val below = labels.drop(rule).mapNotNull { it.textContent?.trim() }
        assertEquals(listOf("Library", "Decks", "Stats", "Query"), above)
        assertTrue(below.contains("Mass Entry"), below.toString())
        assertTrue(below.contains("Server Logs"), below.toString())
        assertTrue(below.contains("Lock"), below.toString())
    }

    @Test
    fun theOwnerPickerIsASegmentedControlNotThreeChoiceCards() = runTest {
        val root = document.createElement("div") as org.w3c.dom.HTMLElement
        document.body!!.appendChild(root)
        roots += root
        renderComposable(root = root) { FilterPanel(Filters()) {} }
        settle()
        (root.querySelector("details[data-facet=collection] summary") as org.w3c.dom.HTMLElement).click()
        settle()
        // `owner-opt` is the wizard's one-big-decision styling: 130px
        // minimum each, which wrapped onto two lines on a phone.
        assertEquals(0, root.querySelectorAll(".owner-opt").length)
        assertTrue(root.querySelectorAll(".seg button").length >= 3)
    }
}
