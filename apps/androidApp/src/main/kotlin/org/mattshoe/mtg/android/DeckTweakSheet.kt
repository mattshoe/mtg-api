package org.mattshoe.mtg.android

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import org.mattshoe.mtg.core.DeckCard
import org.mattshoe.mtg.core.DeckPlan
import org.mattshoe.mtg.core.DeckTweak
import org.mattshoe.mtg.core.Design
import org.mattshoe.mtg.core.Found
import org.mattshoe.mtg.core.Tweak

/**
 * One card in, one card out, or one number changed. On Android.
 *
 * Sibling of `DeckTweakSheet.kt` on the web, down to the sentences:
 * "Takes the same number out as it puts in.", "not owned — would be
 * bought", "No card called “…”.". The phone had none of this at all,
 * so the only way to add a card to a deck from a phone was to rewrite
 * the whole list in the bulk editor.
 *
 * Nothing here decides anything. What the floor is, whether the minus
 * is live, whether a pick is good enough to preview, what the one-line
 * summary reads, and what the list looks like afterwards are all on
 * `DeckTweak` in `:core` — tested by `DeckTweakTest`,
 * `TweakFinderCoreTest` and `TweakCounterCoreTest`. This file draws
 * them and sends taps back.
 *
 * Four screens in one sheet: what to do, which card, how many, and
 * what that would mean. `TweakSheetParityTest` renders every one of
 * them on a device and reads the semantics tree back, because reading
 * this file beside the web one and concluding they match is exactly
 * how the two drifted everywhere else.
 *
 * It is a modal, which it was not. This was a bare `Column` dropped at
 * the end of the shell: nothing dimmed behind it, nothing outside it
 * dismissed it, and nothing capped its height — so the four-hit finder
 * ran past the bottom of a phone with the Preview button somewhere off
 * the end of it. Every other overlay here is a dialog, and the website
 * has always drawn this one inside `.palette-scrim`. The parity suite
 * could not see any of that, because it hosted the sheet in a `Box` of
 * its own with nothing behind it and no screen to run off.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeckTweakSheet(
    state: DeckTweak,
    onState: (DeckTweak) -> Unit,
    onFind: (String) -> Unit,
    onPreview: () -> Unit,
    onApply: () -> Unit,
    onClose: () -> Unit,
) {
    // How tall the screen is, which is what the cap below is a
    // fraction of. `vh` on the web; there is no such unit here.
    val tall = LocalConfiguration.current.screenHeightDp

    // `BasicAlertDialog` rather than the full `AlertDialog` the other
    // overlays use: the sheet already carries its own head, its own ×
    // and its own foot, and the foot is a different pair of buttons on
    // each of its four screens. `AlertDialog` owns all three of those
    // slots, so it would mean a second close button and a confirm that
    // does not know what it is confirming. This is the same dialog
    // underneath — real window, real scrim, back and outside taps both
    // arriving as `onDismissRequest`.
    BasicAlertDialog(
        onDismissRequest = onClose,
        modifier = Modifier.fillMaxSize(),
        // The window is the whole screen, so the scrim below covers
        // the whole screen. At the platform default the window is
        // sized to the sheet and there is nowhere outside it to tap.
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier.fillMaxSize()
                .testTag("tweak-scrim")
                .background(Scrim)
                // `.palette-scrim`'s `onClick { onClose() }`. A raw tap
                // detector and not `clickable`, for two reasons. A
                // screen reader should not be told the dark behind the
                // sheet is a full-screen button — and `clickable` sets
                // `shouldMergeDescendantSemantics`, which folds the
                // entire sheet into one node: every tag inside it
                // disappears from the merged tree and the seventy
                // tests that read them stop being able to see
                // anything.
                .pointerInput(Unit) { detectTapGestures { onClose() } }
                // `padding: 12vh 14px 14px`. The top inset is what
                // leaves the sheet looking like a sheet rather than a
                // screen, and the side inset is why it does not sit
                // four pixels off each edge of a phone.
                .padding(start = 14.dp, end = 14.dp, top = (tall * 0.12f).dp, bottom = 14.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                Modifier.fillMaxWidth()
                    // `.palette.wide`: `min(760px, 94vw)`.
                    .widthIn(max = 760.dp)
                    .testTag("tweak-sheet")
                    // `max-height: 82vh`, and the scroll below it. A
                    // swap with four hits and a plan is taller than a
                    // phone; without this the sheet simply kept going
                    // and the buttons at the end of it were off the
                    // screen with no way to reach them.
                    .heightIn(max = (tall * 0.82f).dp)
                    .background(Bg2, Radius)
                    .border(1.dp, Line2, Radius)
                    // The web's `onClick { it.stopPropagation() }`.
                    // Taps that no control inside the sheet claimed —
                    // the padding, the gaps between rows, the summary
                    // line — would otherwise reach the scrim's tap
                    // detector and shut the sheet from inside it.
                    // Consumed on the main pass, which runs child
                    // first, so every control inside still gets first
                    // refusal and the scroll underneath still works.
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                awaitPointerEvent().changes.forEach { it.consume() }
                            }
                        }
                    }
                    .verticalScroll(rememberScrollState())
                    .padding(Design.PANEL_PAD.dp),
                verticalArrangement = Arrangement.spacedBy(Design.GAP.dp),
            ) {
                // `.panel-head`: what the sheet is for, whose deck it
                // is, and the way out. The title comes off the choice
                // that was made, so it is the web's "Swap this card
                // out" rather than a fixed word that stops describing
                // the screen after the first tap.
                Row(
                    Modifier.fillMaxWidth().testTag("tweak-head"),
                    horizontalArrangement = Arrangement.spacedBy(Design.GAP.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        state.kind?.title ?: "Change this card",
                        fontSize = Design.H2.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Ink,
                    )
                    Spacer(Modifier.weight(1f))
                    Muted(state.deckName, size = Design.MINI)
                    Tap("Close", Modifier.testTag("tweak-close"), onClick = onClose) {
                        Text("×", fontSize = Design.H1.sp, color = Ink2)
                    }
                }

                when {
                    state.saved -> Done(state, onClose)
                    state.choosing -> Choose(state, onState)
                    else -> Body(state, onState, onFind, onPreview, onApply)
                }
            }
        }
    }
}

// ============================================== 1. choose what to do

/**
 * What to do with the card that was tapped.
 *
 * The row in the deck list carries one button rather than three — three
 * marks on every row of a hundred-card list left no room for the card's
 * own name — so the choice lives here, as full-width rows a thumb can
 * hit.
 */
