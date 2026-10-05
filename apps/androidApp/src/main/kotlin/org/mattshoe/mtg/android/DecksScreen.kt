package org.mattshoe.mtg.android

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import org.mattshoe.mtg.core.CardQueries
import org.mattshoe.mtg.core.Deck
import org.mattshoe.mtg.core.Bar
import org.mattshoe.mtg.core.DeckAnalysis
import org.mattshoe.mtg.core.DeckCard
import org.mattshoe.mtg.core.Pip
import org.mattshoe.mtg.core.Prices
import org.mattshoe.mtg.core.DecksState
import org.mattshoe.mtg.core.Design
import org.mattshoe.mtg.core.ExportTo
import org.mattshoe.mtg.core.ShareWhat
import org.mattshoe.mtg.core.Tweak

/**
 * Decks, on Android. Sibling of `DecksPage`.
 *
 * The same tile the site draws: the commander's art as a band across
 * the top, the tile-width name, the identity pips and who it is. The
 * grouping, the gaps and the totals all come from `DecksState`, so the
 * two cannot disagree about what a deck is short of.
 */

/**
 * `object-position: center Y%` on the web, as a Compose crop bias.
 * CSS's 50% is dead centre (bias 0); 0% is the top edge (bias -1). A
 * creature's face sits in the top third of almost every piece of
 * Magic art, which is why every one of these biases high rather than
 * letting Coil centre the crop.
 */
private fun topBias(percent: Int): BiasAlignment = BiasAlignment(0f, (percent - 50) / 50f)

/** `.deck-banner img`: `object-position: center 38%`. */
private val TileCrop = topBias(38)

/** `.deck-hero img`: `object-position: center 34%`. */
private val HeroCrop = topBias(34)

/**
 * `.deck-line .thumb img`: `object-position: center 32%`. The token
 * row's thumbnail is Android's own — the web draws tokens as pills
 * with no art — and takes the same bias as the deck row it resembles.
 */
private val RowCrop = topBias(32)

/**
 * On top of the gap every item in the column already gets, so one
 * owner's shelf and the next read as two groups rather than one long
 * list. The web's `.owner-group + .owner-group` is 28px against the
 * 11px `.owner-head` keeps beneath its own name.
 */
private val OwnerGroupExtraGap = 16.dp

@Composable
fun DecksScreen(
    state: DecksState,
    onOpen: (Deck) -> Unit,
    onClose: () -> Unit,
    admin: Boolean = false,
    /**
     * Handed down from `AppShell`, which keeps one of these per deck
     * (and one for the list) alive across a trip through the card
     * screen — see the comment there. Defaulted so every test that
     * mounts this screen on its own keeps working unchanged.
     */
    scrollState: ScrollState = rememberScrollState(),
    onEdit: (Deck) -> Unit = {},
    onDisassemble: (Deck) -> Unit = {},
    /** Rename the open deck. The slug moves with the name. */
    onRename: (Deck) -> Unit = {},
    onOpenCard: (DeckCard, String) -> Unit = { _, _ -> },
    /** Maintenance, one card at a time, without leaving the screen. */
    onAddCard: () -> Unit = {},
    onTweak: (DeckCard, Tweak?) -> Unit = { _, _ -> },
    onShare: (ShareWhat, ExportTo) -> Unit = { _, _ -> },
) {

    // Two destinations, not one screen with a mode.
    //
    // The list and an open deck used to share this composable, one
    // scrolling `Column` and one hoisted `ScrollState`, which is how
    // an offset could travel from the list into the deck you tapped.
    // Matt, on finding it: "That should be its own destination in the
    // nav graph." It is now — `AppShell` composes one or the other,
    // never both, and each owns its own scroll container.
    //
    // This wrapper stays because seventy-odd tests mount
    // `DecksScreen` directly and the split is not their subject.
    val open = state.open
    if (open == null) {
        DecksListScreen(state, scrollState, admin, onOpen)
    } else {
        DeckDetailScreen(
            state = state,
            open = open,
            scrollState = scrollState,
            admin = admin,
            onClose = onClose,
            onEdit = onEdit,
            onDisassemble = onDisassemble,
            onRename = onRename,
            onOpenCard = onOpenCard,
            onAddCard = onAddCard,
            onTweak = onTweak,
            onShare = onShare,
        )
    }
}

/**
 * The shelf of decks. Its own destination, with its own scroll
 * container — `deck-list`.
 */
