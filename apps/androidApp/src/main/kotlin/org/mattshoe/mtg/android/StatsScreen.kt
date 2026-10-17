package org.mattshoe.mtg.android

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.mattshoe.mtg.core.CollectionBreakdown
import org.mattshoe.mtg.core.Design
import org.mattshoe.mtg.core.Pip
import org.mattshoe.mtg.core.Prices
import org.mattshoe.mtg.core.StatChart
import org.mattshoe.mtg.core.StatTab
import org.mattshoe.mtg.core.StatsState

/**
 * Collection totals, on Android. Sibling of `StatsPage`.
 *
 * The same key-and-number list the site shows, in the same panel,
 * over the same caption naming whose collection it is.
 */
@Composable
fun StatsScreen(state: StatsState, onState: (StatsState) -> Unit = {}) {
    Column(
        Modifier.fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(Design.WRAP_PAD_NARROW.dp),
        verticalArrangement = Arrangement.spacedBy(Design.GAP.dp),
    ) {
        // No heading: the tab says "Stats". A Both / Matt / Kayla
        // segmented control was here, which was a list of the only two
        // people there would ever be. The page is one collection now,
        // so this says which one rather than offering a choice.
        Box(Modifier.testTag("scope")) {
            Line(state.scope.label, Ink3, modifier = Modifier.testTag("scope-label"))
        }

        when {
            state.busy -> Line("Loading…", Ink3, modifier = Modifier.testTag("stats-busy"))
            state.error != null -> Line(
                "Could not load stats: ${state.error}",
                Bad,
                modifier = Modifier.testTag("stats-err"),
            )
            // With the cards loaded the overview's figures say all of
            // this, so the list is only for a collection with none.
            state.cards.isNotEmpty() -> Tabbed(state, onState)
            else -> {
                val t = state.totals
                Panel {
                    listOf(
                        "Printings" to t.printings.toString(),
                        "Unique cards" to t.uniques.toString(),
                        "Physical cards" to t.physical.toString(),
                        "Free copies" to t.free.toString(),
                        "Decks" to t.decks.toString(),
                        "Sets" to t.sets.toString(),
                        "Foils" to t.foils.toString(),
                        // `unpriced`, never `$0`: nothing priced and
                        // nothing worth anything are different facts.
                        "Value" to Prices.money(t.value, dash = "unpriced"),
                    ).forEachIndexed { i, (label, value) ->
                        Row(
                            Modifier.fillMaxWidth()
                                .testTag("stat-row-$i")
                                .semantics { contentDescription = "$label $value" },
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Line(label, Ink2, Design.BODY, modifier = Modifier.testTag("stat-label-$i"))
                            // `.tag`: the number is a stated fact in a
                            // pill, not prose run together with its label.
                            Box(Modifier.testTag("stat-value-$i")) { Tag(value) }
                        }
                    }
                }
                t.pricedAt?.let { PricedAt(it) }
            }
        }
    }
}

@Composable
private fun PricedAt(at: String) =
    Line("Prices from $at", Ink3, Design.MINI, modifier = Modifier.testTag("priced-at"))

/**
 * The tab bar, the filters, the tab's numbers and its sections. The
 * web's `Tabbed` in `StatsPage` draws the same, from the same
 * `StatsState`.
 *
 * Matt: "i asked for a tabbed fucking page where the landing tab is
 * overall stats about the whole collection, then a series of other
 * tabs about each card type ... MAKE IT LOOK STYLISH AND ORGANIZED AND
 * TABBED AND READY TO DIGEST". The first version wrapped the tabs as
 * chips, the same chips as the filters, over every chart in one list.
 */