@Composable
private fun Choose(state: DeckTweak, onState: (DeckTweak) -> Unit) {
    state.subject?.let { Subject(it, null) }
    Option(
        0,
        "Swap it for another card",
        "Takes the same number out as it puts in.",
    ) { onState(state.doing(Tweak.SWAP)) }
    Option(
        1,
        "Change how many",
        "There ${if (state.qty == 1) "is" else "are"} ${state.qty} in the deck.",
    ) { onState(state.doing(Tweak.QUANTITY)) }
    Option(
        2,
        "Take it out of the deck",
        "The copies go back to bulk.",
    ) { onState(state.doing(Tweak.REMOVE)) }
}

/** `.opt`: a mark, a label, and the line that says what it does. */
@Composable
private fun Option(ordinal: Int, label: String, help: String, go: () -> Unit) {
    Tap(
        label,
        Modifier.fillMaxWidth().testTag("tweak-opt-$ordinal")
            .background(Bg3, RadiusSm)
            .border(1.dp, Line2, RadiusSm),
        onClick = go,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(16.dp)
                    .testTag("tweak-opt-mark-$ordinal")
                    .border(1.dp, Line2, CircleShape),
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(label, fontSize = Design.SMALL.sp, color = Ink, fontWeight = FontWeight.Medium)
                Muted(help, size = Design.MINI)
            }
        }
    }
}