@Composable
internal fun DecksListScreen(
    state: DecksState,
    scrollState: ScrollState = rememberScrollState(),
    admin: Boolean = false,
    onOpen: (Deck) -> Unit = {},
) {
    Column(
        Modifier.fillMaxWidth()
            .testTag("deck-list")
            .verticalScroll(scrollState)
            .padding(Design.WRAP_PAD_NARROW.dp),
        verticalArrangement = Arrangement.spacedBy(Design.GAP.dp),
    ) {
        // No heading and no New deck button. The tab at the bottom
        // says Decks, and a deck is started from the entry wizard's
        // first question now — Matt: "get rid of the one on the decks
        // list page."
        when {
            state.busy -> Line("Loading…", Ink3)
            state.error != null -> Line("Could not load decks: ${state.error}", Bad)
            state.decks.isEmpty() -> Line("No decks yet.", Ink3)
            else -> state.byOwner.forEachIndexed { i, (owner, decks) ->
                // A shelf, with a heading that says how many are on
                // it. The web's `.owner-head` is the name and the
                // count together, not a bare name, with a rule
                // under it (`border-bottom`). Two shelves have to
                // read as two shelves, so every one after the
                // first gets extra air above it on top of the
                // column's own gap — 28px against 11px on the web,
                // not Android's old uniform 8dp everywhere.
                GroupHead(
                    owner.replaceFirstChar(Char::uppercase),
                    "${decks.size} " + if (decks.size == 1) "deck" else "decks",
                    Design.H2,
                    underline = true,
                    extraTopGap = if (i > 0) OwnerGroupExtraGap else 0.dp,
                )
                decks.forEach { DeckTile(it, onOpen) }
            }
        }
    }
}

/**
 * The deck's destination before its cards have arrived.
 *
 * Its own destination rather than the shelf, because the address
 * already names this deck — showing the list here would mean the page
 * changed under you twice for one tap, and would hand `← Decks` to
 * the wrong screen.
 */
@Composable
internal fun DeckLoadingScreen(
    slug: String,
    error: String? = null,
    onClose: () -> Unit = {},
) {
    Column(
        Modifier.fillMaxWidth()
            .testTag("deck-detail")
            .padding(Design.WRAP_PAD_NARROW.dp),
        verticalArrangement = Arrangement.spacedBy(Design.GAP.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Design.GAP.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Ghost("← Decks", onClick = onClose)
        }
        if (error != null) ErrBox(error) else Line("Loading $slug…", Ink3)
    }
}

/**
 * One deck, read. Its own destination, with its own scroll container
 * — `deck-detail`, which is what makes it impossible for the list's
 * offset to be inherited here.
 */
@Composable
internal fun DeckDetailScreen(
    state: DecksState,
    open: Deck,
    scrollState: ScrollState = rememberScrollState(),
    admin: Boolean = false,
    onClose: () -> Unit = {},
    onEdit: (Deck) -> Unit = {},
    onDisassemble: (Deck) -> Unit = {},
    onRename: (Deck) -> Unit = {},
    onOpenCard: (DeckCard, String) -> Unit = { _, _ -> },
    onAddCard: () -> Unit = {},
    onTweak: (DeckCard, Tweak?) -> Unit = { _, _ -> },
    onShare: (ShareWhat, ExportTo) -> Unit = { _, _ -> },
) {
    Column(
        Modifier.fillMaxWidth()
            .testTag("deck-detail")
            .verticalScroll(scrollState)
            .padding(Design.WRAP_PAD_NARROW.dp),
        verticalArrangement = Arrangement.spacedBy(Design.GAP.dp),
    ) {
        var sharing by remember { mutableStateOf(false) }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Design.GAP.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Ghost("← Decks", onClick = onClose)
            Spacer(Modifier.weight(1f))
            // Anchored to its own button, the way the website
            // anchors `.app-menu.from-right` to `.menu-anchor`.
            // It used to be inline — a panel pushed into the
            // column under the header — on the reasoning that a
            // menu which is part of the page cannot end up off
            // the edge of the screen. True, but it also meant the
            // menu shoved the whole deck down the page every time
            // it opened, which is not what pressing a share
            // button should do.
            //
            // `DropdownMenu` gets the anchoring without the
            // clipping: it keeps itself inside the window by
            // construction, so Android never has to solve the
            // off-screen problem the web needed container
            // queries for.
            Box {
                ShareButton(sharing) { sharing = !sharing }
                ShareMenu(
                    open = sharing,
                    onDismiss = { sharing = false },
                ) { what, where -> sharing = false; onShare(what, where) }
            }
        }
        Hero(open, state)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Tag("${state.totalCards} cards")
            if (state.gaps.isNotEmpty()) Tag("${state.gaps.size} not owned", Bad)
        }
        if (admin) {
            // Both of these move real cards, and both show the
            // server's own dry run before they are allowed to.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Design.GAP.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Btn("Edit list") { onEdit(open) }
                Btn("Rename") { onRename(open) }
                Btn("Disassemble", danger = true) { onDisassemble(open) }
            }
        }
        DeckStatsPanel(DeckAnalysis.of(state.cards))
        if (admin) {
            Panel { Primary("+ Add a card", onClick = onAddCard) }
        }
        // `byType` is the core's, the same list the website reads,
        // so the two cannot group or order a deck differently.
        state.byType.forEach { (group, cards) ->
            // The web wraps this in `.panel-head`: a shaded band
            // with a rule under it, and the capitals every
            // `.panel-head h2` gets.
            GroupHead(group.title, "${cards.sumOf { it.qty }}", uppercase = true, banded = true)
            Panel {
                cards.forEachIndexed { i, c ->
                    // `.deck-line + .deck-line { border-top }`: a
                    // rule between rows, not after the last one.
                    if (i > 0) Hairline("deck-line-rule-${group.title}-$i")
                    CardLine(c, open.owner, onOpenCard, admin, onTweak)
                }
            }
        }

        TokenList(state.tokens)
    }
}

