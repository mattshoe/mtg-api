package org.mattshoe.mtg.web

import androidx.compose.runtime.Composable
import org.jetbrains.compose.web.attributes.disabled
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.mattshoe.mtg.core.Stepper

/**
 * "- 3 +": two buttons and the number between them.
 *
 * Matt: "if i have 3 copies of something it would say "- 3 +" but
 * actual button and UI not text". What the number is — the deck's
 * count or the collection's — and what a press sends are `:core`'s;
 * this draws it. The phone's `CountStepper` is the same three parts.
 */
@Composable
fun CountStepper(stepper: Stepper, onStep: (Int) -> Unit) {
    Div(attrs = {
        classes("stepper")
        attr("role", "group")
        attr("aria-label", "How many, ${stepper.unit}")
    }) {
        Button(attrs = {
            classes("btn", "step-minus")
            attr("aria-label", "One fewer")
            if (!stepper.canTakeOne) disabled()
            onClick { onStep(-1) }
        }) { Text("−") }
        Span(attrs = {
            classes("step-count", "mono")
            attr("aria-live", "polite")
        }) { Text(stepper.count.toString()) }
        Button(attrs = {
            classes("btn", "step-plus")
            attr("aria-label", "One more")
            if (!stepper.canAddOne) disabled()
            onClick { onStep(+1) }
        }) { Text("+") }
        Span(attrs = { classes("step-unit", "muted", "small") }) { Text(stepper.unit) }
    }
}