// =============================================== 2, 3 and 4 together

@Composable
private fun Body(
    state: DeckTweak,
    onState: (DeckTweak) -> Unit,
    onFind: (String) -> Unit,
    onPreview: () -> Unit,
    onApply: () -> Unit,
) {
    state.subject?.let { Subject(it, state.kind) }

    if (state.needsACard) Finder(state, onState, onFind)

    // Removing takes the whole row; everything else has a number.
    if (state.kind != Tweak.REMOVE) Counter(state, onState)

    Text(
        state.summary,
        Modifier.testTag("tweak-says"),
        fontSize = Design.SMALL.sp,
        color = Ink,
        fontWeight = FontWeight.Medium,
    )

    state.plan?.let { Plan(it) }
    state.error?.let { Text(it, Modifier.testTag("tweak-error"), fontSize = Design.SMALL.sp, color = Bad) }
    state.errors.forEach { Text(it, fontSize = Design.SMALL.sp, color = Bad) }

    // `.wiz-foot`: the two beats, never both at once. Before a plan
    // there is only Preview; with one there is only Apply, and the way
    // back to change the number.
    Row(
        Modifier.fillMaxWidth().testTag("tweak-foot"),
        horizontalArrangement = Arrangement.spacedBy(Design.GAP.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (state.plan == null) {
            Primary(if (state.busy) "Working…" else "Preview →", enabled = state.ready, onClick = onPreview)
            if (!state.ready && state.needsACard && state.pick == null) {
                Muted("Find the card first.", size = Design.MINI)
            }
        } else {
            Btn("← Change it") { onState(state.copy(plan = null)) }
            Primary(
                if (state.busy) "Working…" else "${state.kind?.verb ?: "Do"} it",
                enabled = state.canApply,
                onClick = onApply,
            )
        }
    }
}

/** The card being acted on, so the sheet says what it is about. */
@Composable
private fun Subject(card: DeckCard, kind: Tweak?) {
    Row(
        Modifier.fillMaxWidth().testTag("tweak-subject").padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).background(Bg3, Radius).clip(Radius)) {
            card.art?.let {
                AsyncImage(
                    model = it,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    contentScale = ContentScale.Crop,
                )
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(card.shown, fontSize = Design.SMALL.sp, color = Ink)
            // `knownTypeLine`, not `typeLine`: a basic land nobody
            // inventories has no printing to read one off, and the web
            // names it anyway.
            card.knownTypeLine?.takeIf { it.isNotBlank() }?.let { Muted(it, size = Design.MINI) }
        }
        Muted("${card.qty}×", size = Design.MINI)
        // Which of the two cards in a swap this one is. In words: the
        // arrow in the summary line is the only other thing saying it,
        // and an arrow is not a label.
        //
        // `.tag.mini` with no tone class, which is `var(--text-2)` —
        // not `.tag.warn`. Leaving is not a warning, and amber here
        // spent the one colour the rest of this sheet reserves for
        // "something is wrong" on the ordinary half of a swap.
        if (kind == Tweak.SWAP) Tag("going out")
    }
}

// ==================================================== 2. the finder

