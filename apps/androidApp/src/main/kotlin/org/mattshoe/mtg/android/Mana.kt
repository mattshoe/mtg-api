package org.mattshoe.mtg.android

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import org.mattshoe.mtg.core.Design
import org.mattshoe.mtg.core.ManaCost
import org.mattshoe.mtg.core.Pip

/**
 * One mana symbol, the same picture the website draws.
 *
 * Sibling of `ManaPip` on the web, down to the URL: both ask
 * `ManaCost.symbolArt` for it, so the two cannot end up showing
 * different artwork for the same symbol. The phone was drawing a
 * letter in a coloured circle instead — its own invention, and
 * nothing like the page.
 *
 * It also happens to be the accessible choice. A droplet, a skull, a
 * flame and a tree are told apart by their shape, where W and U in
 * two circles are told apart by hue and nothing else.
 *
 * The letter stays underneath as the fallback. A symbol is fetched
 * over the network, and a phone on a train, a Scryfall outage or a
 * test with no network would otherwise leave a row of empty circles
 * saying nothing at all.
 */
@Composable
fun ManaSymbol(
    letter: String,
    size: Dp = 16.dp,
    text: TextUnit = 10.sp,
    tag: String? = null,
    modifier: Modifier = Modifier,
) {
    val named = Pip.of(letter)?.label ?: letter
    var box = modifier.size(size).semantics { contentDescription = named }
    if (tag != null) box = box.testTag(tag)
    Box(box, contentAlignment = Alignment.Center) {
        // The letter first and the artwork over it, rather than the
        // artwork with the letter as a placeholder slot. A slot only
        // draws while the request is in flight or after it has failed,
        // and an image library that is never initialised — a phone
        // offline, a test with no network — reaches neither state, so
        // the pip came out empty and said nothing at all. Underneath
        // it is always there, and a loaded symbol is opaque and covers
        // it completely.
        Fallback(letter, size, text, tag)
        AsyncImage(
            model = ManaCost.symbolArt(letter),
            contentDescription = null,
            modifier = Modifier.size(size).clip(CircleShape),
        )
    }
}

/** The letter in its colour, for when the artwork is not there. */
@Composable
private fun Fallback(letter: String, size: Dp, text: TextUnit, tag: String?) {
    Box(
        Modifier.size(size).background(c(Design.pip(letter)), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            letter,
            if (tag != null) Modifier.testTag("$tag-letter") else Modifier,
            color = Bg,
            fontSize = text,
            lineHeight = text,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
    }
}
