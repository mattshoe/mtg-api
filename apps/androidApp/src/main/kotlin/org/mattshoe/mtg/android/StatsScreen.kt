package org.mattshoe.mtg.android

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
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
                t.pricedAt?.let {
                    Line(
                        "Prices from $it",
                        Ink3,
                        Design.MINI,
                        modifier = Modifier.testTag("priced-at"),
                    )
                }
                if (state.cards.isNotEmpty()) Charts(state, onState)
            }
        }
    }
}

/**
 * The tabs, the filters and every chart. The web's `Charts` in
 * `StatsPage` draws the same, from the same `CollectionBreakdown`.
 *
 * Matt: "i want TONS of data and i want to be able to filter on like
 * colors and types and stuff ... a tab for every card type would be
 * cool, with the landing page being the data on the collection as a
 * whole".
 */
@Composable
private fun Charts(state: StatsState, onState: (StatsState) -> Unit) {
    // Wrapped rather than scrolled sideways, as on the web, so every
    // tab is in sight at once.
    FlowRow(
        Modifier.fillMaxWidth().padding(top = 8.dp).testTag("stats-tabs"),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        state.tabs.forEach { (tab, n) ->
            Toggle(
                "${tab.title} $n",
                on = tab == state.tab,
                tag = "stats-tab-${tab.name}",
                role = Role.Tab,
                onClick = { onState(state.onTab(tab)) },
            ) {
                Line(tab.title, if (tab == state.tab) Accent2 else Ink2, Design.SMALL, FontWeight.Medium)
                Line("$n", if (tab == state.tab) Accent2 else Ink3, Design.MINI, fontFamily = monoSmall.fontFamily)
            }
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
            listOf(
                "${b.copies}" to "cards",
                "${b.uniques}" to "unique",
                Prices.money(b.value, dash = "unpriced") to "value",
                b.averageManaValueText to "avg mana",
                "${b.foils}" to "foils",
                "${b.sets}" to "sets",
            ).forEachIndexed { i, (n, k) ->
                StatFigure(
                    n,
                    k,
                    hint = if (k == "value" && b.unpriced > 0) "${b.unpriced} cards have no price" else "",
                    modifier = Modifier.testTag("stats-figure-$i"),
                )
            }
        }
    }

    // One column on a phone, and side by side as the screen widens, as
    // the web's `.stats-grid` does with `minmax(260px, 1fr)`.
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = (maxWidth / CHART_MIN).toInt().coerceAtLeast(1)
        Column(verticalArrangement = Arrangement.spacedBy(Design.GAP.dp)) {
            charts(b).chunked(columns).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Design.GAP.dp)) {
                    row.forEach { (title, body) ->
                        Panel(Modifier.weight(1f).testTag("stats-chart-$title")) { body() }
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** How narrow a chart may get before the next one moves down a row. */
private val CHART_MIN = 300.dp

/** A chip that is on or off: a tab, a rarity. `.chip` and `.chip.on` on the web. */
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

/** Every chart, titled, in the web's order. */
private fun charts(b: CollectionBreakdown): List<Pair<String, @Composable () -> Unit>> {
    val money: (Int) -> String = { Prices.money(it.toDouble()) }
    return buildList {
        add("Colour" to @Composable { Heading("Colour"); Centred { StatRing("Per colour", b.colours) } })
        add("Colour identity" to @Composable {
            Heading("Colour identity")
            Centred { StatRing("Exact identity", b.identities, numbered = b.identityRing) }
        })
        add("Card types" to @Composable {
            StatBars("Card types", b.types, b.copies, "Of ${b.copies} cards. A card of two types counts in each.")
        })
        if (b.hasCurve) add("Mana curve" to @Composable { StatCurve(b.curve, "Average ${b.averageManaValueText}. Lands excluded.") })
        add("Rarity" to @Composable { StatBars("Rarity", b.rarities, b.copies, "Of ${b.copies} cards.") })
        if (b.tribes.isNotEmpty()) add("Creature types" to @Composable { StatBars("Creature types", b.tribes, b.creatures, b.tribesNote) })
        add("Price per card" to @Composable { StatBars("Price per card", b.priceBands, b.copies, "Copies, by what one is worth.") })
        if (b.valueByColour.isNotEmpty()) {
            add("Value by colour" to @Composable {
                StatBars("Value by colour", b.valueByColour, b.valueByColour.sumOf { it.value }, "A card of two colours counts in each.", money)
            })
        }
        if (b.valueByRarity.isNotEmpty()) {
            add("Value by rarity" to @Composable {
                StatBars("Value by rarity", b.valueByRarity, b.valueByRarity.sumOf { it.value }, "In whole dollars.", money)
            })
        }
        add("Top sets" to @Composable { StatBars("Top sets", b.topSets, b.copies, "Of ${b.copies} cards.") })
        if (b.years.isNotEmpty()) add("Release years" to @Composable { StatBars("Release years", b.years, b.copies, "By the year the printing came out.") })
        if (b.artists.isNotEmpty()) add("Top artists" to @Composable { StatBars("Top artists", b.artists, b.copies, "Of ${b.copies} cards.") })
        if (b.priciest.isNotEmpty()) add("Most valuable" to @Composable { Priciest(b) })
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