/** Search the collection for the card coming in. */
@Composable
private fun Finder(state: DeckTweak, onState: (DeckTweak) -> Unit, onFind: (String) -> Unit) {
    OutlinedTextField(
        value = state.term,
        onValueChange = { typed ->
            onState(state.typed(typed))
            // The same threshold the state uses to decide whether it
            // is searching, so the box and the server agree about
            // what is worth asking.
            if (typed.trim().length >= DeckTweak.MIN_TERM) onFind(typed)
        },
        modifier = Modifier.fillMaxWidth().testTag("tweak-term"),
        singleLine = true,
        shape = RadiusSm,
        textStyle = LocalTextStyle.current.copy(fontSize = Design.BODY.sp),
        placeholder = {
            Text(
                if (state.kind == Tweak.SWAP) "Swap in…" else "Card name",
                color = Ink3,
                fontSize = Design.BODY.sp,
            )
        },
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = Bg,
            unfocusedContainerColor = Bg,
            focusedBorderColor = Accent,
            unfocusedBorderColor = Line2,
            focusedTextColor = Ink,
            unfocusedTextColor = Ink,
            cursorColor = Accent,
        ),
    )

    state.pick?.let { chosen ->
        Row(
            Modifier.fillMaxWidth().testTag("tweak-pick").padding(vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(chosen.name, fontSize = Design.SMALL.sp, color = Ink, fontWeight = FontWeight.Medium)
                chosen.typeLine?.takeIf { it.isNotBlank() }?.let { Muted(it, size = Design.MINI) }
            }
            if (chosen.qty > 0) {
                Tag("${chosen.qty} owned · ${chosen.owner}")
            } else {
                // Nobody has one, so this is a purchase. The plan will
                // say so; the sheet says it before the plan is asked
                // for.
                Tag("not owned — would be bought", Warn)
            }
        }
    }

    if (state.pick == null && state.found.isNotEmpty()) {
        Column(
            Modifier.fillMaxWidth().testTag("tweak-hits"),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            state.found.forEachIndexed { i, f -> Hit(i, f) { onState(state.picked(f)) } }
        }
    }

    // A term that was asked about and came back with nothing used to
    // render as blank space, which reads exactly like a finder still
    // thinking. Only once the answer is back, so it never calls a card
    // missing before it has looked.
    if (state.pick == null &&
        state.found.isEmpty() &&
        !state.searching &&
        state.term.trim().length >= DeckTweak.MIN_TERM
    ) {
        Muted("No card called “${state.term.trim()}”.", Modifier.testTag("tweak-none"), size = Design.SMALL)
    }
}

/**
 * One hit. A whole-row target, not a name to aim at.
 *
 * 44dp tall because that is what a thumb needs, and what the web's
 * coarse-pointer rule puts on the same row.
 */
@Composable
private fun Hit(ordinal: Int, f: Found, pick: () -> Unit) {
    Tap(
        f.name,
        Modifier.fillMaxWidth().testTag("tweak-hit-$ordinal")
            .background(Bg3, RadiusSm)
            .border(1.dp, Line2, RadiusSm),
        onClick = pick,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(f.name, fontSize = Design.SMALL.sp, color = Ink)
                // What the card is, which is what tells two hits of
                // the same name apart when the owner does not.
                f.typeLine?.takeIf { it.isNotBlank() }?.let { Muted(it, size = Design.MINI) }
            }
            // Owned or not, in words. Hue is not a channel the person
            // who owns this collection has, so a row that differed
            // only by being redder said nothing at all.
            if (f.qty > 0) Tag("${f.qty}× ${f.owner}") else Tag("not owned", Warn)
        }
    }
}

// =================================================== 3. the counter

/**
 * How many, with the two buttons a thumb actually wants.
 *
 * The number is stepped from a holder rather than from the state this
 * was composed with, because taps arrive faster than frames. Compose
 * recomposes on a frame, so three taps inside one all run the same
 * handler against the same `state` — and the deck ends up one card
 * bigger instead of three.
 */
