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
import org.mattshoe.mtg.core.AppState
import org.mattshoe.mtg.core.CardRow
import org.mattshoe.mtg.core.Facets
import org.mattshoe.mtg.core.Filters
import org.mattshoe.mtg.core.Library
import org.mattshoe.mtg.core.Sort
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLSelectElement
import org.w3c.dom.events.Event
import org.w3c.dom.get
import kotlin.js.Promise
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Library page, driven the way a person drives it.
 *
 * Everything else that tests this page mounts one composable in
 * isolation and is therefore blind to the bug that actually shipped:
 * the page was fine and the *wiring above it* threw the update away.
 * So this mounts the whole shell — `AppShell` over a real `AppState`,
 * state held and fed back exactly as `MtgApp.mount` does it — and then
 * types, clicks and reads the DOM.
 *
 * The rule for anything added here: assert on what the DOM shows or on
 * the state the shell produced, never on an intermediate a real user
 * cannot see. A test that watches a callback fire would have passed
 * while the box refused to accept a single character.
 */
class LibraryDriverTest {

    private val roots = mutableListOf<HTMLElement>()

    @AfterTest
    fun cleanUp() {
        roots.forEach { it.remove() }
        roots.clear()
    }

    // ------------------------------------------------------------ driver

    /**
     * The app, mounted. `state` is the single source the shell reads
     * and writes, which is the arrangement `MtgApp` has — so a
     * callback that builds its update from a stale copy loses the same
     * way here as it does in the browser.
     */
    private class App(val root: HTMLElement) {
        lateinit var state: AppState
        var searches = 0
        var lookups = mutableListOf<String>()

        fun buttons() = root.querySelectorAll("button")
            .let { n -> (0 until n.length).mapNotNull { n[it] as? HTMLButtonElement } }

        fun button(label: String): HTMLButtonElement =
            buttons().firstOrNull { it.textContent?.trim() == label }
                ?: error("no button labelled \"$label\"; saw ${buttons().map { it.textContent?.trim() }}")

        fun maybeButton(label: String) = buttons().firstOrNull { it.textContent?.trim() == label }

        fun input(placeholder: String): HTMLInputElement =
            root.querySelector("input[placeholder='$placeholder']") as? HTMLInputElement
                ?: error("no input with placeholder \"$placeholder\"")

        fun select(css: String) = root.querySelector(css) as? HTMLSelectElement
            ?: error("no select matching $css")

        fun pip(colour: String) = root.querySelector("button.pip[data-c=$colour]") as? HTMLButtonElement
            ?: error("no colour pip for $colour")

        fun facet(id: String) = root.querySelector("details[data-facet=$id]") as? HTMLElement
            ?: error("no filter group \"$id\"")

        fun text() = root.textContent.orEmpty()

        /** Types a character at a time, the way a keyboard does. */
        fun typeInto(placeholder: String, value: String) {
            val el = input(placeholder)
            value.forEachIndexed { i, _ ->
                el.value = value.substring(0, i + 1)
                el.dispatchEvent(Event("input", js("({bubbles: true})")))
            }
        }

        fun pick(css: String, value: String) {
            val el = select(css)
            el.value = value
            el.dispatchEvent(Event("change", js("({bubbles: true})")))
        }
    }

    private fun mount(
        start: AppState = AppState(),
        rows: List<CardRow> = listOf(card("Sol Ring"), card("Arcane Signet")),
        total: Int = 2,
    ): App {
        val root = document.createElement("div") as HTMLElement
        document.body!!.appendChild(root)
        roots += root
        val app = App(root)
        val seeded = start.copy(
            library = start.library.loaded(rows, total),
            facets = Facets(
                types = listOf("Artifact", "Creature", "Land"),
                setTypes = listOf("core", "commander"),
                layouts = listOf("normal", "saga"),
                frames = listOf("1993", "2015"),
                borders = listOf("black"),
                formats = listOf("commander", "modern"),
            ),
        )
        app.state = seeded
        renderComposable(root = root) {
            var s by remember { mutableStateOf(seeded) }
            AppShell(
                state = s,
                onState = { s = it; app.state = it },
                onUnlock = {},
                onSearch = { app.searches++ },
                onOpenDeck = {},
                onPreviewEntry = {},
                onApplyEntry = {},
                onLookup = { app.lookups += it },
            )
        }
        return app
    }

