package org.mattshoe.mtg.web

import androidx.compose.runtime.Composable
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Text
import org.w3c.dom.HTMLElement

/**
 * A single-line box with an x at its right that empties it.
 *
 * Matt: "i want all single line text entry fields to have an x on the
 * right to clear the text". Wrap the `Input`; the x shows only while
 * [value] holds something, and pressing it hands the focus back to the
 * box so the next letter goes where it was aimed. The phone's `clearX`.
 */
@Composable
fun Clearable(value: String, onClear: () -> Unit, input: @Composable () -> Unit) {
    Div(attrs = { classes("clearable") }) {
        input()
        if (value.isNotEmpty()) {
            Button(attrs = {
                classes("clear-x")
                attr("type", "button")
                attr("aria-label", "Clear")
                onClick { e ->
                    onClear()
                    // `unsafeCast`, not `as`: inside a test's frame the
                    // element is the frame's own HTMLElement, not this
                    // window's, and `as` would throw.
                    e.currentTarget?.unsafeCast<HTMLElement>()?.parentElement
                        ?.querySelector("input")?.unsafeCast<HTMLElement>()?.focus()
                }
            }) { Text("×") }
        }
    }
}
