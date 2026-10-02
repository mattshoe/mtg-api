package org.mattshoe.mtg.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import kotlinx.browser.document
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.attributes.placeholder
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Input
import org.jetbrains.compose.web.dom.Li
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.Ul
import org.mattshoe.mtg.core.Completion
import org.w3c.dom.HTMLElement
import org.w3c.dom.Node
import org.w3c.dom.events.Event
import org.w3c.dom.events.EventListener

/**
 * A text field that suggests card names.
 *
 * Sibling of `AutocompleteField` on Android. What is highlighted and
 * what the arrow keys do is `Completion` in the shared core — this
 * draws it, and the caller does the lookup, so being offline or rate
 * limited is a quiet empty list on both platforms rather than two
 * different errors.
 */
@Composable
fun AutocompleteField(
    hint: String,
    state: Completion,
    onState: (Completion) -> Unit,
    onPick: (String) -> Unit,
    /**
     * Put the list away. Not the same event as the name changing.
     *
     * Closing used to go out through `onState` as a whole rebuilt
     * `Completion`, which carries the term — so a press somewhere
     * else handed the name filter a term from whenever this last
     * drew. Clear the box, touch the screen, and the search you had
     * just cleared came back.
     */
    onDismiss: () -> Unit = {},
    extraClasses: List<String> = emptyList(),
) {
    // The field's own box, so a press somewhere else can be told apart
    // from a press on a suggestion. Without it the list had no way to
    // close except Escape or picking something, and a phone has no
    // Escape — you could scroll the whole page with the suggestions
    // still hanging over it.
    val box = remember { arrayOfNulls<HTMLElement>(1) }

    // Picking has to survive being asked twice. A tap on a phone sends
    // mousedown *and* click, and both are wired, because which of the
    // two arrives is not something to find out in production. The
    // second one lands on an already-picked `Completion`, whose list
    // is empty, so it is a no-op rather than a second search.
    fun take(i: Int) {
        val (next, picked) = state.pick(i)
        onState(next)
        picked?.let(onPick)
    }

    DisposableEffect(state.open) {
        val away = EventListener { e: Event ->
            val target = e.target as? Node
            val mine = box[0]
            if (mine != null && (target == null || !mine.contains(target))) onDismiss()
        }
        if (state.open) document.addEventListener("pointerdown", away, true)
        onDispose { document.removeEventListener("pointerdown", away, true) }
    }

    Div(attrs = {
        classes("ac")
        ref { el -> box[0] = el; onDispose { box[0] = null } }
    }) {
        Input(type = InputType.Text) {
            classes("field")
            extraClasses.forEach { classes(it) }
            placeholder(hint)
            attr("autocomplete", "off")
            attr("role", "combobox")
            attr("aria-autocomplete", "list")
            value(state.term)
            onInput { onState(state.typed(it.value)) }
            onKeyDown { e ->
                if (!state.open || state.isEmpty) return@onKeyDown
                when (e.key) {
                    "ArrowDown" -> { e.preventDefault(); onState(state.down()) }
                    "ArrowUp" -> { e.preventDefault(); onState(state.up()) }
                    "Enter" -> if (state.active >= 0) {
                        e.preventDefault()
                        take(state.active)
                    }
                    // Stopped, so escape closes the list rather than
                    // whatever overlay the field is sitting in.
                    "Escape" -> { e.stopPropagation(); onDismiss() }
                }
            }
        }
        if (state.open && !state.isEmpty) {
            Ul(attrs = { classes("ac-list") }) {
                state.items.forEachIndexed { i, name ->
                    Li(attrs = {
                        if (i == state.active) classes("on")
                        // mousedown rather than click: the input blurs
                        // on press, and a blur-driven close would remove
                        // the target before click fired.
                        onMouseDown { e ->
                            e.preventDefault()
                            take(i)
                        }
                        // And click as well, for the touchscreen where
                        // the mouse events are a courtesy rather than a
                        // promise. Tapping a suggestion did nothing at
                        // all on a phone.
                        onClick { e ->
                            e.preventDefault()
                            take(i)
                        }
                        // No mouse-enter handler. It wrote a state
                        // derived from the one this composition
                        // captured, and within a single frame that
                        // capture is already stale — so the enter
                        // that follows a tap handed back the list as
                        // it was *before* the pick, open again, with
                        // the name you had just chosen sitting in the
                        // box behind it. Hover is a paint, so the
                        // stylesheet does it.
                    }) { Text(name) }
                }
            }
        }
    }
}
