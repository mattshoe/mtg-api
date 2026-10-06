package org.mattshoe.mtg.android

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import org.mattshoe.mtg.core.CardQueries
import org.mattshoe.mtg.core.DeckCard
import org.mattshoe.mtg.core.Design
import org.mattshoe.mtg.core.Prices
import org.mattshoe.mtg.core.Tweak

/**
 * The deck's cards, as something you swipe through.
 *
 * Matt: "When tapping my cards in a deck, i want them to show in a
 * 'carousel' [...] where i can swipe through them. It should be an
 * overlay so u can see the screen behind it."
 *
 * So the deck stays on screen under a scrim rather than being
 * replaced, and this is what a tap on a row does — not a feature you
 * launch from somewhere. The card's own page, with the printings and
 * the rulings and the legalities, is one button away at the bottom.
 *
 * Which card is showing lives in `AppState.peek` as a position in
 * `pageOrder`, not as a copy of the card, because the sheet under
 * the carousel changes the deck — see `AppState.peekAt`.
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
    val index = at.coerceIn(0, cards.lastIndex)
    val card = cards[index]

    Box(
        Modifier.fillMaxSize()
            .testTag("card-carousel")
            // A scrim rather than a page: the deck is still there
            // behind it, which is what makes this an overlay and not
            // a navigation.
            .background(Color.Black.copy(alpha = 0.72f))
            // Swallows the press and does nothing with it. Matt: "i
            // don't want click throughs to dismiss the carousel. Only
            // the back button." A card you are reading is not a menu
            // you dismiss by looking away, and the scrim is most of
            // the screen — but the press still has to stop here, or
            // it reaches a row behind and opens a different card
            // under the one you are looking at.
            //
            // `detectTapGestures` and not `clickable`, because a
            // full-screen `clickable` sets
            // `shouldMergeDescendantSemantics` and folds everything
            // under it into one node.
            .pointerInput(Unit) { detectTapGestures { } },
    ) {
        val pager = rememberPagerState(initialPage = index) { cards.size }
        // Two directions, both one-way at a time. The flow reports
        // where the finger left it; the effect below only moves the
        // pager when something else moved the state, so a swipe does
        // not fight the thing it just caused.
        LaunchedEffect(pager) {
            snapshotFlow { pager.currentPage }.collect { page -> if (page != at) onSwipe(page) }
        }
        LaunchedEffect(index) {
            if (pager.currentPage != index) pager.scrollToPage(index)
        }

        // A column and not two alignments in a box. Aligned top and
        // bottom, the card ran a third of its height behind the
        // sheet on a short phone — the third with the rules text on
        // it. The card takes whatever is left above the sheet and
        // sits in the middle of it.
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Box(
                Modifier.fillMaxWidth().weight(1f).padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                HorizontalPager(
                    state = pager,
                    modifier = Modifier.fillMaxWidth().testTag("carousel-pager"),
                    // A sliver of the next card showing is what says
                    // "there are more of these and they move
                    // sideways" without a caption saying it.
                    contentPadding = PaddingValues(horizontal = 40.dp),
                    pageSpacing = 12.dp,
                ) { page ->
                    CardFace(cards[page])
                }
            }

            CardSheet(
                card = card,
                place = place,
                admin = admin,
                onDetails = onDetails,
                onTweak = onTweak,
            )
        }
    }
}

/** One card, as big as the width allows. */
@Composable
private fun CardFace(card: DeckCard) {
    val url = CardQueries.art(card.scryfallId, "normal")
    Box(
        Modifier.fillMaxWidth()
            .aspectRatio(Design.CARD_ASPECT)
            // 4.75% of the width, which is what the stylesheet rounds
            // a card frame by.
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(5))
            .background(Bg3)
            .semantics { contentDescription = card.shown },
    ) {
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        } else {
            // A card the deck wants that nobody owns has no printing
            // and so no picture. Its name, rather than a grey
            // rectangle that reads as a failure to load.
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Line(card.shown, Ink2, Design.H3, FontWeight.SemiBold)
            }
        }
    }
}

/**
 * The sheet under the carousel.
 *
 * Matt: "should only have some very very basic information like
 * name, value, set, etc. But it should have a button or something to
 * navigate to the full details [...] should also let you change
 * count, swap the card, remove it etc."
 *
 * So: four facts and four buttons. Everything else about the card —
 * the printings, the rulings, the legalities, which decks it is in —
 * is behind "Full details", which is the card's own page and already
 * exists.
 */
@Composable
private fun CardSheet(
    card: DeckCard,
    place: String?,
    admin: Boolean,
    onDetails: () -> Unit,
    onTweak: (DeckCard, Tweak) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxWidth()
            .testTag("carousel-sheet")
            .background(Bg2, SheetShape)
            .border(1.dp, Line, SheetShape)
            // A press on the sheet is for the sheet. Without this it
            // falls through to the scrim's dismiss and the carousel
            // shuts under your thumb.
            .pointerInput(Unit) { detectTapGestures { } }
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Line(card.shown, Ink, Design.H3, FontWeight.SemiBold)
                // The printing and what it is worth, on one line.
                // `printing` is null for a card nobody owns, which is
                // also the card with no price, so the line collapses
                // to nothing rather than printing two dashes.
                listOfNotNull(
                    card.printing,
                    card.price?.let { Prices.money(it) },
                ).takeIf { it.isNotEmpty() }?.let {
                    Line(it.joinToString(" · "), Ink2, Design.SMALL)
                }
                card.knownTypeLine?.takeIf { it.isNotBlank() }?.let {
                    Line(it, Ink3, Design.MINI)
                }
            }
            place?.let { Line(it, Ink3, Design.MINI, modifier = Modifier.testTag("carousel-place")) }
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Tag("${card.qty}× in deck")
            Tag("${card.owned} owned", if (card.short > 0) Bad else Ink2)
        }

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Primary("Full details", onClick = onDetails)
            if (admin) {
                Btn("Count") { onTweak(card, Tweak.QUANTITY) }
                Btn("Swap") { onTweak(card, Tweak.SWAP) }
                Btn("Remove", danger = true) { onTweak(card, Tweak.REMOVE) }
            }
        }
    }
}
