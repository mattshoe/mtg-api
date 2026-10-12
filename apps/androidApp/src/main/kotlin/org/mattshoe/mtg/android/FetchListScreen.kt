package org.mattshoe.mtg.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.mattshoe.mtg.core.Design
import org.mattshoe.mtg.core.FetchListState

/**
 * A deck list in, where every card is filed out, on Android. Sibling
 * of the web's `FetchListPage`.
 *
 * One panel per type, colour and rarity, in the order Matt's binders
 * are sorted in, then what he does not own. The filing is `FetchList`'s.
 */
@Composable
fun FetchListScreen(
    state: FetchListState,
    onState: (FetchListState) -> Unit,
    onFind: () -> Unit,
    onSave: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(Design.WRAP_PAD_NARROW.dp)
            .testTag("fetch"),
        verticalArrangement = Arrangement.spacedBy(Design.GAP.dp),
    ) {
        Panel(head = "Fetch list", note = "Paste a deck list. Every card comes back filed under its type, colour and rarity.") {
            Field(
                value = state.list,
                onValueChange = { onState(state.typed(it)) },
                placeholder = "1 Sol Ring\n1 Counterspell",
                modifier = Modifier.heightIn(min = 150.dp).testTag("fetch-list"),
                singleLine = false,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.testTag("fetch-find")) {
                    Primary(if (state.busy) "Finding…" else "Find cards", enabled = state.canFind) { onFind() }
                }
                if (state.result != null || state.list.isNotEmpty()) {
                    Ghost("New list") { onState(state.cleared()) }
                }
            }
            state.error?.let { Line(it, Bad, modifier = Modifier.testTag("fetch-err")) }
            if (state.checking) {
                Line(FetchListState.CHECKING, Ink3, Design.MINI, modifier = Modifier.testTag("fetch-checking"))
            }
        }

        state.result?.let { result ->
            Panel {
                Line("Title", Ink2, Design.BODY)
                Field(
                    value = state.title,
                    onValueChange = { onState(state.titled(it)) },
                    placeholder = "Required to save",
                    modifier = Modifier.fillMaxWidth().testTag("fetch-title"),
                )
                Box(Modifier.testTag("fetch-save")) { Btn("Save") { onSave() } }
            }

            result.sections.forEach { section ->
                Panel(modifier = Modifier.testTag("fetch-section:${section.title}")) {
                    // The colour as its mana symbols, in the order the
                    // card prints them, between the type and the rarity.
                    Row(
                        Modifier.testTag("fetch-head:${section.title}"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Line(section.type.label, Ink, Design.H2, FontWeight.SemiBold)
                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            section.symbols.forEachIndexed { i, letter ->
                                ManaSymbol(letter, size = 18.dp, tag = "fetch-symbol:${section.title}:$i")
                            }
                        }
                        Line(section.rarity.label, Ink, Design.H2, FontWeight.SemiBold)
                    }
                    section.cards.forEach { card ->
                        Column(Modifier.fillMaxWidth().testTag("fetch-card:${card.name}")) {
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Line("${card.want}×", Ink3, Design.BODY)
                                Line(card.name, Ink, Design.BODY, modifier = Modifier.weight(1f))
                                if (card.have < card.want) Tag("have ${card.have}", Warn)
                            }
                            if (card.alsoCheck.isNotEmpty()) {
                                Line(
                                    card.alsoCheckLine,
                                    Ink3,
                                    Design.MINI,
                                    modifier = Modifier.testTag("fetch-also:${card.name}"),
                                )
                            }
                        }
                    }
                }
            }

            if (result.missing.isNotEmpty()) {
                Panel(modifier = Modifier.testTag("fetch-section:Missing"), head = "Not in your collection") {
                    result.missing.forEach { m ->
                        Row(
                            Modifier.fillMaxWidth().testTag("fetch-missing:${m.name}"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Line("${m.short}×", Ink3, Design.BODY)
                            Line(m.name, Ink, Design.BODY, modifier = Modifier.weight(1f))
                            if (m.have > 0) Line("you have ${m.have}", Ink3, Design.MINI)
                        }
                    }
                }
            }
        }

        if (state.saved.isNotEmpty()) {
            Panel(head = "Saved lists") {
                state.saved.forEach { s ->
                    Row(
                        Modifier.fillMaxWidth().testTag("fetch-saved:${s.title}"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.weight(1f)) { Ghost(s.title) { onState(state.open(s.id)) } }
                        Line(s.at.take(10), Ink3, Design.MINI)
                        Ghost("Delete") { onState(state.delete(s.id)) }
                    }
                }
            }
        }
    }
}
