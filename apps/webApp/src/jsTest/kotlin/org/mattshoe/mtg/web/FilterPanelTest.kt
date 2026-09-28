package org.mattshoe.mtg.web

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.browser.document
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import org.jetbrains.compose.web.renderComposable
import org.mattshoe.mtg.core.ColorMode
import org.mattshoe.mtg.core.Facets
import org.mattshoe.mtg.core.Filters
import org.mattshoe.mtg.core.Flag
import org.mattshoe.mtg.core.Pool
import org.mattshoe.mtg.core.Tri
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The filter panel, clicked, in a real browser.
 *
 * Every one of these is something I claimed worked and had not pressed.
 * The panel is the screen — the rest of the Library is a grid around it
 * — so it gets its own suite, and the suite drives it the way a person
 * does: click the control, then look at the `Filters` that came out.
 */
class FilterPanelTest {

    private val roots = mutableListOf<HTMLElement>()

    @AfterTest
    fun cleanUp() {
        roots.forEach { it.remove() }
        roots.clear()
    }

    private suspend fun settle() = repeat(3) {
        Promise<Unit> { resolve, _ -> kotlinx.browser.window.requestAnimationFrame { resolve(Unit) } }.await()
    }

    private val facets = Facets(
        types = listOf("Artifact", "Creature", "Land"),
        setTypes = listOf("core", "commander"),
        layouts = listOf("normal", "saga"),
        frames = listOf("1993", "2015"),
        borders = listOf("black", "borderless"),
        formats = listOf("commander", "modern"),
        decks = listOf(org.mattshoe.mtg.core.DeckRef2("alela", "Alela", "matt")),
    )

    /**
     * Mounts the panel wired to real state, the way the page wires it,
     * and opens the group under test — everything is folded away to
     * start, so that is the first thing a person does too.
     */
    private fun mount(start: Filters = Filters(), open: String? = null): Panel {
        val root = document.createElement("div") as HTMLElement
        document.body!!.appendChild(root)
        roots += root
        var held = start
        renderComposable(root = root) {
            var f by remember { mutableStateOf(start) }
            FilterPanel(f, facets) { f = it; held = it }
        }
        val panel = Panel(root) { held }
        open?.let { panel.fold(it) }
        return panel
    }

    private class Panel(val root: HTMLElement, val filters: () -> Filters) {

        fun buttons() = root.querySelectorAll("button")
            .let { n -> (0 until n.length).mapNotNull { n[it] as? HTMLButtonElement } }

        fun button(label: String) = buttons().first { it.textContent?.trim() == label }

        fun has(label: String) = buttons().any { it.textContent?.trim() == label }

        fun pip(colour: String) = root.querySelector("button.pip[data-c=$colour]") as HTMLButtonElement

        fun group(id: String) = root.querySelector("details[data-facet=$id]") as HTMLElement

        fun open(id: String) = group(id).hasAttribute("open")

        /** Clicks the header, which is the whole target on the real page. */
        fun fold(id: String) = (group(id).querySelector("summary") as HTMLElement).click()

        fun field(placeholder: String) =
            root.querySelector("input[placeholder='$placeholder']") as HTMLInputElement

        fun type(placeholder: String, value: String) {
            val input = field(placeholder)
            input.value = value
            input.dispatchEvent(Event("input", js("({bubbles: true})")))
        }

        fun check(label: String) {
            val boxes = root.querySelectorAll("label.check")
            (0 until boxes.length)
                .map { boxes[it] as HTMLElement }
                .first { it.textContent?.trim() == label }
                .let { (it.querySelector("input") as HTMLInputElement).click() }
        }

        fun select(index: Int, value: String) {
            val el = root.querySelectorAll("select")[index] as HTMLSelectElement
            el.value = value
            el.dispatchEvent(Event("change", js("({bubbles: true})")))
        }

        fun text() = root.textContent.orEmpty()
    }

    // ------------------------------------------------------------ colour

    @Test
    fun coloursAccumulateRatherThanReplacingEachOther() = runTest {
        val p = mount(open = "colour")
        settle()
        p.pip("W").click(); settle()
        p.pip("U").click(); settle()
        p.pip("B").click(); settle()
        assertEquals(listOf("W", "U", "B"), p.filters().colors)
    }

