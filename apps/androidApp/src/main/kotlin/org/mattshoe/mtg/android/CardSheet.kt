package org.mattshoe.mtg.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.mattshoe.mtg.core.CardDetail

/**
 * One card, opened. Sibling of `CardSheet` on the web.
 *
 * Both are handed a `CardDetail` and neither works out how many copies
 * are spare — `free` is on the state, so the number cannot come out
 * different on a phone.
 */
@Composable
fun CardSheet(card: CardDetail, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } },
        title = {
            // Selectable, the same as the web. Copying a card name out
            // of the drawer is most of what the drawer is for.
            SelectionContainer { Text(card.name, fontSize = 18.sp) }
        },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when {
                    card.busy -> Text("Loading…")
                    card.error != null -> Text(card.error!!)
                    else -> Body(card)
                }
            }
        },
    )
}

@Composable
private fun Body(card: CardDetail) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("${card.owned} owned", fontSize = 13.sp)
        Text("${card.free} free", fontSize = 13.sp)
        Text(card.owner, fontSize = 13.sp)
    }
    if (card.overCommitted) {
        Text(
            "${card.committed} committed to decks — more than are owned",
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }

    Text("Printings", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
    if (card.printings.isEmpty()) {
        Text("None owned.", fontSize = 13.sp)
    } else {
        card.printings.forEach { p ->
            Text(
                listOfNotNull(
                    p.setCode.uppercase(),
                    p.collectorNumber,
                    p.setName,
                    p.finish,
                    "${p.qty}×",
                ).joinToString(" · "),
                fontSize = 13.sp,
            )
        }
    }

    Text("In decks", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
    if (card.usedIn.isEmpty()) {
        Text("Not in a deck.", fontSize = 13.sp)
    } else {
        card.usedIn.forEach { use ->
            Text(
                listOfNotNull(
                    use.name,
                    "${use.qty}×",
                    use.role,
                    // A proxy does not consume a real card, so it must
                    // not read like one that does.
                    if (use.isProxy) "proxy" else null,
                ).joinToString(" · "),
                fontSize = 13.sp,
            )
        }
    }
}