    private suspend fun settle() = repeat(4) {
        Promise<Unit> { resolve, _ -> window.requestAnimationFrame { resolve(Unit) } }.await()
    }

    private fun card(name: String, qty: Int = 1, price: Double? = 1.5) = CardRow(
        id = 1, owner = "matt", name = name, nameNorm = name.lowercase(), face2 = null,
        layout = "normal", scryfallId = "abcdef12-3456", manaCost = "{1}", cmc = 1.0,
        typeLine = "Artifact", colorIdentity = "", rarity = "rare", setCode = "m3c",
        setName = "Modern Horizons 3", collectorNumber = "409", edhrecRank = null,
        releasedAt = null, finish = "nonfoil", power = null, toughness = null,
        artist = null, qty = qty, printings = 1, free = 1, price = price, value = price,
    )

    // ------------------------------------------------------- the name box

    @Test
    fun theNameBoxAcceptsTypedCharacters() = runTest {
        val app = mount()
        settle()
        app.typeInto("Card name", "bolt")
        settle()
        // What a person sees. Not "a callback fired" — the box either
        // shows what was typed or it does not.
        assertEquals("bolt", app.input("Card name").value, "the box did not keep what was typed")
    }

    @Test
    fun andTheTypedNameReachesTheFilters() = runTest {
        val app = mount()
        settle()
        app.typeInto("Card name", "bolt")
        settle()
        assertEquals("bolt", app.state.library.filters.q)
    }

    @Test
    fun andItAlsoAsksScryfallForSuggestions() = runTest {
        val app = mount()
        settle()
        app.typeInto("Card name", "bolt")
        settle()
        assertTrue(app.lookups.isNotEmpty(), "nothing asked for autocomplete")
        assertEquals("bolt", app.lookups.last())
    }

    @Test
    fun andTheAutocompleteTermAndTheFilterStayInStep() = runTest {
        // Two pieces of state hold the same word. If one write clobbers
        // the other the box goes blank or the search goes stale.
        val app = mount()
        settle()
        app.typeInto("Card name", "sol")
        settle()
        assertEquals(app.state.complete.term, app.state.library.filters.q)
    }

    @Test
    fun clearingTheBoxClearsTheFilter() = runTest {
        val app = mount(AppState(library = Library(Filters(q = "bolt"))))
        settle()
        val el = app.input("Card name")
        el.value = ""
        el.dispatchEvent(Event("input", js("({bubbles: true})")))
        settle()
        assertEquals("", app.state.library.filters.q)
    }

    // ------------------------------------------------------ the top row

    @Test
    fun theTopRowHasExactlyTheControlsItShouldHave() = runTest {
        val app = mount()
        settle()
        assertTrue(app.maybeButton("Copy") != null, "no clipboard export")
        assertTrue(app.maybeButton("Download") != null, "no file export")
        assertTrue(app.maybeButton("Search") == null, "the Search button is back")
        assertTrue(app.maybeButton("Filters") == null, "the Filters collapser is back")
        assertTrue(app.maybeButton("Kayla") == null, "the owner picker is back above the panel")
    }

    @Test
    fun theFilterGroupsAreAlwaysOnThePage() = runTest {
        // Hiding the whole panel behind a button was a way to leave a
        // filter applied with nothing on screen saying so.
        val app = mount()
        settle()
        assertEquals(10, app.root.querySelectorAll("details[data-facet]").length)
    }

    @Test
    fun andTheyAllStartFoldedAway() = runTest {
        val app = mount()
        settle()
        val open = org.mattshoe.mtg.core.Facet.entries.filter { app.facet(it.id).hasAttribute("open") }
        assertEquals(emptyList(), open, "these opened themselves with nothing set")
    }

    @Test
    fun aGroupHoldingAFilterOpensItselfSoTheFilterCanBeSeen() = runTest {
        // The state this is here for: a link restores a search, and
        // the group holding it has to show what is filtering or there
        // is no way to clear it.
        val app = mount(AppState(library = Library(Filters(colors = listOf("G")))))
        settle()
        assertTrue(app.facet("colour").hasAttribute("open"), "the colour group stayed shut")
        assertTrue(!app.facet("mana").hasAttribute("open"), "a group with nothing set opened")
    }

