package org.mattshoe.mtg.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import kotlinx.browser.window
import org.jetbrains.compose.web.attributes.disabled
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Img
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.mattshoe.mtg.core.CardQueries
import org.mattshoe.mtg.core.DeckCard
import org.mattshoe.mtg.core.Prices
import org.mattshoe.mtg.core.Tweak
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event

/**
 * The deck's cards, as something you swipe through.
 *
 * The website's half of what the phone grew: tapping a card in a
 * deck opens this over the deck rather than navigating to the card's
 * own page, which is a button on the sheet. Everything it obeys —
 * which card, where in the run, what Back does — is `AppState.peek`
 * in the shared core, so the two apps cannot disagree about it.
 *
 * Swiping is the browser's own horizontal scroll with snap points
 * rather than a drag handler, because a trackpad, a touchscreen, a
 * scrollbar and shift-wheel all already work that way and a
 * hand-written drag would support exactly one of them.
 */
@Composable
fun CardCarousel(
    cards: List<DeckCard>,
    at: Int,
    place: String?,
    admin: Boolean,
    onSwipe: (Int) -> Unit,
    onDetails: () -> Unit,
    onTweak: (DeckCard, Tweak) -> Unit,
) {
    if (cards.isEmpty()) return
    val index = at.coerceIn(0, cards.size - 1)
    val card = cards[index]
    val rail = remember { arrayOfNulls<HTMLElement>(1) }

    Div(attrs = {
        classes("peek-scrim")
        // Swallows the press and does nothing with it. Matt: "i don't
        // want click throughs to dismiss the carousel. Only the back
        // button." It still has to stop here, or it falls through to
        // a row behind and opens a different card under the one you
        // are reading.
        onClick { it.stopPropagation() }
    }) {
        Div(attrs = {
            classes("peek-rail")
            ref { el ->
                rail[0] = el
                // Where the scroll came to rest, as an index. Read on
                // a timer rather than on every scroll event: a flick
                // fires dozens and only the last one is a decision.
                var pending = 0
                val onScroll: (Event) -> Unit = {
                    window.clearTimeout(pending)
                    pending = window.setTimeout({
                        val width = el.clientWidth
                        if (width > 0) {
                            val landed = kotlin.math.round(el.scrollLeft / width).toInt()
                            if (landed != at) onSwipe(landed)
                        }
                    }, 80)
                }
                el.addEventListener("scroll", onScroll)
                onDispose {
                    window.clearTimeout(pending)
                    el.removeEventListener("scroll", onScroll)
                    rail[0] = null
                }
            }
        }) {
            cards.forEach { c -> CardFace(c) }
        }

        // Put the rail where the state says, when something other
        // than a scroll moved it — the arrows, or a card being
        // removed under the carousel.
        DisposableEffect(index, cards.size) {
            rail[0]?.let { el ->
                val want = index * el.clientWidth
                if (kotlin.math.abs(el.scrollLeft - want) > 4) el.scrollLeft = want.toDouble()
            }
            onDispose { }
        }

        Sheet(card, place, admin, index, cards.size, onSwipe, onDetails, onTweak)
    }
}

/** One card, as big as the rail allows. */
@Composable
private fun CardFace(card: DeckCard) {
    Div(attrs = { classes("peek-card") }) {
        val url = CardQueries.art(card.scryfallId, "normal")
        if (url != null) {
            Img(src = url, alt = card.shown)
        } else {
            // A card the deck wants that nobody owns has no printing
            // and so no picture. Its name, rather than a grey
            // rectangle that reads as a failure to load.
            Div(attrs = { classes("peek-blank") }) { Text(card.shown) }
        }
    }
}

/**
 * The sheet under the carousel: four facts and four buttons.
 *
 * Everything else about the card — the printings, the rulings, the
 * legalities, which decks it is in — is behind "Full details", which
 * is the card's own page and already exists.
 */
@Composable
private fun Sheet(
    card: DeckCard,
    place: String?,
    admin: Boolean,
    index: Int,
    count: Int,
    onSwipe: (Int) -> Unit,
    onDetails: () -> Unit,
    onTweak: (DeckCard, Tweak) -> Unit,
) {
    Div(attrs = { classes("peek-sheet") }) {
        Div(attrs = { classes("peek-head") }) {
            Div(attrs = { classes("peek-titles") }) {
                Div(attrs = { classes("peek-name") }) { Text(card.shown) }
                // The printing and what it is worth, on one line.
                // `printing` is null for a card nobody owns, which is
                // also the card with no price, so the line collapses
                // rather than printing two dashes.
                listOfNotNull(card.printing, card.price?.let { Prices.money(it) })
                    .takeIf { it.isNotEmpty() }
                    ?.let { Div(attrs = { classes("peek-sub") }) { Text(it.joinToString(" · ")) } }
                card.knownTypeLine?.takeIf { it.isNotBlank() }
                    ?.let { Div(attrs = { classes("peek-type") }) { Text(it) } }
            }
            place?.let { Span(attrs = { classes("peek-place") }) { Text(it) } }
        }

        Div(attrs = { classes("peek-tags") }) {
            Span(attrs = { classes("tag") }) { Text("${card.qty}× in deck") }
            Span(attrs = { classes("tag"); if (card.short > 0) classes("bad") }) {
                Text("${card.owned} owned")
            }
        }

        Div(attrs = { classes("peek-acts") }) {
            // Arrows as well as the swipe: a mouse has no swipe, and
            // a keyboard has neither.
            Button(attrs = {
                classes("btn", "sm", "ghost")
                attr("aria-label", "Previous card")
                if (index == 0) disabled()
                onClick { onSwipe(index - 1) }
            }) { Text("←") }
            Button(attrs = {
                classes("btn", "sm", "ghost")
                attr("aria-label", "Next card")
                if (index >= count - 1) disabled()
                onClick { onSwipe(index + 1) }
            }) { Text("→") }
            Button(attrs = {
                classes("btn", "sm", "primary")
                onClick { onDetails() }
            }) { Text("Full details") }
            if (admin) {
                Button(attrs = { classes("btn", "sm"); onClick { onTweak(card, Tweak.QUANTITY) } }) {
                    Text("Count")
                }
                Button(attrs = { classes("btn", "sm"); onClick { onTweak(card, Tweak.SWAP) } }) {
                    Text("Swap")
                }
                Button(attrs = { classes("btn", "sm", "danger"); onClick { onTweak(card, Tweak.REMOVE) } }) {
                    Text("Remove")
                }
            }
        }
    }
}
