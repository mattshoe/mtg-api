package org.mattshoe.mtg.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.DisposableEffectResult
import androidx.compose.runtime.DisposableEffectScope
import androidx.compose.runtime.remember
import kotlinx.browser.window
import org.jetbrains.compose.web.attributes.disabled
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Img
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.mattshoe.mtg.core.CardQueries
import org.mattshoe.mtg.core.CardZoom
import org.mattshoe.mtg.core.DeckCard
import org.mattshoe.mtg.core.PeekCard
import org.mattshoe.mtg.core.Prices
import org.mattshoe.mtg.core.Tweak
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event
import org.w3c.dom.pointerevents.PointerEvent

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
    cards: List<PeekCard>,
    at: Int,
    place: String?,
    admin: Boolean,
    onSwipe: (Int) -> Unit,
    onDetails: () -> Unit,
    onTweak: (DeckCard, Tweak) -> Unit,
    zoom: CardZoom = CardZoom(),
    onZoom: (CardZoom) -> Unit = {},
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
            // Matt: "swiping to the next card should only work while
            // fully zoomed out". The stylesheet stops the rail
            // scrolling, and one finger becomes a drag of the art.
            if (zoom.zoomed) classes("zoomed")
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
            cards.forEachIndexed { i, c ->
                if (i == index) CardFace(c, zoom, onZoom) else CardFace(c)
            }
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

        Sheet(card, place, admin, index, cards.size, zoom.zoomed, onSwipe, onDetails, onTweak)
    }
}

/**
 * One card, as big as the rail allows.
 *
 * The one on show also takes fingers: two zoom, and one drags the art
 * once zoomed. Pointer events rather than touch events, so a mouse
 * drags too, and ctrl-wheel — which is what a trackpad pinch arrives
 * as — zooms. The arithmetic is `CardZoom`'s, shared with the phone.
 */