    @Test
    fun clickingAColourTwiceTakesItBackOff() = runTest {
        val p = mount(open = "colour")
        settle()
        p.pip("G").click(); settle()
        p.pip("G").click(); settle()
        assertEquals(emptyList(), p.filters().colors)
    }

    @Test
    fun aChosenColourIsMarkedSoTheStylesheetCanPaintIt() = runTest {
        val p = mount(open = "colour")
        settle()
        p.pip("R").click(); settle()
        // `.pip[data-c=R].on` is what turns it red. Without the
        // attribute there is nothing for the rule to hook onto and the
        // selection is invisible, which is how it shipped.
        assertEquals("R", p.pip("R").getAttribute("data-c"))
        assertTrue(p.pip("R").className.contains("on"), p.pip("R").className)
        assertFalse(p.pip("G").className.contains("on"))
    }

    @Test
    fun andTheSelectorTheStylesheetUsesActuallyMatches() = runTest {
        // The markup being right is half of it. `app.css` paints a
        // chosen colour with `.pip[data-c=W].on`, and the last thing
        // that shipped had an `.on` rule that lost to `.btn.ghost`
        // further down the file — the selection was real and
        // invisible, which is indistinguishable from broken. This
        // injects the stylesheet's own selector and checks it lands.
        val style = document.createElement("style") as HTMLElement
        style.textContent = ".pip[data-c=W].on { background-color: rgb(1, 2, 3); }"
        document.head!!.appendChild(style)

        val p = mount(open = "colour")
        settle()
        p.pip("W").click()
        settle()
        val painted = kotlinx.browser.window.getComputedStyle(p.pip("W")).backgroundColor
        val unpainted = kotlinx.browser.window.getComputedStyle(p.pip("G")).backgroundColor
        style.remove()

        assertEquals("rgb(1, 2, 3)", painted, "the stylesheet's pip selector does not match the markup")
        assertFalse(unpainted == "rgb(1, 2, 3)", "an unchosen colour must not be painted")
    }

    @Test
    fun colourlessIsItsOwnPipAndNotASixthColour() = runTest {
        val p = mount(open = "colour")
        settle()
        p.pip("C").click(); settle()
        assertEquals(listOf("C"), p.filters().colors)
    }

    @Test
    fun allFourModesAreOfferedAndStick() = runTest {
        val p = mount(open = "colour")
        settle()
        ColorMode.entries.forEach { mode ->
            p.button(mode.label).click(); settle()
            assertEquals(mode, p.filters().colorMode)
        }
    }

    @Test
    fun theShortcutsSetWholeSelections() = runTest {
        val p = mount(open = "colour")
        settle()
        p.button("all five").click(); settle()
        assertEquals(listOf("W", "U", "B", "R", "G"), p.filters().colors)
        p.button("clear").click(); settle()
        assertEquals(emptyList(), p.filters().colors)
        p.button("colourless").click(); settle()
        assertEquals(listOf("C"), p.filters().colors)
        assertEquals(ColorMode.EXACTLY, p.filters().colorMode)
    }

    @Test
    fun producesIsItsOwnSetOfPips() = runTest {
        val p = mount(open = "colour")
        settle()
        val pips = p.root.querySelectorAll("button.pip[data-c=G]")
        assertEquals(2, pips.length, "colours and produces each get a row")
        (pips[1] as HTMLButtonElement).click()
        settle()
        assertEquals(listOf("G"), p.filters().produces)
        assertEquals(emptyList(), p.filters().colors)
    }

    // --------------------------------------------------------- the folds

    @Test
    fun everythingIsFoldedAwayToStart() = runTest {
        val p = mount()
        settle()
        org.mattshoe.mtg.core.Facet.entries.forEach {
            assertFalse(p.open(it.id), "${it.title} should start closed")
        }
    }

    @Test
    fun eachGroupFoldsOnItsOwn() = runTest {
        val p = mount()
        settle()
        p.fold("colour"); settle()
        p.fold("mana"); settle()
        assertTrue(p.open("mana"), "opening one group")
        assertTrue(p.open("colour"), "and it must not close another")

        p.fold("colour"); settle()
        assertFalse(p.open("colour"))
        assertTrue(p.open("mana"), "closing one must not close another")
    }

    @Test
    fun aClosedGroupDoesNotRenderItsControls() = runTest {
        val p = mount()
        settle()
        assertTrue(p.has("Reset everything"), "sanity: the panel rendered")
        assertFalse(p.text().contains("Mana cost contains"))
        p.fold("mana"); settle()
        assertTrue(p.text().contains("Mana cost contains"))
    }

