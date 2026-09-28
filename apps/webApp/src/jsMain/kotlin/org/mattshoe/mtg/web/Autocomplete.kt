package org.mattshoe.mtg.web

import androidx.compose.runtime.Composable
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.attributes.placeholder
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Input
import org.jetbrains.compose.web.dom.Li
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.Ul
import org.mattshoe.mtg.core.Completion

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
    extraClasses: List<String> = emptyList(),
) {
    Div(attrs = { classes("ac") }) {
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
                        val (next, name) = state.pick(state.active)
                        onState(next)
                        name?.let(onPick)
                    }
                    // Stopped, so escape closes the list rather than
                    // whatever overlay the field is sitting in.
                    "Escape" -> { e.stopPropagation(); onState(state.closed()) }
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
                            val (next, picked) = state.pick(i)
                            onState(next)
                            picked?.let(onPick)
                        }
                        onMouseEnter { onState(state.highlight(i)) }
                    }) { Text(name) }
                }
            }
        }
    }
}