@Composable
private fun DeckTile(deck: Deck, onOpen: (Deck) -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .background(Bg2, Radius)
            .border(1.dp, Line, Radius)
            .clip(Radius)
            .clickable { onOpen(deck) },
    ) {
        CardQueries.banner(deck.artId, deck.commanderName)?.let { url ->
            AsyncImage(
                model = url,
                contentDescription = null,
                modifier = Modifier.fillMaxWidth().height(96.dp).background(Bg3),
                contentScale = ContentScale.Crop,
                alignment = TileCrop,
            )
        }
        Column(
            Modifier.padding(horizontal = 13.dp, vertical = 11.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Line(deck.title, Ink, Design.H3, FontWeight.SemiBold, Modifier.weight(1f))
                deck.bracket?.let { Tag("bracket $it", Info) }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Identity(deck.identity)
                Line(deck.commanderName ?: "—", Ink2, Design.MINI)
            }
        }
    }
}

/** WUBRG pips, or one colourless one. The `.mana` row on the web. */
@Composable
private fun Identity(
    ci: String,
    size: androidx.compose.ui.unit.Dp = 16.dp,
    text: androidx.compose.ui.unit.TextUnit = 10.sp,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        (ci.ifEmpty { "C" }).forEach { letter ->
            ManaSymbol(letter.toString(), size = size, text = text)
        }
    }
}

/**
 * The commander across the top of its own deck. The web's `.deck-hero`.
 *
 * The art is a landscape crop, so the band is a fixed height with the
 * picture covering it and the name over a wash — white on bare art is
 * unreadable over half the commanders in the game.
 */
@Composable
private fun Hero(deck: Deck, state: DecksState) {
    val cmdr = state.commander
    val url = cmdr?.art ?: CardQueries.banner(deck.artId, deck.commanderName)
    if (url == null) {
        // Nothing at all rather than the deck's name: the header at
        // the top of the app is already showing it, because an open
        // deck is the one thing the bottom bar cannot name.
        return
    }
    Box(Modifier.fillMaxWidth().height(150.dp).background(Bg3, Radius).clip(Radius)) {
        AsyncImage(
            model = url,
            contentDescription = null,
            modifier = Modifier.fillMaxWidth().height(150.dp),
            contentScale = ContentScale.Crop,
            alignment = HeroCrop,
        )
        Box(
            Modifier.fillMaxWidth().height(150.dp)
                .background(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        0f to androidx.compose.ui.graphics.Color.Transparent,
                        0.55f to androidx.compose.ui.graphics.Color(0x8C04060A),
                        1f to androidx.compose.ui.graphics.Color(0xEB04060A),
                    ),
                ),
        )
        Column(
            Modifier.align(Alignment.BottomStart).padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            // The commander leads, the way the web's `.who` does, and
            // the deck's own name is already the thing you tapped to
            // get here — it was the big line on Android and the
            // commander was demoted into the small one, so the hero
            // repeated what the header above it already said and the
            // card the deck is actually built around came second.
            // Clamped at two lines: `-webkit-line-clamp: 2`, because
            // "Rograkh, Son of Rohgahh" and friends do not fit on one.
            val who = (cmdr?.shown ?: deck.commanderName)?.takeIf { it.isNotBlank() }
            Line(
                who ?: deck.title,
                Ink,
                Design.H2,
                FontWeight.SemiBold,
                // Tagged because the commander's name appears twice on
                // this screen — here and as a row in the list below —
                // so a test asking about "the hero's name" has to be
                // able to say which one it means.
                modifier = Modifier.testTag("deck-hero-who"),
                maxLines = 2,
            )

            // `Bracket N · <colours> · N cards`, the web's `.what` in
            // the web's order. Android had no colours here at all and
            // no count.
            //
            // One deliberate departure: the colours are real mana
            // symbols, not the letters "WU". The web joins
            // `colorPips` as text, but Matt asked for symbols on every
            // colour pip, Android already draws them that way on the
            // deck tile two composables up, and printing letters here
            // would be the one place on the phone that went backwards.
            Row(
                Modifier.testTag("deck-hero-what"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val parts = buildList<@Composable () -> Unit> {
                    deck.bracket?.let { b -> add { Line("Bracket $b", Ink2, Design.MINI) } }
                    if (deck.identity.isNotEmpty()) {
                        add { Identity(deck.identity, size = 13.dp, text = 8.sp) }
                    }
                    add { Line("${state.totalCards} cards", Ink2, Design.MINI) }
                }
                parts.forEachIndexed { i, part ->
                    if (i > 0) Line(" · ", Ink2, Design.MINI)
                    part()
                }
            }
        }
    }
}