@Composable
private fun CardFace(card: PeekCard, zoom: CardZoom? = null, onZoom: (CardZoom) -> Unit = {}) {
    val now = remember { arrayOf(CardZoom()) }
    val tell = remember { arrayOf(onZoom) }
    now[0] = zoom ?: CardZoom()
    tell[0] = onZoom
    Div(attrs = {
        classes("peek-card")
        if (zoom != null) ref { el -> pinchZoom(el, now, tell) }
    }) {
        val url = CardQueries.art(card.scryfallId, "normal")
        // Drawn about the centre, which is where `CardZoom` measures
        // from.
        val transform = zoom?.takeIf { it.zoomed }
            ?.let { "translate(${it.x}px, ${it.y}px) scale(${it.scale})" }
        if (url != null) {
            Img(src = url, alt = card.title, attrs = { transform?.let { t -> style { property("transform", t) } } })
        } else {
            // A card the deck wants that nobody owns has no printing
            // and so no picture. Its name, rather than a grey
            // rectangle that reads as a failure to load.
            Div(attrs = {
                classes("peek-blank")
                transform?.let { t -> style { property("transform", t) } }
            }) { Text(card.title) }
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
    card: PeekCard,
    place: String?,
    admin: Boolean,
    index: Int,
    count: Int,
    zoomed: Boolean,
    onSwipe: (Int) -> Unit,
    onDetails: () -> Unit,
    onTweak: (DeckCard, Tweak) -> Unit,
) {
    Div(attrs = { classes("peek-sheet") }) {
        Div(attrs = { classes("peek-head") }) {
            Div(attrs = { classes("peek-titles") }) {
                Div(attrs = { classes("peek-name") }) { Text(card.title) }
                // The printing and what it is worth, on one line.
                // `printing` is null for a card nobody owns, which is
                // also the card with no price, so the line collapses
                // rather than printing two dashes.
                listOfNotNull(card.printing, card.price?.let { Prices.money(it) })
                    .takeIf { it.isNotEmpty() }
                    ?.let { Div(attrs = { classes("peek-sub") }) { Text(it.joinToString(" · ")) } }
                card.typeLine?.takeIf { it.isNotBlank() }
                    ?.let { Div(attrs = { classes("peek-type") }) { Text(it) } }
            }
            place?.let { Span(attrs = { classes("peek-place") }) { Text(it) } }
        }

        // Already worded by `:core`: what a run can state about a
        // card differs — a deck knows how many it wants, the Library
        // knows how many are spare — and that is a fact about the run
        // rather than a rendering decision.
        Div(attrs = { classes("peek-tags") }) {
            card.tags.forEach { tag ->
                Span(attrs = { classes("tag"); if (tag.bad) classes("bad") }) { Text(tag.text) }
            }
        }

        Div(attrs = { classes("peek-acts") }) {
            // Arrows as well as the swipe: a mouse has no swipe, and
            // a keyboard has neither.
            Button(attrs = {
                classes("btn", "sm", "ghost")
                attr("aria-label", "Previous card")
                // Zoomed in, the card stays put: the same rule as
                // the swipe.
                if (index == 0 || zoomed) disabled()
                onClick { onSwipe(index - 1) }
            }) { Text("←") }
            Button(attrs = {
                classes("btn", "sm", "ghost")
                attr("aria-label", "Next card")
                if (index >= count - 1 || zoomed) disabled()
                onClick { onSwipe(index + 1) }
            }) { Text("→") }
            Button(attrs = {
                classes("btn", "sm", "primary")
                onClick { onDetails() }
            }) { Text("Full details") }
            // On the deck row rather than on `admin`: being admin in
            // the Library still leaves nothing to count, swap or
            // remove, because there is no deck.
            card.inDeck?.takeIf { admin }?.let { row ->
                Button(attrs = { classes("btn", "sm"); onClick { onTweak(row, Tweak.QUANTITY) } }) {
                    Text("Count")
                }
                Button(attrs = { classes("btn", "sm"); onClick { onTweak(row, Tweak.SWAP) } }) {
                    Text("Swap")
                }
                Button(attrs = { classes("btn", "sm", "danger"); onClick { onTweak(row, Tweak.REMOVE) } }) {
                    Text("Remove")
                }
            }
        }
    }
}

/**
 * The fingers on the card on show, as `CardZoom` moves.
 *
 * [now] and [tell] are boxes the composable refreshes every time it
 * draws, because this is attached once and the zoom moves under it.
 */
private fun DisposableEffectScope.pinchZoom(
    el: HTMLElement,
    now: Array<CardZoom>,
    tell: Array<(CardZoom) -> Unit>,
): DisposableEffectResult {
    val down = LinkedHashMap<Int, Pair<Double, Double>>()
    // The card's own box, untransformed. The cell centres the card,
    // so the cell's centre is the card's.
    fun face(): HTMLElement = (el.firstElementChild as? HTMLElement) ?: el
    fun centre(): Pair<Double, Double> {
        val r = el.getBoundingClientRect()
        return (r.left + r.width / 2) to (r.top + r.height / 2)
    }
    fun send(z: CardZoom) {
        if (z != now[0]) {
            now[0] = z
            tell[0](z)
        }
    }
    fun mid(a: List<Pair<Double, Double>>) = ((a[0].first + a[1].first) / 2) to ((a[0].second + a[1].second) / 2)
    fun gap(a: List<Pair<Double, Double>>) = kotlin.math.hypot(a[0].first - a[1].first, a[0].second - a[1].second)

    val onDown: (Event) -> Unit = { e ->
        val p = e as PointerEvent
        down[p.pointerId] = p.clientX.toDouble() to p.clientY.toDouble()
        if (down.size >= 2 || now[0].zoomed) {
            runCatching { el.setPointerCapture(p.pointerId) }
            e.preventDefault()
        }
    }
    val onMove: (Event) -> Unit = move@{ e ->
        val p = e as PointerEvent
        if (p.pointerId !in down) return@move
        val before = down.values.take(2)
        down[p.pointerId] = p.clientX.toDouble() to p.clientY.toDouble()
        val after = down.values.take(2)
        val z = now[0]
        val w = face().offsetWidth.toFloat()
        val h = face().offsetHeight.toFloat()
        if (w <= 0f || h <= 0f) return@move
        if (after.size >= 2) {
            val g0 = gap(before)
            if (g0 <= 0.0) return@move
            val (cx, cy) = centre()
            val m0 = mid(before)
            val m1 = mid(after)
            send(
                z.pinch((gap(after) / g0).toFloat(), (m0.first - cx).toFloat(), (m0.second - cy).toFloat(), w, h)
                    .pan((m1.first - m0.first).toFloat(), (m1.second - m0.second).toFloat(), w, h),
            )
            e.preventDefault()
        } else if (z.zoomed) {
            send(z.pan((after[0].first - before[0].first).toFloat(), (after[0].second - before[0].second).toFloat(), w, h))
            e.preventDefault()
        }
    }
    val onUp: (Event) -> Unit = { e ->
        down.remove((e as PointerEvent).pointerId)
        if (down.isEmpty()) send(now[0].settled())
    }
    // A trackpad pinch arrives as a wheel with ctrl held. It has no
    // lift-off to settle on, and the clamp already lands exactly on
    // fully out.
    val onWheel: (Event) -> Unit = wheel@{ e ->
        val wheel = e.asDynamic()
        if (wheel.ctrlKey != true) return@wheel
        e.preventDefault()
        val w = face().offsetWidth.toFloat()
        val h = face().offsetHeight.toFloat()
        if (w <= 0f || h <= 0f) return@wheel
        val (cx, cy) = centre()
        val factor = kotlin.math.exp(-(wheel.deltaY as Number).toDouble() / 100.0).toFloat()
        val x = ((wheel.clientX as Number).toDouble() - cx).toFloat()
        val y = ((wheel.clientY as Number).toDouble() - cy).toFloat()
        send(now[0].pinch(factor, x, y, w, h))
    }
    el.addEventListener("pointerdown", onDown)
    el.addEventListener("pointermove", onMove)
    el.addEventListener("pointerup", onUp)
    el.addEventListener("pointercancel", onUp)
    el.addEventListener("wheel", onWheel, js("({passive: false})"))
    return onDispose {
        el.removeEventListener("pointerdown", onDown)
        el.removeEventListener("pointermove", onMove)
        el.removeEventListener("pointerup", onUp)
        el.removeEventListener("pointercancel", onUp)
        el.removeEventListener("wheel", onWheel)
    }
}