@Composable
private fun Tabbed(state: StatsState, onState: (StatsState) -> Unit) {
    // One line that scrolls sideways over a hairline, underlined under
    // the open tab, as the web's `.stats-tabs`.
    Row(
        Modifier.fillMaxWidth()
            .testTag("stats-tabs")
            .drawBehind {
                val y = size.height - 0.5.dp.toPx()
                drawLine(Line, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
            }
            .horizontalScroll(rememberScrollState()),
    ) {
        state.tabs.forEach { (tab, n) ->
            Tab(tab, n, on = tab == state.tab) { onState(state.onTab(tab)) }
        }
    }

    FlowRow(
        Modifier.fillMaxWidth().testTag("stats-filters"),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Pip.entries.forEach { pip ->
            val on = pip in state.filter.colours
            // What says this one is chosen is the ring and the dimming,
            // not the hue, as in the Library's filters.
            Box(
                Modifier
                    .testTag("stats-colour-${pip.letter}")
                    .size(40.dp)
                    .background(if (on) c(Design.pip(pip.letter)).copy(alpha = 0.22f) else Bg, CircleShape)
                    .border(if (on) 3.dp else 2.dp, if (on) Accent else c(Design.pip(pip.letter)), CircleShape)
                    .semantics { contentDescription = pip.label }
                    .toggleable(value = on, role = Role.Checkbox) { onState(state.toggleColour(pip)) },
                contentAlignment = Alignment.Center,
            ) {
                ManaSymbol(pip.letter, size = 22.dp, text = Design.SMALL.sp, modifier = Modifier.alpha(if (on) 1f else 0.55f))
            }
        }
        state.rarities.forEach { r ->
            val label = r.replaceFirstChar(Char::uppercase)
            Toggle(label, on = r in state.filter.rarities, tag = "stats-rarity-$r", role = Role.Checkbox, onClick = {
                onState(state.toggleRarity(r))
            }) {
                Line(label, if (r in state.filter.rarities) Accent2 else Ink2, Design.MINI, FontWeight.Medium)
            }
        }
        if (!state.filter.isEmpty) {
            IconAction("Clear the filters", Glyph.CROSS) { onState(state.clearFilters()) }
        }
    }

    val b = state.breakdown
    if (b.isEmpty) {
        Line("No cards match these filters.", Ink3, modifier = Modifier.testTag("stats-empty"))
        return
    }

    Panel {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            state.figures.forEachIndexed { i, f ->
                StatFigure(f.value, f.label, hint = f.hint, modifier = Modifier.testTag("stats-figure-$i"))
            }
        }
    }
    if (state.tab == StatTab.ALL) state.totals.pricedAt?.let { PricedAt(it) }

    state.sections.forEach { section ->
        Column(
            Modifier.fillMaxWidth().padding(top = 14.dp).testTag("stats-section-${section.title}"),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Line(
                section.title,
                Ink,
                Design.H2,
                FontWeight.SemiBold,
                Modifier.testTag("stats-section-title-${section.title}").semantics { heading() },
            )
            // One column on a phone, and side by side as the screen
            // widens, as the web's `.stats-grid` does with
            // `minmax(260px, 1fr)`.
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val columns = (maxWidth / CHART_MIN).toInt().coerceAtLeast(1)
                Column(verticalArrangement = Arrangement.spacedBy(Design.GAP.dp)) {
                    section.charts.chunked(columns).forEach { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Design.GAP.dp)) {
                            row.forEach { chart ->
                                Panel(Modifier.weight(1f).testTag("stats-chart-${chart.title}")) { Chart(chart, b) }
                            }
                            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }
    }
}

/** How narrow a chart may get before the next one moves down a row. */
private val CHART_MIN = 300.dp

/**
 * One tab: its title and count, and a line along its foot when it is
 * the open one. Brighter words as well, so lightness says which is
 * open and never hue alone. The web's `.stats-tab`.
 */
@Composable
private fun Tab(tab: StatTab, n: Int, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .testTag("stats-tab-${tab.name}")
            .sizeIn(minHeight = TouchTarget)
            .semantics { contentDescription = "${tab.title} $n" }
            .selectable(selected = on, role = Role.Tab, onClick = onClick),
    ) {
        Row(
            Modifier.align(Alignment.Center).padding(horizontal = 12.dp).clearAndSetSemantics {},
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Line(tab.title, if (on) Ink else Ink3, Design.SMALL, FontWeight.SemiBold)
            Line("$n", if (on) Accent2 else Ink3, Design.TINY, fontFamily = monoSmall.fontFamily)
        }
        if (on) {
            Box(Modifier.matchParentSize(), contentAlignment = Alignment.BottomCenter) {
                Box(Modifier.fillMaxWidth().height(2.dp).background(Accent).testTag("stats-tab-underline"))
            }
        }
    }
}