@Composable
private fun Counter(state: DeckTweak, onState: (DeckTweak) -> Unit) {
    val stepped = remember(state.qty) { intArrayOf(state.qty) }

    fun put(n: Int) {
        val next = state.clamped(n)
        stepped[0] = next
        onState(state.count(next))
    }

    Row(
        Modifier.fillMaxWidth().testTag("tweak-counter"),
        horizontalArrangement = Arrangement.spacedBy(Design.GAP.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Step("One fewer", "−", "tweak-minus", state.canTakeOne) { put(stepped[0] - 1) }
        OutlinedTextField(
            // Controlled by the state, so a 999 shows as the 99 the
            // state actually holds and "lots" never stays in the box.
            value = "${state.qty}",
            onValueChange = { typed ->
                // Anything that is not a number leaves the number
                // alone. An empty box used to read as nought, and for
                // a count nought takes the card out of the deck — so
                // backspacing to retype deleted it.
                val next = state.clamped(typed.trim().toIntOrNull() ?: state.qty)
                stepped[0] = next
                onState(state.count(next))
            },
            modifier = Modifier.testTag("tweak-qty").widthIn(min = 72.dp).weight(1f),
            singleLine = true,
            shape = RadiusSm,
            // Under 16sp a phone's own zoom gets involved, and the
            // letter keyboard is no use for a number.
            textStyle = LocalTextStyle.current.copy(fontSize = 16.sp, textAlign = TextAlign.Center),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Bg,
                unfocusedContainerColor = Bg,
                focusedBorderColor = Accent,
                unfocusedBorderColor = Line2,
                focusedTextColor = Ink,
                unfocusedTextColor = Ink,
                cursorColor = Accent,
            ),
        )
        Step("One more", "+", "tweak-plus", state.canAddOne) { put(stepped[0] + 1) }
    }
}

/**
 * A −  or a +. 44dp square, and visibly shut at its end.
 *
 * Shut is carried as weight, not as a hue: the glyph and its border
 * fade together, so the difference survives being unable to tell two
 * colours apart. The semantics say `disabled()` as well, which is what
 * a screen reader and every test here read.
 */
@Composable
private fun Step(label: String, glyph: String, tag: String, enabled: Boolean, onClick: () -> Unit) {
    Tap(
        label,
        Modifier.testTag(tag)
            .size(44.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .background(Bg3, RadiusSm)
            .border(1.dp, Line2, RadiusSm),
        enabled = enabled,
        onClick = onClick,
    ) {
        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
            Text(glyph, fontSize = Design.H2.sp, color = Ink, fontWeight = FontWeight.SemiBold)
        }
    }
}

// ============================================ 4. preview, and apply

/**
 * What the server says it would do.
 *
 * The sourcing lines are the point: a card going into a deck has to
 * come from somewhere, and "this one would be bought" is worth knowing
 * before it is true.
 */
@Composable
private fun Plan(p: DeckPlan) {
    Row(
        Modifier.fillMaxWidth().testTag("tweak-plan"),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Figure("${p.cardCount}", "cards after")
        Figure("${p.added.size + p.changed.size}", "in")
        Figure("${p.removed.size}", "out")
    }
    if (p.acquired.isNotEmpty()) {
        Tag(
            "${p.acquired.sumOf { it.qty }} to buy: " + p.acquired.joinToString(", ") { it.name },
            Warn,
        )
    }
    if (p.returned.isNotEmpty()) {
        Tag("back to bulk: " + p.returned.joinToString(", ") { "${it.qty}× ${it.name}" }, Ok)
    }
}

/** `.tally-cell`: a number with the word for what it counts under it. */
@Composable
private fun Figure(value: String, label: String) {
    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(value, fontSize = Design.H2.sp, fontWeight = FontWeight.SemiBold, color = Ink)
        Muted(label, size = Design.TINY)
    }
}

@Composable
private fun Done(state: DeckTweak, onClose: () -> Unit) {
    Text(
        "Done — ${state.summary}",
        Modifier.testTag("tweak-says"),
        fontSize = Design.SMALL.sp,
        color = Ink,
        fontWeight = FontWeight.Medium,
    )
    Row(Modifier.fillMaxWidth().testTag("tweak-foot")) {
        Primary("Back to the deck", onClick = onClose)
    }
}

// ------------------------------------------------------------ plumbing

/**
 * A pressable region that announces itself.
 *
 * `Theme.kt`'s buttons are all text with a background, and these need
 * to hold a row. Same contract though: a `Role.Button`, a name, and
 * `disabled()` rather than disappearing when it is off — so "absent"
 * and "refused" stay different answers.
 */
@Composable
private fun Tap(
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        modifier
            .heightIn(min = 44.dp)
            .semantics {
                role = Role.Button
                contentDescription = label
                if (!enabled) disabled()
            }
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.CenterStart,
    ) { content() }
}
