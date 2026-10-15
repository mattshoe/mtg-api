package org.mattshoe.mtg.android

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.mattshoe.mtg.core.DeckList
import org.mattshoe.mtg.core.FetchCard
import org.mattshoe.mtg.core.Design
import org.mattshoe.mtg.core.FetchListState
import kotlin.math.cos
import kotlin.math.sin

/**
 * A deck list in, where every card is filed out, on Android. Sibling
 * of the web's `FetchListPage`.
 *
 * Two pages. Matt: "the root page should have the entry box and the
 * saved fetches", then "searching should take you to a NEW page" where
 * he can "check off cards as i find them". Which one is on screen is
 * `AppState.onFetchList`.
 *
 * One panel per type, colour and rarity, in the order Matt's binders
 * are sorted in, then what he does not own. The filing is `FetchList`'s.
 */
@Composable
fun FetchListScreen(
    state: FetchListState,
    onList: Boolean,
    onState: (FetchListState) -> Unit,
    onFind: () -> Unit,
    onSave: () -> Unit,
    onOpen: (String) -> Unit,
    onBack: () -> Unit,
    onPeek: (FetchCard) -> Unit = {},
) {
    Column(
        Modifier.fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(Design.WRAP_PAD_NARROW.dp)
            .testTag(if (onList) "fetch-page" else "fetch"),
        verticalArrangement = Arrangement.spacedBy(Design.GAP.dp),
    ) {
        if (onList) FetchListOpen(state, onState, onFind, onSave, onBack, onPeek)
        else FetchListRoot(state, onState, onFind, onOpen)
    }
}

/** The entry box, and every list kept. */
@Composable
private fun FetchListRoot(
    state: FetchListState,
    onState: (FetchListState) -> Unit,
    onFind: () -> Unit,
    onOpen: (String) -> Unit,
) {
    Panel(head = "Fetch list", note = "Paste a deck list. Every card comes back filed under its type, colour and rarity.") {
        Field(
            value = state.draft,
            onValueChange = { onState(state.drafted(it)) },
            placeholder = "1 Sol Ring\n1 Counterspell",
            modifier = Modifier.heightIn(min = 150.dp).testTag("fetch-list"),
            singleLine = false,
        )
        Box(Modifier.testTag("fetch-find")) {
            Primary("Find cards", enabled = state.canStart) { onFind() }
        }
    }

    if (state.saved.isNotEmpty() || state.unsaved) {
        Panel(head = "Saved lists") {
            if (state.unsaved) {
                Row(
                    Modifier.fillMaxWidth().testTag("fetch-current"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.weight(1f)) { Ghost(FetchListState.UNSAVED) { onOpen("") } }
                    Line(state.progress, Ink3, Design.MINI)
                }
            }
            state.saved.forEach { s ->
                Row(
                    Modifier.fillMaxWidth().testTag("fetch-saved:${s.title}"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.weight(1f)) { Ghost(s.title) { onOpen(s.id) } }
                    Line(s.at.take(10), Ink3, Design.MINI)
                    Ghost("Delete") { onState(state.delete(s.id)) }
                }
            }
        }
    }
}