    @Test
    fun aGroupWithSomethingSetSaysHowMany() = runTest {
        val p = mount(Filters(cmcMin = "2", cmcMax = "4", manaCost = "{G}"))
        settle()
        val pill = p.group("mana").querySelector(".count-pill") as HTMLElement
        assertEquals("3", pill.textContent)
        // And it opens itself, so an arriving link shows its work.
        assertTrue(p.open("mana"))
    }

    @Test
    fun aGroupWithNothingSetHasNoBadge() = runTest {
        val p = mount()
        settle()
        assertEquals(null, p.group("mana").querySelector(".count-pill"))
    }

    // ------------------------------------------------------- every field

    @Test
    fun theCollectionGroupWritesItsFields() = runTest {
        val p = mount(open = "collection")
        settle()
        p.button("Kayla").click(); settle()
        assertEquals("kayla", p.filters().owner)

        p.button("Unassigned").click(); settle()
        assertEquals(Pool.FREE, p.filters().pool)

        p.type("any", "2"); settle()
        assertEquals("2", p.filters().freeMin)
    }

    @Test
    fun theRangesWriteBothEnds() = runTest {
        val p = mount(open = "mana")
        settle()
        // The mana value range is the first one inside the mana group.
        val group = p.group("mana")
        (group.querySelector("input[placeholder=min]") as HTMLInputElement).let {
            it.value = "3"
            it.dispatchEvent(Event("input", js("({bubbles: true})")))
        }
        settle()
        assertEquals("3", p.filters().cmcMin)
        (group.querySelector("input[placeholder=max]") as HTMLInputElement).let {
            it.value = "6"
            it.dispatchEvent(Event("input", js("({bubbles: true})")))
        }
        settle()
        assertEquals("6", p.filters().cmcMax)
    }

    @Test
    fun theTypeChecksAreMultiSelect() = runTest {
        val p = mount()
        settle()
        p.fold("type"); settle()
        p.check("Creature"); settle()
        p.check("Artifact"); settle()
        assertEquals(listOf("Creature", "Artifact"), p.filters().types)
        p.check("Creature"); settle()
        assertEquals(listOf("Artifact"), p.filters().types)
    }

    @Test
    fun theWordFieldsWriteTheirOwnColumns() = runTest {
        val p = mount()
        settle()
        p.fold("text"); settle()
        p.type("sol ring", "bolt"); settle()
        assertEquals("bolt", p.filters().q)
        p.type("draw a card", "draw"); settle()
        assertEquals("draw", p.filters().text)
        p.type("enters tapped", "enters the battlefield tapped"); settle()
        assertEquals("enters the battlefield tapped", p.filters().textLike)
        p.type("Rebecca Guay", "guay"); settle()
        assertEquals("guay", p.filters().artist)
    }

    @Test
    fun tokensAddOnEnterAndRemoveOnClick() = runTest {
        val p = mount()
        settle()
        p.fold("tags"); settle()
        val input = p.field("Flying, Ward…")
        input.value = "Flying"
        input.dispatchEvent(Event("input", js("({bubbles: true})")))
        settle()
        input.dispatchEvent(js("new KeyboardEvent('keydown', {key: 'Enter', bubbles: true})") as Event)
        settle()
        assertEquals(listOf("Flying"), p.filters().keywords)
        p.button("Flying ×").click(); settle()
        assertEquals(emptyList(), p.filters().keywords)
    }

    @Test
    fun theFlagsAreThreeValued() = runTest {
        val p = mount()
        settle()
        p.fold("flags"); settle()
        val row = p.root.querySelectorAll("div.tri")[0] as HTMLElement
        val yes = row.querySelectorAll("button")[1] as HTMLButtonElement
        yes.click(); settle()
        assertEquals(Tri.YES, p.filters().flags[Flag.RESERVED])
        val no = (p.root.querySelectorAll("div.tri")[0] as HTMLElement)
            .querySelectorAll("button")[2] as HTMLButtonElement
        no.click(); settle()
        assertEquals(Tri.NO, p.filters().flags[Flag.RESERVED])
    }