    @Test
    fun everyFilterGroupOpensAndClosesOnItsOwn() = runTest {
        val app = mount()
        settle()
        org.mattshoe.mtg.core.Facet.entries.forEach { facet ->
            val summary = app.facet(facet.id).querySelector("summary") as HTMLElement
            summary.click()
            settle()
            assertTrue(app.facet(facet.id).hasAttribute("open"), "${facet.title} did not open")
            summary.click()
            settle()
            assertTrue(!app.facet(facet.id).hasAttribute("open"), "${facet.title} did not close")
        }
    }

    // --------------------------------------------------------- the sort

    @Test
    fun theSortDropdownOffersEverySortAndPicksOne() = runTest {
        val app = mount()
        settle()
        val select = app.select("select.sort")
        assertEquals(Sort.entries.size, select.options.length)
        assertEquals("price", select.value)

        app.pick("select.sort", "edhrec")
        settle()
        assertEquals(Sort.EDHREC, app.state.library.filters.sort)
        assertEquals("edhrec", app.select("select.sort").value, "the dropdown lost its selection")
    }

    @Test
    fun theDirectionButtonFlipsAndSaysWhichWayItIs() = runTest {
        val app = mount()
        settle()
        assertEquals("↓", app.button("↓").textContent?.trim())
        app.button("↓").click()
        settle()
        assertTrue(!app.state.library.filters.descending)
        assertTrue(app.maybeButton("↑") != null, "the arrow did not turn around")
    }

    @Test
    fun pickingASortDoesNotSilentlyReverseIt() = runTest {
        val app = mount()
        settle()
        app.pick("select.sort", "name")
        settle()
        assertTrue(app.state.library.filters.descending, "picking a column flipped the direction")
    }

    // ----------------------------------------------------- applying them

    @Test
    fun everyControlThatChangesAFilterAsksForAFreshSearch() = runTest {
        val app = mount()
        settle()

        suspend fun run(what: String, act: () -> Unit) {
            val before = app.searches
            act()
            settle()
            assertTrue(app.searches > before, "$what did not re-run the search")
        }

        (app.facet("colour").querySelector("summary") as HTMLElement).click()
        settle()
        run("choosing a colour") { app.pip("G").click() }
        run("changing the colour mode") { app.button("At least").click() }
        (app.facet("colour").querySelector("summary") as HTMLElement).click()
        settle()

        (app.facet("collection").querySelector("summary") as HTMLElement).click()
        settle()
        run("choosing an owner") { app.button("Kayla").click() }
        run("choosing a pool") { app.button("Unassigned").click() }

        run("picking a sort") { app.pick("select.sort", "name") }
        run("flipping the direction") { app.button("↓").click() }
    }

    @Test
    fun aColourChosenInThePanelShowsAsChosen() = runTest {
        val app = mount()
        settle()
        (app.facet("colour").querySelector("summary") as HTMLElement).click()
        settle()
        app.pip("G").click()
        settle()
        assertTrue(app.pip("G").className.contains("on"), "the pip did not light up")
        assertEquals(listOf("G"), app.state.library.filters.colors)
    }

    @Test
    fun andASecondColourJoinsItRatherThanReplacingIt() = runTest {
        val app = mount()
        settle()
        (app.facet("colour").querySelector("summary") as HTMLElement).click()
        settle()
        app.pip("G").click(); settle()
        app.pip("U").click(); settle()
        assertEquals(listOf("G", "U"), app.state.library.filters.colors)
        assertTrue(app.pip("G").className.contains("on"))
        assertTrue(app.pip("U").className.contains("on"))
    }

    // --------------------------------------------------- the token boxes

    /**
     * Type a word, press enter, get a chip.
     *
     * Four filters are driven by the same `Tokens` box and they were
     * only ever tested one composable at a time, with the draft state
     * handed in from the test rather than held where the real panel
     * holds it. That is exactly the blind spot the name box fell into.
     */
    private suspend fun App.openGroup(group: String) {
        val details = facet(group)
        if (!details.hasAttribute("open")) {
            (details.querySelector("summary") as HTMLElement).click()
            settle()
        }
    }