/** A chip that is on or off: a rarity. `.chip` and `.chip.on` on the web. */
@Composable
private fun Toggle(
    told: String,
    on: Boolean,
    tag: String,
    role: Role,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Row(
        Modifier
            .testTag(tag)
            .sizeIn(minHeight = TouchTarget)
            .clearAndSetSemantics {
                contentDescription = told
                toggleableState = ToggleableState(on)
                this.role = role
                onClick { onClick(); true }
            }
            .toggleable(value = on, role = role) { onClick() }
            .padding(vertical = 4.dp)
            .background(if (on) AccentDim else Bg3, Pill)
            .border(1.dp, if (on) Accent else Line2, Pill)
            .padding(horizontal = 11.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

/** One chart, titled, drawn as the web's `Chart` draws it. */
@Composable
private fun Chart(chart: StatChart, b: CollectionBreakdown) {
    val money: (Int) -> String = { Prices.money(it.toDouble()) }
    val t = chart.title
    when (chart) {
        StatChart.COLOUR -> { Heading(t); Centred { StatRing("Per colour", b.colours) } }
        StatChart.IDENTITY -> { Heading(t); Centred { StatRing("Exact identity", b.identities, numbered = b.identityRing) } }
        StatChart.TYPES -> StatBars(t, b.types, b.copies, "Of ${b.copies} cards. A card of two types counts in each.")
        StatChart.CURVE -> StatCurve(b.curve, "Average ${b.averageManaValueText}. Lands excluded.")
        StatChart.RARITY -> StatBars(t, b.rarities, b.copies, "Of ${b.copies} cards.")
        StatChart.TRIBES -> StatBars(t, b.tribes, b.creatures, b.tribesNote)
        StatChart.PRICES -> StatBars(t, b.priceBands, b.copies, "Copies, by what one is worth.")
        StatChart.VALUE_BY_COLOUR ->
            StatBars(t, b.valueByColour, b.valueByColour.sumOf { it.value }, "A card of two colours counts in each.", money)
        StatChart.VALUE_BY_RARITY ->
            StatBars(t, b.valueByRarity, b.valueByRarity.sumOf { it.value }, "In whole dollars.", money)
        StatChart.SETS -> StatBars(t, b.topSets, b.copies, "Of ${b.copies} cards.")
        StatChart.YEARS -> StatBars(t, b.years, b.copies, "By the year the printing came out.")
        StatChart.ARTISTS -> StatBars(t, b.artists, b.copies, "Of ${b.copies} cards.")
        StatChart.PRICIEST -> Priciest(b)
    }
}

@Composable
private fun Heading(title: String) = Line(title.uppercase(), Ink3, Design.MINI, FontWeight.SemiBold)

@Composable
private fun Centred(content: @Composable () -> Unit) =
    Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) { content() }

/** The ten copies worth the most, one row per name. The web's `.priciest` table. */
@Composable
private fun Priciest(b: CollectionBreakdown) {
    Heading("Most valuable")
    b.priciest.forEachIndexed { i, card ->
        val each = Prices.money(card.each)
        Row(
            Modifier.fillMaxWidth()
                .padding(top = 6.dp)
                .testTag("stats-priciest-$i")
                .clearAndSetSemantics {
                    contentDescription = "${card.name}, ${card.setName}${if (card.foil) ", foil" else ""}, $each each, ${card.qty} owned"
                },
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Line("${i + 1}", Ink, Design.TINY, FontWeight.Bold, Modifier.width(18.dp))
            Column(Modifier.weight(1f)) {
                Line(card.name, Ink2, Design.SMALL)
                Line(card.setName + if (card.foil) " · foil" else "", Ink3, Design.MINI)
            }
            Line(each, Ink2, Design.SMALL, tabularNums = true)
            Line("×${card.qty}", Ink3, Design.MINI, tabularNums = true)
        }
    }
}