/**
 * One card in the list: a square crop of its art, the name and type,
 * and how many. The whole row opens the card, the way the web's does.
 */
@Composable
private fun CardLine(
    card: DeckCard,
    owner: String,
    onOpen: (DeckCard, String) -> Unit,
    admin: Boolean = false,
    onTweak: (DeckCard, Tweak?) -> Unit = { _, _ -> },
) {
    Row(
        Modifier.fillMaxWidth().clickable { onOpen(card, owner) }.padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // `art_crop` is a landscape band; the square comes from this
        // box cropping it. `.deck-line .thumb` has a 1px border on
        // the web, which Coil's own crop had nothing to match, and a
        // 6px corner rather than the page's 10 — ten on a forty-pixel
        // box takes a quarter of its width off each corner and the
        // art reads as a circle with the sides flattened.
        Box(
            Modifier.size(40.dp)
                .background(Bg3, RadiusThumb)
                .border(1.dp, Line, RadiusThumb)
                .clip(RadiusThumb),
        ) {
            card.art?.let {
                AsyncImage(
                    model = it,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    contentScale = ContentScale.Crop,
                    alignment = RowCrop,
                )
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Line(card.shown, Ink, Design.SMALL)
            // `knownTypeLine`, not `typeLine`: a basic land nobody
            // inventories has no printing to read one off, and the
            // web names it anyway. The phone was leaving every
            // Island, Plains and Forest with a blank second line.
            card.knownTypeLine?.takeIf { it.isNotBlank() }?.let { Line(it, Ink3, Design.MINI) }
        }
        if (card.short > 0) Tag("has ${card.owned}", Bad)
        // `.deck-line .num` is `var(--mono)`, so a 6 and a 16 do not
        // shift the row's text beside them.
        Line("${card.qty}×", Ink3, Design.MINI, fontFamily = monoSmall.fontFamily)
        // Maintenance lives on the row the card is on. One button,
        // not three: three marks on every row of a hundred-card list
        // left no room for the card's own name. What to do is asked
        // inside the sheet.
        if (admin) RowAction(card, onTweak)
    }
}

/**
 * The "⋯" on a card's row. Admin only, and it keeps the press.
 *
 * The row it sits on is itself a button, so without its own click the
 * sheet would open with the card page behind it. A 44dp box rather
 * than a 20dp glyph, which is the floor the web's coarse-pointer rule
 * puts on it.
 */
@Composable
private fun RowAction(card: DeckCard, onTweak: (DeckCard, Tweak?) -> Unit) {
    Box(
        Modifier.size(44.dp)
            .semantics {
                role = Role.Button
                contentDescription = "Change ${card.shown}"
            }
            // Nothing is decided here: the sheet asks what to do.
            .clickable { onTweak(card, null) },
        contentAlignment = Alignment.Center,
    ) {
        Line("⋯", Ink2, Design.H3, FontWeight.SemiBold)
    }
}

/**
 * A panel head: what it is, and how many of it. The web's
 * `.panel-head` is an `h2` and a count tag, never a bare word.
 */
@Composable
private fun GroupHead(
    title: String,
    count: String,
    size: Int = Design.H3,
    /** `.panel-head h2 { text-transform: uppercase }`. `.owner-head` is not one of these. */
    uppercase: Boolean = false,
    /** `.owner-head { border-bottom }`: a rule under the heading, nothing behind it. */
    underline: Boolean = false,
    /** `.panel-head { background; border-bottom }`: a shaded band, with a rule under it. */
    banded: Boolean = false,
    /** Extra air above this heading, on top of whatever gap the column already gives it. */
    extraTopGap: androidx.compose.ui.unit.Dp = 0.dp,
) {
    Column(Modifier.fillMaxWidth().padding(top = 6.dp + extraTopGap)) {
        Row(
            Modifier.fillMaxWidth()
                .then(if (banded) Modifier.background(Bg3) else Modifier)
                .padding(
                    horizontal = if (banded) 12.dp else 0.dp,
                    vertical = if (banded) 8.dp else 0.dp,
                )
                .testTag("group-head-$title"),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Line(
                if (uppercase) title.uppercase() else title,
                Ink,
                size,
                FontWeight.SemiBold,
                Modifier.weight(1f).semantics { heading() },
            )
            Tag(count)
        }
        if (underline || banded) {
            Box(
                Modifier.fillMaxWidth().height(1.dp).background(Line)
                    .testTag("group-rule-$title"),
            )
        }
    }
}

/** `.deck-line + .deck-line { border-top }`: a rule between rows, not after the last. */
@Composable
private fun Hairline(tag: String) {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Line).testTag(tag))
}

