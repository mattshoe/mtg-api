package org.mattshoe.mtg.android

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.mattshoe.mtg.core.Stepper

/**
 * "- 3 +": two buttons and the number between them.
 *
 * Matt: "if i have 3 copies of something it would say "- 3 +" but
 * actual button and UI not text". What the number is — the deck's
 * count or the collection's — and what a press sends are `:core`'s;
 * this draws it. The website's `CountStepper` is the same three parts.
 */
@Composable
fun CountStepper(stepper: Stepper, onStep: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.testTag("stepper"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StepButton("−", "One fewer", "step-minus", stepper.canTakeOne) { onStep(-1) }
        Text(
            stepper.count.toString(),
            Modifier.widthIn(min = 24.dp).testTag("step-count"),
            color = Ink,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        StepButton("+", "One more", "step-plus", stepper.canAddOne) { onStep(+1) }
        Text(stepper.unit, color = Ink2, fontSize = 12.5.sp)
    }
}

/** `.stepper .btn`: square, bordered, big enough for a thumb. */
@Composable
private fun StepButton(label: String, said: String, tag: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(TouchTarget)
            .testTag(tag)
            .background(Bg3, RadiusSm)
            .border(1.dp, Line2, RadiusSm)
            .semantics {
                role = Role.Button
                contentDescription = said
                if (!enabled) disabled()
            }
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (enabled) Ink else Ink3, fontSize = 20.sp, fontWeight = ButtonWeight)
    }
}