/** One list: the loading view, then the sections with a box per card. */
@Composable
private fun FetchListOpen(
    state: FetchListState,
    onState: (FetchListState) -> Unit,
    onFind: () -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit,
    onPeek: (FetchCard) -> Unit = {},
) {
    Panel {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.testTag("fetch-back")) { Ghost("‹ Lists") { onBack() } }
            Line(
                state.title.ifBlank { "Fetch list" },
                Ink,
                Design.H2,
                FontWeight.SemiBold,
                modifier = Modifier.weight(1f).testTag("fetch-page-title"),
            )
            if (state.list.isNotBlank() && state.editing == null) {
                Box(Modifier.testTag("fetch-edit")) { Btn("Edit", enabled = !state.busy) { onState(state.edit()) } }
            }
        }
        state.editing?.let { text ->
            Field(
                value = text,
                onValueChange = { onState(state.editTyped(it)) },
                placeholder = "1 Sol Ring\n1 Counterspell",
                modifier = Modifier.heightIn(min = 150.dp).testTag("fetch-edit-list"),
                singleLine = false,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.testTag("fetch-refind")) {
                    Primary("Find cards", enabled = DeckList.entries(text).isNotEmpty()) { onFind() }
                }
                Ghost("Cancel") { onState(state.cancelEdit()) }
            }
        }
        if (state.list.isBlank()) Line(FetchListState.NOTHING_OPEN, Ink3, Design.BODY)
        state.error?.let { msg ->
            Line(msg, Bad, modifier = Modifier.testTag("fetch-err"))
            if (state.list.isNotBlank() && state.editing == null && !state.busy) {
                Box(Modifier.testTag("fetch-retry")) { Btn("Try again") { onFind() } }
            }
        }
        if (state.result != null && !state.busy) {
            Box(Modifier.testTag("fetch-progress")) { Tag(state.progress, Info) }
            Line("Title", Ink2, Design.BODY)
            Field(
                value = state.title,
                onValueChange = { onState(state.titled(it)) },
                placeholder = "Required to save",
                modifier = Modifier.fillMaxWidth().testTag("fetch-title"),
            )
            Box(Modifier.testTag("fetch-save")) { Btn("Save") { onSave() } }
        }
        if (state.checking && !state.busy) {
            Line(FetchListState.CHECKING, Ink3, Design.MINI, modifier = Modifier.testTag("fetch-checking"))
        }
    }

    if (state.busy) {
        FetchLoading(state.entries.size)
        return
    }

    val result = state.result ?: return
    result.sections.forEach { section ->
        Panel(modifier = Modifier.testTag("fetch-section:${section.title}")) {
            // The colour as its mana symbols, in the order the card
            // prints them, between the type and the rarity.
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
                val ticked = state.isTicked(card.name)
                Column(Modifier.fillMaxWidth().testTag("fetch-card:${card.name}")) {
                    // The box ticks it. Anywhere else on the row opens
                    // the carousel over the list at this card. Matt:
                    // "tapping the row (outside checkbox) should show
                    // the carousel".
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable { onPeek(card) }
                            .testTag("fetch-row:${card.name}"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(
                            Modifier
                                .toggleable(value = ticked, role = Role.Checkbox) { onState(state.tick(card.name)) }
                                .semantics { contentDescription = "Found ${card.name}" }
                                .testTag("fetch-tick:${card.name}"),
                        ) {
                            Checkbox(
                                checked = ticked,
                                onCheckedChange = null,
                                colors = CheckboxDefaults.colors(checkedColor = Accent, uncheckedColor = Ink3),
                            )
                        }
                        Line("${card.want}×", Ink3, Design.BODY)
                        Line(
                            card.name,
                            if (ticked) Ink3 else Ink,
                            Design.BODY,
                            modifier = Modifier.weight(1f),
                        )
                        if (card.have < card.want) Tag("have ${card.have}", Warn)
                        RemoveButton(card.name) { onState(state.remove(card)) }
                    }
                    if (card.alsoCheck.isNotEmpty()) {
                        Line(
                            card.alsoCheckLine,
                            Ink3,
                            Design.MINI,
                            modifier = Modifier.testTag("fetch-also:${card.name}"),
                        )
                    }
                    // No copy in bulk: it is in a deck, so say which.
                    card.decksLine?.let {
                        Line(it, Ink3, Design.MINI, modifier = Modifier.testTag("fetch-decks:${card.name}"))
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
                    RemoveButton(m.name) { onState(state.remove(m)) }
                }
            }
        }
    }
}

/**
 * Takes the card off the list, text and all. Matt: "make sure doing so
 * actually edits the deck list text as well". Its own press, so it
 * opens no carousel on the way.
 */
@Composable
private fun RemoveButton(name: String, onRemove: () -> Unit) {
    Box(Modifier.testTag("fetch-remove:$name").semantics(mergeDescendants = true) { contentDescription = "Remove $name from the list" }) {
        Ghost("Remove") { onRemove() }
    }
}

/**
 * While the collection is being asked: the five colours going round a
 * ring, lighting up in turn, and how many cards it is looking for.
 * The web's `FetchLoading` draws the same thing in CSS.
 */
@Composable
private fun FetchLoading(cards: Int) {
    val spin = rememberInfiniteTransition(label = "fetch-loading")
    val turn by spin.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(4000, easing = LinearEasing)),
        label = "turn",
    )
    val beat by spin.animateFloat(
        initialValue = 0f,
        targetValue = 5f,
        animationSpec = infiniteRepeatable(tween(1500, easing = LinearEasing), RepeatMode.Restart),
        label = "beat",
    )
    Panel(modifier = Modifier.testTag("fetch-loading")) {
        Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(120.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(64.dp), color = Accent, trackColor = Line2, strokeWidth = 3.dp)
                Box(Modifier.size(120.dp).rotate(turn), contentAlignment = Alignment.Center) {
                    listOf("W", "U", "B", "R", "G").forEachIndexed { i, letter ->
                        val a = Math.toRadians(i * 72.0 - 90.0)
                        val lit = (beat.toInt() % 5) == i
                        ManaSymbol(
                            letter,
                            size = 22.dp,
                            modifier = Modifier
                                .offset((cos(a) * 46).dp, (sin(a) * 46).dp)
                                .alpha(if (lit) 1f else 0.45f),
                        )
                    }
                }
            }
        }
        Line(
            FetchListState.finding(cards),
            Ink2,
            Design.BODY,
            modifier = Modifier.fillMaxWidth().testTag("fetch-loading-line"),
        )
    }
}
