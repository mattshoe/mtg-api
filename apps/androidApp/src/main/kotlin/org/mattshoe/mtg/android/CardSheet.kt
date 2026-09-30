package org.mattshoe.mtg.android

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.mattshoe.mtg.core.CardDetail
import org.mattshoe.mtg.core.Prices

/**
 * One card, opened. Sibling of `CardSheet` on the web.
 *
 * Both are handed a `CardDetail` and neither works out how many copies
 * are spare — `free` is on the state, so the number cannot come out
 * different on a phone.
 */
@Composable
fun CardSheet(card: CardDetail, onClose: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            TextButton(onClick = onClose) { Text("← Back") }
        }
        // Selectable, the same as the web. Copying a card name off the
        // page is most of what the page is for.
        SelectionContainer { Text(card.name, fontSize = 18.sp, fontWeight = FontWeight.SemiBold) }
        when {
            card.busy -> Text("Loading…")
            card.error != null -> Text(card.error!!)
            else -> Body(card)
        }
    }
}

@Composable
private fun Body(card: CardDetail) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("${card.owned} owned", fontSize = 13.sp)
        Text("${card.free} free", fontSize = 13.sp)
    }

    // Who has how many. The page used to be one person's, which made
    // the other half of the collection invisible.
    if (card.byOwner.isNotEmpty()) {
        Text("Who owns it", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        card.byOwner.forEach { h ->
            Text(
                listOfNotNull(
                    h.owner,
                    "${h.owned} owned",
                    "${h.free} free",
                    if (h.short > 0) "${h.short} short" else null,
                ).joinToString(" · "),
                fontSize = 13.sp,
            )
        }
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
        // Where to buy another, when Scryfall has a listing for that
        // exact printing. The price beside it is the one for the finish
        // this copy is in, worked out by the `card_prices` view, so a
        // foil is not quoted at the nonfoil price.
        val open = androidx.compose.ui.platform.LocalUriHandler.current
        card.printings.forEach { p ->
            val shop = p.tcgplayer
            Row(
                Modifier.fillMaxWidth()
                    .then(
                        if (shop == null) Modifier
                        else Modifier.clickable { runCatching { open.openUri(shop) } },
                    )
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    listOfNotNull(
                        p.setCode.uppercase(),
                        p.collectorNumber,
                        p.setName,
                        p.finish.takeIf { it != "nonfoil" },
                        "${p.qty}×",
                    ).joinToString(" · ") + if (shop != null) " ↗" else "",
                    fontSize = 13.sp,
                    modifier = Modifier.weight(1f),
                )
                Text(Prices.money(p.price), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }

    Text("Legal in", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
    if (card.legalities.isEmpty()) {
        Text("Nothing recorded.", fontSize = 13.sp)
    } else {
        card.legalities.forEach { l ->
            Text("${l.format} — ${l.label}", fontSize = 13.sp)
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

    if (card.rulings.isNotEmpty()) {
        Text("Rulings", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        card.rulings.forEach { r -> Text("${r.date}  ${r.text}", fontSize = 12.sp) }
    }
}
