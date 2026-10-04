package org.mattshoe.mtg.android

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import org.mattshoe.mtg.core.Completion

/** The suggestion list, for anything that needs to point at it. */
const val SUGGESTIONS_TAG = "card-name-suggestions"

/**
 * Directly below the box.
 *
 * `Popup`'s own alignments are all relative to the anchor's rectangle,
 * so the closest of them — `BottomStart` — puts the list's *bottom* on
 * the box's bottom and draws it upwards over the field. What is wanted
 * is the list's top on the box's bottom, which is two numbers and no
 * alignment at all.
 */
private object BelowTheAnchor : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset = IntOffset(anchorBounds.left, anchorBounds.bottom)
}

/**
 * A text field that suggests card names. Sibling of `AutocompleteField`
 * on the web.
 *
 * What is highlighted and when it is worth asking is `Completion` in
 * the shared core; the caller does the lookup, so being offline is a
 * quiet empty list on both platforms rather than two different errors.
 *
 * The list is a `Popup` — its own window, floating over the screen —
 * and not a `Column` underneath the box. Two reasons, and the second is
 * the one that matters.
 *
 * It looks right: the web's list is `position: absolute` over the page,
 * so suggestions appearing never shoved the sort control and the whole
 * card grid down the screen and then yanked them back.
 *
 * And it can be dismissed. A composable only ever hears about touches
 * inside its own bounds, so an inline list had no way to learn that a
 * tap had landed somewhere else — which is why this had no `onDismiss`
 * at all, and picking a suggestion or typing back below two characters
 * were the only two ways out. A window is different: a window can ask
 * the platform to tell it about touches outside itself, which is what
 * `dismissOnClickOutside` does here. Because the window is not
 * focusable those touches still reach whatever they were aimed at
 * underneath — non-consuming, the same as the web's capture-phase
 * `pointerdown` listener, which does not call `preventDefault` either.
 *
 * What this deliberately is not is a watcher at the root of the shell.
 * That was tried: a `Box` wrapping the whole app with a `pointerInput`
 * sitting in `awaitPointerEvent(Initial)` forever. It worked, and it
 * also broke thirteen unrelated screen tests at once — touch injection,
 * `performScrollTo` and text input all failing app-wide, because
 * everything on the phone was then underneath one permanently
 * listening pointer handler. A window that asks the window manager
 * about its own outside taps costs the rest of the app nothing.
 */
@Composable
fun AutocompleteField(
    label: String,
    state: Completion,
    onState: (Completion) -> Unit,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Put the list away, touching nothing else. Sibling of the web's
     * `onDismiss`.
     *
     * Not `onState(state.closed())` from the call site either, for the
     * reason the web's own comment gives: a `Completion` carries the
     * term, so a close built from whatever this frame drew can hand the
     * caller a term from before the box was last touched. Clear the
     * box, tap elsewhere, and the search you had just cleared came
     * back.
     */
    onDismiss: () -> Unit = {},
) {
    // The box is also the anchor: the `Popup` is a child of it, so the
    // box's own rectangle is what the list is positioned against, and
    // `maxWidth` is how the list comes out exactly as wide as the field
    // — a window is sized by its content, so left to itself it would
    // be as wide as its longest card name.
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val boxWidth = maxWidth

        OutlinedTextField(
            value = state.term,
            onValueChange = { onState(state.typed(it)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(label) },
        )

        if (state.open && !state.isEmpty) {
            Popup(
                popupPositionProvider = BelowTheAnchor,
                onDismissRequest = onDismiss,
                properties = PopupProperties(
                    // Not focusable, three times over. The box keeps
                    // the keyboard, so the next letter typed goes where
                    // it was aimed; a tap that lands outside still
                    // reaches the control it was aimed at instead of
                    // being eaten by the dismiss; and Back stays with
                    // the activity's own handler, which runs
                    // `AppState.back` — one order for the whole app,
                    // with the list first in it, rather than a second
                    // handler in this window racing it.
                    focusable = false,
                    dismissOnClickOutside = true,
                    dismissOnBackPress = false,
                ),
            ) {
                SuggestionList(boxWidth, state, onState, onPick)
            }
        }
    }
}

@Composable
private fun SuggestionList(
    width: Dp,
    state: Completion,
    onState: (Completion) -> Unit,
    onPick: (String) -> Unit,
) {
    // A window has nothing behind it, so the list has to bring its own
    // background or it reads as text printed over the card grid.
    Surface(
        Modifier.width(width).testTag(SUGGESTIONS_TAG),
        tonalElevation = 3.dp,
        shadowElevation = 3.dp,
    ) {
        Column(Modifier.fillMaxWidth().heightIn(max = 180.dp).verticalScroll(rememberScrollState())) {
            state.items.forEachIndexed { i, name ->
                Text(
                    name,
                    Modifier.fillMaxWidth()
                        .clickable {
                            val (next, picked) = state.pick(i)
                            onState(next)
                            picked?.let(onPick)
                        }
                        .padding(vertical = 8.dp, horizontal = 4.dp),
                    fontSize = 14.sp,
                    // Dead until arrow keys and Enter land. `Completion`
                    // already keeps `active`; nothing on the phone
                    // moves it yet.
                    fontWeight = if (i == state.active) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
    }
}