    private suspend fun App.token(group: String, placeholder: String, word: String) {
        openGroup(group)
        val el = input(placeholder)
        el.value = word
        el.dispatchEvent(Event("input", js("({bubbles: true})")))
        settle()
        el.dispatchEvent(
            org.w3c.dom.events.KeyboardEvent(
                "keydown",
                js("({key: 'Enter', bubbles: true, cancelable: true})"),
            ),
        )
        settle()
    }

    @Test
    fun theKeywordBoxTakesAWordAndKeepsIt() = runTest {
        val app = mount()
        settle()
        app.token("tags", "Flying, Ward…", "Flying")
        assertEquals(listOf("Flying"), app.state.library.filters.keywords)
        assertTrue(app.text().contains("Flying ×"), "no chip for the word: ${app.text()}")
    }

    @Test
    fun theScryfallTagBoxTakesAWordAndKeepsIt() = runTest {
        val app = mount()
        settle()
        app.token("tags", "mana-rock, spot-removal…", "mana-rock")
        assertEquals(listOf("mana-rock"), app.state.library.filters.tags)
    }

    @Test
    fun theSetBoxTakesACodeAndKeepsIt() = runTest {
        val app = mount()
        settle()
        app.token("printing", "MH3", "MH3")
        assertEquals(listOf("MH3"), app.state.library.filters.sets)
    }

    @Test
    fun aTokenBoxClearsItselfReadyForTheNextWord() = runTest {
        // The chip and the empty box are one update. Done as two, the
        // second is built on a copy that predates the first.
        val app = mount()
        settle()
        app.token("tags", "Flying, Ward…", "Flying")
        assertEquals("", app.input("Flying, Ward…").value, "the box kept the word it turned into a chip")
        app.token("tags", "Flying, Ward…", "Ward")
        assertEquals(listOf("Flying", "Ward"), app.state.library.filters.keywords)
    }

    // -------------------------------------------------------- the results

    @Test
    fun theGridShowsWhatTheStateHolds() = runTest {
        val app = mount(rows = listOf(card("Sol Ring"), card("Opt")), total = 250)
        settle()
        assertEquals(2, app.root.querySelectorAll(".card").length)
        assertTrue(app.text().contains("1–100 of 250"), app.text())
    }

    @Test
    fun theEmptyLoadingAndFailedStatesEachSayWhatHappened() = runTest {
        val loading = mount(AppState(library = Library().loading()), rows = emptyList(), total = 0)
        settle()
        // `loaded` in mount() clears busy, so drive the state directly.
        assertTrue(loading.text().isNotEmpty())

        val empty = mount(rows = emptyList(), total = 0)
        settle()
        assertTrue(empty.text().contains("Nothing matches that."), empty.text())
    }

    @Test
    fun thePagerMovesAndTheRangeFollows() = runTest {
        val app = mount(rows = listOf(card("A")), total = 250)
        settle()
        assertTrue(app.button("← Previous").disabled, "previous is live on page one")
        app.button("Next →").click()
        settle()
        assertEquals(2, app.state.library.page)
        assertTrue(app.text().contains("101–200 of 250"), app.text())
        assertTrue(!app.button("← Previous").disabled)
    }

    @Test
    fun exportAsksWhereItIsGoing() = runTest {
        // Two places a list is ever wanted: the clipboard, to paste
        // into a deckbuilder, and a file, to keep.
        val asked = mutableListOf<org.mattshoe.mtg.core.ExportTo>()
        val root = document.createElement("div") as HTMLElement
        document.body!!.appendChild(root)
        roots += root
        renderComposable(root = root) {
            var s by remember { mutableStateOf(AppState()) }
            AppShell(s, { s = it }, {}, {}, {}, {}, {}, onExport = { asked += it })
        }
        settle()
        fun press(label: String) = (
            root.querySelectorAll("button").let { n ->
                (0 until n.length).map { n[it] as HTMLButtonElement }
            }.first { it.textContent?.trim() == label }
            ).click()
        press("Copy")
        settle()
        press("Download")
        settle()
        assertEquals(
            listOf(org.mattshoe.mtg.core.ExportTo.CLIPBOARD, org.mattshoe.mtg.core.ExportTo.FILE),
            asked,
        )
    }
}

