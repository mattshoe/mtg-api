package org.mattshoe.mtg.web

import androidx.compose.runtime.Composable
import org.jetbrains.compose.web.dom.Img
import org.jetbrains.compose.web.dom.Span
import org.mattshoe.mtg.core.ManaCost
import org.mattshoe.mtg.core.Pip

/**
 * Mana, drawn as mana.
 *
 * Scryfall's own symbol artwork rather than a letter in a coloured
 * circle. `{1}{G}` is something to decode; the pictures are what
 * everybody who plays this game already reads, and they carry their
 * own shape, so they survive a colour deficiency in a way a coloured
 * dot does not.
 */
@Composable
fun ManaCostRow(cost: String?, extraClass: String? = null) {
    val symbols = ManaCost.art(cost)
    if (symbols.isEmpty()) return
    Span(attrs = {
        classes("mana-cost")
        extraClass?.let { classes(it) }
        attr("title", cost.orEmpty())
        attr("aria-label", cost.orEmpty())
    }) {
        symbols.forEach { (symbol, url) ->
            Img(src = url, alt = "{$symbol}", attrs = {
                classes("mana-sym")
                attr("loading", "lazy")
            })
        }
    }
}

/** One colour's symbol, for an identity or a filter. */
@Composable
fun ManaPip(letter: String, extraClass: String? = null) {
    Img(
        src = ManaCost.symbolArt(letter),
        alt = "{$letter}",
        attrs = {
            classes("mana-sym")
            extraClass?.let { classes(it) }
            attr("loading", "lazy")
            attr("title", Pip.of(letter)?.label ?: letter)
        },
    )
}