    @Test
    fun theFormatDropdownOffersWhatTheCollectionHas() = runTest {
        val p = mount()
        settle()
        p.fold("legality"); settle()
        assertTrue(p.text().contains("commander"))
        val selects = p.root.querySelectorAll("select")
        // The legality group's format select is the last one rendered.
        val format = selects[selects.length - 2] as HTMLSelectElement
        format.value = "modern"
        format.dispatchEvent(Event("change", js("({bubbles: true})")))
        settle()
        assertEquals("modern", p.filters().format)
    }

    @Test
    fun resetPutsEverythingBack() = runTest {
        val p = mount(Filters(q = "bolt", colors = listOf("R"), cmcMin = "2"))
        settle()
        p.button("Reset everything").click(); settle()
        assertEquals(Filters(), p.filters())
    }

    @Test
    fun theQueryBoxIsGone() = runTest {
        val p = mount()
        settle()
        // Asked for, and removed. The parser stays in the core because
        // it costs nothing and is tested, but there is no box.
        assertFalse(p.text().contains("Query language"))
        assertEquals(0, p.root.querySelectorAll("input.qbox").length)
    }
}

/**
 * The panel wired to the page, which is where it was broken.
 *
 * The panel itself was fine — every control wrote its field, and the
 * SQL those fields produce is checked in the core and against the real
 * collection. What was missing is the step after: nothing re-ran the
 * search, so choosing a colour changed the state and left the same
 * hundred cards on screen. Indistinguishable from a filter that does
 * nothing.
 */
class FilterApplyTest {

    private val roots = mutableListOf<HTMLElement>()

    @AfterTest
    fun cleanUp() {
        roots.forEach { it.remove() }
        roots.clear()
    }

    private suspend fun settle() = repeat(3) {
        Promise<Unit> { resolve, _ -> kotlinx.browser.window.requestAnimationFrame { resolve(Unit) } }.await()
    }

    private class Page(val root: HTMLElement) {
        var library = org.mattshoe.mtg.core.Library()
        var searches = 0

        fun buttons() = root.querySelectorAll("button")
            .let { n -> (0 until n.length).mapNotNull { n[it] as? HTMLButtonElement } }

        fun button(label: String) = buttons().first { it.textContent?.trim() == label }
        fun pip(c: String) = root.querySelector("button.pip[data-c=$c]") as HTMLButtonElement

        /** What the app would actually ask the database for. */
        fun query() = org.mattshoe.mtg.core.buildQuery(library.filters)
    }

    private fun mount(open: String? = null): Page {
        val root = document.createElement("div") as HTMLElement
        document.body!!.appendChild(root)
        roots += root
        val page = Page(root)
        renderComposable(root = root) {
            var lib by remember { mutableStateOf(org.mattshoe.mtg.core.Library()) }
            LibraryPage(
                state = lib,
                onState = { lib = it; page.library = it },
                onSearch = { page.searches++ },
                onOpen = {},
                showFilters = true,
            )
        }
        open?.let {
            (root.querySelector("details[data-facet=$it] summary") as HTMLElement).click()
        }
        return page
    }

    @Test
    fun choosingAColourReachesTheQuery() = runTest {
        val p = mount(open = "colour")
        settle()
        p.pip("W").click(); settle()
        p.pip("U").click(); settle()
        val sql = p.query()
        assertTrue(sql.sql.contains("color_identity"), sql.sql)
        // At most, the default, excludes the three not chosen.
        assertEquals(listOf("%B%", "%R%", "%G%"), sql.params)
    }

    @Test
    fun andSomethingHasToGoAndFetchIt() = runTest {
        val p = mount(open = "colour")
        settle()
        p.pip("W").click(); settle()
        // This is the whole bug: the filter was applied to the state
        // and nobody asked the database again.
        assertTrue(p.searches > 0, "choosing a colour did not re-run the search")
    }

    @Test
    fun soDoesEveryOtherToggle() = runTest {
        val p = mount()
        settle()
        val before = p.searches
        p.button("Matt").click(); settle()
        assertTrue(p.searches > before, "choosing an owner did not re-run the search")
    }

    @Test
    fun andChangingTheSortDoesNotRunItTwice() = runTest {
        val p = mount()
        settle()
        val before = p.searches
        p.root.querySelector("select.sort")?.let {
            (it as HTMLSelectElement).value = "name"
            it.dispatchEvent(Event("change", js("({bubbles: true})")))
        }
        settle()
        assertEquals(before + 1, p.searches, "a sort change asked the database twice")
    }
}