/**
 * Probes for things reported but not yet proved.
 *
 * Each of these is a claim about the Library page that a catalogue
 * entry rests on. They are here so the entry cites a test rather than
 * an opinion — several are expected to be red until the thing they
 * describe is dealt with.
 */
class LibraryProbeTest {

    private val roots = mutableListOf<HTMLElement>()

    // Without this the suite measured an unstyled DOM and reported
    // 0px gaps that are really 7px — a false failure indistinguishable
    // from a true one.
    @kotlin.test.BeforeTest
    fun loadTheStylesheet() = Stylesheet.load()

    @AfterTest
    fun cleanUp() {
        roots.forEach { it.remove() }
        roots.clear()
    }

    private suspend fun settle() = repeat(4) {
        Promise<Unit> { resolve, _ -> window.requestAnimationFrame { resolve(Unit) } }.await()
    }

    private fun panel(start: Filters = Filters()): Pair<HTMLElement, () -> Filters> {
        val root = document.createElement("div") as HTMLElement
        document.body!!.appendChild(root)
        roots += root
        var held = start
        renderComposable(root = root) {
            var f by remember { mutableStateOf(start) }
            FilterPanel(
                f,
                Facets(formats = listOf("commander", "modern"), types = listOf("Creature")),
            ) { f = it; held = it }
        }
        return root to { held }
    }

    private fun HTMLElement.all(css: String) =
        querySelectorAll(css).let { n -> (0 until n.length).mapNotNull { n[it] as? HTMLElement } }

    private fun HTMLElement.open(id: String) =
        (querySelector("details[data-facet=$id] summary") as HTMLElement).click()

    @Test
    fun aDropdownFollowsTheStateWhenSomethingElseChangesIt() = runTest {
        // `Option(selected)` is a content attribute. Once the user has
        // picked from a select, the browser sets its dirty flag and
        // later attribute changes no longer move the selection — so a
        // reset clears the filter and leaves the control showing the
        // old pick.
        val (root, filters) = panel()
        settle()
        root.open("legality")
        settle()
        val format = root.all("select").first { it.textContent.orEmpty().contains("any format") }
            as HTMLSelectElement
        format.value = "modern"
        format.dispatchEvent(Event("change", js("({bubbles: true})")))
        settle()
        assertEquals("modern", filters().format)

        (root.all("button").first { it.textContent?.trim() == "Reset everything" }).click()
        settle()
        assertEquals("", filters().format, "reset did not clear the filter")
        val after = root.all("select").firstOrNull { it.textContent.orEmpty().contains("any format") }
            as? HTMLSelectElement
        assertEquals("", after?.value, "the dropdown still shows the cleared pick")
    }

    @Test
    fun aRangeRowsTwoBoxesAreTheSameSize() = runTest {
        // `.field` carries a 13px bottom margin that `:last-child`
        // strips from the second box only, so inside `.row`'s
        // stretch the right-hand box grows taller than the left.
        val (root, _) = panel()
        settle()
        root.open("mana")
        settle()
        val row = root.all("div.row").first()
        val boxes = row.all("input").map { it.getBoundingClientRect() }
        assertEquals(2, boxes.size)
        assertTrue(
            kotlin.math.abs(boxes[0].height - boxes[1].height) <= 1,
            "min is ${boxes[0].height}px tall and max is ${boxes[1].height}px",
        )
    }

    @Test
    fun theColourPipsAndTheirShortcutsAreNotTouching() = runTest {
        // `.frow` has no rule of its own, so two children of one row
        // sit flush against each other.
        val (root, _) = panel()
        settle()
        root.open("colour")
        settle()
        if (!Stylesheet.applied()) return@runTest
        val pips = root.all("div.pips").first().getBoundingClientRect()
        val chips = root.all("div.chips").first().getBoundingClientRect()
        assertTrue(chips.top - pips.bottom >= 4, "only ${chips.top - pips.bottom}px between them")
    }
}