/**
 * Share, with something to say about what.
 *
 * A deck is worth handing over two ways: as a link to this page, and
 * as the list itself for somebody to paste into their own builder.
 * Each can go to the clipboard or come down as a file, so the four
 * are a menu rather than four buttons crowding the header.
 */
@Composable
private fun ShareButton(open: Boolean, onClick: () -> Unit) {
    Box(
        // `TouchTarget`, not 44dp. Item 4.8 put a 48dp floor under
        // everything you press and this control was missed, because it
        // is its own `Box` rather than a `Ghost` or a `NavPill`. Its
        // own test caught it at 44.
        Modifier.size(TouchTarget)
            .semantics {
                role = Role.Button
                contentDescription = "Share this deck"
            }
            .background(
                if (open) AccentDim else androidx.compose.ui.graphics.Color.Transparent,
                Radius,
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        // The mark, drawn — see `ShareMark`, which reproduces the
        // website's own SVG. Boxed and gold while it is open, so
        // "open" reads as a shape and a lightness rather than a hue
        // nobody can see.
        ShareMark(if (open) Accent2 else Ink2)
    }
}

@Composable
private fun ShareMenu(
    open: Boolean,
    onDismiss: () -> Unit,
    onShare: (ShareWhat, ExportTo) -> Unit,
) {
    androidx.compose.material3.DropdownMenu(
        expanded = open,
        onDismissRequest = onDismiss,
        // The app's own panel, not Material's: `Bg2` behind a hairline
        // of `Line`, the same as every other panel on the phone and
        // the same as `.app-menu` on the web.
        modifier = Modifier.background(Bg2, Radius).border(1.dp, Line, Radius),
    ) {
        // A group is its label and the two options under it, close
        // enough together to read as one thing. Evenly spaced, the
        // second label sat as far from its own options as from the
        // group above it and the menu read as six loose words.
        Column(
            Modifier.widthIn(min = 180.dp).padding(Design.PANEL_PAD.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ShareWhat.entries.forEach { what ->
                Column {
                    Line(
                        what.label,
                        Ink3,
                        Design.MINI,
                        FontWeight.SemiBold,
                        Modifier.padding(bottom = 2.dp),
                    )
                    ExportTo.entries.forEach { where ->
                        Line(
                            where.label,
                            Ink,
                            Design.SMALL,
                            FontWeight.Normal,
                            Modifier.fillMaxWidth()
                                .semantics { role = Role.Button }
                                .clickable { onShare(what, where) }
                                .padding(vertical = 9.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * What the deck is made of, on a phone. Sibling of `DeckStatsPanel`.
 *
 * Every number is `DeckAnalysis`, the same object the website reads,
 * so the two cannot disagree about a deck's curve. The charts are the
 * web's charts: the same six figures, the same columns, the same two
 * rings, the same captions. A phone has one column, so the panels are
 * stacked rather than set in a grid — that is the only difference
 * either platform is allowed.
 */
@Composable
internal fun DeckStatsPanel(s: org.mattshoe.mtg.core.DeckStats) {
    Panel {
        Line(
            // `.panel-head h2 { text-transform: uppercase }` on the web.
            "The deck at a glance".uppercase(),
            Ink,
            Design.H3,
            FontWeight.SemiBold,
            Modifier.semantics { heading() },
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 8.dp),
        ) {
            Figure("${s.totalCards}", "cards")
            Figure("${s.lands}", "lands · ${s.landShare}%")
            Figure(s.averageManaValueText, "avg mana")
            Figure("${s.spells}", "spells")
            // The hint is the web's `title`. A phone has nothing to
            // hover, so it is the figure's description instead —
            // which is also the only way a screen reader hears it.
            s.value?.let {
                Figure(
                    Prices.money(it),
                    "value",
                    hint = if (s.unpriced > 0) "${s.unpriced} cards have no price" else "",
                )
            }
            if (s.missing > 0) Figure("${s.missing}", "not owned", Warn)
        }
    }

    if (s.hasCurve) Panel { Curve(s) }
    if (s.pips.isNotEmpty() || s.sources.isNotEmpty()) Panel { Colours(s) }
    if (s.types.isNotEmpty()) Panel { Bars("Card types", s.types, s.totalCards) }
    if (s.rarities.isNotEmpty()) Panel { Bars("Rarity", s.rarities, s.totalCards) }

    if (s.unknown > 0) {
        Line(
            "${s.unknown} card${if (s.unknown == 1) "" else "s"} in this list have no printing " +
                "in the collection, so nothing is known about them — they are counted in the " +
                "total and left out of every chart above.",
            Ink3,
            Design.MINI,
        )
    }
}

/** How tall the chart is, and how much of that the count above a full bar needs. */
private val CURVE_HEIGHT = 132.dp
private val CURVE_HEAD = 18.dp

/** A column is rounded where it ends and square where it stands, as on the web. */
private val CurveCap =
    androidx.compose.foundation.shape.RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)

/**
 * `.curve .bar`'s `linear-gradient(to top, var(--accent),
 * color-mix(in srgb, var(--accent) 55%, var(--bg-3)))`.
 *
 * CSS's `to top` puts the first stop at the foot, so the bar is full
 * accent where it stands and dimmed where it ends. Compose's
 * `verticalGradient` reads the other way round, which is why the
 * mixed colour is first here.
 *
 * It is not decoration. A flat gold column against a flat gold
 * column beside it has one edge between them and nothing else; the
 * ramp gives every bar a light foot and a dark head, so the shape of
 * the curve reads as lightness rather than as eight rectangles of
 * one colour.
 */
private val CurveFill = Brush.verticalGradient(
    listOf(mix(Accent, Bg3, Design.CURVE_BAR_MIX), Accent),
)

/**
 * The curve, as columns. The web's `Curve`.
 *
 * Lands are not in it — they cost nothing and would put a third of
 * the deck in the nought column, which is the one thing a curve must
 * not say.
 */
@Composable
private fun Curve(s: org.mattshoe.mtg.core.DeckStats) {
    Line("Mana curve".uppercase(), Ink3, Design.MINI, FontWeight.SemiBold)
    val most = s.curve.maxOfOrNull { it.value } ?: 0
    Row(
        Modifier.fillMaxWidth().height(CURVE_HEIGHT).padding(top = 10.dp).testTag("curve"),
        // `.curve { gap: 6px }`. Five was a dp narrower than the
        // page's, which over eight columns is a chart eight dp wider
        // with eight slightly fatter bars in it.
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        s.curve.forEach { bar ->
            Column(
                Modifier.weight(1f).fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom,
            ) {
                // The bar measures itself against the track, not
                // against the column: the column also holds the
                // number and the label, and a fraction of the column
                // overruns what is left, which is how the web drew
                // fifteen and seventeen exactly alike. The head is
                // the room the number needs above a full-height bar,
                // and the number rides on top of its own bar rather
                // than sitting in a row along the top of the chart,
                // where it was nowhere near what it counted.
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    val room = (maxHeight - CURVE_HEAD).coerceAtLeast(0.dp)
                    val tall = if (most <= 0) 0.dp else room * (bar.value.toFloat() / most)
                    Column(
                        Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Bottom,
                    ) {
                        // Pinned to exactly the room reserved for it.
                        // Left to size itself the number came out a
                        // shade taller than `CURVE_HEAD` on some
                        // densities, the column overflowed, and the
                        // only bar with no slack — the tallest — was
                        // squeezed to fit. Seventeen then drew 74dp
                        // against fifteen's 71 instead of its honest
                        // 80, which is the "why are the 15 and 17 the
                        // same height" this chart was rebuilt to fix,
                        // alive again and showing on one emulator in
                        // three.
                        Box(
                            Modifier.height(CURVE_HEAD).fillMaxWidth(),
                            contentAlignment = Alignment.BottomCenter,
                        ) {
                            Line(
                                if (bar.value > 0) "${bar.value}" else "",
                                Ink2,
                                Design.MINI,
                                FontWeight.SemiBold,
                                Modifier.testTag("curve-count-${bar.label}"),
                                // `.curve .n` is `var(--mono)` on the
                                // web, so a 15 and a 17 line up digit
                                // for digit over their bars.
                                fontFamily = monoSmall.fontFamily,
                            )
                        }
                        Box(
                            Modifier.fillMaxWidth()
                                // A zero column is still a column: the
                                // web leaves a 2px stub so the chart
                                // has a floor to read the rest against.
                                .height(tall.coerceAtLeast(2.dp))
                                // `.curve .col.none .bar` is flat
                                // `--bg-3`: an empty column is a
                                // floor to read the rest against and
                                // not a bar with nothing in it.
                                .then(
                                    if (bar.value > 0) {
                                        Modifier.background(CurveFill, CurveCap)
                                    } else {
                                        Modifier.background(Bg3, CurveCap)
                                    },
                                )
                                .testTag("curve-bar-${bar.label}"),
                        )
                    }
                }
                Line(
                    bar.label,
                    Ink3,
                    Design.MINI,
                    modifier = Modifier.testTag("curve-label-${bar.label}"),
                    // `.curve .x` is `var(--mono)` on the web too.
                    fontFamily = monoSmall.fontFamily,
                )
            }
        }
    }
    Line("Median ${s.medianManaValueText}. Lands excluded.", Ink3, Design.MINI)
}

/**
 * What the deck asks for against what it can make, per colour.
 * The web's `Colours`.
 *
 * Two bars per colour: pips needed, then sources at half strength.
 * A splash with no sources is the thing this is for, and it is
 * invisible in either chart on its own.
 */
@Composable
private fun Colours(s: org.mattshoe.mtg.core.DeckStats) {
    Line("Colour".uppercase(), Ink3, Design.MINI, FontWeight.SemiBold)
    val most = maxOf(
        s.pips.maxOfOrNull { it.value } ?: 0,
        s.sources.maxOfOrNull { it.value } ?: 0,
    )
    Pip.entries.forEach { pip ->
        val needs = s.pips.firstOrNull { it.label == pip.label }?.value ?: 0
        val makes = s.sources.firstOrNull { it.label == pip.label }?.value ?: 0
        if (needs == 0 && makes == 0) return@forEach
        Row(
            Modifier.fillMaxWidth().padding(top = 7.dp).testTag("mana-row-${pip.letter}"),
            horizontalArrangement = Arrangement.spacedBy(9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PipDot(pip.letter, 18.dp, 10.sp, "pip-row-${pip.letter}")
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Track(needs, most, pip, "needs", faded = false)
                Track(makes, most, pip, "makes", faded = true)
            }
        }
    }
    Row(
        Modifier.fillMaxWidth().padding(top = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Ring("Needs", s.pips, Modifier.weight(1f))
        Ring("Makes", s.sources, Modifier.weight(1f))
    }
    // One caption, the web's `.sub`, and the splash warning is the last
    // sentence of it rather than a line of its own in amber. The web
    // appends it to the same text node; amber on the phone made it a
    // second thing to read and said "wrong" in the one channel this
    // collection's owner cannot see anyway — the sentence itself is
    // what carries it.
    Line(
        "Pips the deck asks for, against cards that can produce them. " +
            "Hybrid pips count for both halves." +
            if (s.unsupported.isNotEmpty()) {
                " No source for ${s.unsupported.joinToString(", ")}."
            } else {
                ""
            },
        Ink3,
        Design.MINI,
    )
}

/**
 * One colour split, as a ring. The web's `Pie`.
 *
 * A bar says how many white pips there are; a ring says what share of
 * the deck's colour is white, which is the question you ask when
 * deciding whether a splash is really a splash. Every slice is also
 * named by its letter in the key below, because a chart that encodes
 * a colour only as a hue says nothing to half its readers.
 */
@Composable
private fun Ring(caption: String, bars: List<Bar>, modifier: Modifier = Modifier) {
    val total = bars.sumOf { it.value }
    // Nothing to split: a colourless deck has no colour chart.
    if (total <= 0) {
        Box(modifier)
        return
    }
    val slices = bars.map { it to (it.value * 100) / total }
    val told = slices.joinToString(", ") { (bar, pct) -> "${bar.label} $pct%" }
    Column(
        modifier.testTag("ring-$caption"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Canvas(
            Modifier.size(76.dp)
                .semantics { contentDescription = "$caption · $total — $told" },
        ) {
            var at = -90f
            bars.forEach { bar ->
                val sweep = 360f * bar.value / total
                drawArc(
                    color = c(Design.pip(letterFor(bar.label))),
                    startAngle = at,
                    sweepAngle = sweep,
                    useCenter = true,
                )
                at += sweep
            }
            // A hairline on every boundary, so two neighbouring
            // slices are told apart by an edge and not only by their
            // colour.
            at = -90f
            bars.forEach { bar ->
                val rad = at * kotlin.math.PI.toFloat() / 180f
                drawLine(
                    color = Bg2,
                    start = center,
                    end = androidx.compose.ui.geometry.Offset(
                        center.x + (size.minDimension / 2) * kotlin.math.cos(rad),
                        center.y + (size.minDimension / 2) * kotlin.math.sin(rad),
                    ),
                    strokeWidth = 2f,
                )
                at += 360f * bar.value / total
            }
        }
        Line("$caption · $total", Ink3, Design.TINY, FontWeight.SemiBold)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            slices.forEach { (bar, pct) ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PipDot(letterFor(bar.label), 14.dp, 9.sp, "pip-$caption-${letterFor(bar.label)}")
                    Line("$pct%", Ink2, Design.TINY)
                }
            }
        }
    }
}

/** The letter a colour goes by, which is how a slice is named rather than tinted. */
private fun letterFor(label: String): String =
    Pip.entries.firstOrNull { it.label == label }?.letter ?: "C"

/**
 * One mana symbol: the colour, with its letter on it. The web's `.sym`.
 *
 * The letter is the point of it — the owner is colourblind and a disc
 * told apart only by its hue says nothing. Which is why the line box
 * is trimmed to the glyph: a `Text` keeps the theme's line height
 * whatever its font size, so an 8sp letter sat in a 24sp line and
 * centring the line left the letter hanging off the bottom of a small
 * disc as a sliver. The screenshot caught it; no assertion could.
 */
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
@Composable
private fun PipDot(
    letter: String,
    size: androidx.compose.ui.unit.Dp,
    text: androidx.compose.ui.unit.TextUnit,
    tag: String,
) {
    // The real symbol, the same one the website draws. It was a letter
    // in a coloured circle here, which is the phone's own invention and
    // nothing like the page — and a droplet against a skull is legible
    // to somebody who cannot separate blue from black, which W against
    // U is not.
    ManaSymbol(letter, size = size, text = text, tag = tag)
}

@Composable
private fun Figure(
    n: String,
    k: String,
    tint: androidx.compose.ui.graphics.Color = Ink,
    hint: String = "",
) {
    val told = if (hint.isEmpty()) "$n $k" else "$n $k — $hint"
    Column(
        Modifier.semantics { contentDescription = told }
            // The web's `.figures` grid draws a 1px seam between cells
            // with a `--line` background under a 1px gap; a hairline
            // border around each cell is the same seam without a grid
            // of Android's own to lay six figures into on a phone.
            .border(1.dp, Line, RadiusSm)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Line(n, tint, Design.H2, FontWeight.SemiBold)
        // `.figure .k { text-transform: uppercase }` on the web. The
        // accessible name stays sentence case — a screen reader is
        // not shown CSS text-transform either.
        Line(k.uppercase(), Ink3, Design.MINI)
    }
}

@Composable
private fun Track(n: Int, most: Int, pip: Pip, what: String, faded: Boolean) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f).height(9.dp).background(Bg3, Radius)) {
            Box(
                Modifier.fillMaxWidth(if (most <= 0) 0f else n.toFloat() / most)
                    .height(9.dp)
                    // Sources at half strength, the way the web fades
                    // `.fill.makes`: the pair is one comparison and
                    // the top bar is the demand.
                    .background(
                        c(Design.pip(pip.letter)).copy(alpha = if (faded) 0.5f else 1f),
                        Radius,
                    )
                    .testTag("track-${pip.letter}-$what"),
            )
        }
        Line("$n $what", Ink3, Design.MINI)
    }
}

@Composable
private fun Bars(title: String, bars: List<Bar>, total: Int) {
    // `.stats-card > h3 { text-transform: uppercase }` on the web.
    Line(title.uppercase(), Ink3, Design.MINI, FontWeight.SemiBold)
    val most = bars.maxOfOrNull { it.value } ?: 0
    bars.forEach { bar ->
        Row(
            Modifier.fillMaxWidth().padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Line(bar.label, Ink2, Design.MINI, modifier = Modifier.weight(0.42f))
            Box(Modifier.weight(1f).height(8.dp).background(Bg3, Radius)) {
                Box(
                    Modifier.fillMaxWidth(if (most <= 0) 0f else bar.value.toFloat() / most)
                        .height(8.dp)
                        .background(Accent, Radius)
                        .testTag("hbar-${bar.label}"),
                )
            }
            Line("${bar.value}", Ink3, Design.MINI)
        }
    }
    Line("Of $total cards.", Ink3, Design.MINI)
}

/**
 * The tokens the deck makes, below the list, as real cards.
 *
 * Scryfall names them in every card's `all_parts`, so these are
 * printed tokens with their own art rather than a phrase read out of
 * the rules text. Two 1/1 Warriors that differ only by colour are two
 * tokens, which is why the colour is on the row.
 */
@Composable
private fun TokenList(tokens: List<org.mattshoe.mtg.core.TokenCard>) {
    if (tokens.isEmpty()) return
    GroupHead("Tokens", "${tokens.size}", uppercase = true, banded = true)
    val open = androidx.compose.ui.platform.LocalUriHandler.current
    Panel {
        tokens.forEach { token ->
            Row(
                Modifier.fillMaxWidth()
                    // Where to buy one, when Scryfall has a listing.
                    // A token from a set nobody sells singles of stays
                    // a row rather than becoming a tap to nowhere.
                    .then(
                        token.tcgplayer?.let { url ->
                            Modifier.clickable { runCatching { open.openUri(url) } }
                        } ?: Modifier,
                    )
                    .padding(vertical = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // The same 6px square as a deck row's. The web's
                // `.token` is a pill with no picture in it at all, so
                // there is no rule to copy here — this follows the
                // thumbnail it sits closest to rather than the page
                // radius, which rounded a 40dp box nearly round.
                Box(Modifier.size(40.dp).background(Bg3, RadiusThumb).clip(RadiusThumb)) {
                    token.art?.let {
                        AsyncImage(
                            model = it,
                            contentDescription = null,
                            modifier = Modifier.size(40.dp),
                            contentScale = ContentScale.Crop,
                            alignment = RowCrop,
                        )
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Line(token.name, Ink, Design.SMALL)
                        token.stats?.let { Line(it, Ink2, Design.MINI) }
                        Identity(token.colors)
                    }
                    Line(token.shortType, Ink3, Design.MINI)
                }
                if (token.tcgplayer != null) Line("↗", Ink3, Design.MINI)
                Line("${token.madeBy}×", Ink3, Design.MINI)
            }
        }
        Line(
            "Made by the cards in this deck. You will want these to hand — " +
                "each one goes to TCGplayer.",
            Ink3,
            Design.MINI,
        )
    }
}
